package snc.openchargingnetwork.node.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("ocn.features")
class FeatureFlagsProperties {
    var banulaValidation: Boolean = false
}
