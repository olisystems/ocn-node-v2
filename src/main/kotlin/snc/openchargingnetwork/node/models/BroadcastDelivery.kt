package snc.openchargingnetwork.node.models

import snc.openchargingnetwork.node.models.ocpi.BasicRole

/** What happened to one receiver of a hub broadcast. */
data class BroadcastDelivery(
    val receiver: BasicRole,
    val outcome: Outcome,
    val httpStatus: Int? = null,
    val ocpiStatus: Int? = null,
    val message: String? = null
) {
    enum class Outcome {
        DELIVERED,
        /** The receiver answered with an HTTP or OCPI error. */
        REJECTED,
        /** No usable answer: the call failed, or the body was not OCPI. */
        UNREACHABLE,
        /** No answer before the broadcast stopped waiting; the delivery is still running. */
        PENDING
    }

    val failed: Boolean
        get() = outcome == Outcome.REJECTED || outcome == Outcome.UNREACHABLE

    /** One line for the sender. Holds no receiver URLs, since the sender can be any party. */
    fun describe(): String {
        val party = "${receiver.country}/${receiver.id}"
        return when (outcome) {
            Outcome.DELIVERED -> "$party accepted it"
            Outcome.REJECTED ->
                "$party rejected it (HTTP $httpStatus, OCPI $ocpiStatus${message?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""})"
            Outcome.UNREACHABLE -> "$party ${message ?: "could not be reached"}"
            Outcome.PENDING -> "$party has not answered yet (delivery continues)"
        }
    }
}
