package org.qo.services.llmServices

import com.google.gson.JsonObject

internal fun extractEnableMarkdownFlag(request: JsonObject): Boolean {
    val value = request.remove("enable-markdown") ?: return false
    if (!value.isJsonPrimitive || !value.asJsonPrimitive.isBoolean) {
        throw IllegalArgumentException("enable-markdown must be a boolean")
    }
    return value.asBoolean
}
