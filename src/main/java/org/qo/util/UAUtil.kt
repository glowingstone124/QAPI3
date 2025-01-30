package org.qo.util

import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Service

@Service
class UAUtil {
    fun isCLIToolRequest(request: HttpServletRequest): Boolean {
        val userAgent: String? = request.getHeader("User-Agent")
        if (userAgent == null) {
            return false;
        }
        return userAgent.contains("curl") || userAgent.contains("postman") || userAgent.contains("apifox")
    }
}