package snc.openchargingnetwork.node.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@ConfigurationProperties("ocn.haas")
@Component
class HaasProperties {

    // Disable the service
    var enabled: Boolean = false

    // Service URL
    var url: String = ""
}