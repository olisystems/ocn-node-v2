package snc.openchargingnetwork.node.controllers.ocn

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import snc.openchargingnetwork.node.config.HCIProperties
import snc.openchargingnetwork.node.services.HubClientInfoService
import snc.openchargingnetwork.node.services.WalletService

@RestController
@RequestMapping("\${ocn.node.apiPrefix}/ocn/message/ocn/client-info")
class ClientInfoController(
    private val hubClientInfoService: HubClientInfoService,
    private val walletService: WalletService,
    private val hciProperties: HCIProperties
) {
    @PutMapping
    fun updateClientInfo(
        @RequestHeader("OCPI-from-country-code") fromCountryCode: String,
        @RequestHeader("OCPI-from-party-id") fromPartyID: String,
        @RequestHeader("OCN-Signature") signature: String,
        @RequestBody body: String
    ): ResponseEntity<Any> {

        val clientInfo = walletService.verifyClientInfo(body, signature)

        if (hciProperties.countryCode !== fromCountryCode || hciProperties.partyId !== fromPartyID ) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Invalid Hub Client Info publisher")
        }

        // save all received client info (even if connected parties are not interested, they might
        // be in the future)
        hubClientInfoService.saveClientInfo(clientInfo)

        val parties =
            hubClientInfoService.getPartiesToNotifyOfClientInfoChange(clientInfo = clientInfo)

        if (parties.isNotEmpty()) {
            hubClientInfoService.notifyPartiesOfClientInfoChange(parties, clientInfo)
        }

        return ResponseEntity.ok("New client info object stored and broadcasted")
    }
}
