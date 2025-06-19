package snc.openchargingnetwork.node.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * Configuration properties for the OCN Registry Subgraph Indexer.
 * https://thegraph.com/explorer/subgraphs/6cQY2p5PYJYrWMK64aQtK6GYoqqcHABnA8MuBYTma8WT?view=Query&chain=arbitrum-one
 */
@ConfigurationProperties("ocn.registry.indexer")
@Component
class RegistryIndexerProperties {

    var url: String = ""

    var token: String = ""

    val operatorsQuery = """
    {
        operators {
            id,
            domain,
            parties {
               countryCode
                cvStatus
                id
                name
                operator {
                    id
                    domain
                }
                partyAddress
                partyId
                paymentStatus
                roles
                url
            }
       }
    }
    """

    val partiesQuery = """
            {
                parties {
                    countryCode
                    cvStatus
                    id
                    name
                    operator {
                        id
                        domain
                    }
                    partyAddress
                    partyId
                    paymentStatus
                    roles
                    url
                }
            }
        """
    val singlePartyQuery = """
            {
             party(id: "%s") {
                id
                active
                deleted
                cvStatus
                name
                operator {
                  domain
                  id
                }
                partyAddress
                paymentStatus
                countryCode
                partyId
                roles
                url
              }
            }
        """
    val singleVerificationQuery = """
            {
                otherVerifieds(first: 5, orderBy: blockNumber, orderDirection: desc, where: {identifier: "%s"}) {
                    identifier,
                    name,
                    owner,
                    blockNumber,
                },
                cpoverifieds(first: 5, orderBy: blockNumber, orderDirection: desc, where: {identifier: "%s"}) {
                    identifier,
                    name,
                    owner,
                    blockNumber,
                },
                empverifieds(first: 5, orderBy: blockNumber, orderDirection: desc, where: {identifier: "%s"}) {
                    identifier,
                    name,
                    owner,
                    vatid,
                    bilanzkreis,
                    marktfunktion,
                    lieferant,
                    blockNumber,
                },
            }
        """
}