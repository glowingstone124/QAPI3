package org.qo.services.llmServices

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.mockito.Mockito
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class LLMOutputBudgetTest {
    private val limitKeys = listOf("max_tokens", "max_completion_tokens", "max_output_tokens")

    private suspend fun normalize(protocol: LLMProtocol, mode: String, limitKey: String? = null): LLMServices.NormalizedRequest {
        val services = Mockito.mock(LLMServices::class.java, Mockito.RETURNS_DEEP_STUBS)
        Mockito.`when`(services.systemPrompt.current()).thenReturn("")
        Mockito.`when`(services.groupContextService).thenReturn(LLMGroupContextService())
        val requester = LLMServices.LLMRequester(uid = 1, name = "test", source = "qq")
        Mockito.`when`(services.conversationService.historyMessages(requester.conversationKey())).thenReturn(JsonArray())
        val provider = Mockito.mock(LLMProvider::class.java)
        Mockito.`when`(provider.name).thenReturn("test")
        Mockito.`when`(provider.modelName(mode)).thenReturn("test-model")
        Mockito.`when`(provider.protocol(mode)).thenReturn(protocol)
        Mockito.`when`(provider.modelConfig(mode)).thenReturn(LLMModelConfig("test-model", protocol))
        Mockito.`when`(provider.contextWindow).thenReturn(131072)
        val body = JsonParser.parseString("""{"reasoning_effort":"high","messages":[{"role":"user","content":"Explain a complex problem"}]}""").asJsonObject
        limitKey?.let { body.addProperty(it, 32768) }
        return services.normalizeRequest(body.toString(), false, requester, mode, provider)
    }

    private fun wire(request: LLMServices.NormalizedRequest, protocol: LLMProtocol): JsonObject =
        LLMAdapterRegistry.forProtocol(protocol).adapt(LLMAdapterRequest(
            JsonParser.parseString(request.body).asJsonObject, JsonArray(), request.reasoningEffort, webSearch = false,
        ))

    @Test fun `ordinary QQ requests omit optional output caps and retain reasoning policy`() = runBlocking {
        for (protocol in listOf(LLMProtocol.CHAT_COMPLETIONS, LLMProtocol.RESPONSES)) {
            for (mode in listOf("fast", "thinking")) {
                val normalized = normalize(protocol, mode)
                val outgoing = wire(normalized, protocol)
                limitKeys.forEach { assertFalse(outgoing.has(it), "$protocol $mode unexpectedly sets $it") }
                assertEquals(LLMReasoningEffort.NONE, normalized.reasoningEffort)
                assertEquals("none", if (protocol == LLMProtocol.RESPONSES)
                    outgoing.getAsJsonObject("reasoning").get("effort").asString
                else outgoing.get("reasoning_effort").asString)
            }
        }
    }

    @Test fun `caller output limits survive normalization and protocol conversion`() = runBlocking {
        for (protocol in LLMProtocol.entries) {
            // Chat Completions forwards its native token-limit fields unchanged.
            val supportedKeys = if (protocol == LLMProtocol.CHAT_COMPLETIONS) limitKeys.take(2) else limitKeys
            for (mode in listOf("fast", "thinking")) {
                for (key in supportedKeys) {
                    val normalized = normalize(protocol, mode, key)
                    assertEquals(32768, JsonParser.parseString(normalized.body).asJsonObject.get(key).asInt)
                    val outgoing = wire(normalized, protocol)
                    val actual = when (protocol) {
                        LLMProtocol.CHAT_COMPLETIONS -> outgoing.get(key)
                        LLMProtocol.RESPONSES -> outgoing.get("max_output_tokens")
                        LLMProtocol.ANTHROPIC -> outgoing.get("max_tokens")
                        LLMProtocol.COMMANDCODE -> outgoing.getAsJsonObject("params").get("max_tokens")
                    }
                    assertEquals(32768, actual.asInt, "$protocol $mode $key")
                }
            }
        }
    }

    @Test fun `protocol defaults remain available without small mode caps`() = runBlocking {
        for (mode in listOf("fast", "thinking")) {
            val anthropic = wire(normalize(LLMProtocol.ANTHROPIC, mode), LLMProtocol.ANTHROPIC)
            assertEquals(4096, anthropic.get("max_tokens").asInt)
            val command = normalize(LLMProtocol.COMMANDCODE, mode)
            limitKeys.forEach { assertFalse(JsonParser.parseString(command.body).asJsonObject.has(it)) }
            assertEquals(65536, wire(command, LLMProtocol.COMMANDCODE).getAsJsonObject("params").get("max_tokens").asInt)
        }
    }
}
