package org.qo.services.llmServices

import com.google.gson.JsonParser
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.runBlocking
import org.mockito.Mockito
import kotlin.test.Test
import kotlin.test.assertEquals

class LLMIntermediateMessageTest {
    private val requester = LLMServices.LLMRequester(42, "Alice", "qq", groupId = 7)

    @Test
    fun `image preamble is sent once and retained only when delivery fails`(): Unit = runBlocking {
        for (status in listOf(HttpStatusCode.OK, HttpStatusCode.ServiceUnavailable)) {
            val service = Mockito.mock(LLMServices::class.java, Mockito.RETURNS_DEEP_STUBS)
            Mockito.`when`(service.providers.current().imageGeneration).thenReturn(
                LLMImageGenerationConfig(qbotEndpoint = "https://bot.example/", qbotToken = "test-token")
            )
            var requests = 0
            HttpClient(MockEngine { outgoing ->
                requests++
                assertEquals("https://bot.example/action/send_message", outgoing.url.toString())
                assertEquals("test-token", outgoing.headers[HttpHeaders.Authorization])
                val body = JsonParser.parseString((outgoing.body as TextContent).text).asJsonObject
                assertEquals(7, body.get("group_id").asInt)
                assertEquals("稍等一下", body.get("text").asString)
                respond("{}", status)
            }).use { client ->
                Mockito.`when`(service.client).thenReturn(client)
                val messages = mutableListOf("之前的回复")
                service.recordIntermediateMessage("  稍等一下  ", true, true, requester, messages)
                assertEquals(1, requests)
                assertEquals(
                    if (status == HttpStatusCode.OK) listOf("之前的回复") else listOf("之前的回复", "稍等一下"),
                    messages,
                )
            }
        }
    }

    @Test
    fun `ordinary tools and missing group or bot configuration keep text for the final response`(): Unit = runBlocking {
        for ((hasImage, groupId, endpoint) in listOf(Triple(false, 7L, "https://bot.example"), Triple(true, null, "https://bot.example"), Triple(true, 7L, ""))) {
            val service = Mockito.mock(LLMServices::class.java, Mockito.RETURNS_DEEP_STUBS)
            Mockito.`when`(service.providers.current().imageGeneration).thenReturn(
                LLMImageGenerationConfig(qbotEndpoint = endpoint, qbotToken = "test-token")
            )
            var requests = 0
            HttpClient(MockEngine { requests++; respond("{}") }).use { client ->
                Mockito.`when`(service.client).thenReturn(client)
                val messages = mutableListOf<String>()
                service.recordIntermediateMessage("稍等一下", true, hasImage, requester.copy(groupId = groupId), messages)
                assertEquals(listOf("稍等一下"), messages)
                assertEquals(0, requests)
            }
        }
    }

    @Test
    fun `empty intermediate text is ignored before consulting bot configuration`(): Unit = runBlocking {
        val service = Mockito.mock(LLMServices::class.java)
        val messages = mutableListOf<String>()
        for (text in listOf(null, "", " \n ", "<think>private</think>")) {
            service.recordIntermediateMessage(text, true, true, requester, messages)
        }
        assertEquals(emptyList(), messages)
        Mockito.verify(service, Mockito.never()).providers
        Mockito.verify(service, Mockito.never()).client
    }
}
