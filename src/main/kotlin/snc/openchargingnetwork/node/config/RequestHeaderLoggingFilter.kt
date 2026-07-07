package snc.openchargingnetwork.node.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.filter.OncePerRequestFilter

@Configuration
class RequestHeaderLoggingFilterConfig(private val properties: NodeProperties) {

    @Bean
    fun headerLoggingFilter(): FilterRegistrationBean<RequestHeaderLoggingFilter> {
        val registration = FilterRegistrationBean<RequestHeaderLoggingFilter>()
        registration.filter = RequestHeaderLoggingFilter(properties)
        registration.addUrlPatterns("/ocpi/receiver/*", "/ocpi/sender/*", "/ocn/message")
        registration.order = 1
        return registration
    }
}

class RequestHeaderLoggingFilter(private val properties: NodeProperties) : OncePerRequestFilter() {

    companion object {
        private val log = LoggerFactory.getLogger(RequestHeaderLoggingFilter::class.java)
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        if (properties.logFullHeaders) {
            val headers = mutableMapOf<String, String>()
            request.headerNames?.asIterator()?.forEach { name ->
                headers[name] = request.getHeader(name)
            }
            log.info(
                "Incoming request | method: {} | uri: {} | headers: {}",
                request.method,
                request.requestURI,
                headers
            )
        }
        filterChain.doFilter(request, response)
    }
}
