/*
    Copyright 2019-2020 eMobility GmbH

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.
*/

package snc.openchargingnetwork.node.services

import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.task.AsyncTaskExecutor
import org.springframework.http.HttpMethod
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import snc.openchargingnetwork.node.components.HttpClientComponent
import snc.openchargingnetwork.node.config.NodeProperties
import snc.openchargingnetwork.node.models.BroadcastDelivery
import snc.openchargingnetwork.node.models.BroadcastDelivery.Outcome
import snc.openchargingnetwork.node.models.OcnHeaders
import snc.openchargingnetwork.node.models.entities.PlatformEntity
import snc.openchargingnetwork.node.models.exceptions.OcpiHubGenericException
import snc.openchargingnetwork.node.models.entities.RoleEntity
import snc.openchargingnetwork.node.models.ocpi.*
import snc.openchargingnetwork.node.repositories.EndpointRepository
import snc.openchargingnetwork.node.repositories.PlatformRepository
import snc.openchargingnetwork.node.repositories.RoleRepository
import snc.openchargingnetwork.node.tools.generateUUIDv4Token
import snc.openchargingnetwork.node.tools.toBs64String
import snc.openchargingnetwork.node.tools.withCorrelationId

/**
 * Generic service for handling module notifications to connected parties This service manages OCPI
 * module notifications with comprehensive functionality Uses event-driven architecture for
 * broadcasting changes across different modules
 */
@Service
class ModuleNotificationService(
    private val platformRepo: PlatformRepository,
    private val roleRepo: RoleRepository,
    private val endpointRepo: EndpointRepository,
    private val httpClientComponent: HttpClientComponent,
    private val routingService: RoutingService,
    private val ocnRulesService: OcnRulesService,
    private val nodeProperties: NodeProperties,
    // Spring Boot's @Async executor, which copies the MDC (correlation ID) into its threads.
    @Qualifier("applicationTaskExecutor") private val taskExecutor: AsyncTaskExecutor
) {

    companion object {
        private val logger = LoggerFactory.getLogger(ModuleNotificationService::class.java)
    }

    /**
     * Get parties who should be sent a module Push notification (sans the changedPlatform if
     * provided)
     */
    fun getPartiesToNotifyOfModuleChange(
        moduleId: ModuleID,
        changedPlatform: PlatformEntity? = null,
        partyId: String,
        countryCode: String
    ): List<RoleEntity> {
        val clientsToNotify = mutableListOf<RoleEntity>()
        var changedPlatformId = changedPlatform?.id

        if (changedPlatform == null) {
            val roles = roleRepo.findAllByCountryCodeAndPartyIDAllIgnoreCase(countryCode, partyId)
            if (roles.count() > 0) {
                changedPlatformId = roles.first().platformID
            }
        }

        for (platform in platformRepo.findAll()) {
            // Only push the update if the platform is connected and it isn't the platform that
            // triggered
            // the event
            if (platform.status == ConnectionStatus.CONNECTED && platform.id != changedPlatformId) {
                // Only push the update if the platform has implemented the module Receiver endpoint
                val modulePutEndpoint =
                    endpointRepo.findFirstByPlatformIDAndIdentifierAndRoleOrderByIdAsc(
                        platformID = platform.id,
                        identifier = moduleId.id,
                        Role = InterfaceRole.RECEIVER
                    )

                if (modulePutEndpoint != null) {
                    for (clientRole in roleRepo.findAllByPlatformID(platform.id)) {
                        // Never notify the object/module owner back to itself
                        if (isSameParty(clientRole.countryCode, clientRole.partyID, countryCode, partyId)) {
                            continue
                        }
                        // Only push the update if the role has whitelisted the module owner
                        // and is an applicable counterpart for this module (not every role).
                        val counterParty = BasicRole(id = partyId, country = countryCode)
                        if (
                            ocnRulesService.isWhitelisted(platform, counterParty) &&
                                isCounterpartRole(moduleId, clientRole.role)
                        ) {
                            clientsToNotify.add(clientRole)
                        }
                    }
                }
            }
        }

        return clientsToNotify
    }

    /**
     * OCPI Broadcast Push targets applicable opposite roles, not every role on a platform.
     * Locations/tariffs originate from CPOs; tokens from eMSPs.
     */
    private fun isCounterpartRole(moduleId: ModuleID, recipientRole: Role): Boolean {
        return when (moduleId) {
            ModuleID.LOCATIONS,
            ModuleID.TARIFFS ->
                recipientRole in setOf(Role.EMSP, Role.NSP, Role.NAP, Role.HUB, Role.SCSP)
            ModuleID.TOKENS -> recipientRole in setOf(Role.CPO, Role.HUB)
            else -> true
        }
    }

    /** Send a notification of a module change to a list of parties with a custom sender */
    fun notifyPartiesOfModuleChange(
        moduleId: ModuleID,
        parties: Iterable<RoleEntity>,
        changedData: Any?,
        urlPath: String,
        sender: BasicRole,
        method: HttpMethod = HttpMethod.PUT,
        queryParams: Map<String, Any?>? = null
    ) {
        // One correlation ID for the whole broadcast, so every receiver can be linked to it.
        withCorrelationId {
            for (party in parties) {
                val platform = platformRepo.findById(party.platformID).orElse(null)
                if (platform != null) {
                    notifyPartyOfModuleChange(
                        moduleId = moduleId,
                        partyId = party.partyID,
                        countryCode = party.countryCode,
                        authToken = platform.getAuthTokenToIncludeInRequestHeader(),
                        changedData = changedData,
                        urlPath = urlPath,
                        sender = sender,
                        method = method,
                        queryParams = queryParams
                    )
                }
            }
        }
    }

    /**
     * Send a notification of a module change to a list of parties asynchronously with a custom
     * sender
     */
    @Async
    fun notifyPartiesOfModuleChangeAsync(
        moduleId: ModuleID,
        parties: Iterable<RoleEntity>,
        changedData: Any?,
        urlPath: String,
        sender: BasicRole,
        method: HttpMethod = HttpMethod.PUT,
        queryParams: Map<String, Any?>? = null
    ) {
        logger.info(
            "Starting async notification of ${moduleId.id} change to ${parties.count()} parties (custom sender)"
        )
        notifyPartiesOfModuleChange(
            moduleId,
            parties,
            changedData,
            urlPath,
            sender,
            method,
            queryParams
        )
        logger.info("Completed async notification of ${moduleId.id} change (custom sender)")
    }

    /**
     * Broadcast an object push while preserving its HTTP method, path and query parameters, and
     * report what each receiver answered so the sender learns about rejections. Receivers are
     * called in parallel; those that have not answered within [NodeProperties.broadcastWaitMillis]
     * are reported as pending, and their outcome is logged when it arrives.
     */
    fun broadcastObjectRequest(request: OcpiRequestVariables): List<BroadcastDelivery> {
        val parties = broadcastReceivers(request)
        // Hub Broadcast Push must advertise the hub identity in OCPI-from-* headers.
        val hubCountryCode =
            nodeProperties.countryCode
                ?: throw IllegalStateException("ocn.node.countryCode must be configured")
        val hubPartyId =
            nodeProperties.partyId
                ?: throw IllegalStateException("ocn.node.partyId must be configured")
        val hubSender = BasicRole(id = hubPartyId, country = hubCountryCode)

        // Set before submitting, so the executor copies one correlation ID to every receiver.
        val deliveries = withCorrelationId {
            parties.mapNotNull { party ->
                val platform = platformRepo.findById(party.platformID).orElse(null) ?: return@mapNotNull null
                val receiver = BasicRole(party.partyID, party.countryCode)
                val authToken = platform.getAuthTokenToIncludeInRequestHeader()
                receiver to
                    taskExecutor.submitCompletable(Callable {
                        notifyPartyOfModuleChange(
                            moduleId = request.module,
                            partyId = party.partyID,
                            countryCode = party.countryCode,
                            authToken = authToken,
                            changedData = request.body,
                            urlPath = request.urlPath?.removePrefix("/") ?: "",
                            sender = hubSender,
                            method = request.method,
                            queryParams = request.queryParams
                        )
                    })
            }
        }

        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(nodeProperties.broadcastWaitMillis)
        return deliveries.map { (receiver, delivery) -> awaitDelivery(receiver, delivery, deadline) }
    }

    private fun awaitDelivery(
        receiver: BasicRole,
        delivery: CompletableFuture<BroadcastDelivery>,
        deadline: Long
    ): BroadcastDelivery {
        return try {
            delivery.get(maxOf(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)
        } catch (e: TimeoutException) {
            delivery.whenComplete { late, _ ->
                logger.info("Late broadcast answer from {}: {}", receiver, late?.describe() ?: "failed")
            }
            BroadcastDelivery(receiver, Outcome.PENDING)
        } catch (e: Exception) {
            logger.error("Broadcast to {} failed: {}", receiver, e.message)
            BroadcastDelivery(receiver, Outcome.UNREACHABLE)
        }
    }

    /** Parties that should receive a hub broadcast of [request], without its object owner. */
    private fun broadcastReceivers(request: OcpiRequestVariables): List<RoleEntity> {
        val requestSender = request.headers.sender
        val objectOwner = resolveObjectOwner(request)
        // Prefer object owner (path/body country_code + party_id) so a hub re-broadcast
        // does not bounce the object back to the owning CPO/eMSP.
        val ownerCountry = objectOwner?.country ?: requestSender.country
        val ownerPartyId = objectOwner?.id ?: requestSender.id

        val parties =
            getPartiesToNotifyOfModuleChange(
                    moduleId = request.module,
                    partyId = ownerPartyId,
                    countryCode = ownerCountry
                )
                .filterNot { party ->
                    isSameParty(party.countryCode, party.partyID, ownerCountry, ownerPartyId)
                }

        if (objectOwner != null &&
            !isSameParty(objectOwner.country, objectOwner.id, requestSender.country, requestSender.id)
        ) {
            logger.info(
                "Broadcasting {} excluding object owner {}/{} (request sender {}/{})",
                request.module.id,
                objectOwner.country,
                objectOwner.id,
                requestSender.country,
                requestSender.id
            )
        }

        return parties
    }

    /**
     * Resolve the OCPI object owner from the request path (`/{country_code}/{party_id}/...`) or
     * body (`country_code` / `party_id`).
     */
    internal fun resolveObjectOwner(request: OcpiRequestVariables): BasicRole? {
        resolveObjectOwnerFromUrlPath(request.urlPath)?.let {
            return it
        }
        return resolveObjectOwnerFromBody(request.body)
    }

    private fun resolveObjectOwnerFromUrlPath(urlPath: String?): BasicRole? {
        if (urlPath.isNullOrBlank()) {
            return null
        }
        val segments = urlPath.trim('/').split('/').filter { it.isNotBlank() }
        if (segments.size < 2) {
            return null
        }
        val countryCode = segments[0]
        val partyId = segments[1]
        if (!looksLikeOcpiParty(countryCode, partyId)) {
            return null
        }
        return BasicRole(id = partyId, country = countryCode)
    }

    @Suppress("UNCHECKED_CAST")
    private fun resolveObjectOwnerFromBody(body: Any?): BasicRole? {
        val map =
            when (body) {
                is Map<*, *> -> body as Map<*, *>
                else -> return null
            }
        val countryCode = (map["country_code"] ?: map["countryCode"])?.toString()?.trim()
        val partyId = (map["party_id"] ?: map["partyId"])?.toString()?.trim()
        if (countryCode.isNullOrBlank() || partyId.isNullOrBlank()) {
            return null
        }
        if (!looksLikeOcpiParty(countryCode, partyId)) {
            return null
        }
        return BasicRole(id = partyId, country = countryCode)
    }

    private fun looksLikeOcpiParty(countryCode: String, partyId: String): Boolean {
        return countryCode.length == 2 && partyId.length == 3
    }

    private fun isSameParty(
        countryA: String?,
        partyA: String?,
        countryB: String?,
        partyB: String?
    ): Boolean {
        return !countryA.isNullOrBlank() &&
            !partyA.isNullOrBlank() &&
            countryA.equals(countryB, ignoreCase = true) &&
            partyA.equals(partyB, ignoreCase = true)
    }

    fun notifyPartyOfModuleChange(
        moduleId: ModuleID,
        partyId: String,
        countryCode: String,
        authToken: String,
        changedData: Any?,
        urlPath: String,
        sender: BasicRole,
        method: HttpMethod = HttpMethod.PUT,
        queryParams: Map<String, Any?>? = null
    ): BroadcastDelivery {
        return withCorrelationId { correlationId ->
            val receiver = BasicRole(partyId, countryCode)
            val requestVariables =
                OcpiRequestVariables(
                    module = moduleId,
                    interfaceRole = InterfaceRole.RECEIVER,
                    method = method,
                    headers =
                        OcnHeaders(
                            authorization = "Token ${authToken.toBs64String()}",
                            requestID = generateUUIDv4Token(),
                            correlationID = correlationId,
                            sender = sender,
                            receiver = receiver
                        ),
                    body = changedData,
                    urlPath = urlPath,
                    queryParams = queryParams
                )

            // Notifications only log a failure; the hub broadcast also reports it to the sender.
            try {
                val (url, headers) =
                    routingService.prepareLocalPlatformRequest(requestVariables, proxied = false)
                val response = httpClientComponent.makeOcpiRequest<Unit>(url, headers, requestVariables)
                if (response.isOcpiSuccess()) {
                    BroadcastDelivery(receiver, Outcome.DELIVERED, response.statusCode, response.body?.statusCode)
                } else {
                    logger.error(
                        "$receiver rejected the ${moduleId.id} change: HTTP ${response.statusCode}, " +
                            "OCPI ${response.body?.statusCode} ${response.body?.statusMessage.orEmpty()}"
                    )
                    BroadcastDelivery(
                        receiver,
                        Outcome.REJECTED,
                        response.statusCode,
                        response.body?.statusCode,
                        response.body?.statusMessage
                    )
                }
            } catch (e: Exception) {
                logger.error("Error notifying $receiver of ${moduleId.id} change: ${e.message}")
                // The exception text can name the receiver's URL, which the sender must not see.
                val reason =
                    if (e is OcpiHubGenericException) "answered without a valid OCPI body"
                    else "could not be reached"
                BroadcastDelivery(receiver, Outcome.UNREACHABLE, message = reason)
            }
        }
    }
}
