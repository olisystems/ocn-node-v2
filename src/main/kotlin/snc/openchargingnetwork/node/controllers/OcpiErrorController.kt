package snc.openchargingnetwork.node.controllers

import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.boot.web.servlet.error.ErrorController
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import snc.openchargingnetwork.node.models.ocpi.OcpiResponse
import snc.openchargingnetwork.node.models.ocpi.OcpiStatus

@RestController
class OcpiErrorController : ErrorController {

    companion object {
        private val logger = LoggerFactory.getLogger(OcpiErrorController::class.java)

        private val OCPI_PATH_PREFIXES = listOf("/ocpi/receiver/", "/ocpi/sender/")
    }

    @RequestMapping("/error")
    fun handleError(request: HttpServletRequest): ResponseEntity<Any> {
        val status = request.getAttribute("javax.servlet.error.status_code") as? Int
            ?: request.getAttribute("jakarta.servlet.error.status_code") as? Int
            ?: 500
        val requestUri = request.getAttribute("javax.servlet.error.request_uri") as? String
            ?: request.getAttribute("jakarta.servlet.error.request_uri") as? String
            ?: request.requestURI ?: ""
        val errorMessage = request.getAttribute("javax.servlet.error.message") as? String
            ?: request.getAttribute("jakarta.servlet.error.message") as? String

        val isOcpiPath = OCPI_PATH_PREFIXES.any { requestUri.contains(it) }

        // OCPI spec §5:
        // - Invalid JSON body → HTTP 400 MUST (transport layer)
        // - GET on unknown resource → HTTP 404 SHOULD (transport layer)
        // - Server errors after valid JSON is parsed → HTTP 200 + OCPI status code (OCPI layer)
        return if (isOcpiPath && status >= 500) {
            logger.error("[ErrorFallback] OCPI server error: status={}, uri={}, message={}", status, requestUri, errorMessage)
            val body = OcpiResponse<Unit>(
                statusCode = OcpiStatus.SERVER_ERROR.code,
                statusMessage = errorMessage ?: "Internal server error"
            )
            ResponseEntity.status(HttpStatus.OK).body(body)
        } else {
            if (isOcpiPath) {
                logger.error("[ErrorFallback] OCPI transport-layer error: status={}, uri={}, message={}", status, requestUri, errorMessage)
            }
            val body = mapOf(
                "status" to status,
                "error" to (errorMessage ?: HttpStatus.valueOf(status).reasonPhrase),
                "path" to requestUri
            )
            ResponseEntity.status(status).body(body)
        }
    }
}
