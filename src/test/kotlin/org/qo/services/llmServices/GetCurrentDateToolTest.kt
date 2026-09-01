package org.qo.services.llmServices

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Test
import org.qo.services.llmServices.tools.GetCurrentDateTool
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GetCurrentDateToolTest {
	private val tool = GetCurrentDateTool()
	private val fixedClock = Clock.fixed(
		Instant.parse("2026-09-01T00:30:45Z"),
		ZoneOffset.UTC,
	)

	@Test
	fun `tool returns current date in default Shanghai timezone`() {
		val result = execute(JsonObject())

		assertEquals("get_current_date", result.get("tool").asString)
		assertEquals("Asia/Shanghai", result.get("timezone").asString)
		assertEquals("2026-09-01", result.get("date").asString)
		assertEquals("08:30:45", result.get("time").asString)
		assertEquals("Tuesday", result.get("day_of_week").asString)
		assertEquals("星期二", result.get("day_of_week_zh").asString)
		assertEquals("+08:00", result.get("offset").asString)
		assertEquals(Instant.parse("2026-09-01T00:30:45Z").epochSecond, result.get("unix_timestamp").asLong)
	}

	@Test
	fun `tool respects requested timezone`() {
		val args = JsonObject().apply { addProperty("timezone", "America/Los_Angeles") }

		val result = execute(args)

		assertEquals("America/Los_Angeles", result.get("timezone").asString)
		assertEquals("2026-08-31", result.get("date").asString)
		assertEquals("17:30:45", result.get("time").asString)
		assertEquals("-07:00", result.get("offset").asString)
	}

	@Test
	fun `tool rejects invalid timezone`() {
		val args = JsonObject().apply { addProperty("timezone", "not/a-timezone") }

		val result = execute(args)

		assertEquals("invalid_timezone", result.get("error").asString)
		assertTrue(result.get("message").asString.contains("not/a-timezone"))
	}

	private fun execute(args: JsonObject) = JsonParser.parseString(
		tool.executeAt(args, fixedClock),
	).asJsonObject
}
