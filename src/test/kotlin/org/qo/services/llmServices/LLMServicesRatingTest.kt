package org.qo.services.llmServices

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LLMServicesRatingTest {

	private val allowed = setOf("onebot:1001", "onebot:1002", "onebot:2001")

	@Test
	fun `parseRatingOutput parses pure json correctly`() {
		val json = """
			{
			  "ratings": [
			    {
			      "id": "onebot:1001",
			      "cost": 15.5,
			      "should_reply": true,
			      "reason": "群友提问，适合回复"
			    },
			    {
			      "id": "onebot:1002",
			      "cost": 85.0,
			      "should_reply": false,
			      "reason": "双人专属对话"
			    }
			  ]
			}
		""".trimIndent()

		val ratings = parseRatingOutput(json, allowed)
		assertNotNull(ratings)
		assertEquals(2, ratings.size)
		assertEquals("onebot:1001", ratings[0].id)
		assertEquals(15.5, ratings[0].cost)
		assertTrue(ratings[0].shouldReply)
		assertEquals("群友提问，适合回复", ratings[0].reason)
		assertEquals("onebot:1002", ratings[1].id)
		assertEquals(85.0, ratings[1].cost)
		assertEquals(false, ratings[1].shouldReply)
	}

	@Test
	fun `parseRatingOutput strips markdown code block fences`() {
		val json = """
			```json
			{
			  "ratings": [
			    {
			      "id": "onebot:2001",
			      "cost": 20.0,
			      "should_reply": true,
			      "reason": "开玩笑接梗"
			    }
			  ]
			}
			```
		""".trimIndent()

		val ratings = parseRatingOutput(json, allowed)
		assertNotNull(ratings)
		assertEquals(1, ratings.size)
		assertEquals("onebot:2001", ratings[0].id)
		assertEquals(20.0, ratings[0].cost)
	}

	@Test
	fun `parseRatingOutput rejects malformed root and ratings as object`() {
		assertNull(parseRatingOutput("not a json", allowed))
		assertNull(parseRatingOutput("{\"ratings\": {\"id\": \"onebot:1001\", \"cost\": 10.0}}", allowed))
		assertNull(parseRatingOutput("{\"something_else\": 123}", allowed))
	}

	@Test
	fun `parseRatingOutput rejects string cost oops`() {
		val json = """
			{
			  "ratings": [
			    {"id": "onebot:1001", "cost": "oops", "should_reply": true}
			  ]
			}
		""".trimIndent()
		assertNull(parseRatingOutput(json, allowed))
	}

	@Test
	fun `parseRatingOutput rejects negative cost`() {
		val json = """
			{
			  "ratings": [
			    {"id": "onebot:1001", "cost": -5.0, "should_reply": true}
			  ]
			}
		""".trimIndent()
		assertNull(parseRatingOutput(json, allowed))
	}

	@Test
	fun `parseRatingOutput rejects cost greater than 100`() {
		val json = """
			{
			  "ratings": [
			    {"id": "onebot:1001", "cost": 105.0, "should_reply": true}
			  ]
			}
		""".trimIndent()
		assertNull(parseRatingOutput(json, allowed))
	}

	@Test
	fun `parseRatingOutput rejects unknown candidate id`() {
		val json = """
			{
			  "ratings": [
			    {"id": "unknown_id_999", "cost": 10.0, "should_reply": true}
			  ]
			}
		""".trimIndent()
		assertNull(parseRatingOutput(json, allowed))
	}
}
