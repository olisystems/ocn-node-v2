/*
    Copyright 2019-2020 eMobility GmbH

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.
*/

package snc.openchargingnetwork.node.models.exceptions

import jakarta.servlet.http.HttpServletRequest
import java.net.ConnectException
import java.net.SocketTimeoutException
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.bind.MissingRequestHeaderException
import shareandcharge.openchargingnetwork.notary.Notary
import shareandcharge.openchargingnetwork.notary.ValuesToSign
import snc.openchargingnetwork.node.config.NodeProperties
import snc.openchargingnetwork.node.models.ocpi.OcpiResponse
import snc.openchargingnetwork.node.models.ocpi.OcpiStatus

@Order(Ordered.HIGHEST_PRECEDENCE)
@ControllerAdvice
class ExceptionHandler(private val properties: NodeProperties) {

    companion object {
        private val logger = LoggerFactory.getLogger(ExceptionHandler::class.java)
    }

    private fun signError(body: OcpiResponse<Unit>): String {
        return Notary().sign(ValuesToSign(body = body), properties.privateKey!!).serialize()
    }

    private fun isSigningEnabled(): Boolean {
        return properties.signatures
    }

    /** GENERIC EXCEPTIONS */

    // TODO: Not critical, check behaviour when there are missing headers or parameters in the
    // request.
    //  This should not be handled with generic error handling but part of a request validation
    // framework as per modern implementations.

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleHttpMessageNotReadable(e: HttpMessageNotReadableException, httpRequest: HttpServletRequest): ResponseEntity<OcpiResponse<Unit>> {
        logger.error("Failed to parse request body: ${e.message}")
        val body =
                OcpiResponse<Unit>(
                        statusCode = OcpiStatus.CLIENT_INVALID_PARAMETERS.code,
                        statusMessage = "Invalid request body"
                )
        if (isSigningEnabled()) {
            body.signature = signError(body)
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body)
    }

    @ExceptionHandler(MissingRequestHeaderException::class)
    fun handleMissingRequestHeader(e: MissingRequestHeaderException, httpRequest: HttpServletRequest): ResponseEntity<OcpiResponse<Unit>> {
        logger.error("Missing required request header '${e.headerName}': ${e.message}")
        val body =
                OcpiResponse<Unit>(
                        statusCode = OcpiStatus.CLIENT_INVALID_PARAMETERS.code,
                        statusMessage = "Missing required header: ${e.headerName}"
                )
        if (isSigningEnabled()) {
            body.signature = signError(body)
        }
        return ResponseEntity.status(HttpStatus.OK).body(body)
    }

    @ExceptionHandler(MissingServletRequestParameterException::class)
    fun handleMissingServletRequestParameter(e: MissingServletRequestParameterException, httpRequest: HttpServletRequest): ResponseEntity<OcpiResponse<Unit>> {
        logger.error("Missing required request parameter '${e.parameterName}': ${e.message}")
        val body =
                OcpiResponse<Unit>(
                        statusCode = OcpiStatus.CLIENT_INVALID_PARAMETERS.code,
                        statusMessage = "Missing required parameter: ${e.parameterName}"
                )
        if (isSigningEnabled()) {
            body.signature = signError(body)
        }
        return ResponseEntity.status(HttpStatus.OK).body(body)
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleMethodArgumentTypeMismatch(e: MethodArgumentTypeMismatchException, httpRequest: HttpServletRequest): ResponseEntity<OcpiResponse<Unit>> {
        logger.error("Invalid argument type for parameter '${e.name}': ${e.message}")
        val body =
                OcpiResponse<Unit>(
                        statusCode = OcpiStatus.CLIENT_INVALID_PARAMETERS.code,
                        statusMessage = "Invalid parameter value: ${e.name}"
                )
        if (isSigningEnabled()) {
            body.signature = signError(body)
        }
        return ResponseEntity.status(HttpStatus.OK).body(body)
    }

    @ExceptionHandler(SocketTimeoutException::class)
    fun handleSocketTimeoutException(
            e: SocketTimeoutException,
            httpRequest: HttpServletRequest
    ): ResponseEntity<OcpiResponse<Unit>> {
        val body =
                OcpiResponse<Unit>(
                        statusCode = OcpiStatus.HUB_REQUEST_TIMEOUT.code,
                        statusMessage = e.message
                )
        if (isSigningEnabled()) {
            body.signature = signError(body)
        }
        return ResponseEntity.status(HttpStatus.OK).body(body)
    }

    @ExceptionHandler(ConnectException::class)
    fun handleConnectException(e: ConnectException, httpRequest: HttpServletRequest): ResponseEntity<OcpiResponse<Unit>> {
        val body =
                OcpiResponse<Unit>(
                        statusCode = OcpiStatus.HUB_CONNECTION_PROBLEM.code,
                        statusMessage = e.message
                )
        if (isSigningEnabled()) {
            body.signature = signError(body)
        }
        return ResponseEntity.status(HttpStatus.OK).body(body)
    }

    /** OCPI EXCEPTIONS */
    private fun ocpiErrorToResponseEntity(
            httpStatus: HttpStatus,
            ocpiStatus: OcpiStatus,
            message: String?,
            httpRequest: HttpServletRequest
    ): ResponseEntity<OcpiResponse<Unit>> {
        val body = OcpiResponse<Unit>(statusCode = ocpiStatus.code, statusMessage = message)
        if (isSigningEnabled()) {
            body.signature = signError(body)
        }
        return ResponseEntity.status(httpStatus).body(body)
    }

    @ExceptionHandler(OcpiClientGenericException::class)
    fun handleOcpiClientGenericException(e: OcpiClientGenericException, httpRequest: HttpServletRequest) =
            ocpiErrorToResponseEntity(
                    httpStatus = e.httpStatus,
                    ocpiStatus = e.ocpiStatus,
                    message = e.message,
                    httpRequest = httpRequest
            )

    @ExceptionHandler(OcpiClientInvalidParametersException::class)
    fun handleOcpiClientInvalidParametersException(e: OcpiClientInvalidParametersException, httpRequest: HttpServletRequest) =
            ocpiErrorToResponseEntity(
                    httpStatus = e.httpStatus,
                    ocpiStatus = e.ocpiStatus,
                    message = e.message,
                    httpRequest = httpRequest
            )

    @ExceptionHandler(OcpiClientNotEnoughInformationException::class)
    fun handleOcpiClientNotEnoughInformationException(e: OcpiClientNotEnoughInformationException, httpRequest: HttpServletRequest) =
            ocpiErrorToResponseEntity(
                    httpStatus = e.httpStatus,
                    ocpiStatus = e.ocpiStatus,
                    message = e.message,
                    httpRequest = httpRequest
            )

    @ExceptionHandler(OcpiClientUnknownLocationException::class)
    fun handleOcpiClientUnknownLocationException(e: OcpiClientUnknownLocationException, httpRequest: HttpServletRequest) =
            ocpiErrorToResponseEntity(
                    httpStatus = e.httpStatus,
                    ocpiStatus = e.ocpiStatus,
                    message = e.message,
                    httpRequest = httpRequest
            )

    @ExceptionHandler(OcpiServerGenericException::class)
    fun handleOcpiServerGenericException(e: OcpiServerGenericException, httpRequest: HttpServletRequest) =
            ocpiErrorToResponseEntity(
                    httpStatus = e.httpStatus,
                    ocpiStatus = e.ocpiStatus,
                    message = e.message,
                    httpRequest = httpRequest
            )

    @ExceptionHandler(OcpiServerUnusableApiException::class)
    fun handleOcpiServerUnusableApiException(e: OcpiServerUnusableApiException, httpRequest: HttpServletRequest) =
            ocpiErrorToResponseEntity(
                    httpStatus = e.httpStatus,
                    ocpiStatus = e.ocpiStatus,
                    message = e.message,
                    httpRequest = httpRequest
            )

    @ExceptionHandler(OcpiServerNoMatchingEndpointsException::class)
    fun handleOcpiServerNoMatchingEndpointsException(e: OcpiServerNoMatchingEndpointsException, httpRequest: HttpServletRequest) =
            ocpiErrorToResponseEntity(
                    httpStatus = e.httpStatus,
                    ocpiStatus = e.ocpiStatus,
                    message = e.message,
                    httpRequest = httpRequest
            )

    @ExceptionHandler(OcpiServerUnsupportedVersionException::class)
    fun handleOcpiServerUnsupportedVersionException(e: OcpiServerUnsupportedVersionException, httpRequest: HttpServletRequest) =
            ocpiErrorToResponseEntity(
                    httpStatus = e.httpStatus,
                    ocpiStatus = e.ocpiStatus,
                    message = e.message,
                    httpRequest = httpRequest
            )

    @ExceptionHandler(OcpiHubConnectionProblemException::class)
    fun handleOcpiHubConnectionProblemException(e: OcpiHubConnectionProblemException, httpRequest: HttpServletRequest) =
            ocpiErrorToResponseEntity(
                    httpStatus = e.httpStatus,
                    ocpiStatus = e.ocpiStatus,
                    message = e.message,
                    httpRequest = httpRequest
            )

    @ExceptionHandler(OcpiHubTimeoutOnRequestException::class)
    fun handleOcpiHubTimeoutOnRequestException(e: OcpiHubTimeoutOnRequestException, httpRequest: HttpServletRequest) =
            ocpiErrorToResponseEntity(
                    httpStatus = e.httpStatus,
                    ocpiStatus = e.ocpiStatus,
                    message = e.message,
                    httpRequest = httpRequest
            )

    @ExceptionHandler(OcpiHubUnknownReceiverException::class)
    fun handleOcpiHubUnknownReceiverException(e: OcpiHubUnknownReceiverException, httpRequest: HttpServletRequest) =
            ocpiErrorToResponseEntity(
                    httpStatus = e.httpStatus,
                    ocpiStatus = e.ocpiStatus,
                    message = e.message,
                    httpRequest = httpRequest
            )

    @ExceptionHandler(OcpiHubGenericException::class)
    fun handleOcpiHubGenericException(e: OcpiHubGenericException, httpRequest: HttpServletRequest) =
            ocpiErrorToResponseEntity(
                    httpStatus = e.httpStatus,
                    ocpiStatus = e.ocpiStatus,
                    message = e.message,
                    httpRequest = httpRequest
            )

    /** OCN Exceptions */
    @ExceptionHandler(InvalidOcnSignatureException::class)
    fun handleInvalidOcnSignatureException(
            e: InvalidOcnSignatureException
    ): ResponseEntity<String> {
        return ResponseEntity.status(400).body(e.message)
    }
}
