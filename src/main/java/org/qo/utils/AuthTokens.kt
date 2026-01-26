package org.qo.utils

object AuthTokens {
    fun resolve(tokenHeader: String?, authorizationHeader: String?): String? {
        if (!tokenHeader.isNullOrBlank()) {
            return tokenHeader.trim()
        }
        if (authorizationHeader.isNullOrBlank()) {
            return null
        }
        val value = authorizationHeader.trim()
        if (value.startsWith("Bearer ", ignoreCase = true)) {
            val token = value.substring(7).trim()
            return token.ifBlank { null }
        }
        return value.ifBlank { null }
    }
}
