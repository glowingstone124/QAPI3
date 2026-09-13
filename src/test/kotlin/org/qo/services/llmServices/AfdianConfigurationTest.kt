package org.qo.services.llmServices

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AfdianConfigurationTest {
    @TempDir lateinit var directory: Path

    private fun load(json: String): AfdianConfig {
        val file = directory.resolve("afdian.json")
        Files.writeString(file, json)
        return AfdianConfig.fromFile(file)
    }

    @Test
    fun `file supplies credentials query URL and purchase links with fixed credit amounts`() {
        val config = load("""{
            "userId":"merchant",
            "apiToken":"test-token",
            "queryUrl":"https://afdian.com/api/open/query-order",
            "packs":{
                "5":{"skuId":"sku5","checkoutUrl":"https://ifdian.net/order?sku_id=sku5"},
                "10":{"skuId":"sku10","checkoutUrl":"https://afdian.com/order?sku_id=sku10"},
                "20":{"skuId":"sku20","checkoutUrl":"https://afdian.net/order?sku_id=sku20"}
            }
        }""")
        assertEquals("merchant", config.userId)
        assertEquals("test-token", config.apiToken)
        assertEquals("https://afdian.com/api/open/query-order", config.queryUrl)
        assertEquals(listOf(5 to 350, 10 to 800, 20 to 1800), config.packs.map { it.amount to it.credits })
        assertEquals("sku10", config.packs[1].skuId)
        assertEquals("https://afdian.com/order?sku_id=sku10", config.packs[1].checkoutUrl)
    }

    @Test
    fun `missing configuration disables purchase packs and defaults query URL`() {
        val config = AfdianConfig.fromFile(directory.resolve("missing.json"))
        assertTrue(config.userId.isBlank() && config.apiToken.isBlank())
        assertTrue(config.packs.all { it.skuId.isBlank() && it.checkoutUrl.isBlank() })
        assertEquals(AfdianConfig.DEFAULT_QUERY_URL, config.queryUrl)
        assertEquals(AfdianConfig.DEFAULT_QUERY_URL, load("{}").queryUrl)
    }

    @Test
    fun `malformed and incorrectly typed configuration does not silently disable payments`() {
        for (json in listOf("{", "[]", """{"apiToken":123}""", """{"packs":[]}""", """{"packs":{"50":{}}}""")) {
            assertFailsWith<IllegalArgumentException> { load(json) }
        }
    }

    @Test
    fun `invalid purchase and query URLs are rejected when configuration loads`() {
        for (url in listOf("http://ifdian.net/order", "https://example.com/order", "https://ifdian.net/order#sku", "https://ifdian.net/order?custom_order_id=other")) {
            assertFailsWith<IllegalArgumentException> {
                load("""{"packs":{"5":{"checkoutUrl":"$url"}}}""")
            }
        }
        assertFailsWith<IllegalArgumentException> { load("""{"queryUrl":"http://ifdian.net/api/open/query-order"}""") }
    }
}
