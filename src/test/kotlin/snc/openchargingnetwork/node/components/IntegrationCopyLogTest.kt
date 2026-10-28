package snc.openchargingnetwork.node.components

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.http.HttpMethod
import snc.openchargingnetwork.node.models.OcnHeaders
import snc.openchargingnetwork.node.models.OcpiHttpResponse
import snc.openchargingnetwork.node.models.ocpi.BasicRole
import snc.openchargingnetwork.node.models.ocpi.InterfaceRole
import snc.openchargingnetwork.node.models.ocpi.ModuleID
import snc.openchargingnetwork.node.models.ocpi.OcpiRequestVariables
import snc.openchargingnetwork.node.models.ocpi.OcpiResponse
import java.io.IOException
import kotlin.test.assertEquals

class IntegrationCopyLogTest {

    private val target = BasicRole("OLI", "DE")

    private val request =
            OcpiRequestVariables(
                    module = ModuleID.SESSIONS,
                    interfaceRole = InterfaceRole.RECEIVER,
                    method = HttpMethod.PATCH,
                    urlPath = "DE/ABC/SIMUL1*1*315",
                    headers =
                            OcnHeaders(
                                    authorization = "Token abc",
                                    requestID = "rid-1",
                                    correlationID = "cid-1",
                                    sender = BasicRole("ABC", "DE"),
                                    receiver = BasicRole("ABE", "DE")
                            )
            )

    @Test
    fun `accepted copy is logged at INFO`() {
        val event = capture { it.logIntegrationCopy("ForwardIntegrations", request, target, "orid-1", response(1000)) }

        assertEquals(Level.INFO, event.level)
        assertEquals(
                "[ForwardIntegrations] SUCCESS — integration copy of PATCH sessions/DE/ABC/SIMUL1*1*315 to DE/OLI | HTTP 200 | ocpi status: 1000 | out_request_id: orid-1",
                event.formattedMessage
        )
    }

    @Test
    fun `rejected copy is logged at ERROR with the receiver's answer`() {
        val event = capture {
            it.logIntegrationCopy("ForwardIntegrations", request, target, "orid-1", response(3000, "cdradapter: EOF"))
        }

        assertEquals(Level.ERROR, event.level)
        assertEquals(
                "[ForwardIntegrations] FAILED — integration copy of PATCH sessions/DE/ABC/SIMUL1*1*315 to DE/OLI was rejected | HTTP 200 | ocpi status: 3000 | message: cdradapter: EOF | out_request_id: orid-1 | not reported to sender DE/ABC",
                event.formattedMessage
        )
    }

    @Test
    fun `copy that could not be sent is logged at ERROR with the exception`() {
        val event = capture {
            it.logIntegrationCopyError("ForwardSender", request, target, null, IOException("Connection refused"))
        }

        assertEquals(Level.ERROR, event.level)
        assertEquals(
                "[ForwardSender] FAILED — integration copy of PATCH sessions/DE/ABC/SIMUL1*1*315 to DE/OLI was not delivered | error: IOException: Connection refused | out_request_id: null | not reported to sender DE/ABC",
                event.formattedMessage
        )
    }

    private fun response(statusCode: Int, message: String? = null) =
            OcpiHttpResponse<Any>(statusCode = 200, headers = emptyMap(), body = OcpiResponse(statusCode, message))

    private fun capture(log: (Logger) -> Unit): ILoggingEvent {
        val logger = LoggerFactory.getLogger("integration-copy-test") as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            log(logger)
        } finally {
            logger.detachAppender(appender)
        }
        return appender.list.single()
    }
}
