package snc.openchargingnetwork.node.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.filter.OncePerRequestFilter
import snc.openchargingnetwork.node.tools.X_CORRELATION_ID
import snc.openchargingnetwork.node.tools.X_REQUEST_ID
import snc.openchargingnetwork.node.tools.partyLabel
import snc.openchargingnetwork.node.tools.putTraceContext

@Configuration
class RequestTracingFilterConfig {

    @Bean
    fun requestTracingFilter(): FilterRegistrationBean<RequestTracingFilter> {
        val registration = FilterRegistrationBean(RequestTracingFilter())
        registration.addUrlPatterns("/*")
        // After TestToolHeaderInjectionFilter (order 0) so the IDs it injects are traced and echoed.
        registration.order = 1
        return registration
    }
}

class RequestTracingFilter : OncePerRequestFilter() {

    // Keeps the IDs on log lines written by OcpiErrorController during the error dispatch.
    override fun shouldNotFilterErrorDispatch() = false

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val requestId = request.getHeader(X_REQUEST_ID)
        val correlationId = request.getHeader(X_CORRELATION_ID)
        putTraceContext(
            requestId = requestId,
            correlationId = correlationId,
            from = partyLabel(request.getHeader("OCPI-from-country-code"), request.getHeader("OCPI-from-party-id")),
            to = partyLabel(request.getHeader("OCPI-to-country-code"), request.getHeader("OCPI-to-party-id"))
        )
        // OCPI 2.2.1 §4.2: every response carries the X-Request-ID and X-Correlation-ID of its request.
        requestId?.let { response.setHeader(X_REQUEST_ID, it) }
        correlationId?.let { response.setHeader(X_CORRELATION_ID, it) }
        try {
            filterChain.doFilter(request, response)
        } finally {
            MDC.clear()
        }
    }
}
