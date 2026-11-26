package snc.openchargingnetwork.node.services

import java.time.Clock
import java.time.Duration
import java.time.temporal.ChronoUnit
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpMethod
import org.springframework.stereotype.Service
import snc.openchargingnetwork.node.components.HttpClientComponent
import snc.openchargingnetwork.node.components.logIntegrationCopy
import snc.openchargingnetwork.node.components.logIntegrationCopyError
import snc.openchargingnetwork.node.config.IntegrationsProperties
import snc.openchargingnetwork.node.models.OcpiHttpResponse
import snc.openchargingnetwork.node.models.Receiver
import snc.openchargingnetwork.node.models.entities.IntegrationCopyOutboxEntity
import snc.openchargingnetwork.node.models.entities.IntegrationCopyStatus
import snc.openchargingnetwork.node.models.ocpi.OcpiRequestVariables
import snc.openchargingnetwork.node.repositories.IntegrationCopyOutboxRepository
import snc.openchargingnetwork.node.tools.TraceKeys
import snc.openchargingnetwork.node.tools.withMdc

/**
 * Database outbox for integration copies. A copy is stored once the main forward was accepted,
 * then sent until the integration party accepts it or the attempts are used up, so it survives a
 * failed send and a restart of the node.
 *
 * Delivery is at least once: a send whose answer was lost is repeated, with a new X-Request-ID
 * and the original X-Correlation-ID.
 */
@Service
class IntegrationCopyOutboxService(
        private val outboxRepo: IntegrationCopyOutboxRepository,
        private val routingService: RoutingService,
        private val httpClientComponent: HttpClientComponent,
        properties: IntegrationsProperties,
        private val clock: Clock
) {

    companion object {
        private val logger = LoggerFactory.getLogger(IntegrationCopyOutboxService::class.java)
        private const val LOG_TAG = "IntegrationCopyOutbox"

        // Only these change an object addressed by the URL path, where a later request must not
        // overtake an earlier one. A GET changes nothing and a POST creates a new object (a CDR),
        // so neither should wait behind another request that keeps failing.
        private val ORDERED_METHODS = setOf(HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE)

        private const val LEASE_LOST = "lease lost before the send"
    }

    private val settings = properties.outbox

    /**
     * @param copy the request to send, already addressed to the integration party
     * @param verificationStatus signature verification result of the original request
     */
    fun enqueue(copy: OcpiRequestVariables, verificationStatus: String?) {
        val now = clock.instant()
        // The sender's token is not needed to resend: the target's own token is looked up for
        // each attempt.
        val stored = copy.copy(headers = copy.headers.copy(authorization = ""))
        outboxRepo.save(
                IntegrationCopyOutboxEntity(
                        targetCountry = copy.headers.receiver.country,
                        targetParty = copy.headers.receiver.id,
                        module = copy.resolveModuleId(),
                        method = copy.method.name(),
                        objectKey = copy.urlPath?.trim('/')?.takeIf { copy.method in ORDERED_METHODS && it.isNotEmpty() },
                        payload = httpClientComponent.mapper.writeValueAsString(stored),
                        verificationStatus = verificationStatus,
                        correlationId = copy.headers.correlationID.take(255),
                        createdAt = now,
                        nextAttemptAt = now
                )
        )
    }

    /** Sends every copy that is due, until none is left that this replica can take. */
    fun dispatchDue() {
        do {
            val sent =
                    outboxRepo
                            .findDueIds(clock.instant(), PageRequest.of(0, settings.batchSize))
                            .count { attempt(it) }
        } while (sent > 0)
    }

    fun purgeDelivered(): Int =
            outboxRepo.deleteDeliveredBefore(
                    clock.instant().minus(Duration.ofHours(settings.deliveredRetentionHours))
            )

    private fun attempt(id: Long): Boolean {
        val claimedAt = clock.instant()
        // Whole milliseconds, so the value read back from the database equals the one written:
        // the lease is what identifies this replica's claim when the result is stored.
        val lease = claimedAt.plusMillis(settings.leaseMillis).truncatedTo(ChronoUnit.MILLIS)
        if (outboxRepo.claim(id, claimedAt, lease) == 0) return false
        val row = outboxRepo.findById(id).orElse(null) ?: return false

        val failure =
                withMdc(TraceKeys.CORRELATION_ID, row.correlationId) {
                    send(row) { lease.isAfter(clock.instant()) && outboxRepo.countHeldUnder(id, lease) == 1L }
                }
        if (failure == LEASE_LOST) {
            logger.warn(
                    "[{}] integration copy {} was not sent: this replica's lease ran out before the request | correlation_id: {}",
                    LOG_TAG, row.id, row.correlationId
            )
            return false
        }

        val now = clock.instant()
        when {
            failure == null -> {
                row.status = IntegrationCopyStatus.DELIVERED
                row.deliveredAt = now
                row.payload = null
            }
            row.attempts >= settings.maxAttempts -> row.status = IntegrationCopyStatus.PARKED
            else -> row.nextAttemptAt = now.plusMillis(backoffMillis(row.attempts))
        }
        val stored =
                outboxRepo.release(id, lease, row.status, row.deliveredAt, row.payload, row.nextAttemptAt, failure)
        if (stored == 0) {
            logger.warn(
                    "[{}] integration copy {} was taken over by another replica while it was being sent; this result is dropped | outcome: {} | correlation_id: {}",
                    LOG_TAG, row.id, failure ?: "accepted", row.correlationId
            )
        } else if (row.status == IntegrationCopyStatus.PARKED) {
            logger.error(
                    "INTEGRATION_COPY_PARKED — integration copy gave up | outbox id: {} | {} {} to {}/{} | attempts: {} | last error: {} | correlation_id: {}",
                    row.id, row.method, row.module, row.targetCountry, row.targetParty, row.attempts,
                    failure, row.correlationId
            )
        }
        return true
    }

    /**
     * @param stillHeld asked right before the request leaves: a replica that stalled past its
     * lease must not send, because another replica may already have delivered this copy and a
     * later write to the same object, which this older one would then undo at the receiver
     * @return null when the party accepted the copy, [LEASE_LOST] when nothing was sent, otherwise
     * a short reason holding no payload
     */
    private fun send(row: IntegrationCopyOutboxEntity, stillHeld: () -> Boolean): String? {
        var request: OcpiRequestVariables? = null
        var outRequestId: String? = null
        return try {
            request = httpClientComponent.convertToRequestVariables(row.payload!!)
            val response: OcpiHttpResponse<Any> =
                    when (routingService.getReceiverType(request.headers.receiver)) {
                        Receiver.LOCAL -> {
                            val (url, headers) = routingService.prepareLocalPlatformRequest(request, false)
                            outRequestId = headers.requestID
                            val localHeaders =
                                    headers.copy(
                                            verificationStatus = row.verificationStatus ?: headers.verificationStatus
                                    )
                            if (!stillHeld()) return LEASE_LOST
                            httpClientComponent.makeOcpiRequest(url, localHeaders, request)
                        }
                        Receiver.REMOTE -> {
                            val (url, headers, body) = routingService.prepareRemotePlatformRequest(request, false)
                            outRequestId = headers.requestID
                            if (!stillHeld()) return LEASE_LOST
                            httpClientComponent.postOcnMessage(url, headers, body)
                        }
                    }
            logger.logIntegrationCopy(LOG_TAG, request, request.headers.receiver, outRequestId, response)
            if (response.isOcpiSuccess()) null
            else "HTTP ${response.statusCode}, OCPI ${response.body?.statusCode}"
        } catch (e: Exception) {
            val reason = e::class.simpleName ?: "Exception"
            if (request == null) {
                // The exception text of a parse failure can quote the payload, so only its type is logged.
                logger.error("[{}] FAILED — integration copy {} could not be read from the outbox | error: {}", LOG_TAG, row.id, reason)
            } else {
                logger.logIntegrationCopyError(LOG_TAG, request, request.headers.receiver, outRequestId, e)
            }
            reason
        }
    }

    private fun backoffMillis(attempts: Int): Long {
        val doublings = (attempts - 1).coerceIn(0, 30)
        return (settings.backoffInitialMillis shl doublings).coerceAtMost(settings.backoffMaxMillis)
    }
}
