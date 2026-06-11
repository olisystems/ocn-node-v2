package snc.openchargingnetwork.node.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.filter.OncePerRequestFilter
import snc.openchargingnetwork.node.repositories.PlatformRepository
import snc.openchargingnetwork.node.repositories.RoleRepository
import snc.openchargingnetwork.node.tools.extractToken
import snc.openchargingnetwork.node.tools.fromBs64String
import java.util.*

@Configuration
class TestToolHeaderInjectionFilterConfig(
    private val platformRepository: PlatformRepository,
    private val roleRepository: RoleRepository,
    private val nodeProperties: NodeProperties
) {

    @Bean
    fun testToolHeaderInjectionFilter(): FilterRegistrationBean<TestToolHeaderInjectionFilter> {
        val registration = FilterRegistrationBean<TestToolHeaderInjectionFilter>()
        registration.filter = TestToolHeaderInjectionFilter(platformRepository, roleRepository)
        val prefix = if (nodeProperties.apiPrefix.isNullOrBlank()) "" else "/${nodeProperties.apiPrefix}"
        registration.addUrlPatterns("${prefix}/ocpi/receiver/*", "${prefix}/ocpi/sender/*")
        registration.order = 0
        return registration
    }
}

class TestToolHeaderInjectionFilter(
    private val platformRepository: PlatformRepository,
    private val roleRepository: RoleRepository
) : OncePerRequestFilter() {

    companion object {
        private val log = LoggerFactory.getLogger(TestToolHeaderInjectionFilter::class.java)

        private val MODULE_TO_PARTY_MAP = mapOf(
            "tariffs" to "BAN",
            "locations" to "BAN",
            "cdrs" to "OLI",
            "sessions" to "OLI",
            "tokens" to "OLI"
        )
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val authorization = request.getHeader("Authorization")
        if (authorization.isNullOrBlank()) {
            filterChain.doFilter(request, response)
            return
        }

        val platform = findPlatformByToken(authorization)
        if (platform == null || !platform.testTool) {
            filterChain.doFilter(request, response)
            return
        }

        log.info("TestTool platform detected (id={}), injecting missing OCPI headers", platform.id)

        val roles = roleRepository.findAllByPlatformID(platform.id)
        val firstRole = roles.firstOrNull()
        if (firstRole == null) {
            log.warn("TestTool platform {} has no roles, cannot inject OCPI-from headers", platform.id)
            filterChain.doFilter(request, response)
            return
        }

        val module = extractModuleFromUri(request.requestURI)
        val toPartyId = MODULE_TO_PARTY_MAP[module] ?: "BAN"

        val wrappedRequest = TestToolRequestWrapper(request, firstRole.countryCode, firstRole.partyID, toPartyId)
        filterChain.doFilter(wrappedRequest, response)
    }

    private fun findPlatformByToken(authorization: String): snc.openchargingnetwork.node.models.entities.PlatformEntity? {
        return try {
            val rawToken = authorization.extractToken()
            val plainToken = rawToken.fromBs64String()
            platformRepository.findByAuth_TokenC(plainToken)
                ?: platformRepository.findByAuth_TokenB(plainToken)
                ?: platformRepository.findByAuth_TokenA(plainToken)
        } catch (e: Exception) {
            null
        }
    }

    private fun extractModuleFromUri(uri: String): String? {
        // URI pattern: /{apiPrefix}/ocpi/receiver/2.2.1/{module}/... or /ocpi/sender/2.2.1/{module}/...
        // Find "ocpi" segment and then module is 3 positions after it
        val parts = uri.split("/")
        val ocpiIndex = parts.indexOf("ocpi")
        // Expected after "ocpi": receiver|sender, 2.2.1, {module}
        return if (ocpiIndex >= 0 && parts.size > ocpiIndex + 3) parts[ocpiIndex + 3] else null
    }
}

class TestToolRequestWrapper(
    request: HttpServletRequest,
    private val fromCountryCode: String,
    private val fromPartyId: String,
    private val toPartyId: String
) : HttpServletRequestWrapper(request) {

    private val injectedHeaders: Map<String, String>

    init {
        val headers = mutableMapOf<String, String>()

        if (request.getHeader("X-Correlation-ID") == null) {
            headers["X-Correlation-ID"] = UUID.randomUUID().toString()
        }
        if (request.getHeader("X-Request-ID") == null) {
            headers["X-Request-ID"] = UUID.randomUUID().toString()
        }
        if (request.getHeader("OCPI-from-country-code") == null) {
            headers["OCPI-from-country-code"] = fromCountryCode
        }
        if (request.getHeader("OCPI-from-party-id") == null) {
            headers["OCPI-from-party-id"] = fromPartyId
        }
        if (request.getHeader("OCPI-to-country-code") == null) {
            headers["OCPI-to-country-code"] = "DE"
        }
        if (request.getHeader("OCPI-to-party-id") == null) {
            headers["OCPI-to-party-id"] = toPartyId
        }

        injectedHeaders = headers
    }

    override fun getHeader(name: String): String? {
        // Case-insensitive lookup in injected headers
        val injected = injectedHeaders.entries.find { it.key.equals(name, ignoreCase = true) }
        if (injected != null) return injected.value
        return super.getHeader(name)
    }

    override fun getHeaders(name: String): Enumeration<String> {
        val injected = injectedHeaders.entries.find { it.key.equals(name, ignoreCase = true) }
        if (injected != null) {
            return Collections.enumeration(listOf(injected.value))
        }
        return super.getHeaders(name)
    }

    override fun getHeaderNames(): Enumeration<String> {
        val allNames = mutableListOf<String>()
        val original = super.getHeaderNames()
        while (original.hasMoreElements()) {
            allNames.add(original.nextElement())
        }
        // Add injected header names that aren't already present (case-insensitive)
        for (key in injectedHeaders.keys) {
            if (allNames.none { it.equals(key, ignoreCase = true) }) {
                allNames.add(key)
            }
        }
        return Collections.enumeration(allNames)
    }
}
