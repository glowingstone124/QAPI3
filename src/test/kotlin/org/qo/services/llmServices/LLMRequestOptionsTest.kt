package org.qo.services.llmServices

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class LLMRequestOptionsTest {
    @Test
    fun `QQ always disables reasoning while thinking elsewhere follows backend policy`() {
        for (mode in listOf("fast", "thinking")) {
            for (requested in LLMReasoningEffort.entries) {
                assertEquals(LLMReasoningEffort.NONE, reasoningEffortForMode(LLMSource.QQ, mode, requested))
            }
        }
        assertEquals(LLMReasoningEffort.NONE, reasoningEffortForMode(LLMSource.WEB, "fast", LLMReasoningEffort.MAX))
        assertEquals(LLMReasoningEffort.MEDIUM, reasoningEffortForMode(LLMSource.WEB, "thinking", LLMReasoningEffort.NONE))
        assertEquals(LLMReasoningEffort.HIGH, reasoningEffortForMode(LLMSource.MINECRAFT, "thinking", LLMReasoningEffort.HIGH))
    }

    @Test
    fun `reasoning defaults are source specific`() {
        assertEquals(LLMReasoningEffort.NONE, defaultReasoningEffort(LLMSource.QQ))
        assertEquals(LLMReasoningEffort.NONE, defaultReasoningEffort(LLMSource.MINECRAFT))
        assertEquals(LLMReasoningEffort.MEDIUM, defaultReasoningEffort(LLMSource.WEB))
    }

    @Test
    fun `markdown stays disabled when option is omitted`() {
        val request = JsonParser.parseString("""{"messages":[]}""").asJsonObject

        assertFalse(extractEnableMarkdownFlag(request))
    }

    @Test
    fun `markdown option is consumed before forwarding upstream`() {
        val request = JsonParser.parseString("""{"enable-markdown":true,"messages":[]}""").asJsonObject

        assertEquals(true, extractEnableMarkdownFlag(request))
        assertFalse(request.has("enable-markdown"))
    }

    @Test
    fun `markdown option rejects non boolean values`() {
        val request = JsonParser.parseString("""{"enable-markdown":"yes","messages":[]}""").asJsonObject

        assertFailsWith<IllegalArgumentException> { extractEnableMarkdownFlag(request) }
    }

    @Test
    fun `reasoning defaults to caller policy and is consumed`() {
        val request = JsonParser.parseString("""{"messages":[]}""").asJsonObject

        assertEquals(LLMReasoningEffort.NONE, extractReasoningEffort(request, LLMReasoningEffort.NONE))
        assertFalse(request.has("reasoning_effort"))
        assertFalse(request.has("reasoning"))
    }

    @Test
    fun `chat completions reasoning effort maps deepseek aliases`() {
        val medium = JsonParser.parseString("""{"reasoning_effort":"medium"}""").asJsonObject
        val xhigh = JsonParser.parseString("""{"reasoning_effort":"xhigh"}""").asJsonObject

        assertEquals(LLMReasoningEffort.MEDIUM, extractReasoningEffort(medium, LLMReasoningEffort.NONE))
        assertEquals(LLMReasoningEffort.HIGH, extractReasoningEffort(xhigh, LLMReasoningEffort.NONE))
        assertFalse(medium.has("reasoning_effort"))
        assertFalse(xhigh.has("reasoning_effort"))
    }

    @Test
    fun `responses reasoning object is accepted`() {
        val request = JsonParser.parseString("""{"reasoning":{"effort":"max"}}""").asJsonObject

        assertEquals(LLMReasoningEffort.MAX, extractReasoningEffort(request, LLMReasoningEffort.NONE))
        assertFalse(request.has("reasoning"))
    }

    @Test
    fun `duplicate reasoning forms are rejected`() {
        val request = JsonParser.parseString(
            """{"reasoning_effort":"low","reasoning":{"effort":"high"}}"""
        ).asJsonObject

        assertFailsWith<IllegalArgumentException> {
            extractReasoningEffort(request, LLMReasoningEffort.NONE)
        }
    }
}
