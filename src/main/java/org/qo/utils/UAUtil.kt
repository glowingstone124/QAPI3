package org.qo.utils

import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.stereotype.Service

@Service
class UAUtil {
    fun isCLIToolRequest(request: ServerHttpRequest): Boolean {
        val userAgent: String? = request.headers.getFirst("User-Agent")
        if (userAgent == null) {
            return false;
        }
        return userAgent.contains("curl") || userAgent.contains("postman") || userAgent.contains("apifox")
    }
}
