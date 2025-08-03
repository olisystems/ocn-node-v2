package snc.openchargingnetwork.node.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ocn.hci-publisher")
public class HCIProperties {

    var countryCode: String? = null
    var partyId: String? = null

}
