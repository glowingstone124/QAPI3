package org.qo.utils

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
class RateLimitFilter : OncePerRequestFilter() {
	private val limiter = RateLimiter()

	override fun doFilterInternal(
		request: HttpServletRequest,
		response: HttpServletResponse,
		filterChain: FilterChain
	) {
		val path = request.requestURI ?: ""
		val clientKey = resolveClientKey(request)
		if (!limiter.allow(path, clientKey)) {
			response.status = 429
			response.contentType = "application/json"
			response.writer.write("{\"code\":429,\"message\":\"Rate limit exceeded\"}")
			return
		}
		filterChain.doFilter(request, response)
	}

	private fun resolveClientKey(request: HttpServletRequest): String {
		val forwarded = request.getHeader("X-Forwarded-For")
		if (!forwarded.isNullOrBlank()) {
			return forwarded.split(",").first().trim()
		}
		return request.remoteAddr ?: "unknown"
	}
}
