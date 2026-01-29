package snc.openchargingnetwork.node.controllers.banula

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import snc.openchargingnetwork.node.models.ValidationResult
import snc.openchargingnetwork.node.services.BanulaValidationService

@ConditionalOnProperty(prefix = "ocn.features", name = ["banulaValidation"], havingValue = "true")
@RequestMapping("\${ocn.node.apiPrefix}/banula/validation")
@RestController
class BanulaValidationController(private val validationService: BanulaValidationService) {

    @PostMapping("/tariff", consumes = ["application/json"])
    fun validateTariff(@RequestBody body: String): ResponseEntity<ValidationResult> {
        val result = validationService.validateTariff(body)
        return if (result.valid) ResponseEntity.ok(result) else ResponseEntity.badRequest().body(result)
    }

    @PostMapping("/tariff/cpo", consumes = ["application/json"])
    fun validateCpoTariff(@RequestBody body: String): ResponseEntity<ValidationResult> {
        val result = validationService.validateCpoTariff(body)
        return if (result.valid) ResponseEntity.ok(result) else ResponseEntity.badRequest().body(result)
    }

    @PostMapping("/tariff/emsp", consumes = ["application/json"])
    fun validateEmspTariff(@RequestBody body: String): ResponseEntity<ValidationResult> {
        val result = validationService.validateEmspTariff(body)
        return if (result.valid) ResponseEntity.ok(result) else ResponseEntity.badRequest().body(result)
    }

    @PostMapping("/location", consumes = ["application/json"])
    fun validateLocation(@RequestBody body: String): ResponseEntity<ValidationResult> {
        val result = validationService.validateLocation(body)
        return if (result.valid) ResponseEntity.ok(result) else ResponseEntity.badRequest().body(result)
    }

    @PostMapping("/token", consumes = ["application/json"])
    fun validateToken(@RequestBody body: String): ResponseEntity<ValidationResult> {
        val result = validationService.validateToken(body)
        return if (result.valid) ResponseEntity.ok(result) else ResponseEntity.badRequest().body(result)
    }

    @PostMapping("/cdr", consumes = ["application/json"])
    fun validateCdr(@RequestBody body: String): ResponseEntity<ValidationResult> {
        val result = validationService.validateCdr(body)
        return if (result.valid) ResponseEntity.ok(result) else ResponseEntity.badRequest().body(result)
    }
}
