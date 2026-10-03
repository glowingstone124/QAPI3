package org.qo.services.llmServices

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito
import org.qo.datas.Mapping
import org.qo.datas.Nodes
import org.qo.db.repository.LlmAccessRecordDbRepository
import org.qo.orm.UserORM
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LLMDispatchTest {
	@TempDir lateinit var tempDir: Path
	private val body = """{"messages":[{"role":"user","content":"hello"}],"group_context":[]}"""

	@Test
	fun `all three entry points settle usage publish the public model and record history`() = runBlocking {
		for (source in LLMSource.entries) {
			fixture().use { fixture ->
				val result = fixture.dispatch(source, body)
				val response = JsonParser.parseString(result.body).asJsonObject
				assertEquals(200, result.status)
				assertEquals("fast", response.get("model").asString)
				assertEquals(99, response.getAsJsonObject("quota").get("remaining").asInt)
				assertEquals(1, fixture.requests)
				assertFalse(Mockito.mockingDetails(fixture.service.toolService).invocations.any { it.method.name.startsWith("logInvalidToolCall") })
				assertEquals(10L, fixture.store.settlements.single().inputTokens)
				assertEquals(5L, fixture.store.settlements.single().outputTokens)
				assertEquals(0, fixture.store.refunds)
				val minecraftGroup = fixture.service.minecraftGroupId(1)
				val key = if (source == LLMSource.MINECRAFT && minecraftGroup != null) "qq:$minecraftGroup:42" else "${source.value}:42"
				assertEquals(key, fixture.history().single()[0])
				assertEquals("answer", fixture.history().single()[2])
			}
		}
	}

	@Test
	fun `normal answers with null or empty tool fields succeed without failure archives`(): Unit = runBlocking {
		for (calls in listOf("null", "[]")) {
			val response = JsonParser.parseString(completion("正常回答")).asJsonObject.apply {
				getAsJsonArray("choices")[0].asJsonObject.getAsJsonObject("message")
					.add("tool_calls", JsonParser.parseString(calls))
			}
			fixture(response.toString()).use { fixture ->
				val result = fixture.dispatch(LLMSource.QQ, body)
				assertEquals(200, result.status)
				assertEquals("正常回答", fixture.history().single()[2])
				assertFalse(Mockito.mockingDetails(fixture.service.toolService).invocations.any { it.method.name.startsWith("logInvalidToolCall") })
			}
		}
	}

	@Test
	fun `all three entry points archive unparseable tool calls before returning the error`(): Unit = runBlocking {
		val response = completion("<tool_call>broken\n模型原始输出</tool_call>")
		for (source in LLMSource.entries) {
			fixture(response).use { fixture ->
				val result = fixture.dispatch(source, body)
				assertEquals(502, result.status)
				assertEquals("invalid_tool_call", JsonParser.parseString(result.body).asJsonObject.getAsJsonObject("error").get("code").asString)
				val tools = fixture.service.toolService
				val logged = Mockito.mockingDetails(tools).invocations.single { it.method.name.startsWith("logInvalidToolCall") }.arguments
				assertEquals(response, logged[0])
				val context = logged[1] as LLMToolContext
				assertEquals(source.value, context.source)
				assertEquals("42", context.uid)
				assertEquals("test", logged[3])
				assertEquals("private-model", logged[4])
				assertEquals(1, logged[5])
				assertFalse(Mockito.mockingDetails(tools).invocations.any { it.method.name == "execute" })
				assertTrue(fixture.history().isEmpty())
			}
		}
	}

	@Test
	fun `all three entry points refund an upstream HTTP failure without recording history`() = runBlocking {
		for (source in LLMSource.entries) {
			fixture("""{"error":{"code":"provider_busy"}}""", HttpStatusCode.ServiceUnavailable).use { fixture ->
				val result = fixture.dispatch(source, body)
				assertEquals(503, result.status)
				assertEquals("provider_busy", JsonParser.parseString(result.body).asJsonObject.getAsJsonObject("error").get("code").asString)
				assertEquals(1, fixture.store.refunds)
				assertTrue(fixture.store.settlements.isEmpty())
				assertTrue(fixture.history().isEmpty())
			}
		}
	}

	@Test
	fun `all three entry points reject exhausted quota before sending an upstream request`() = runBlocking {
		for (source in LLMSource.entries) {
			fixture().use { fixture ->
				fixture.store.status = LLMQuotaStatus.EXCEEDED
				val result = fixture.dispatch(source, body)
				assertEquals(429, result.status)
				assertEquals("weekly_quota_exceeded", JsonParser.parseString(result.body).asJsonObject.getAsJsonObject("error").get("code").asString)
				assertEquals(0, fixture.requests)
				assertEquals(0, fixture.store.refunds)
				assertTrue(fixture.store.settlements.isEmpty())
				assertTrue(fixture.history().isEmpty())
			}
		}
	}

	@Test
	fun `only the QQ entry point formats messages v1 and persists readable text`() = runBlocking {
		val reply = "First\n${LLMBotReplyFormat.BREAK}\nSecond"
		val formattedRequest = JsonParser.parseString(body).asJsonObject.apply {
			addProperty("qbot_reply_format", "messages_v1")
		}.toString()
		for (source in LLMSource.entries) {
			fixture(completion(reply)).use { fixture ->
				val result = fixture.dispatch(source, formattedRequest)
				assertEquals(200, result.status)
				val message = JsonParser.parseString(result.body).asJsonObject.getAsJsonArray("choices")[0].asJsonObject.getAsJsonObject("message")
				if (source == LLMSource.QQ) {
					assertEquals(listOf("First", "Second"), message.getAsJsonArray("qq_messages").map { it.asString })
					assertEquals("First\n\nSecond", fixture.history().single()[2])
				} else {
					assertFalse(message.has("qq_messages"))
				}
			}
		}
	}

	@Test
	fun `web client tool calls are settled without history until the final answer`() = runBlocking {
		val request = """{"tool_execution":"client","tools":[{"type":"function","function":{"name":"build","parameters":{"type":"object"}}}],"messages":[{"role":"user","content":"Build this"}],"group_context":[]}"""
		val toolResponse = """{"model":"private-model","choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":null,"tool_calls":[{"id":"call-1","type":"function","function":{"name":"build","arguments":"{}"}}]}}],"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}"""
		fixture(toolResponse).use { fixture ->
			val result = fixture.dispatch(LLMSource.WEB, request)
			assertEquals(200, result.status)
			assertTrue(fixture.history().isEmpty())
			assertEquals(1, fixture.store.settlements.size)
			Mockito.verifyNoInteractions(fixture.conversations)
		}
		val continued = JsonParser.parseString(request).asJsonObject.apply {
			val assistant = JsonParser.parseString(toolResponse).asJsonObject.getAsJsonArray("choices")[0].asJsonObject.get("message")
			getAsJsonArray("messages").add(assistant)
			getAsJsonArray("messages").add(JsonParser.parseString("""{"role":"tool","tool_call_id":"call-1","content":"done"}"""))
		}.toString()
		fixture(completion("Built")).use { fixture ->
			val result = fixture.dispatch(LLMSource.WEB, continued)
			assertEquals(200, result.status)
			assertEquals("Build this", (fixture.history().single()[1] as JsonElement).asString)
			assertEquals("Built", fixture.history().single()[2])
			assertEquals(1, fixture.store.settlements.size)
		}
	}

	private fun completion(content: String = "answer"): String =
		"""{"model":"private-model","choices":[{"finish_reason":"stop","message":{"role":"assistant","content":${JsonPrimitive(content)}}}],"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}"""

	private suspend fun fixture(response: String = completion(), status: HttpStatusCode = HttpStatusCode.OK): Fixture {
		val file = tempDir.resolve("providers.json")
		Files.writeString(file, """
			{"defaultProvider":"test","providers":{"test":{
				"chatCompletionsUrl":"https://example.org/chat","responsesUrl":"unavailable","anthropicUrl":"unavailable","token":"test-token",
				"models":{
					"fast":{"model":"private-model","protocol":"chat-completions","pricing":{"inputCnyPerMillion":1,"outputCnyPerMillion":4}},
					"thinking":{"model":"private-model","protocol":"chat-completions"}
				}
			}}}
		""".trimIndent())
		val fixture = Fixture(response, status)
		fixture.configure(LLMProvider.fromConfig(file))
		return fixture
	}

	private class Fixture(response: String, status: HttpStatusCode) : AutoCloseable {
		val service = Mockito.mock(LLMServices::class.java)
		val store = AccountingStore()
		val conversations = Mockito.mock(LLMConversationService::class.java)
		var requests = 0
		private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
		private val client = HttpClient(MockEngine {
			requests++
			respond(response, status)
		})

		suspend fun configure(provider: LLMProvider) {
			val principal = LLMPrincipal(42, "Alice", LLMSource.WEB, "Alice")
			val providers = Mockito.mock(ReloadableLLMProvider::class.java)
			Mockito.`when`(providers.current()).thenReturn(provider)
			Mockito.`when`(service.providers).thenReturn(providers)
			Mockito.`when`(service.authenticateWeb("token")).thenReturn(principal)
			Mockito.`when`(service.authenticateServerToken("token")).thenReturn(true)
			Mockito.`when`(service.qqPrincipal(42, "Alice")).thenReturn(principal.copy(source = LLMSource.QQ, sourceIdentity = "42"))
			Mockito.`when`(service.modelPresetFromRequest("fast")).thenReturn("fast")
			val nodes = Mockito.mock(Nodes::class.java)
			Mockito.`when`(nodes.getServerFromToken("token")).thenReturn(1)
			Mockito.`when`(service.nodes).thenReturn(nodes)
			val users = Mockito.mock(UserORM::class.java)
			Mockito.`when`(users.readAsync("Player")).thenReturn(Mapping.Users("Player", 42))
			Mockito.`when`(service.userORM).thenReturn(users)
			Mockito.`when`(service.client).thenReturn(client)
			Mockito.`when`(service.dailyQuotaService).thenReturn(LLMDailyQuotaService(store, 100, 100, "Asia/Shanghai", configuredPromotionMultiplier = 1))
			Mockito.`when`(service.chatHistoryService).thenReturn(Mockito.mock(LLMChatHistoryService::class.java))
			Mockito.`when`(service.groupContextService).thenReturn(Mockito.mock(LLMGroupContextService::class.java))
			Mockito.`when`(service.memberProfileService).thenReturn(Mockito.mock(LLMMemberProfileService::class.java))
			Mockito.`when`(service.memberProfileContextService).thenReturn(Mockito.mock(LLMMemberProfileContextService::class.java))
			Mockito.`when`(service.memoryService).thenReturn(Mockito.mock(LLMMemoryService::class.java))
			Mockito.`when`(service.conversationService).thenReturn(conversations)
			Mockito.`when`(conversations.historyMessages(Mockito.anyString())).thenReturn(JsonArray())
			Mockito.`when`(service.ultraBriefQqUids).thenReturn(emptySet())
			Mockito.`when`(service.systemPrompt).thenReturn(ReloadableSystemPrompt(null, null, ""))
			Mockito.`when`(service.webSearchRules(Mockito.anyBoolean())).thenCallRealMethod()
			val tools = Mockito.mock(LLMToolService::class.java)
			Mockito.`when`(tools.definitions()).thenReturn(JsonArray())
			Mockito.`when`(tools.definitions(Mockito.anySet(), Mockito.nullable(String::class.java))).thenReturn(JsonArray())
			Mockito.`when`(tools.usesRemoteSearch()).thenReturn(true)
			Mockito.`when`(service.toolService).thenReturn(tools)
			Mockito.`when`(service.maxToolRounds).thenReturn(1)
			Mockito.`when`(service.initializationScope).thenReturn(scope)
			Mockito.`when`(service.accessRecordSchemaInitializationStarted).thenReturn(AtomicBoolean(true))
			Mockito.`when`(service.accessRecordSchemaReady).thenReturn(CompletableDeferred(Unit))
			Mockito.`when`(service.accessRecordRepository).thenReturn(Mockito.mock(LlmAccessRecordDbRepository::class.java))
		}

		suspend fun dispatch(source: LLMSource, body: String): LLMNonStreamResult = when (source) {
			LLMSource.WEB -> service.dispatchCompleteChat(body, "token", "fast", "request-1")
			LLMSource.QQ -> service.dispatchCompleteBotChat(body, "token", 42, null, "Alice", model = "fast", clientRequestId = "request-1")
			LLMSource.MINECRAFT -> service.dispatchCompleteMinecraftChat(body, "token", "Player", "0,64,0", "20", "fast", "request-1")
		}

		fun history(): List<Array<Any?>> = Mockito.mockingDetails(conversations).invocations
			.filter { it.method.name == "append" }.map { it.arguments }

		override fun close() {
			scope.cancel()
			client.close()
		}
	}

	private class AccountingStore : LLMQuotaStore {
		var status = LLMQuotaStatus.ACCEPTED
		var refunds = 0
		val settlements = mutableListOf<AiQuotaUsage>()
		override suspend fun reserve(quotaKey: String, requestKey: String, limit: Int, expiresAtEpochSeconds: Long) =
			LLMQuotaStoreDecision(status, 1)
		override suspend fun used(quotaKey: String): Int = 0
		override suspend fun refund(reservation: LLMQuotaReservation): Int = 0.also { refunds++ }
		override suspend fun settle(reservation: LLMQuotaReservation, usage: AiQuotaUsage): LLMQuotaView {
			settlements.add(usage)
			return reservation.view.copy(used = 1, remaining = 99, chargedUnits = usage.chargedUnits)
		}
	}
}
