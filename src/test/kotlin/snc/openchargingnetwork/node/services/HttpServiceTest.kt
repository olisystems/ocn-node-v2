package snc.openchargingnetwork.node.services

import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles
import snc.openchargingnetwork.node.components.HttpClientComponent
import snc.openchargingnetwork.node.config.NodeProperties
import snc.openchargingnetwork.node.models.Party


private val lenientJson = Json { ignoreUnknownKeys = true }

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
        val response = restTemplate.getForEntity("/${properties.apiPrefix}/ocn/registry/nodes", String::class.java)
        println("status: ${response.statusCode}, body: ${response.body}")
        assumeTrue(response.statusCode == HttpStatus.OK, "Registry indexer unavailable, skipping test")
        val entity = response.body!!
        val parties: List<Party> = lenientJson.decodeFromString(entity)
        println("decoded: $parties")
        assertThat(parties).hasSizeGreaterThan(2)
    }

    @Test
    fun testGetIndexedOcnRegistryParty() {
        val response = restTemplate.getForEntity("/${properties.apiPrefix}/ocn/registry/node/DE/OLI", String::class.java)
        println("status: ${response.statusCode}, body: ${response.body}")
        assumeTrue(response.statusCode == HttpStatus.OK, "Registry indexer unavailable, skipping test")
        val entity = response.body!!
        val party: Party = lenientJson.decodeFromString(entity)
        println("decoded: $party")
        assertThat(party.countryCode).isEqualTo("DE")
        assertThat(party.partyId).isEqualTo("OLI")
    }
}