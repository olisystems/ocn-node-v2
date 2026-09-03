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

import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import snc.openchargingnetwork.node.components.HttpClientComponent
import snc.openchargingnetwork.node.config.NodeProperties
import snc.openchargingnetwork.node.models.PartyStillAliveResult
import snc.openchargingnetwork.node.models.StillAliveCheckSummary
import snc.openchargingnetwork.node.models.entities.PlatformEntity
import snc.openchargingnetwork.node.models.exceptions.OcpiClientInvalidParametersException
import snc.openchargingnetwork.node.models.exceptions.OcpiServerUnusableApiException
import snc.openchargingnetwork.node.models.ocpi.BasicRole
import snc.openchargingnetwork.node.models.ocpi.ConnectionStatus
import snc.openchargingnetwork.node.repositories.PlatformRepository
import snc.openchargingnetwork.node.repositories.RoleRepository
import snc.openchargingnetwork.node.tools.getInstant

/**
 * Determines whether the platforms registered on this node are still reachable, by pinging their
 * OCPI Versions endpoint, and updates their connection status accordingly.
 *
 * This is the only place a platform is moved to [ConnectionStatus.OFFLINE]: the hub client info
 * sync only reconciles network parties against the registry and never reports on liveness.
 */
@Service
class StillAliveService(
        private val properties: NodeProperties,
        private val httpClientComponent: HttpClientComponent,
        private val platformRepo: PlatformRepository,
        private val roleRepo: RoleRepository
) {

    companion object {
        private val logger = LoggerFactory.getLogger(StillAliveService::class.java)

        /**
         * Only platforms that completed the credentials handshake are pinged - a PLANNED or
         * SUSPENDED platform has no versions URL or auth token to ping with.
         */
        private val CHECKABLE_STATUSES =
                listOf(ConnectionStatus.CONNECTED, ConnectionStatus.OFFLINE)
    }

    /**
     * Scheduled variant: only pings platforms that haven't been heard from within
     * [NodeProperties.stillAliveRate], leaving recently seen ones untouched.
     */
    fun checkStalePlatforms(): StillAliveCheckSummary {
        val now = Instant.now()
        return checkPlatforms(now, staleCutoff = now.minusMillis(properties.stillAliveRate))
    }

    /**
     * Manual variant: pings every registered platform right away, no matter how recently it was
     * last heard from, so a status can be refreshed on demand instead of waiting for the next
     * scheduled run.
     */
    fun checkAllPlatforms(): StillAliveCheckSummary {
        return checkPlatforms(Instant.now(), staleCutoff = null)
    }

    /**
     * Manual variant for a single party: pings the platform the party is registered on. Every party
     * sharing that platform shares its connection status, so they are all refreshed together.
     *
     * @throws IllegalArgumentException if the party is not registered on this node (e.g. it is a
     * network party this node only knows about from the registry).
     */
    fun checkParty(party: BasicRole): PartyStillAliveResult {
        val role =
                roleRepo.findFirstByCountryCodeAndPartyIDAllIgnoreCaseOrderByIdAsc(
                        countryCode = party.country,
                        partyID = party.id
                )
                        ?: throw IllegalArgumentException(
                                "${party.country}/${party.id} is not registered on this node - a still alive check only applies to platforms connected to it"
                        )

        val platform =
                platformRepo.findById(role.platformID).orElseThrow {
                    IllegalArgumentException(
                            "Platform ${role.platformID} of ${party.country}/${party.id} no longer exists"
                    )
                }

        val status = checkPlatform(platform, Instant.now())
        val affectedParties = roleRepo.findAllByPlatformID(platform.id).count()

        logger.info("Still alive check for ${party.country}/${party.id}: $status")

        return PartyStillAliveResult(
                countryCode = party.country,
                partyID = party.id,
                status = status,
                affectedParties = affectedParties,
                message =
                        "${party.country}/${party.id} is $status" +
                                if (affectedParties > 1) {
                                    " (shared with ${affectedParties - 1} other party role(s) on the same platform)"
                                } else {
                                    ""
                                }
        )
    }

    /**
     * Ping every checkable platform, skipping those last heard from after [staleCutoff] when one is
     * given.
     */
    private fun checkPlatforms(
            checkInstant: Instant,
            staleCutoff: Instant?
    ): StillAliveCheckSummary {
        var connected = 0
        var offline = 0
        var skipped = 0

        for (platform in platformRepo.findByStatusIn(CHECKABLE_STATUSES)) {
            if (staleCutoff != null && getInstant(platform.lastUpdated) >= staleCutoff) {
                skipped++
                continue
            }

            when (checkPlatform(platform, checkInstant)) {
                ConnectionStatus.CONNECTED -> connected++
                else -> offline++
            }
        }

        val checked = connected + offline
        val message =
                "Still alive check completed for $checked platform(s): $connected connected, $offline offline" +
                        if (skipped > 0) " ($skipped skipped as recently seen)" else ""

        logger.info(message)

        return StillAliveCheckSummary(
                checked = checked,
                connected = connected,
                offline = offline,
                skipped = skipped,
                message = message
        )
    }

    /**
     * Update a platform's connection status from its current availability. Saving the platform
     * publishes the reconnected/disconnected domain event, which broadcasts the change to the
     * network.
     */
    private fun checkPlatform(platform: PlatformEntity, checkInstant: Instant): ConnectionStatus {
        if (isClientAvailable(platform)) {
            platform.renewConnection(checkInstant)
        } else {
            platform.disconnect(checkInstant)
        }
        platformRepo.save(platform)
        return platform.status
    }

    /** Ping a platform's Versions endpoint to determine its availability. */
    private fun isClientAvailable(client: PlatformEntity): Boolean {
        try {
            if (client.versionsUrl == null) {
                return false // Client isn't configured. Assume not available
            }
            val authToken = client.getAuthTokenToIncludeInRequestHeader()
            // If no exception thrown during version request, assume that request was successful
            httpClientComponent.getVersions(client.versionsUrl!!, authToken)
            return true
        } catch (e: OcpiServerUnusableApiException) {
            return false
        } catch (e: OcpiClientInvalidParametersException) {
            return false
        }
    }
}
