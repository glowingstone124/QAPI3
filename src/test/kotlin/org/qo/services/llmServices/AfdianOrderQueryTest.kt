package org.qo.services.llmServices

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AfdianOrderQueryTest {
    @Test fun `official query must contain exactly the requested order`() {
        val response = JsonParser.parseString("""{"ec":200,"data":{"list":[{"out_trade_no":"other"},{"out_trade_no":"wanted","status":2}]}}""").asJsonObject
        assertEquals("wanted", queriedAfdianOrder(response, "wanted").get("out_trade_no").asString)
        assertFailsWith<AfdianOrderNotFoundException> { queriedAfdianOrder(response, "missing") }
    }

    @Test fun `empty lists require retry and duplicate matches cannot be used to grant credits`() {
        val empty = JsonParser.parseString("""{"ec":200,"data":{"list":[]}}""").asJsonObject
        assertFailsWith<AfdianOrderNotFoundException> { queriedAfdianOrder(empty, "wanted") }
        val duplicate = JsonParser.parseString("""{"ec":200,"data":{"list":[{"out_trade_no":"wanted"},{"out_trade_no":"wanted"}]}}""").asJsonObject
        assertFailsWith<IllegalStateException> { queriedAfdianOrder(duplicate, "wanted") }
        val failure = JsonParser.parseString("""{"ec":400001,"data":{"list":[]}}""").asJsonObject
        assertFailsWith<IllegalStateException> { queriedAfdianOrder(failure, "wanted") }
    }
}
