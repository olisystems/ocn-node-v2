package snc.openchargingnetwork.node.services

import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.test.context.ActiveProfiles
import snc.openchargingnetwork.node.config.HttpClientComponent
import snc.openchargingnetwork.node.config.NodeProperties
import snc.openchargingnetwork.node.models.Party
import snc.openchargingnetwork.node.models.SpringErrorResponse


@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@ActiveProfiles("test")
class HttpServiceTest(@Autowired val restTemplate: TestRestTemplate,
                      @Autowired val properties: NodeProperties,
                      @Autowired val httpClientComponent: HttpClientComponent,) {
    @Test
    fun getMapper() {
    }

    @Test
    fun getConfigurationModules() {
    }

    @Test
    fun convertToRequestVariables() {
    }

    @Test
    fun makeOcpiRequest() {
    }

    @Test
    fun testMakeOcpiRequest() {
    }

    @Test
    fun getVersions() {
    }

    @Test
    fun getVersionDetail() {
    }

    @Test
    fun postOcnMessage() {
    }

    @Test
    fun putOcnClientInfo() {
    }

    @Test
    fun testGetIndexedOcnRegistry() {
        val entity: String = restTemplate.getForEntity("/${properties.apiPrefix}/ocn/registry/nodes", String::class.java).body!!
        println(entity)
        if (!entity.contains("error")) {
            val parties: List<Party> = Json.decodeFromString(entity)
            println("decoded: $parties")
            assertThat(parties.size > 2)
        } else {
            println("error")
            val errorResponse: SpringErrorResponse = Json.decodeFromString(entity)
            println("error: $errorResponse")
            assertThat(1==2)
        }
    }

    @Test
    fun testGetIndexedOcnRegistryParty() {
        val entity: String = restTemplate.getForEntity("/${properties.apiPrefix}/ocn/registry/node/DE/OLI", String::class.java).body!!
        println(entity)
        if (!entity.contains("error")) {
            val party: Party = Json.decodeFromString(entity)
            println("decoded: $party")
            assertThat(party.countryCode == "DE" && party.id == "OLI")
        } else {
            println("error")
            val errorResponse: SpringErrorResponse = Json.decodeFromString(entity)
            println("error: $errorResponse")
            assertThat(false)
        }
    }
}