package snc.openchargingnetwork.node.config

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import snc.openchargingnetwork.node.components.OcnRegistryComponent
import snc.openchargingnetwork.node.models.CvStatus
import snc.openchargingnetwork.node.models.OcnRegistry
import snc.openchargingnetwork.node.models.Operator
import snc.openchargingnetwork.node.models.Party
import snc.openchargingnetwork.node.models.PaymentStatus
import snc.openchargingnetwork.node.models.ocpi.Role

@TestConfiguration
class TestOcnRegistryComponent {

  @Bean
  @Primary
  fun mockOcnRegistryComponent(): OcnRegistryComponent {
    return object :
            OcnRegistryComponent(
                    httpClientComponent = createMockHttpClientComponent(),
                    registryIndexerProperties = createMockRegistryIndexerProperties()
            ) {
      override fun getRegistry(forceReload: Boolean): OcnRegistry {
        return createMockRegistry()
      }
    }
  }

  private fun createMockHttpClientComponent():
          snc.openchargingnetwork.node.components.HttpClientComponent {
    return object : snc.openchargingnetwork.node.components.HttpClientComponent() {
      // Mock implementation - not used since we override getRegistry
    }
  }

  private fun createMockRegistryIndexerProperties(): RegistryIndexerProperties {
    return RegistryIndexerProperties().apply {
      url = "https://mock-registry.com"
      token = "mock-token"
    }
  }

  private fun createMockRegistry(): OcnRegistry {
    val testOperator1 =
            Operator(
                    id = "0xb43253229b9d16ce16e9c836b472d84269338808",
                    domain = "https://test-operator1.com",
            )

    val testOperator2 =
            Operator(
                    id = "0x0987654321098765432109876543210987654321",
                    domain = "https://test-operator2.com"
            )

    val testParty1 =
            Party(
                    id = "TST",
                    countryCode = "DE",
                    partyId = "TST",
                    partyAddress = "0x1111111111111111111111111111111111111111",
                    roles = listOf(Role.CPO, Role.EMSP),
                    name = "Test Company 1",
                    url = "https://test1.com",
                    paymentStatus = PaymentStatus.PAID,
                    cvStatus = CvStatus.VERIFIED,
                    active = true,
                    deleted = false,
                    operator = testOperator1
            )

    val testParty2 =
            Party(
                    id = "DEM",
                    countryCode = "DE",
                    partyId = "DEM",
                    partyAddress = "0x2222222222222222222222222222222222222222",
                    roles = listOf(Role.CPO),
                    name = "Test Company 2",
                    url = "https://test2.com",
                    paymentStatus = PaymentStatus.PAID,
                    cvStatus = CvStatus.VERIFIED,
                    active = true,
                    deleted = false,
                    operator = testOperator2
            )

    val testParty3 =
            Party(
                    id = "FRA",
                    countryCode = "FR",
                    partyId = "FRA",
                    partyAddress = "0x3333333333333333333333333333333333333333",
                    roles = listOf(Role.EMSP),
                    name = "Test Company 3",
                    url = "https://test3.com",
                    paymentStatus = PaymentStatus.PAID,
                    cvStatus = CvStatus.VERIFIED,
                    active = true,
                    deleted = false,
                    operator = testOperator1
            )

    testOperator1.parties = listOf(testParty1, testParty3)
    testOperator2.parties = listOf(testParty2)

    return OcnRegistry(
            parties = listOf(testParty1, testParty2, testParty3),
            operators = listOf(testOperator1, testOperator2)
    )
  }
}
