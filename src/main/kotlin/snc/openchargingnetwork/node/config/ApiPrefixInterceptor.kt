package snc.openchargingnetwork.node.config

import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

@Component
class ApiPrefixInterceptor(private val nodeProperties: NodeProperties) : HandlerInterceptor {

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        val prefix = nodeProperties.apiPrefix ?: ""
        if (prefix.isNotEmpty() && !request.requestURI.startsWith(prefix)) {
            response.sendRedirect(prefix + request.requestURI)
            return false
        }
        return true
    }
}