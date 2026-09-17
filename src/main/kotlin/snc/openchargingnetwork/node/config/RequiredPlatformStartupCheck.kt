package snc.openchargingnetwork.node.config

import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.annotation.Profile
import org.springframework.context.event.EventListener
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import snc.openchargingnetwork.node.services.RegistryStatus
import snc.openchargingnetwork.node.services.RequiredPlatformStatus
import snc.openchargingnetwork.node.services.RequiredPlatformVerificationService

/**
 * Reports at startup whether the required party (DE/BAN) is actually connected to this node.
 *
 * Logs and continues rather than failing startup: a node that refuses to boot cannot serve the
 * admin API the operator needs to create the platform, and /health sits behind a startup probe
 * that would crash-loop the pod. This mirrors Verification.testRegistryAccess.
 */
@Profile("!test")
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
class RequiredPlatformStartupCheck(
        private val verificationService: RequiredPlatformVerificationService
) {

    companion object {
        private val logger = LoggerFactory.getLogger(RequiredPlatformStartupCheck::class.java)
        private const val BORDER_WIDTH = 69
    }

    @EventListener(ApplicationReadyEvent::class)
    fun check() {
        try {
            report()
        } catch (e: Exception) {
            // An exception thrown from an ApplicationReadyEvent listener aborts SpringApplication.run,
            // which is exactly the crash-loop this check is documented to avoid.
            logger.error("[RequiredPlatform] check failed: {}", e.message, e)
        }
    }

    private fun report() {
        val party = "${verificationService.countryCode} ${verificationService.partyId}"

        when (val status = verificationService.verify()) {
            is RequiredPlatformStatus.CheckDisabled ->
                    logger.info("[RequiredPlatform] check disabled (ocn.required-platform.enabled=false)")
            is RequiredPlatformStatus.Connected ->
                    logger.info(
                            "[RequiredPlatform] {} platform #{} is CONNECTED (roles: {})",
                            party,
                            status.platformId,
                            status.roles.joinToString(", ")
                    )
            is RequiredPlatformStatus.HandshakeIncomplete ->
                    logger.error(
                            banner(
                                    "OCN NODE: REQUIRED PLATFORM NOT HANDSHAKED",
                                    listOf(
                                            "Platform #${status.platformId} for $party exists but its status is ${status.status}.",
                                            if (status.hasTokenC) "Its token C is set but the connection never completed."
                                            else "Token C was never issued - the OCPI credentials handshake did not complete.",
                                            "",
                                            "Fix it from the DE*BAN OCN Connection page:",
                                            "  ${verificationService.dashboardUrl}/admin/deban/credentials",
                                            "Confirm Token A matches this platform's Token A, save, then press",
                                            "\"Perform Handshake\"."
                                    )
                            )
                    )
            is RequiredPlatformStatus.AwaitingHandshake ->
                    logger.error(
                            banner(
                                    "OCN NODE: REQUIRED PLATFORM AWAITING HANDSHAKE",
                                    listOf(
                                            "A Platform exists on this node but no party has claimed it yet, so",
                                            "$party is not connected. Roles are only recorded once the OCPI",
                                            "credentials handshake completes.",
                                            "Unclaimed platform id(s): ${status.platformIds.joinToString(", ")}",
                                            "",
                                            "Do NOT create another Platform - copy the Token A of the platform",
                                            "above from:",
                                            "  ${verificationService.dashboardUrl}/admin/ocn/platforms",
                                            "then paste it at:",
                                            "  ${verificationService.dashboardUrl}/admin/deban/credentials",
                                            "save, and press \"Perform Handshake\"."
                                    )
                            )
                    )
            is RequiredPlatformStatus.PlatformMissing -> reportMissing(party, status.registry)
        }
    }

    private fun reportMissing(party: String, registry: RegistryStatus) {
        val dashboard = verificationService.dashboardUrl
        when (registry) {
            is RegistryStatus.RegisteredToThisNode ->
                    logger.error(
                            banner(
                                    "OCN NODE: REQUIRED PLATFORM MISSING",
                                    listOf(
                                            "No Platform is registered for $party on this node, but $party IS",
                                            "registered in the OCN Registry and points at this node.",
                                            "",
                                            "To connect it:",
                                            " 1. Create the Platform for $party (roles HUB and NSP):",
                                            "      $dashboard/admin/ocn/platforms",
                                            "    Copy the Token A from the response.",
                                            " 2. Open the DE*BAN OCN Connection page:",
                                            "      $dashboard/admin/deban/credentials",
                                            "    Paste Token A, save, then press \"Perform Handshake\"."
                                    )
                            )
                    )
            is RegistryStatus.NotRegistered ->
                    logger.error(
                            banner(
                                    "OCN NODE: REQUIRED PARTY NOT REGISTERED IN THE REGISTRY",
                                    listOf(
                                            "$party is not registered in the OCN Registry (blockchain).",
                                            "Registry subgraph: ${verificationService.registryUrl}",
                                            "Reason: ${registry.reason}",
                                            "",
                                            "Register the $party party in the OCN Registry contract before",
                                            "creating its Platform. Registered parties are listed at:",
                                            "  $dashboard/admin/ocn/parties"
                                    )
                            )
                    )
            is RegistryStatus.RegisteredToAnotherNode ->
                    logger.error(
                            banner(
                                    "OCN NODE: REQUIRED PARTY BELONGS TO ANOTHER NODE",
                                    listOf(
                                            "$party is registered in the OCN Registry but its operator points at",
                                            "OCN node ${registry.domain}, not this one (${verificationService.nodeUrl}).",
                                            "",
                                            "Re-point the party in the registry, or correct this node's",
                                            "OCN_NODE_URL / private key."
                                    )
                            )
                    )
            is RegistryStatus.RegistryUnavailable ->
                    logger.error(
                            banner(
                                    "OCN NODE: REQUIRED PLATFORM MISSING",
                                    listOf(
                                            "No Platform is registered for $party on this node, and the OCN",
                                            "Registry could not be reached to check whether $party is registered",
                                            "on chain.",
                                            "Registry subgraph: ${verificationService.registryUrl}",
                                            "Reason: ${registry.reason}",
                                            "",
                                            "Create the Platform at $dashboard/admin/ocn/platforms once the",
                                            "registry is reachable again."
                                    )
                            )
                    )
        }
    }

    private fun banner(title: String, lines: List<String>): String {
        val border = "=".repeat(BORDER_WIDTH)
        val header = "=== $title ".let { it + "=".repeat(maxOf(0, BORDER_WIDTH - it.length)) }
        return buildString {
            append("\n")
            append(header)
            lines.forEach { append("\n ").append(it) }
            append("\n")
            append(border)
        }
    }
}
