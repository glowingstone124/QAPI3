package org.qo.services.authCentral

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.net.URI

@Service
class ServiceRegistry(
	@Value("\${qapi.auth.central.allowed-services:}")
	configuredAllowedServices: String = ""
) {
	private val allowedPatterns = mutableListOf<Regex>()

	init {
		val defaults = listOf(
			"https://app.qoriginal.vip/*",
			"https://app.qoriginal.vip",
			"https://ai.qoriginal.vip/*",
			"https://ai.qoriginal.vip",
			"https://kotshi.qoriginal.vip/*",
			"https://kotshi.qoriginal.vip",
			"http://localhost:*",
			"http://localhost",
			"http://127.0.0.1:*",
			"http://127.0.0.1"
		)
		val allPatterns = defaults + configuredAllowedServices.split(",")
			.map { it.trim() }
			.filter { it.isNotEmpty() }

		allPatterns.forEach { addPattern(it) }
	}

	fun addPattern(pattern: String) {
		val regexStr = buildString {
			append("^")
			for (c in pattern) {
				when (c) {
					'*' -> append(".*")
					'\\', '.', '[', ']', '{', '}', '(', ')', '+', '?', '^', '$', '|' -> {
						append('\\').append(c)
					}
					else -> append(c)
				}
			}
			append("$")
		}
		allowedPatterns.add(Regex(regexStr, RegexOption.IGNORE_CASE))
	}

	fun isAllowed(serviceUrl: String?): Boolean {
		if (serviceUrl.isNullOrBlank()) return false
		val trimmed = serviceUrl.trim()
		val uri = runCatching { URI.create(trimmed) }.getOrNull() ?: return false
		val scheme = uri.scheme?.lowercase() ?: return false
		if (scheme != "http" && scheme != "https") return false
		return allowedPatterns.any { it.matches(trimmed) }
	}
}
