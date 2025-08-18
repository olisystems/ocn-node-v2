package snc.openchargingnetwork.node.controllers.ocn

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import snc.openchargingnetwork.node.config.HCIProperties
import snc.openchargingnetwork.node.models.ocpi.ModuleID
import snc.openchargingnetwork.node.services.HubClientInfoService
import snc.openchargingnetwork.node.services.ModuleNotificationService
import snc.openchargingnetwork.node.services.WalletService

@RestController
@RequestMapping("\${ocn.node.apiPrefix}/ocn/message/ocn/client-info")
class ClientInfoController(
        private val hubClientInfoService: HubClientInfoService,
        private val moduleNotificationService: ModuleNotificationService,
        private val walletService: WalletService
) {
    @PutMapping
    fun updateClientInfo(
            @RequestHeader("OCPI-from-country-code") fromCountryCode: String,
            @RequestHeader("OCPI-from-party-id") fromPartyID: String,
            @RequestHeader("OCN-Signature") signature: String,
            @RequestBody body: String
    ): ResponseEntity<Any> {
        val clientInfo = walletService.verifyClientInfo(body, signature)

        hubClientInfoService.saveClientInfo(clientInfo)

        val parties =
                moduleNotificationService.getPartiesToNotifyOfModuleChange(
                        moduleId = ModuleID.HUB_CLIENT_INFO,
                        partyId = clientInfo.partyID,
                        countryCode = clientInfo.countryCode
                )

        if (parties.isNotEmpty()) {
            moduleNotificationService.notifyPartiesOfModuleChangeAsync(
                    moduleId = ModuleID.HUB_CLIENT_INFO,
                    parties = parties,
                    changedData = clientInfo,
                    urlPath = "${clientInfo.countryCode}/${clientInfo.partyID}"
            )
        }

        return ResponseEntity.ok("New client info object stored and broadcasted")
    }
}
