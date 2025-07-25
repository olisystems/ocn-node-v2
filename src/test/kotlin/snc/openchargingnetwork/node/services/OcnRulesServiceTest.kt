package snc.openchargingnetwork.node.services

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import snc.openchargingnetwork.node.models.OcnRules
import snc.openchargingnetwork.node.models.OcnRulesList
import snc.openchargingnetwork.node.models.OcnRulesListParty
import snc.openchargingnetwork.node.models.entities.Auth
import snc.openchargingnetwork.node.models.entities.PlatformEntity
import snc.openchargingnetwork.node.models.exceptions.OcpiClientGenericException
import snc.openchargingnetwork.node.models.exceptions.OcpiClientInvalidParametersException
import snc.openchargingnetwork.node.models.ocpi.BasicRole
import snc.openchargingnetwork.node.repositories.OcnRulesListRepository
import snc.openchargingnetwork.node.repositories.PlatformRepository

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class OcnRulesServiceTest(
        @Autowired private val ocnRulesService: OcnRulesService,
        @Autowired private val platformRepository: PlatformRepository,
        @Autowired private val ocnRulesListRepository: OcnRulesListRepository
) {

    private lateinit var testPlatform: PlatformEntity
    private lateinit var testParty: BasicRole
    private lateinit var testAuthorization: String

    @BeforeEach
    fun setUp() {
        // Create test platform
        testPlatform =
                PlatformEntity(
                        status =
                                snc.openchargingnetwork.node.models.ocpi.ConnectionStatus.CONNECTED,
                        auth =
                                Auth(
                                        tokenA = "test-token-a",
                                        tokenB = "test-token-b",
                                        tokenC = "test-token-c"
                                )
                )
        testPlatform = platformRepository.save(testPlatform)

        testParty = BasicRole("TST", "DE")
        testAuthorization = "Token test-token-c"
    }

    @Test
    fun `getRules should return rules for valid authorization`() {
        val rules = ocnRulesService.getRules(testAuthorization)

        assertThat(rules).isInstanceOf(OcnRules::class.java)
        assertThat(rules.signatures).isEqualTo(testPlatform.rules.signatures)
        assertThat(rules.whitelist).isInstanceOf(OcnRulesList::class.java)
        assertThat(rules.blacklist).isInstanceOf(OcnRulesList::class.java)
    }

    @Test
    fun `getRules should throw exception for invalid authorization`() {
        val invalidAuthorization = "Token invalid-token"

        assertThrows<OcpiClientInvalidParametersException> {
            ocnRulesService.getRules(invalidAuthorization)
        }
    }

    @Test
    fun `updateSignatures should toggle signatures setting`() {
        val initialSignatures = testPlatform.rules.signatures

        ocnRulesService.updateSignatures(testAuthorization)

        val updatedPlatform = platformRepository.findById(testPlatform.id!!).get()
        assertThat(updatedPlatform.rules.signatures).isEqualTo(!initialSignatures)
    }

    @Test
    fun `blockAll should set whitelist to true with empty list`() {
        ocnRulesService.blockAll(testAuthorization)

        val updatedPlatform = platformRepository.findById(testPlatform.id!!).get()
        assertThat(updatedPlatform.rules.whitelist).isTrue()
        assertThat(updatedPlatform.rules.blacklist).isFalse()

        val rulesList = ocnRulesListRepository.findAllByPlatformID(testPlatform.id)
        assertThat(rulesList).isEmpty()
    }

    @Test
    fun `updateWhitelist should save whitelist entries`() {
        val parties =
                listOf(
                        OcnRulesListParty(
                                id = "TST",
                                country = "DE",
                                modules = listOf("locations", "sessions")
                        ),
                        OcnRulesListParty(id = "OTH", country = "FR", modules = listOf("locations"))
                )

        ocnRulesService.updateWhitelist(testAuthorization, parties)

        val updatedPlatform = platformRepository.findById(testPlatform.id!!).get()
        assertThat(updatedPlatform.rules.whitelist).isTrue()
        assertThat(updatedPlatform.rules.blacklist).isFalse()

        val rulesList = ocnRulesListRepository.findAllByPlatformID(testPlatform.id)
        assertThat(rulesList).hasSize(2)
        assertThat(rulesList.any { it.counterparty.id == "TST" && it.counterparty.country == "DE" })
                .isTrue()
        assertThat(rulesList.any { it.counterparty.id == "OTH" && it.counterparty.country == "FR" })
                .isTrue()
    }

    @Test
    fun `updateWhitelist with empty list should disable whitelist`() {
        val emptyParties = emptyList<OcnRulesListParty>()

        ocnRulesService.updateWhitelist(testAuthorization, emptyParties)

        val updatedPlatform = platformRepository.findById(testPlatform.id!!).get()
        assertThat(updatedPlatform.rules.whitelist).isFalse()
    }

    @Test
    fun `updateWhitelist should throw exception for empty modules`() {
        val partiesWithEmptyModules =
                listOf(
                        OcnRulesListParty(
                                id = "TST",
                                country = "DE",
                                modules = listOf("locations", "") // Empty module
                        )
                )

        assertThrows<OcpiClientGenericException> {
            ocnRulesService.updateWhitelist(testAuthorization, partiesWithEmptyModules)
        }
    }

    @Test
    fun `updateBlacklist should save blacklist entries`() {
        val parties =
                listOf(OcnRulesListParty(id = "BLK", country = "DE", modules = listOf("locations")))

        ocnRulesService.updateBlacklist(testAuthorization, parties)

        val updatedPlatform = platformRepository.findById(testPlatform.id!!).get()
        assertThat(updatedPlatform.rules.blacklist).isTrue()
        assertThat(updatedPlatform.rules.whitelist).isFalse()

        val rulesList = ocnRulesListRepository.findAllByPlatformID(testPlatform.id)
        assertThat(rulesList).hasSize(1)
        assertThat(rulesList.first().counterparty.id).isEqualTo("BLK")
    }

    @Test
    fun `appendToWhitelist should add single entry`() {
        val party =
                OcnRulesListParty(
                        id = "TST",
                        country = "DE",
                        modules = listOf("locations", "sessions")
                )

        ocnRulesService.appendToWhitelist(testAuthorization, party)

        val updatedPlatform = platformRepository.findById(testPlatform.id!!).get()
        assertThat(updatedPlatform.rules.whitelist).isTrue()

        val rulesList = ocnRulesListRepository.findAllByPlatformID(testPlatform.id)
        assertThat(rulesList).hasSize(1)
        assertThat(rulesList.first().counterparty.id).isEqualTo("TST")
        assertThat(rulesList.first().modules).contains("locations", "sessions")
    }

    @Test
    fun `appendToWhitelist should throw exception for duplicate entry`() {
        val party = OcnRulesListParty(id = "TST", country = "DE", modules = listOf("locations"))

        // Add the party first
        ocnRulesService.appendToWhitelist(testAuthorization, party)

        assertThrows<OcpiClientInvalidParametersException> {
            ocnRulesService.appendToWhitelist(testAuthorization, party)
        }
    }

    @Test
    fun `appendToBlacklist should add single entry`() {
        val party = OcnRulesListParty(id = "BLK", country = "DE", modules = listOf("locations"))

        ocnRulesService.appendToBlacklist(testAuthorization, party)

        val updatedPlatform = platformRepository.findById(testPlatform.id!!).get()
        assertThat(updatedPlatform.rules.blacklist).isTrue()

        val rulesList = ocnRulesListRepository.findAllByPlatformID(testPlatform.id)
        assertThat(rulesList).hasSize(1)
        assertThat(rulesList.first().counterparty.id).isEqualTo("BLK")
    }

    @Test
    fun `deleteFromWhitelist should remove entry`() {
        val party = OcnRulesListParty(id = "TST", country = "DE", modules = listOf("locations"))
        ocnRulesService.appendToWhitelist(testAuthorization, party)

        ocnRulesService.deleteFromWhitelist(testAuthorization, BasicRole("TST", "DE"))

        val rulesList = ocnRulesListRepository.findAllByPlatformID(testPlatform.id)
        assertThat(rulesList).isEmpty()

        val updatedPlatform = platformRepository.findById(testPlatform.id!!).get()
        assertThat(updatedPlatform.rules.whitelist).isFalse()
    }

    @Test
    fun `deleteFromBlacklist should remove entry`() {
        val party = OcnRulesListParty(id = "BLK", country = "DE", modules = listOf("locations"))
        ocnRulesService.appendToBlacklist(testAuthorization, party)

        ocnRulesService.deleteFromBlacklist(testAuthorization, BasicRole("BLK", "DE"))

        val rulesList = ocnRulesListRepository.findAllByPlatformID(testPlatform.id)
        assertThat(rulesList).isEmpty()

        val updatedPlatform = platformRepository.findById(testPlatform.id!!).get()
        assertThat(updatedPlatform.rules.blacklist).isFalse()
    }

    @Test
    fun `isWhitelisted should return true when no rules are active`() {
        val result = ocnRulesService.isWhitelisted(testPlatform, testParty)

        assertThat(result).isTrue()
    }

    @Test
    fun `isWhitelisted should return true for whitelisted party`() {
        val party = OcnRulesListParty(id = "TST", country = "DE", modules = listOf("locations"))
        ocnRulesService.appendToWhitelist(testAuthorization, party)

        val result = ocnRulesService.isWhitelisted(testPlatform, testParty)

        assertThat(result).isTrue()
    }

    @Test
    fun `isWhitelisted should return false for non-whitelisted party`() {
        val party = OcnRulesListParty(id = "TST", country = "DE", modules = listOf("locations"))
        ocnRulesService.appendToWhitelist(testAuthorization, party)
        val nonWhitelistedParty = BasicRole("OTH", "DE")

        val result = ocnRulesService.isWhitelisted(testPlatform, nonWhitelistedParty)

        assertThat(result).isFalse()
    }

    @Test
    fun `isWhitelisted with module should return true for allowed module`() {
        val party =
                OcnRulesListParty(
                        id = "TST",
                        country = "DE",
                        modules = listOf("locations", "sessions")
                )
        ocnRulesService.appendToWhitelist(testAuthorization, party)

        val result = ocnRulesService.isWhitelisted(testPlatform, testParty, "locations")

        assertThat(result).isTrue()
    }

    @Test
    fun `isWhitelisted with module should throw exception for disallowed module`() {
        val party = OcnRulesListParty(id = "TST", country = "DE", modules = listOf("locations"))
        ocnRulesService.appendToWhitelist(testAuthorization, party)

        assertThrows<OcpiClientGenericException> {
            ocnRulesService.isWhitelisted(testPlatform, testParty, "sessions")
        }
    }

    @Test
    fun `isWhitelisted should handle blacklist correctly`() {
        val party = OcnRulesListParty(id = "BLK", country = "DE", modules = listOf("locations"))
        ocnRulesService.appendToBlacklist(testAuthorization, party)
        val blacklistedParty = BasicRole("BLK", "DE")
        val nonBlacklistedParty = BasicRole("OTH", "DE")

        val result1 = ocnRulesService.isWhitelisted(testPlatform, blacklistedParty)
        val result2 = ocnRulesService.isWhitelisted(testPlatform, nonBlacklistedParty)

        assertThat(result1).isFalse()
        assertThat(result2).isTrue()
    }

    @Test
    fun `updateWhitelist and updateBlacklist should not allow both to be active`() {
        val whitelistParties =
                listOf(OcnRulesListParty(id = "TST", country = "DE", modules = listOf("locations")))
        ocnRulesService.updateWhitelist(testAuthorization, whitelistParties)

        val blacklistParties =
                listOf(OcnRulesListParty(id = "BLK", country = "DE", modules = listOf("locations")))

        assertThrows<OcpiClientGenericException> {
            ocnRulesService.updateBlacklist(testAuthorization, blacklistParties)
        }
    }
}
