package org.qo.services.llmServices

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LLMGoogleAdapterTest {
    private fun obj(json: String): JsonObject = JsonParser.parseString(json).asJsonObject

    @Test
    fun `converts system instruction, user and model messages, base64 and url images without leaking model or stream`() {
        val chat = """{
            "model":"gemini-3.8-flash","user_id":"qq:123","web_search_options":{},"reasoning_effort":"high",
            "thinking":{"type":"enabled"},"stream_options":{"include_usage":true},"temperature":0.7,"top_p":0.95,
            "stop":["STOP_SEQ"],"max_tokens":4096,
            "messages":[
                {"role":"system","content":"system instructions"},
                {"role":"user","content":[
                    {"type":"text","text":"describe this image"},
                    {"type":"image_url","image_url":{"url":"data:image/png;base64,aGVsbG8=","detail":"high"}},
                    {"type":"image_url","image_url":{"url":"https://images.example/pic.jpg"}}
                ]},
                {"role":"assistant","content":"I see an image."}
            ]
        }"""
        val tools = JsonParser.parseString("""[{"type":"function","function":{"name":"lookup","description":"find facts","parameters":{"type":"object","properties":{}}}}]""").asJsonArray
        val request = LLMGoogleAdapter.fromChatRequest(chat, tools, LLMReasoningEffort.HIGH)

        // Must not contain top-level model or stream (Google returns 400 Unknown name for these)
        assertFalse(request.has("model"))
        assertFalse(request.has("stream"))
        assertFalse(request.has("user_id"))
        assertFalse(request.has("reasoning_effort"))
        assertFalse(request.has("web_search_options"))
        assertFalse(request.has("stream_options"))

        // System instruction
        assertTrue(request.has("systemInstruction"))
        val systemParts = request.getAsJsonObject("systemInstruction").getAsJsonArray("parts")
        assertEquals("system instructions", systemParts[0].asJsonObject.get("text").asString)

        // Contents
        val contents = request.getAsJsonArray("contents")
        assertEquals(2, contents.size())

        // User content
        val userTurn = contents[0].asJsonObject
        assertEquals("user", userTurn.get("role").asString)
        val userParts = userTurn.getAsJsonArray("parts")
        assertEquals("describe this image", userParts[0].asJsonObject.get("text").asString)

        // Base64 inlineData
        val inlineData = userParts[1].asJsonObject.getAsJsonObject("inlineData")
        assertEquals("image/png", inlineData.get("mimeType").asString)
        assertEquals("aGVsbG8=", inlineData.get("data").asString)

        // URL fileData
        val fileData = userParts[2].asJsonObject.getAsJsonObject("fileData")
        assertEquals("https://images.example/pic.jpg", fileData.get("fileUri").asString)

        // Assistant content -> model role
        val modelTurn = contents[1].asJsonObject
        assertEquals("model", modelTurn.get("role").asString)
        assertEquals("I see an image.", modelTurn.getAsJsonArray("parts")[0].asJsonObject.get("text").asString)

        // Generation config
        val genConfig = request.getAsJsonObject("generationConfig")
        assertNotNull(genConfig)
        assertEquals(4096, genConfig.get("maxOutputTokens").asInt)
        assertEquals(0.7, genConfig.get("temperature").asDouble)
        assertEquals(0.95, genConfig.get("topP").asDouble)
        assertEquals("STOP_SEQ", genConfig.getAsJsonArray("stopSequences")[0].asString)
    }

    @Test
    fun `parametersJsonSchema preserves Kotshi schemas including additionalProperties false and integer enums while removing schema`() {
        val chat = """{"model":"gemini-3.8-flash","messages":[{"role":"user","content":"build"}]}"""
        val tools = JsonParser.parseString("""[
            {
                "type":"function",
                "function":{
                    "name":"compose_pattern",
                    "description":"compose multi-block structures",
                    "parameters":{
                        "${'$'}schema":"http://json-schema.org/draft-07/schema#",
                        "type":"object",
                        "additionalProperties":false,
                        "required":["rotation","steps"],
                        "properties":{
                            "rotation":{"type":"integer","enum":[0,90,180,270]},
                            "steps":{"type":"string","minLength":1,"pattern":"^[a-z]+$"}
                        }
                    }
                }
            },
            {
                "type":"function",
                "function":{
                    "name":"no_params",
                    "parameters":null
                }
            }
        ]""").asJsonArray

        val request = LLMGoogleAdapter.fromChatRequest(chat, tools, LLMReasoningEffort.NONE)
        val toolObj = request.getAsJsonArray("tools")[0].asJsonObject
        val declarations = toolObj.getAsJsonArray("functionDeclarations")
        assertEquals(2, declarations.size())

        val composeDecl = declarations[0].asJsonObject
        assertEquals("compose_pattern", composeDecl.get("name").asString)
        val schema = composeDecl.getAsJsonObject("parametersJsonSchema")
        assertNotNull(schema)
        assertFalse(schema.has("${'$'}schema"))
        assertEquals(false, schema.get("additionalProperties").asBoolean)
        assertEquals(listOf(0, 90, 180, 270), schema.getAsJsonObject("properties").getAsJsonObject("rotation").getAsJsonArray("enum").map { it.asInt })
        assertEquals("^[a-z]+$", schema.getAsJsonObject("properties").getAsJsonObject("steps").get("pattern").asString)

        val noParamsDecl = declarations[1].asJsonObject
        assertEquals("object", noParamsDecl.getAsJsonObject("parametersJsonSchema").get("type").asString)

        // Tool config AUTO
        val toolConfig = request.getAsJsonObject("toolConfig")
        assertEquals("AUTO", toolConfig.getAsJsonObject("functionCallingConfig").get("mode").asString)
    }

    @Test
    fun `googleSearch toggle appends googleSearch to tools array`() {
        val chat = """{"model":"gemini-3.8-flash","messages":[{"role":"user","content":"search"}]}"""
        val tools = JsonParser.parseString("""[{"type":"function","function":{"name":"local_tool","parameters":{"type":"object"}}}]""").asJsonArray

        // webSearch = true
        val withSearch = LLMGoogleAdapter.fromChatRequest(chat, tools, LLMReasoningEffort.NONE, webSearch = true)
        val toolsWithSearch = withSearch.getAsJsonArray("tools")
        assertTrue(toolsWithSearch.any { it.asJsonObject.has("googleSearch") })
        assertTrue(toolsWithSearch.any { it.asJsonObject.has("functionDeclarations") })

        // webSearch = false
        val withoutSearch = LLMGoogleAdapter.fromChatRequest(chat, tools, LLMReasoningEffort.NONE, webSearch = false)
        val toolsWithoutSearch = withoutSearch.getAsJsonArray("tools")
        assertFalse(toolsWithoutSearch.any { it.asJsonObject.has("googleSearch") })
    }

    @Test
    fun `thinkingConfig dual-track Gemini 3 thinkingLevel vs Gemini 2-5 thinkingBudget`() {
        // Gemini 3 Flash: thinkingLevel MINIMAL when disabled, LOW/MEDIUM/HIGH when enabled
        val chat3FlashDisabled = """{"model":"gemini-3.8-flash","messages":[{"role":"user","content":"hi"}]}"""
        val req3FlashDisabled = LLMGoogleAdapter.fromChatRequest(chat3FlashDisabled, JsonArray(), LLMReasoningEffort.NONE)
        val gen3FlashDisabled = req3FlashDisabled.getAsJsonObject("generationConfig")
        assertEquals("MINIMAL", gen3FlashDisabled.getAsJsonObject("thinkingConfig").get("thinkingLevel").asString)
        assertFalse(gen3FlashDisabled.getAsJsonObject("thinkingConfig").has("thinkingBudget"))
        assertFalse(gen3FlashDisabled.getAsJsonObject("thinkingConfig").has("includeThoughts"))

        val req3FlashMedium = LLMGoogleAdapter.fromChatRequest(chat3FlashDisabled, JsonArray(), LLMReasoningEffort.MEDIUM)
        val gen3FlashMedium = req3FlashMedium.getAsJsonObject("generationConfig")
        assertEquals("MEDIUM", gen3FlashMedium.getAsJsonObject("thinkingConfig").get("thinkingLevel").asString)
        assertTrue(gen3FlashMedium.getAsJsonObject("thinkingConfig").get("includeThoughts").asBoolean)
        assertFalse(gen3FlashMedium.getAsJsonObject("thinkingConfig").has("thinkingBudget"))

        // Gemini 3 Pro (non-flash): thinkingLevel LOW when disabled
        val chat3ProDisabled = """{"model":"gemini-3-pro","messages":[{"role":"user","content":"hi"}]}"""
        val req3ProDisabled = LLMGoogleAdapter.fromChatRequest(chat3ProDisabled, JsonArray(), LLMReasoningEffort.NONE)
        assertEquals("LOW", req3ProDisabled.getAsJsonObject("generationConfig").getAsJsonObject("thinkingConfig").get("thinkingLevel").asString)

        // Gemini 2.5 Flash: thinkingBudget
        val chat25Disabled = """{"model":"gemini-2.5-flash","messages":[{"role":"user","content":"hi"}]}"""
        val req25Disabled = LLMGoogleAdapter.fromChatRequest(chat25Disabled, JsonArray(), LLMReasoningEffort.NONE)
        assertEquals(0, req25Disabled.getAsJsonObject("generationConfig").getAsJsonObject("thinkingConfig").get("thinkingBudget").asInt)
        assertFalse(req25Disabled.getAsJsonObject("generationConfig").getAsJsonObject("thinkingConfig").has("thinkingLevel"))

        // Gemini 2.5 Pro disabled: thinkingBudget 128
        val chat25ProDisabled = """{"model":"gemini-2.5-pro","messages":[{"role":"user","content":"hi"}]}"""
        val req25ProDisabled = LLMGoogleAdapter.fromChatRequest(chat25ProDisabled, JsonArray(), LLMReasoningEffort.NONE)
        assertEquals(128, req25ProDisabled.getAsJsonObject("generationConfig").getAsJsonObject("thinkingConfig").get("thinkingBudget").asInt)

        // Gemini 2.5 adaptive: thinkingBudget -1
        val req25Adaptive = LLMGoogleAdapter.fromChatRequest(chat25Disabled, JsonArray(), LLMReasoningEffort.HIGH, thinkingMode = "adaptive")
        assertEquals(-1, req25Adaptive.getAsJsonObject("generationConfig").getAsJsonObject("thinkingConfig").get("thinkingBudget").asInt)
        assertTrue(req25Adaptive.getAsJsonObject("generationConfig").getAsJsonObject("thinkingConfig").get("includeThoughts").asBoolean)

        // Gemini 2.5 enabled: budget mapped by effort
        val req25High = LLMGoogleAdapter.fromChatRequest(chat25Disabled, JsonArray(), LLMReasoningEffort.HIGH)
        assertEquals(4096, req25High.getAsJsonObject("generationConfig").getAsJsonObject("thinkingConfig").get("thinkingBudget").asInt)
        assertTrue(req25High.getAsJsonObject("generationConfig").getAsJsonObject("thinkingConfig").get("includeThoughts").asBoolean)

        // Gemini 1.5 or 2.0 without thinking: no thinkingConfig
        val chat15 = """{"model":"gemini-1.5-pro","messages":[{"role":"user","content":"hi"}]}"""
        val req15 = LLMGoogleAdapter.fromChatRequest(chat15, JsonArray(), LLMReasoningEffort.HIGH)
        val gen15 = req15.getAsJsonObject("generationConfig")
        assertTrue(gen15 == null || !gen15.has("thinkingConfig"))
    }

    @Test
    fun `multi-turn continuation preserves native thought parts and thoughtSignature`() {
        val request = obj("""{"contents":[{"role":"user","parts":[{"text":"query"}]}]}""")
        val response = obj("""{
            "candidates":[{
                "content":{
                    "role":"model",
                    "parts":[
                        {"thought":true,"text":"internal reasoning","thoughtSignature":"sig_secret_123"},
                        {"functionCall":{"id":"call_1","name":"lookup","args":{"id":42}},"thoughtSignature":"sig_secret_123"}
                    ]
                },
                "finishReason":"STOP"
            }]
        }""")

        val calls = LLMGoogleAdapter.functionCalls(response)
        assertEquals(1, calls.size)
        assertEquals("call_1", calls[0].callId)
        assertEquals("lookup", calls[0].name)

        // Continuation
        LLMGoogleAdapter.appendContinuation(request, response, mapOf("call_1" to """{"result":"found"}"""))
        val contents = request.getAsJsonArray("contents")
        assertEquals(3, contents.size())

        // Model turn preserved verbatim
        val modelTurn = contents[1].asJsonObject
        assertEquals("model", modelTurn.get("role").asString)
        val modelParts = modelTurn.getAsJsonArray("parts")
        assertEquals(2, modelParts.size())
        assertEquals(true, modelParts[0].asJsonObject.get("thought").asBoolean)
        assertEquals("sig_secret_123", modelParts[0].asJsonObject.get("thoughtSignature").asString)
        assertEquals("sig_secret_123", modelParts[1].asJsonObject.get("thoughtSignature").asString)

        // User turn carries functionResponse with matching id and parsed json response
        val userTurn = contents[2].asJsonObject
        assertEquals("user", userTurn.get("role").asString)
        val responsePart = userTurn.getAsJsonArray("parts")[0].asJsonObject.getAsJsonObject("functionResponse")
        assertEquals("call_1", responsePart.get("id").asString)
        assertEquals("lookup", responsePart.get("name").asString)
        assertEquals("found", responsePart.getAsJsonObject("response").get("result").asString)
    }

    @Test
    fun `stateless OpenAI tool_calls history injects skip_thought_signature_validator and maps callId to name for tool messages`() {
        val chat = """{
            "model":"gemini-3.8-flash",
            "messages":[
                {"role":"user","content":"find something"},
                {"role":"assistant","content":null,"tool_calls":[{"id":"call_99","type":"function","function":{"name":"fetch","arguments":"{\"q\":\"test\"}"}}]},
                {"role":"tool","tool_call_id":"call_99","content":"{\"ok\":true}"}
            ]
        }"""
        val request = LLMGoogleAdapter.fromChatRequest(chat, JsonArray(), LLMReasoningEffort.NONE)
        val contents = request.getAsJsonArray("contents")
        assertEquals(3, contents.size())

        // Assistant turn has thoughtSignature sentinel on functionCall
        val modelTurn = contents[1].asJsonObject
        assertEquals("model", modelTurn.get("role").asString)
        val callPart = modelTurn.getAsJsonArray("parts")[0].asJsonObject
        assertEquals("skip_thought_signature_validator", callPart.get("thoughtSignature")?.asString)
        assertEquals("fetch", callPart.getAsJsonObject("functionCall").get("name").asString)

        // Tool turn maps tool_call_id "call_99" to name "fetch"
        val toolTurn = contents[2].asJsonObject
        assertEquals("user", toolTurn.get("role").asString)
        val funcResp = toolTurn.getAsJsonArray("parts")[0].asJsonObject.getAsJsonObject("functionResponse")
        assertEquals("fetch", funcResp.get("name").asString)
        assertEquals(true, funcResp.getAsJsonObject("response").get("ok").asBoolean)
    }

    @Test
    fun `extracts text while filtering thought parts and appends groundingChunks citations`() {
        val response = obj("""{
            "candidates":[{
                "content":{
                    "role":"model",
                    "parts":[
                        {"thought":true,"text":"thinking to be excluded"},
                        {"text":"Verified answer."}
                    ]
                },
                "groundingMetadata":{
                    "groundingChunks":[
                        {"web":{"uri":"https://source.example/fact","title":"Fact Source"}},
                        {"web":{"uri":"https://invalid","title":"Invalid"}}
                    ]
                },
                "finishReason":"STOP"
            }]
        }""")

        val text = LLMGoogleAdapter.text(response)
        assertFalse(text.contains("thinking to be excluded"))
        assertTrue(text.startsWith("Verified answer."))
        assertTrue(text.contains("[Fact Source](https://source.example/fact)"))
    }

    @Test
    fun `blockError detects promptFeedback, error object, and malformed function call`() {
        val blocked = obj("""{
            "promptFeedback":{"blockReason":"SAFETY"}
        }""")
        val errorObj = obj("""{
            "error":{"message":"bad"}
        }""")
        val malformed = obj("""{
            "candidates":[{"finishReason":"MALFORMED_FUNCTION_CALL"}]
        }""")
        val unblocked = obj("""{
            "candidates":[{"content":{"role":"model","parts":[{"text":"safe"}]},"finishReason":"STOP"}]
        }""")
        assertEquals("Google prompt blocked: SAFETY", LLMGoogleAdapter.blockError(blocked))
        assertEquals("Google upstream error: bad", LLMGoogleAdapter.blockError(errorObj))
        assertEquals("Google tool call failed: MALFORMED_FUNCTION_CALL", LLMGoogleAdapter.blockError(malformed))
        assertNull(LLMGoogleAdapter.blockError(unblocked))
    }

    @Test
    fun `log redaction masks x-goog-api-key headers and keys`() {
        val json = """{"x-goog-api-key":"AIzaSySecret123","other":"normal"}"""
        val redacted = LLMLogRedaction.formatBody(json)
        assertTrue(redacted.contains("<redacted>"))
        assertFalse(redacted.contains("AIzaSySecret123"))
    }

    @Test
    fun `toChatCompletion maps usageMetadata, cachedContentTokenCount, thoughtsTokenCount, and finishReasons`() {
        val response = obj("""{
            "candidates":[{
                "content":{
                    "role":"model",
                    "parts":[{"text":"Result text"}]
                },
                "finishReason":"MAX_TOKENS"
            }],
            "usageMetadata":{
                "promptTokenCount":10,
                "toolUsePromptTokenCount":2,
                "candidatesTokenCount":20,
                "thoughtsTokenCount":30,
                "cachedContentTokenCount":5,
                "totalTokenCount":62
            }
        }""")

        val completionJson = LLMGoogleAdapter.toChatCompletion(response, fallbackModel = "test-model")
        val root = obj(completionJson)
        val choice = root.getAsJsonArray("choices")[0].asJsonObject

        assertEquals("length", choice.get("finish_reason").asString)
        assertEquals("Result text", choice.getAsJsonObject("message").get("content").asString)

        val usage = root.getAsJsonObject("usage")
        assertNotNull(usage)
        assertEquals(12, usage.get("prompt_tokens").asInt) // 10 + 2
        assertEquals(50, usage.get("completion_tokens").asInt) // 20 + 30
        assertEquals(62, usage.get("total_tokens").asInt)
        assertEquals(5, usage.get("prompt_cache_hit_tokens").asInt)
        assertEquals(7, usage.get("prompt_cache_miss_tokens").asInt) // 12 - 5
        assertEquals(5, usage.getAsJsonObject("prompt_tokens_details").get("cached_tokens").asInt)
        assertEquals(30, usage.getAsJsonObject("completion_tokens_details").get("reasoning_tokens").asInt)
    }

    @Test
    fun `LLMGoogleStreamState reassembles streamed SSE parts, thoughts, function calls, and cumulative usage`() {
        val state = LLMGoogleStreamState()
        val chunk1 = obj("""{
            "candidates":[{
                "content":{"role":"model","parts":[{"thought":true,"text":"step1"}]}
            }]
        }""")
        val update1 = state.accept(chunk1)
        assertEquals("thinking", update1.phase)
        assertEquals("", update1.text)

        val chunk2 = obj("""{
            "candidates":[{
                "content":{"role":"model","parts":[{"text":"Hello "}]}
            }]
        }""")
        val update2 = state.accept(chunk2)
        assertEquals("generating", update2.phase)
        assertEquals("Hello ", update2.text)

        val chunk3 = obj("""{
            "candidates":[{
                "content":{"role":"model","parts":[{"text":"world!"}]},
                "finishReason":"STOP"
            }],
            "usageMetadata":{
                "promptTokenCount":5,
                "candidatesTokenCount":2,
                "thoughtsTokenCount":3,
                "totalTokenCount":10
            }
        }""")
        val update3 = state.accept(chunk3)
        assertEquals("generating", update3.phase)
        assertEquals("world!", update3.text)

        val completed = state.finish()
        assertEquals("Hello world!", LLMGoogleAdapter.text(completed))
        val usage = completed.getAsJsonObject("usageMetadata")
        assertEquals(5, usage.get("promptTokenCount").asInt)
        assertEquals(2, usage.get("candidatesTokenCount").asInt)
        assertEquals(3, usage.get("thoughtsTokenCount").asInt)
    }
}
