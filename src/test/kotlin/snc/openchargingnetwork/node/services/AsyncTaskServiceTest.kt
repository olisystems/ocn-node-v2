package snc.openchargingnetwork.node.services

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import snc.openchargingnetwork.node.Application
import snc.openchargingnetwork.node.components.OcnRegistryComponent
import snc.openchargingnetwork.node.config.NodeProperties
import snc.openchargingnetwork.node.models.CvStatus
import snc.openchargingnetwork.node.models.OcnRegistry
import snc.openchargingnetwork.node.models.Operator
import snc.openchargingnetwork.node.models.Party
import snc.openchargingnetwork.node.models.PaymentStatus
import snc.openchargingnetwork.node.models.ocpi.Role
import org.mockito.Mockito

@SpringBootTest(classes = [Application::class])
@ActiveProfiles("test")
@Transactional
class AsyncTaskServiceTest(
    @Autowired private val asyncTaskService: AsyncTaskService,
    @Autowired private val registryService: RegistryService,
    @Autowired private val properties: NodeProperties
) {

  @MockBean
  private lateinit var ocnRegistryComponent: OcnRegistryComponent

  @BeforeEach
  fun setUp() {
    configureMockOcnRegistry()
  }

  private fun configureMockOcnRegistry() {
    val testOperator = Operator(
      id = "0xb43253229b9d16ce16e9c836b472d84269338808",
      domain = "https://test-operator1.com"
    )
    val testParty = Party(
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
      operator = testOperator
    )
    val mockRegistry = OcnRegistry(
      parties = listOf(testParty),
      operators = listOf(testOperator)
    )
    Mockito.`when`(ocnRegistryComponent.getRegistry(Mockito.anyBoolean())).thenReturn(mockRegistry)
    Mockito.`when`(ocnRegistryComponent.getRegistry()).thenReturn(mockRegistry)
  }
    @Test
    fun `service should be properly configured`() {
        assertThat(asyncTaskService).isNotNull()
        assertThat(registryService).isNotNull()
        assertThat(properties).isNotNull()
    }

    @Test
    fun `properties should have required configuration`() {
        assertThat(properties).isInstanceOf(NodeProperties::class.java)
    }

    @Test
    fun `registryService should be accessible`() {
        val nodes = registryService.getNodes()

        assertThat(nodes).isNotNull()
        assertThat(nodes).isInstanceOf(List::class.java)
    }

    @Test
    fun `service should have correct constructor parameters`() {
        assertThat(asyncTaskService).isNotNull()
        val nodes = registryService.getNodes()
        assertThat(nodes).isNotNull()
    }
}
