package snc.openchargingnetwork.node.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * The party that must be connected for this node to be useful. Routing for LOCATIONS and TARIFFS
 * targets DE/BAN unconditionally (see IntegrationsRoutingService), so a node without that platform
 * silently drops those messages.
 *
 * Deliberately separate from `ocn.node.countryCode`/`partyId`, which are this node's *own* OCPI
 * identity and answer a different question.
 */
@ConfigurationProperties("ocn.required-platform")
@Component
class RequiredPlatformProperties {

    var enabled: Boolean = true

    var countryCode: String = "DE"

    var partyId: String = "BAN"

    /** Base URL of transit-dashboard, used to point the operator at the pages that fix things. */
    var dashboardUrl: String = "http://localhost:3094"
}
