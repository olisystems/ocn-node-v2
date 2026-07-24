package snc.openchargingnetwork.node.controllers

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.http.HttpMethod
import org.springframework.http.ResponseEntity
import snc.openchargingnetwork.node.components.OcpiRequestHandlerBuilder
import snc.openchargingnetwork.node.controllers.ocpi.v2_2.TokensController
import snc.openchargingnetwork.node.models.ocpi.*
import snc.openchargingnetwork.node.services.NodeObjectRoutingService

class TokensControllerTest {

    @Test
    fun `put token delegates object routing with token type`() {
        val requestHandlerBuilder = Mockito.mock(OcpiRequestHandlerBuilder::class.java)
        val nodeObjectRoutingService = Mockito.mock(NodeObjectRoutingService::class.java)
        val controller = TokensController(requestHandlerBuilder, nodeObjectRoutingService)
        whenever(nodeObjectRoutingService.route<Unit>(any(), anyOrNull()))
            .thenReturn(ResponseEntity.ok(OcpiResponse(statusCode = OcpiStatus.SUCCESS.code)))
        val token =
            Token(
                countryCode = "DE",
                partyID = "MSP",
                uid = "token-1",
                type = TokenType.RFID,
                contractID = "DE-MSP-C1",
                issuer = "Test eMSP",
                valid = true,
                whitelist = WhitelistType.ALWAYS,
                lastUpdated = "2020-01-01T00:00:00Z"
            )

        controller.putClientOwnedToken(
            authorization = "Token test",
            requestID = "request",
            correlationID = "correlation",
            fromCountryCode = "DE",
            fromPartyID = "MSP",
            toCountryCode = "DE",
            toPartyID = "BAN",
            countryCode = "DE",
            partyID = "MSP",
            tokenUID = "token-1",
            type = TokenType.RFID,
            body = token
        )

        val captor = argumentCaptor<OcpiRequestVariables>()
        verify(nodeObjectRoutingService).route<Unit>(captor.capture(), anyOrNull())
        assertThat(captor.firstValue.module).isEqualTo(ModuleID.TOKENS)
        assertThat(captor.firstValue.method).isEqualTo(HttpMethod.PUT)
        assertThat(captor.firstValue.urlPath).isEqualTo("/DE/MSP/token-1")
        assertThat(captor.firstValue.queryParams?.get("type")).isEqualTo(TokenType.RFID)
        assertThat(captor.firstValue.body).isEqualTo(token)
    }
}
