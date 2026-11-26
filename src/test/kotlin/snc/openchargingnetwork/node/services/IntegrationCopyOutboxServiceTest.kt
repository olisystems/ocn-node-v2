package snc.openchargingnetwork.node.services

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.http.Headers
import io.ktor.http.HttpStatusCode
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpMethod
import org.springframework.test.context.ActiveProfiles
import snc.openchargingnetwork.node.Application
import snc.openchargingnetwork.node.components.HttpClientComponent
import snc.openchargingnetwork.node.config.IntegrationsProperties
import snc.openchargingnetwork.node.config.NodeProperties
import snc.openchargingnetwork.node.models.OcnHeaders
import snc.openchargingnetwork.node.models.Receiver
import snc.openchargingnetwork.node.models.SyncedHttpResponse
import snc.openchargingnetwork.node.models.entities.IntegrationCopyOutboxEntity
import snc.openchargingnetwork.node.models.entities.IntegrationCopyStatus
import snc.openchargingnetwork.node.models.ocpi.BasicRole
import snc.openchargingnetwork.node.models.ocpi.InterfaceRole
import snc.openchargingnetwork.node.models.ocpi.ModuleID
import snc.openchargingnetwork.node.models.ocpi.OcpiRequestVariables
import snc.openchargingnetwork.node.repositories.IntegrationCopyOutboxRepository

/** The outbox against a real database; only the routing lookup and the HTTP call are replaced. */
@SpringBootTest(classes = [Application::class])
@ActiveProfiles("test")
class IntegrationCopyOutboxServiceTest(@Autowired private val outboxRepo: IntegrationCopyOutboxRepository) {

    private val target = BasicRole("OLI", "DE")
    private val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
    private val http = ScriptedHttpClient()
    private val routingService = Mockito.mock(RoutingService::class.java)
    private val properties = IntegrationsProperties().apply { outbox.maxAttempts = 3 }
    private val service = IntegrationCopyOutboxService(outboxRepo, routingService, http, properties, clock)

    private val serviceLogger = LoggerFactory.getLogger(IntegrationCopyOutboxService::class.java) as Logger
    private val logAppender = ListAppender<ILoggingEvent>().apply { start() }

    @BeforeEach
    fun setUp() {
        outboxRepo.deleteAll()
        http.reset()
        logAppender.list.clear()
        serviceLogger.addAppender(logAppender)
        whenever(routingService.getReceiverType(target)).thenReturn(Receiver.LOCAL)
        doAnswer { invocation ->
            val request = invocation.getArgument<OcpiRequestVariables>(0)
            Pair(
                "http://oli.test/sessions${request.urlPath}",
                request.headers.copy(authorization = "Token target-token", requestID = "out-${http.sent.size + 1}")
            )
        }.whenever(routingService).prepareLocalPlatformRequest(any(), eq(false))
    }

    @AfterEach
    fun tearDown() {
        serviceLogger.detachAppender(logAppender)
        outboxRepo.deleteAll()
    }

    @Test
    fun `a copy whose first attempt fails is kept and delivered on a later attempt`() {
        service.enqueue(sessionCopy(HttpMethod.PUT, "/DE/ABC/s1", "first"), "VERIFIED")
        http.script(Outcome.Unreachable, Outcome.Accepted)

        service.dispatchDue()

        var row = outboxRepo.findAll().single()
        assertThat(row.status).isEqualTo(IntegrationCopyStatus.PENDING)
        assertThat(row.attempts).isEqualTo(1)
        assertThat(row.lastError).isEqualTo("IOException")
        assertThat(row.nextAttemptAt).isEqualTo(clock.now.plusMillis(properties.outbox.backoffInitialMillis))
        assertThat(row.lockedUntil).isNull()

        service.dispatchDue()
        assertThat(http.sent).describedAs("not retried before the backoff has passed").hasSize(1)

        clock.advance(properties.outbox.backoffInitialMillis)
        service.dispatchDue()

        row = outboxRepo.findAll().single()
        assertThat(http.sent).hasSize(2)
        assertThat(http.sent.last().headers["Authorization"]).isEqualTo("Token target-token")
        assertThat(http.sent.last().headers["OCN-Verification-Status"]).isEqualTo("VERIFIED")
        assertThat(row.status).isEqualTo(IntegrationCopyStatus.DELIVERED)
        assertThat(row.attempts).isEqualTo(2)
        assertThat(row.payload).describedAs("payload is dropped once delivered").isNull()
    }

    @Test
    fun `a copy the party rejects is retried like one that could not be sent`() {
        service.enqueue(sessionCopy(HttpMethod.PUT, "/DE/ABC/s1", "first"), null)
        http.script(Outcome.Rejected)

        service.dispatchDue()

        val row = outboxRepo.findAll().single()
        assertThat(row.status).isEqualTo(IntegrationCopyStatus.PENDING)
        assertThat(row.lastError).isEqualTo("HTTP 200, OCPI 3000")
    }

    @Test
    fun `writes to one object are sent in creation order and wait for an earlier one`() {
        service.enqueue(sessionCopy(HttpMethod.PUT, "/DE/ABC/s1", "s1-put"), null)
        service.enqueue(sessionCopy(HttpMethod.PATCH, "/DE/ABC/s1", "s1-patch"), null)
        service.enqueue(sessionCopy(HttpMethod.PUT, "/DE/ABC/s2", "s2-put"), null)
        http.script(Outcome.Unreachable)

        service.dispatchDue()

        assertThat(http.sent.map { it.marker })
            .describedAs("the patch waits for the put it follows; the other session is not held back")
            .containsExactly("s1-put", "s2-put")

        clock.advance(properties.outbox.backoffInitialMillis)
        service.dispatchDue()

        assertThat(http.sent.map { it.marker }).containsExactly("s1-put", "s2-put", "s1-put", "s1-patch")
        assertThat(outboxRepo.findAll().map { it.status }).containsOnly(IntegrationCopyStatus.DELIVERED)
    }

    @Test
    fun `requests that create or read are not held back by an earlier failure`() {
        service.enqueue(sessionCopy(HttpMethod.POST, null, "cdr-1"), null)
        service.enqueue(sessionCopy(HttpMethod.POST, null, "cdr-2"), null)
        http.script(Outcome.Unreachable)

        service.dispatchDue()

        assertThat(http.sent.map { it.marker }).containsExactly("cdr-1", "cdr-2")
    }

    @Test
    fun `a copy is parked after the last attempt and logged without its payload`() {
        service.enqueue(sessionCopy(HttpMethod.PUT, "/DE/ABC/s1", "PAYLOAD-SECRET"), null)
        service.enqueue(sessionCopy(HttpMethod.PATCH, "/DE/ABC/s1", "follow-up"), null)
        http.script(Outcome.Unreachable, Outcome.Rejected, Outcome.Unreachable)

        repeat(properties.outbox.maxAttempts) {
            service.dispatchDue()
            clock.advance(properties.outbox.backoffMaxMillis)
        }

        val parked = outboxRepo.findAll().minByOrNull { it.id!! }!!
        assertThat(parked.status).isEqualTo(IntegrationCopyStatus.PARKED)
        assertThat(parked.attempts).isEqualTo(3)
        assertThat(parked.payload).describedAs("kept so an operator can requeue it").contains("PAYLOAD-SECRET")

        val parkedLogs = logAppender.list.filter { it.formattedMessage.contains("INTEGRATION_COPY_PARKED") }
        assertThat(parkedLogs).hasSize(1)
        assertThat(parkedLogs.single().level).isEqualTo(Level.ERROR)
        assertThat(parkedLogs.single().formattedMessage)
            .contains("outbox id: ${parked.id}", "PUT sessions to DE/OLI", "attempts: 3", "correlation_id: cid-1")
        assertThat(logAppender.list.map { it.formattedMessage })
            .noneMatch { it.contains("PAYLOAD-SECRET") || it.contains("sender-token") || it.contains("target-token") }

        service.dispatchDue()

        assertThat(http.sent.map { it.marker })
            .describedAs("a parked copy is not sent again and no longer holds back later ones")
            .containsExactly("PAYLOAD-SECRET", "PAYLOAD-SECRET", "PAYLOAD-SECRET", "follow-up")
    }

    @Test
    fun `the stored request does not hold the sender's token`() {
        service.enqueue(sessionCopy(HttpMethod.PUT, "/DE/ABC/s1", "first"), null)

        assertThat(outboxRepo.findAll().single().payload).contains("first").doesNotContain("sender-token")
    }

    @Test
    fun `only one replica can take a copy until its lease runs out`() {
        service.enqueue(sessionCopy(HttpMethod.PUT, "/DE/ABC/s1", "first"), null)
        val id = outboxRepo.findAll().single().id!!
        val lease = Duration.ofMinutes(5)

        assertThat(outboxRepo.claim(id, clock.now, clock.now.plus(lease))).isEqualTo(1)
        assertThat(outboxRepo.claim(id, clock.now, clock.now.plus(lease))).isEqualTo(0)

        service.dispatchDue()
        assertThat(http.sent).describedAs("a copy another replica is sending is left alone").isEmpty()

        clock.advance(lease.toMillis() + 1)
        service.dispatchDue()
        assertThat(http.sent).describedAs("taken over once the other replica's lease has run out").hasSize(1)
    }

    @Test
    fun `a replica that picked a copy before another one failed it waits for the backoff`() {
        service.enqueue(sessionCopy(HttpMethod.PUT, "/DE/ABC/s1", "first"), null)
        val id = outboxRepo.findAll().single().id!!
        val pickedAt = clock.now
        http.script(Outcome.Unreachable)

        service.dispatchDue()

        assertThat(outboxRepo.claim(id, pickedAt, pickedAt.plusSeconds(300)))
            .describedAs("the row is no longer due, although it was when this replica listed it")
            .isEqualTo(0)
        assertThat(outboxRepo.findAll().single().attempts).isEqualTo(1)
    }

    @Test
    fun `a send that outlasts its lease does not overwrite the replica that took the copy over`() {
        service.enqueue(sessionCopy(HttpMethod.PUT, "/DE/ABC/s1", "first"), null)
        val id = outboxRepo.findAll().single().id!!
        val due = clock.now
        var otherLease: Instant? = null
        http.duringNextSend = {
            clock.advance(properties.outbox.leaseMillis + 1)
            otherLease = clock.now.plusMillis(properties.outbox.leaseMillis)
            assertThat(outboxRepo.claim(id, clock.now, otherLease!!)).isEqualTo(1)
        }
        http.script(Outcome.Unreachable)

        service.dispatchDue()

        val row = outboxRepo.findAll().single()
        assertThat(row.lockedUntil).describedAs("the other replica still holds the copy").isEqualTo(otherLease)
        assertThat(row.nextAttemptAt).isEqualTo(due)
        assertThat(row.lastError).isNull()
        assertThat(row.attempts).isEqualTo(2)
        assertThat(http.sent).describedAs("the stale replica does not send the copy again").hasSize(1)
    }

    @Test
    fun `a replica that stalls past its lease before the request does not send`() {
        service.enqueue(sessionCopy(HttpMethod.PUT, "/DE/ABC/s1", "first"), null)
        val id = outboxRepo.findAll().single().id!!
        var otherLease: Instant? = null
        doAnswer { invocation ->
            val request = invocation.getArgument<OcpiRequestVariables>(0)
            if (otherLease == null) {
                clock.advance(properties.outbox.leaseMillis + 1)
                otherLease = clock.now.plusMillis(properties.outbox.leaseMillis)
                assertThat(outboxRepo.claim(id, clock.now, otherLease!!)).isEqualTo(1)
            }
            Pair("http://oli.test/sessions${request.urlPath}", request.headers.copy(authorization = "Token target-token"))
        }.whenever(routingService).prepareLocalPlatformRequest(any(), eq(false))

        service.dispatchDue()

        assertThat(http.sent).describedAs("the copy now belongs to the replica that took it over").isEmpty()
        val row = outboxRepo.findAll().single()
        assertThat(row.lockedUntil).isEqualTo(otherLease)
        assertThat(row.status).isEqualTo(IntegrationCopyStatus.PENDING)
        assertThat(row.lastError).isNull()
    }

    @Test
    fun `a later write is not taken while an earlier one to the same object is pending again`() {
        service.enqueue(sessionCopy(HttpMethod.PUT, "/DE/ABC/s1", "first"), null)
        service.enqueue(sessionCopy(HttpMethod.PATCH, "/DE/ABC/s1", "second"), null)
        val (first, second) = outboxRepo.findAll().sortedBy { it.id }.map { it.id!! }

        assertThat(outboxRepo.claim(second, clock.now, clock.now.plusSeconds(300)))
            .describedAs("listed as due by a replica before the earlier write failed and went back to pending")
            .isEqualTo(0)
        assertThat(outboxRepo.claim(first, clock.now, clock.now.plusSeconds(300))).isEqualTo(1)
    }

    @Test
    fun `replicas claiming at the same time never both get a copy`() {
        repeat(20) { service.enqueue(sessionCopy(HttpMethod.POST, null, "cdr-$it"), null) }
        val ids = outboxRepo.findAll().map { it.id!! }
        val replicas = 4
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(replicas)

        val claims =
            (1..replicas)
                .map {
                    pool.submit<Int> {
                        start.await()
                        ids.sumOf { id -> outboxRepo.claim(id, clock.now, clock.now.plusSeconds(300)) }
                    }
                }
                .also { start.countDown() }
                .sumOf { it.get() }
        pool.shutdown()

        assertThat(claims).isEqualTo(ids.size)
    }

    @Test
    fun `cleanup deletes delivered copies past the retention and nothing else`() {
        val retention = Duration.ofHours(properties.outbox.deliveredRetentionHours)
        val old = clock.now.minus(retention).minusSeconds(1)
        val oldDelivered = outboxRepo.save(row(IntegrationCopyStatus.DELIVERED, deliveredAt = old))
        val recentDelivered = outboxRepo.save(row(IntegrationCopyStatus.DELIVERED, deliveredAt = clock.now))
        val oldParked = outboxRepo.save(row(IntegrationCopyStatus.PARKED, deliveredAt = null))

        assertThat(service.purgeDelivered()).isEqualTo(1)

        assertThat(outboxRepo.findAll().map { it.id })
            .containsExactlyInAnyOrder(recentDelivered.id, oldParked.id)
            .doesNotContain(oldDelivered.id)
    }

    private fun row(status: IntegrationCopyStatus, deliveredAt: Instant?) =
        IntegrationCopyOutboxEntity(
            targetCountry = "DE",
            targetParty = "OLI",
            module = "sessions",
            method = "PUT",
            objectKey = "DE/ABC/s1",
            payload = null,
            verificationStatus = null,
            correlationId = "cid-1",
            createdAt = clock.now.minus(Duration.ofDays(30)),
            nextAttemptAt = clock.now.minus(Duration.ofDays(30)),
            status = status,
            deliveredAt = deliveredAt
        )

    private fun sessionCopy(method: HttpMethod, urlPath: String?, marker: String) =
        OcpiRequestVariables(
            module = ModuleID.SESSIONS,
            interfaceRole = InterfaceRole.RECEIVER,
            method = method,
            urlPath = urlPath,
            headers =
                OcnHeaders(
                    authorization = "Token sender-token",
                    requestID = "rid-1",
                    correlationID = "cid-1",
                    sender = BasicRole("ABC", "DE"),
                    receiver = target
                ),
            body = mapOf("id" to marker)
        )

    private class MutableClock(var now: Instant) : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        fun advance(millis: Long) {
            now = now.plusMillis(millis)
        }
    }

    private enum class Outcome { Accepted, Rejected, Unreachable }

    private class SentRequest(val headers: Map<String, String>, body: Any?) {
        val marker: String = Regex("\"id\":\"([^\"]+)\"").find(body.toString())!!.groupValues[1]
    }

    /** Answers with the scripted outcomes in order, then accepts everything. */
    private class ScriptedHttpClient : HttpClientComponent(NodeProperties()) {
        val sent = mutableListOf<SentRequest>()
        private val outcomes = ArrayDeque<Outcome>()
        var duringNextSend: (() -> Unit)? = null

        fun script(vararg next: Outcome) {
            outcomes.addAll(next)
        }

        fun reset() {
            sent.clear()
            outcomes.clear()
            duringNextSend = null
        }

        override fun sendHttpRequest(
            endpoint: String,
            method: HttpMethod,
            body: Any?,
            headers: Map<String, String>,
            queryParams: Map<String, String>
        ): SyncedHttpResponse {
            sent += SentRequest(headers, body)
            duringNextSend?.also { duringNextSend = null }?.invoke()
            return when (outcomes.removeFirstOrNull() ?: Outcome.Accepted) {
                Outcome.Accepted -> ocpiResponse(1000)
                Outcome.Rejected -> ocpiResponse(3000)
                Outcome.Unreachable -> throw IOException("Connection refused")
            }
        }

        private fun ocpiResponse(statusCode: Int) =
            SyncedHttpResponse(
                statusCode = HttpStatusCode.OK,
                headers = Headers.Empty,
                contentType = null,
                contentLength = null,
                body = """{"status_code":$statusCode,"timestamp":"2026-01-01T00:00:00Z"}"""
            )
    }
}
