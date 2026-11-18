package snc.openchargingnetwork.node.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Integration copies: the extra copy of a request that the node sends to fixed parties (see
 * IntegrationsRoutingService) through a database outbox (see IntegrationCopyOutboxService).
 */
@ConfigurationProperties("ocn.integrations")
class IntegrationsProperties {

    var targets = Targets()

    var outbox = Outbox()

    /** Receiving parties per module, each written as COUNTRY/PARTY. An empty list sends no copy. */
    class Targets {
        var cdrs: List<String> = listOf("DE/OLI")
        var tokens: List<String> = listOf("DE/OLI")
        var sessions: List<String> = listOf("DE/OLI")
        var locations: List<String> = listOf("DE/BAN")
        var tariffs: List<String> = listOf("DE/BAN")
    }

    class Outbox {
        // Turns off the scheduled sending and cleanup. Copies are still stored, and the attempt
        // right after a request is still made.
        var workerEnabled: Boolean = true

        // How often each replica looks for copies that are due for a retry.
        var pollMillis: Long = 5_000

        // Sends per copy, the first one included, before it is parked.
        var maxAttempts: Int = 10

        // Wait after a failed send: doubles from the initial value up to the maximum. The
        // defaults park a copy about 36 minutes after its first attempt.
        var backoffInitialMillis: Long = 5_000
        var backoffMaxMillis: Long = 900_000

        // How long a replica may hold a copy it is sending. Must be longer than the slowest
        // outbound request, or a second replica sends the same copy again.
        var leaseMillis: Long = 300_000

        var batchSize: Int = 20

        // Delivered rows are deleted after this long. Parked rows are kept.
        var deliveredRetentionHours: Long = 168

        var cleanupMillis: Long = 3_600_000
    }
}
