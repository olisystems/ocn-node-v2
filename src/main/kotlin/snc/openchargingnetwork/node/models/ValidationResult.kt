package snc.openchargingnetwork.node.models

data class ValidationResult(
    val valid: Boolean,
    val errors: List<String> = emptyList(),
    val suggestions: List<String> = emptyList()
)
