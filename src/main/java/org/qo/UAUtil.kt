package org.qo

import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Service

@Service
class UAUtil {
    fun isCLIToolRequest(request: HttpServletRequest): Boolean {
        val userAgent = request.getHeader("User-Agent")
        return userAgent.contains("curl") || userAgent.contains("postman") || userAgent.contains("apifox")
    }
}