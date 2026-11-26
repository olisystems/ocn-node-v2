package snc.openchargingnetwork.node.models.entities

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.Instant

enum class IntegrationCopyStatus {
    PENDING,
    DELIVERED,
    /** Attempts used up. Stays in the table until an operator requeues or removes it. */
    PARKED
}

/**
 * One integration copy waiting to be sent (see IntegrationCopyOutboxService).
 *
 * [payload] is the request to resend and can hold personal data, so it must never be logged. It
 * is stored without the sender's Authorization token and is cleared once the copy is delivered.
 */
@Entity
@Table(
        name = "integration_copy_outbox",
        indexes = [Index(name = "idx_integration_copy_outbox_due", columnList = "status, nextAttemptAt")]
)
class IntegrationCopyOutboxEntity(
        val targetCountry: String,
        val targetParty: String,
        val module: String,
        val method: String,
        /**
         * The object the request writes to, or null for requests that are not ordered. Rows with
         * the same target, module and object key are sent in id order.
         */
        @Column(length = 1024) val objectKey: String?,
        @Column(columnDefinition = "text") var payload: String?,
        val verificationStatus: String?,
        val correlationId: String,
        val createdAt: Instant,
        var nextAttemptAt: Instant,
        /** Set while one replica is sending the row; an expired value means that replica died. */
        var lockedUntil: Instant? = null,
        @Enumerated(EnumType.STRING) var status: IntegrationCopyStatus = IntegrationCopyStatus.PENDING,
        var attempts: Int = 0,
        var lastError: String? = null,
        var deliveredAt: Instant? = null,
        // IDENTITY, not the default pooled sequence: each replica would take its own block of
        // ids, and id order would no longer be creation order.
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null
)
