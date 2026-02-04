package snc.openchargingnetwork.node.services

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import snc.openchargingnetwork.node.models.ValidationResult
import snc.openchargingnetwork.node.models.ocpi.CDR
import snc.openchargingnetwork.node.models.ocpi.Location
import snc.openchargingnetwork.node.models.ocpi.Session
import snc.openchargingnetwork.node.models.ocpi.Tariff
import snc.openchargingnetwork.node.models.ocpi.TariffDimensionType
import snc.openchargingnetwork.node.models.ocpi.Token

@ConditionalOnProperty(prefix = "ocn.features", name = ["banulaValidation"], havingValue = "true")
@Service
class BanulaValidationService {
    private val objectMapper =
        jacksonObjectMapper().apply {
            configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
        }
    private val evseIdRegex =
        Regex("^[A-Za-z]{2}\\*?[A-Za-z0-9]{3}\\*?E[A-Za-z0-9][A-Za-z0-9\\*]{0,30}$", RegexOption.IGNORE_CASE)

    fun validateCpoTariff(json: String): ValidationResult {
        if (json.isBlank()) {
            return ValidationResult(
                valid = false,
                errors = listOf("Request body must be a non-empty JSON object"),
                suggestions = listOf("Provide a JSON payload that matches the OCPI Tariff schema")
            )
        }

        return try {
            val tariff = objectMapper.readValue<Tariff>(json)
            val errors = mutableListOf<String>()
            val suggestions = mutableListOf<String>()

            val priceComponents =
                tariff.elements.flatMap { it.priceComponents }
            val componentTypes = priceComponents.map { it.type }.toSet()

            if (priceComponents.size != 2) {
                errors.add("CPO tariff must contain exactly 2 price components (FLAT and ENERGY)")
                suggestions.add("Provide two price components: one with type FLAT and one with type ENERGY")
            }

            if (!componentTypes.contains(TariffDimensionType.FLAT)) {
                errors.add("CPO tariff must include a FLAT price component for infrastructure")
                suggestions.add("Add a price component with type FLAT for the CPO infrastructure fee")
            }

            if (!componentTypes.contains(TariffDimensionType.ENERGY)) {
                errors.add("CPO tariff must include an ENERGY price component for grid fee")
                suggestions.add("Add a price component with type ENERGY for the grid fee")
            }

            val energyMix = tariff.energyMix
            if (energyMix == null) {
                errors.add("CPO tariff must include energy_mix")
                suggestions.add(
                    "Set energy_mix.is_green_energy=true and energy_mix.energy_product_name=BANULA_CPO_TARIFF"
                )
            } else {
                if (energyMix.isGreenEnergy != true) {
                    errors.add("energy_mix.is_green_energy must be true")
                    suggestions.add("Set energy_mix.is_green_energy to true")
                }
                if (energyMix.energyProductName != "BANULA_CPO_TARIFF") {
                    errors.add("energy_mix.energy_product_name must be BANULA_CPO_TARIFF")
                    suggestions.add("Set energy_mix.energy_product_name to BANULA_CPO_TARIFF")
                }
            }

            ValidationResult(errors.isEmpty(), errors, suggestions)
        } catch (ex: Exception) {
            ValidationResult(
                valid = false,
                errors = listOf(ex.message ?: "Invalid JSON payload"),
                suggestions = listOf("Ensure the payload matches the OCPI Tariff schema")
            )
        }
    }

    fun validateEmspTariff(json: String): ValidationResult {
        if (json.isBlank()) {
            return ValidationResult(
                valid = false,
                errors = listOf("Request body must be a non-empty JSON object"),
                suggestions = listOf("Provide a JSON payload that matches the OCPI Tariff schema")
            )
        }

        return try {
            val tariff = objectMapper.readValue<Tariff>(json)
            val errors = mutableListOf<String>()
            val suggestions = mutableListOf<String>()

            val priceComponents =
                tariff.elements.flatMap { it.priceComponents }
            val componentTypes = priceComponents.map { it.type }.toSet()

            if (priceComponents.isEmpty()) {
                errors.add("eMSP tariff must contain at least 1 price component (ENERGY)")
                suggestions.add("Provide at least one price component with type ENERGY")
            }

            if (componentTypes.isNotEmpty() && componentTypes.any { it != TariffDimensionType.ENERGY }) {
                errors.add("eMSP tariff price components must only include ENERGY")
                suggestions.add("Remove non-ENERGY price components and keep only ENERGY")
            }

            val energyMix = tariff.energyMix
            if (energyMix == null) {
                errors.add("eMSP tariff must include energy_mix")
                suggestions.add(
                    "Set energy_mix.is_green_energy=true and energy_mix.energy_product_name=BANULA_EMSP_TARIFF"
                )
            } else {
                if (energyMix.isGreenEnergy != true) {
                    errors.add("energy_mix.is_green_energy must be true")
                    suggestions.add("Set energy_mix.is_green_energy to true")
                }
                if (energyMix.energyProductName != "BANULA_EMSP_TARIFF") {
                    errors.add("energy_mix.energy_product_name must be BANULA_EMSP_TARIFF")
                    suggestions.add("Set energy_mix.energy_product_name to BANULA_EMSP_TARIFF")
                }
            }

            ValidationResult(errors.isEmpty(), errors, suggestions)
        } catch (ex: Exception) {
            ValidationResult(
                valid = false,
                errors = listOf(ex.message ?: "Invalid JSON payload"),
                suggestions = listOf("Ensure the payload matches the OCPI Tariff schema")
            )
        }
    }

    fun validateLocation(json: String): ValidationResult {
        if (json.isBlank()) {
            return ValidationResult(
                valid = false,
                errors = listOf("Request body must be a non-empty JSON object"),
                suggestions = listOf("Provide a JSON payload that matches the OCPI Location schema")
            )
        }

        return try {
            val location = objectMapper.readValue<Location>(json)
            val errors = mutableListOf<String>()
            val suggestions = mutableListOf<String>()

            val energyMix = location.energyMix
            if (energyMix == null) {
                errors.add("Location must include energy_mix")
                suggestions.add("Set energy_mix.is_green_energy=true and energy_mix.energy_product_name=BANULA")
            } else {
                if (energyMix.isGreenEnergy != true) {
                    errors.add("energy_mix.is_green_energy must be true")
                    suggestions.add("Set energy_mix.is_green_energy to true")
                }
                if (energyMix.energyProductName != "BANULA") {
                    errors.add("energy_mix.energy_product_name must be BANULA")
                    suggestions.add("Set energy_mix.energy_product_name to BANULA")
                }
            }

            location.evses?.forEachIndexed { evseIndex, evse ->
                val evseId = evse.evseId
                if (evse.status != snc.openchargingnetwork.node.models.ocpi.EvseStatus.REMOVED) {
                    if (evseId.isNullOrBlank()) {
                        errors.add("EVSE ${evseIndex + 1} must include evse_id when status is not REMOVED")
                        suggestions.add("Provide a valid eMI3 V1.0 EVSE ID for evse_id")
                    } else if (!evseIdRegex.matches(evseId)) {
                        errors.add("EVSE ${evseIndex + 1} evse_id does not match eMI3 V1.0 format")
                        suggestions.add("Use eMI3 V1.0 format like FR*A23*E45B*78C")
                    }
                }

                evse.connectors.forEachIndexed { connectorIndex, connector ->
                    if (connector.maxElectricPower == null) {
                        errors.add("EVSE ${evseIndex + 1} connector ${connectorIndex + 1} must include max_electric_power")
                        suggestions.add("Set max_electric_power for each connector in the location")
                    }
                    if (connector.tariffIDs.isNullOrEmpty()) {
                        errors.add("EVSE ${evseIndex + 1} connector ${connectorIndex + 1} must include tariff_ids")
                        suggestions.add("Provide tariff_ids for each connector in the location")
                    }
                }
            }

            ValidationResult(errors.isEmpty(), errors, suggestions)
        } catch (ex: Exception) {
            ValidationResult(
                valid = false,
                errors = listOf(ex.message ?: "Invalid JSON payload"),
                suggestions = listOf("Ensure the payload matches the OCPI Location schema")
            )
        }
    }

    fun validateToken(json: String): ValidationResult {
        if (json.isBlank()) {
            return ValidationResult(
                valid = false,
                errors = listOf("Request body must be a non-empty JSON object"),
                suggestions = listOf("Provide a JSON payload that matches the OCPI Token schema")
            )
        }

        return try {
            val token = objectMapper.readValue<Token>(json)
            val errors = mutableListOf<String>()
            val suggestions = mutableListOf<String>()

            val energyContract = token.energyContract
            val expectedSupplierName = "${token.countryCode}*${token.partyID}"
            if (energyContract == null) {
                errors.add("Token must include energy_contract")
                suggestions.add("Set energy_contract.supplier_name to $expectedSupplierName")
            } else {
                if (energyContract.supplierName != expectedSupplierName) {
                    errors.add("energy_contract.supplier_name must be $expectedSupplierName")
                    suggestions.add("Set energy_contract.supplier_name to $expectedSupplierName")
                }
                if (energyContract.contractID != token.contractID) {
                    errors.add("energy_contract.contract_id must match token.contract_id")
                    suggestions.add("Set energy_contract.contract_id to ${token.contractID}")
                }
            }

            ValidationResult(errors.isEmpty(), errors, suggestions)
        } catch (ex: Exception) {
            ValidationResult(
                valid = false,
                errors = listOf(ex.message ?: "Invalid JSON payload"),
                suggestions = listOf("Ensure the payload matches the OCPI Token schema")
            )
        }
    }

    fun validateSession(json: String): ValidationResult {
        if (json.isBlank()) {
            return ValidationResult(
                valid = false,
                errors = listOf("Request body must be a non-empty JSON object"),
                suggestions = listOf("Provide a JSON payload that matches the OCPI Session schema")
            )
        }

        return try {
            val session = objectMapper.readValue<Session>(json)
            val errors = mutableListOf<String>()
            val suggestions = mutableListOf<String>()

            if (session.authorizationReference.isNullOrBlank()) {
                errors.add("Session must include authorization_reference")
                suggestions.add("Provide authorization_reference for the session")
            }

            if (session.totalCost == null) {
                errors.add("Session must include total_cost")
                suggestions.add("Provide total_cost for the session")
            }

            suggestions.add("Charging periods should use 5-minute intervals between entries")

            ValidationResult(errors.isEmpty(), errors, suggestions)
        } catch (ex: Exception) {
            ValidationResult(
                valid = false,
                errors = listOf(ex.message ?: "Invalid JSON payload"),
                suggestions = listOf("Ensure the payload matches the OCPI Session schema")
            )
        }
    }

    fun validateCdr(json: String): ValidationResult {
        if (json.isBlank()) {
            return ValidationResult(
                valid = false,
                errors = listOf("Request body must be a non-empty JSON object"),
                suggestions = listOf("Provide a JSON payload that matches the OCPI CDR schema")
            )
        }

        return try {
            val cdr = objectMapper.readValue<CDR>(json)
            val errors = mutableListOf<String>()
            val suggestions = mutableListOf<String>()

            if (cdr.tariffs.isNullOrEmpty()) {
                errors.add("CDR must include at least 1 tariff")
                suggestions.add("Provide at least one tariff in the tariffs array")
            }

            ValidationResult(errors.isEmpty(), errors, suggestions)
        } catch (ex: Exception) {
            ValidationResult(
                valid = false,
                errors = listOf(ex.message ?: "Invalid JSON payload"),
                suggestions = listOf("Ensure the payload matches the OCPI CDR schema")
            )
        }
    }

}
