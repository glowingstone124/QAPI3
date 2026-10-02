package org.qo.services.llmServices

import com.google.gson.JsonPrimitive
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LLMToolCallParsingTest {
	private val service = Mockito.mock(LLMServices::class.java)

	@Test
	fun `normal answers with absent null or empty calls and routing metadata are accepted`() {
		val answer = "别乱猜啊，我第三只眼是关着的，你心里想的我知道个啥。不过你一提 nvidia，我脑子里自动弹出 dsv4 涨价那条。"
		for (calls in listOf("", ",\"tool_calls\":null", ",\"tool_calls\":[]")) {
			val response = completion(answer, calls)
			assertTrue(service.extractToolCalls(response).isEmpty())
			assertFalse(service.containsToolMarkup(response), calls)
		}
		val metadata = """, "metadata":{"tool_calls":null,"note":"<tool_call>routing only"}"""
		assertFalse(service.containsToolMarkup(completion(answer, metadata = metadata)))
		assertFalse(service.containsToolMarkup(completion("tool_calls 是字段名，invoke name= 是字符串。")))
	}

	@Test
	fun `broken markup malformed call arrays and missing calls on tool finish remain failures`() {
		for (response in listOf(
			completion("<tool_call>broken"),
			completion("<｜｜DSML｜｜tool_calls>broken"),
			completion("", ",\"tool_calls\":[{}]"),
			completion("", ",\"tool_calls\":{}"),
			completion("", ",\"tool_calls\":null", finish = "tool_calls"),
		)) {
			assertTrue(service.extractToolCalls(response).isEmpty())
			assertTrue(service.containsToolMarkup(response), response)
		}
	}

	@Test
	fun `null and empty native calls do not hide parseable DSML calls`() {
		val content = """<｜｜DSML｜｜tool_calls><｜｜DSML｜｜invoke name="get_current_date"><｜｜DSML｜｜parameter name="timezone">Asia/Shanghai</｜｜DSML｜｜parameter></｜｜DSML｜｜invoke></｜｜DSML｜｜tool_calls>"""
		for (calls in listOf("", ",\"tool_calls\":null", ",\"tool_calls\":[]")) {
			val call = service.extractToolCalls(completion(content, calls)).single()
			assertEquals("get_current_date", call.name)
			assertEquals("""{"timezone":"Asia/Shanghai"}""", call.arguments)
		}
	}

	@Test
	fun `valid native calls retain their IDs names and arguments`() {
		val response = """{"choices":[{"finish_reason":"tool_calls","message":{"content":null,"tool_calls":[{"id":"call-1","function":{"name":"get_current_date","arguments":"{}"}}]}}]}"""
		assertEquals(LLMServices.ToolCall("call-1", "get_current_date", "{}"), service.extractToolCalls(response).single())
	}

	private fun completion(content: String, calls: String = "", finish: String = "stop", metadata: String = ""): String =
		"""{"choices":[{"finish_reason":"$finish","message":{"role":"assistant","content":${JsonPrimitive(content)}$calls}}]$metadata}"""
}
