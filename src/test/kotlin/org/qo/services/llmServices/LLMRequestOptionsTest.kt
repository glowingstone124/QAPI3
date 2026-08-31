package org.qo.services.llmServices

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class LLMRequestOptionsTest {
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
}
