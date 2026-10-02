package org.qo.services.llmServices

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.qo.services.llmServices.tools.AddMemoryTool
import org.qo.services.llmServices.tools.ForgetMemberProfileFieldTool
import org.qo.services.llmServices.tools.ForgetMemoryTool
import org.qo.services.llmServices.tools.GetMemberProfileTool
import org.qo.services.llmServices.tools.QueryMetroLinesTool
import org.qo.services.llmServices.tools.SearchChatHistoryTool
import org.qo.services.llmServices.tools.SearchMemoryTool
import org.qo.services.llmServices.tools.Tools
import org.qo.services.llmServices.tools.UpsertMemberProfileTool
import org.qo.services.metroServices.MetroServiceImpl
import org.qo.services.transportationServices.Dimension
import org.qo.services.transportationServices.LineType
import org.qo.services.transportationServices.RouteConstraints
import org.qo.services.transportationServices.Station
import org.qo.services.transportationServices.TransportationServiceImpl
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LLMStructuredToolsTest {
	private val context = LLMToolContext(7, "42", "Alice", currentMessage = "/remember 昵称叫 Alice")

	@Test
	fun `group tools reject missing context before parsing arguments or accessing storage`(): Unit = runBlocking {
		val memory = Mockito.mock(LLMMemoryService::class.java)
		val history = Mockito.mock(LLMChatHistoryService::class.java)
		val tools = listOf(AddMemoryTool(memory), SearchMemoryTool(memory), ForgetMemoryTool(memory), SearchChatHistoryTool(history))
		for (tool in tools) {
			val result = execute(tool, """{"query":{},"subject":{},"memory_id":{}}""", context.copy(groupId = null))
			assertEquals("missing_group", result.get("error").asString)
		}
		Mockito.verifyNoInteractions(memory, history)
	}

	@Test
	fun `memory aliases retain null precedence and trimmed primitive arguments`(): Unit = runBlocking {
		val memory = Mockito.mock(LLMMemoryService::class.java)
		val record = LLMMemoryRecord("memory-1", 7, "11", "key", "<note>", "custom", "42", "Alice", 1, 1, null)
		Mockito.`when`(memory.upsertMemory(7, "11", "key", "<note>", " custom ", "42", "Alice", null))
			.thenReturn(MemoryMutation(record, true))
		val tool = AddMemoryTool(memory)
		val args = """{"subject":11,"memory_key":" key ","fact":null,"data":" <note> ","category":" custom "}"""

		assertEquals("bad_arguments", execute(tool, args).get("error").asString)
		Mockito.verifyNoInteractions(memory)
		val withoutFact = JsonParser.parseString(args).asJsonObject.apply { remove("fact") }
		val raw = tool.execute(withoutFact, context)
		val saved = JsonParser.parseString(raw).asJsonObject
		assertEquals("add_memory", saved.get("tool").asString)
		assertTrue(saved.get("saved").asBoolean)
		assertTrue(saved.get("created").asBoolean)
		assertEquals(7, saved.get("group_id").asInt)
		assertEquals("memory-1", saved.getAsJsonObject("memory").get("id").asString)
		assertTrue(raw.contains("<note>"))
		Mockito.verify(memory).upsertMemory(7, "11", "key", "<note>", " custom ", "42", "Alice", null)
	}

	@Test
	fun `memory search always uses the current group and keeps numeric query coercion`(): Unit = runBlocking {
		val memory = Mockito.mock(LLMMemoryService::class.java)
		Mockito.`when`(memory.search(7, "123", 8)).thenReturn(emptyList())

		val result = execute(SearchMemoryTool(memory), """{"query":123,"group_id":999}""")

		assertEquals("search_memory", result.get("tool").asString)
		assertEquals(7, result.get("group_id").asInt)
		assertEquals(0, result.get("returned").asInt)
		assertEquals(0, result.getAsJsonArray("memories").size())
		Mockito.verify(memory).search(7, "123", 8)
	}

	@Test
	fun `profile tools retain consent target and group scope boundaries`(): Unit = runBlocking {
		val profiles = Mockito.mock(LLMMemberProfileService::class.java)
		val read = GetMemberProfileTool(profiles)
		val upsert = UpsertMemberProfileTool(profiles)
		val forget = ForgetMemberProfileFieldTool(profiles)
		assertEquals("persistence_consent_required", execute(upsert, "{}", context.copy(uid = null, currentMessage = "叫我 Alice")).get("error").asString)
		for (tool in listOf(read, upsert, forget)) {
			assertEquals("forbidden_target", execute(tool, """{"qq_uid":" 99 "}""").get("error").asString)
		}
		val nickname = """{"qq_uid":42,"field_key":" group_nickname ","value":" Alice ","scope":null}"""
		assertEquals("missing_group", execute(upsert, nickname, context.copy(groupId = null)).get("error").asString)
		assertEquals("missing_group", execute(forget, nickname, context.copy(groupId = null)).get("error").asString)
		assertEquals("bad_arguments", execute(upsert, nickname.replace("null", "\"global\"")).get("error").asString)
		Mockito.verifyNoInteractions(profiles)

		val result = execute(read, """{"qq_uid":null}""")
		assertEquals(42, result.get("qq_uid").asInt)
		assertFalse(result.get("found").asBoolean)
		assertFalse(result.has("profile"))
		Mockito.verify(profiles).profile(42, 7)
	}

	@Test
	fun `chat history keeps timestamp normalization defaults and output truncation`(): Unit = runBlocking {
		val history = Mockito.mock(LLMChatHistoryService::class.java)
		val message = LLMChatHistoryRecord("onebot:1", 7, 42, "Alice", "x".repeat(1200), 1, 1)
		Mockito.`when`(history.search(7, "topic", null, 1_700_000_000_000, 1_700_000_001_000, 12)).thenReturn(listOf(message))

		val result = execute(SearchChatHistoryTool(history), """{"query":" topic ","uid":null,"from_time":1700000000,"to_time":1700000001000,"limit":null}""")

		assertEquals(7, result.get("group_id").asInt)
		assertEquals(1, result.get("returned").asInt)
		assertEquals(1000, result.getAsJsonArray("messages")[0].asJsonObject.get("content").asString.length)
		Mockito.verify(history).search(7, "topic", null, 1_700_000_000_000, 1_700_000_001_000, 12)
	}

	@Test
	fun `metro route failures preserve candidates constraints and distinct messages`(): Unit = runBlocking {
		val metro = Mockito.mock(MetroServiceImpl::class.java)
		val transportation = Mockito.mock(TransportationServiceImpl::class.java)
		val tool = QueryMetroLinesTool(metro, transportation)
		Mockito.`when`(transportation.getStationById("A")).thenReturn(Station("起点", "A", emptyArray(), "Start"))
		Mockito.`when`(transportation.queryStationsByName("B")).thenReturn(emptyList())
		Mockito.`when`(transportation.listStations()).thenReturn(emptyList())
		val args = """{"from":" A ","to":" B ","exclude_dims":[null,"nether"],"exclude_types":"walk, blue_ice"}"""

		val missing = execute(tool, args)
		assertEquals("起点或终点没有匹配到站点。", missing.get("message").asString)
		assertFalse(missing.get("found").asBoolean)
		assertEquals(1, missing.getAsJsonArray("from_candidates").size())
		assertEquals(0, missing.getAsJsonArray("to_candidates").size())
		assertEquals(listOf("NETHER"), missing.getAsJsonObject("constraints").getAsJsonArray("exclude_dims").map { it.asString })
		assertEquals(listOf("WALK", "BLUEICE"), missing.getAsJsonObject("constraints").getAsJsonArray("exclude_types").map { it.asString })

		Mockito.`when`(transportation.getStationById("B")).thenReturn(Station("终点", "B", emptyArray(), "End"))
		val unreachable = execute(tool, args)
		assertEquals("站点存在，但没有计算到可用路线。", unreachable.get("message").asString)
		assertFalse(unreachable.get("found").asBoolean)
		assertEquals(1, unreachable.getAsJsonArray("to_candidates").size())
		Mockito.verify(transportation).calculateRoute("A", "B", RouteConstraints(setOf(Dimension.NETHER), setOf(LineType.WALK, LineType.BLUEICE)))
		Mockito.verifyNoInteractions(metro)
	}

	private suspend fun execute(tool: Tools, args: String, toolContext: LLMToolContext = context): JsonObject =
		JsonParser.parseString(tool.execute(JsonParser.parseString(args).asJsonObject, toolContext)).asJsonObject
}
