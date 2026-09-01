package org.qo.services.llmServices

import com.google.gson.JsonObject

internal enum class LLMReasoningEffort(val wireValue: String) {
    NONE("none"),
    LOW("low"),
    HIGH("high"),
    MAX("max"),
}

internal fun defaultReasoningEffort(source: LLMSource?): LLMReasoningEffort = when (source) {
    LLMSource.WEB -> LLMReasoningEffort.HIGH
    else -> LLMReasoningEffort.NONE
}

internal fun extractEnableMarkdownFlag(request: JsonObject): Boolean {
    val value = request.remove("enable-markdown") ?: return false
    if (!value.isJsonPrimitive || !value.asJsonPrimitive.isBoolean) {
        throw IllegalArgumentException("enable-markdown must be a boolean")
    }
    return value.asBoolean
}

internal fun extractReasoningEffort(
    request: JsonObject,
    default: LLMReasoningEffort,
): LLMReasoningEffort {
    val chatCompletionsValue = request.remove("reasoning_effort")
    val responsesObject = request.remove("reasoning")
    val responsesValue = responsesObject?.let {
        if (!it.isJsonObject) {
            throw IllegalArgumentException("reasoning must be an object")
        }
        it.asJsonObject.get("effort")
            ?: throw IllegalArgumentException("reasoning.effort is required")
    }

    if (chatCompletionsValue != null && responsesValue != null) {
        throw IllegalArgumentException("reasoning_effort and reasoning.effort cannot both be set")
    }

    val value = chatCompletionsValue ?: responsesValue ?: return default
    if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) {
        throw IllegalArgumentException("reasoning effort must be a string")
    }
    return when (value.asString.lowercase()) {
        "none" -> LLMReasoningEffort.NONE
        "low" -> LLMReasoningEffort.LOW
        "medium", "high", "xhigh" -> LLMReasoningEffort.HIGH
        "max" -> LLMReasoningEffort.MAX
        else -> throw IllegalArgumentException("reasoning effort must be one of none, low, medium, high, xhigh, max")
    }
}
