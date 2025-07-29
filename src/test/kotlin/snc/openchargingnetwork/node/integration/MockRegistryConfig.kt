package snc.openchargingnetwork.node.integration

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import snc.openchargingnetwork.node.components.OcnRegistryComponent
import snc.openchargingnetwork.node.config.RegistryIndexerProperties
import snc.openchargingnetwork.node.models.CvStatus
import snc.openchargingnetwork.node.models.OcnRegistry
import snc.openchargingnetwork.node.models.Operator
import snc.openchargingnetwork.node.models.Party
import snc.openchargingnetwork.node.models.PaymentStatus
import snc.openchargingnetwork.node.models.ocpi.Role

@TestConfiguration
class MockRegistryConfig {

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
    // Create mock parties that will be registered during the test
    val mockParties =
            listOf(
                    Party(
                            id = "DE/CPO",
                            countryCode = "DE",
                            partyId = "CPO",
                            partyAddress = "0x1234567890123456789012345678901234567890",
                            roles = listOf(Role.CPO),
                            name = "Test CPO",
                            url = "http://localhost:8081/ocn-v2",
                            paymentStatus = PaymentStatus.PAID,
                            cvStatus = CvStatus.VERIFIED,
                            active = true,
                            deleted = false,
                            operator =
                                    Operator(
                                            id = "0x1234567890123456789012345678901234567890",
                                            domain = "test-cpo.com"
                                    )
                    ),
                    Party(
                            id = "FR/EMS",
                            countryCode = "FR",
                            partyId = "EMS",
                            partyAddress = "0x0987654321098765432109876543210987654321",
                            roles = listOf(Role.EMSP),
                            name = "Test EMS",
                            url = "http://localhost:8082/ocn-v2",
                            paymentStatus = PaymentStatus.PAID,
                            cvStatus = CvStatus.VERIFIED,
                            active = true,
                            deleted = false,
                            operator =
                                    Operator(
                                            id = "0x0987654321098765432109876543210987654321",
                                            domain = "test-ems.com"
                                    )
                    )
            )

    val mockOperators =
            listOf(
                    Operator(
                            id = "0x1234567890123456789012345678901234567890",
                            domain = "test-cpo.com",
                            parties =
                                    mockParties.filter {
                                      it.operator.id == "0x1234567890123456789012345678901234567890"
                                    }
                    ),
                    Operator(
                            id = "0x0987654321098765432109876543210987654321",
                            domain = "test-ems.com",
                            parties =
                                    mockParties.filter {
                                      it.operator.id == "0x0987654321098765432109876543210987654321"
                                    }
                    )
            )

    return OcnRegistry(parties = mockParties, operators = mockOperators)
  }
}
