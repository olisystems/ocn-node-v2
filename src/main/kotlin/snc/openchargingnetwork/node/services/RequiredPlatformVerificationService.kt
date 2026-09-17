package snc.openchargingnetwork.node.services

import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import snc.openchargingnetwork.node.config.NodeProperties
import snc.openchargingnetwork.node.config.RegistryIndexerProperties
import snc.openchargingnetwork.node.config.RequiredPlatformProperties
import snc.openchargingnetwork.node.models.ocpi.BasicRole
import snc.openchargingnetwork.node.models.ocpi.ConnectionStatus
import snc.openchargingnetwork.node.repositories.PlatformRepository
import snc.openchargingnetwork.node.repositories.RoleRepository

/** Where the required party stands relative to the OCN Registry on chain. */
sealed class RegistryStatus {
    object RegisteredToThisNode : RegistryStatus()

    data class RegisteredToAnotherNode(val domain: String) : RegistryStatus()

    data class NotRegistered(val reason: String) : RegistryStatus()

    /** The registry could not be reached, so registration is unknown - not absent. */
    data class RegistryUnavailable(val reason: String) : RegistryStatus()
}

sealed class RequiredPlatformStatus {
    data class Connected(val platformId: Long, val roles: List<String>) : RequiredPlatformStatus()

    data class HandshakeIncomplete(
            val platformId: Long,
            val status: ConnectionStatus,
            val hasTokenC: Boolean
    ) : RequiredPlatformStatus()

    /**
     * A Platform row exists but no party has claimed it yet. Roles are only written when the
     * credentials handshake completes, so a freshly created Platform has none and cannot be found
     * by party - telling the operator to create another one would just make a duplicate.
     */
    data class AwaitingHandshake(val platformIds: List<Long>) : RequiredPlatformStatus()

    data class PlatformMissing(val registry: RegistryStatus) : RequiredPlatformStatus()

    object CheckDisabled : RequiredPlatformStatus()
}

/**
 * Answers whether the required party (DE/BAN by default) is connected to this node, and when it is
 * not, whether the reason is a missing handshake or a missing blockchain registration.
 */
@Service
class RequiredPlatformVerificationService(
        private val platformRepo: PlatformRepository,
        private val roleRepo: RoleRepository,
        private val registryService: RegistryService,
        private val requiredPlatformProperties: RequiredPlatformProperties,
        private val registryIndexerProperties: RegistryIndexerProperties,
        private val nodeProperties: NodeProperties
) {

    companion object {
        private val logger = LoggerFactory.getLogger(RequiredPlatformVerificationService::class.java)
    }

    val countryCode: String
        get() = requiredPlatformProperties.countryCode

    val partyId: String
        get() = requiredPlatformProperties.partyId

    val dashboardUrl: String
        get() = requiredPlatformProperties.dashboardUrl.trimEnd('/')

    val registryUrl: String
        get() = registryIndexerProperties.url

    val nodeUrl: String
        get() = nodeProperties.url

    fun verify(): RequiredPlatformStatus {
        if (!requiredPlatformProperties.enabled) {
            return RequiredPlatformStatus.CheckDisabled
        }

        val role =
                roleRepo.findFirstByCountryCodeAndPartyIDAllIgnoreCaseOrderByIdAsc(
                        countryCode,
                        partyId
                )
                        ?: return unclaimedPlatformStatus()

        val platform =
                platformRepo.findByIdOrNull(role.platformID) ?: return unclaimedPlatformStatus()

        val platformId = platform.id ?: role.platformID

        // Same predicate AdminController.createPlatform uses to call a platform active.
        if (platform.status != ConnectionStatus.CONNECTED || platform.auth.tokenC.isNullOrEmpty()) {
            return RequiredPlatformStatus.HandshakeIncomplete(
                    platformId,
                    platform.status,
                    !platform.auth.tokenC.isNullOrEmpty()
            )
        }

        val roles = roleRepo.findAllByPlatformID(platformId).map { it.role.toString() }
        return RequiredPlatformStatus.Connected(platformId, roles)
    }

    /**
     * Distinguishes "nobody created a Platform" from "a Platform is created and waiting". Same
     * zero-roles test AdminController.deleteUnusedPlatforms uses.
     */
    private fun unclaimedPlatformStatus(): RequiredPlatformStatus {
        val unclaimed =
                platformRepo.findAll().filter { roleRepo.findAllByPlatformID(it.id).none() }.mapNotNull {
                    it.id
                }
        return if (unclaimed.isNotEmpty()) {
            RequiredPlatformStatus.AwaitingHandshake(unclaimed)
        } else {
            RequiredPlatformStatus.PlatformMissing(checkRegistry())
        }
    }

    /**
     * A registry that cannot be reached is reported separately from a party that is genuinely
     * absent - otherwise a flaky subgraph reads as a deregistered party and sends the operator off
     * to fix the blockchain for nothing.
     */
    private fun checkRegistry(): RegistryStatus {
        val role = BasicRole(partyId, countryCode)
        return try {
            if (registryService.isRoleKnown(role, belongsToMe = true)) {
                RegistryStatus.RegisteredToThisNode
            } else {
                RegistryStatus.RegisteredToAnotherNode(registryService.getRemoteNodeUrlOf(role))
            }
        } catch (e: Exception) {
            val reason = e.message ?: e::class.java.simpleName
            if (reason.contains("not registered on OCN Registry", ignoreCase = true)) {
                RegistryStatus.NotRegistered(reason)
            } else {
                logger.warn("[RequiredPlatform] registry lookup failed: {}", reason)
                RegistryStatus.RegistryUnavailable(reason)
            }
        }
    }
}
