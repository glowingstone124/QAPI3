package org.qo.services.llmServices

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

/** Loaded once at startup. Payment credentials are never exposed to the Web client. */
class AfdianConfig(
    val userId: String = "",
    val apiToken: String = "",
    val queryUrl: String = DEFAULT_QUERY_URL,
    val packs: List<CreditPack> = PACK_CREDITS.map { (amount, credits) -> CreditPack(amount, credits, "", "") },
) {
    companion object {
        const val DEFAULT_QUERY_URL = "https://ifdian.net/api/open/query-order"
        private val PACK_CREDITS = mapOf(5 to 350, 10 to 800, 20 to 1800)

        fun fromFile(path: Path): AfdianConfig {
            if (Files.notExists(path)) return AfdianConfig()
            val root = try {
                JsonParser.parseString(Files.readString(path)).asJsonObject
            } catch (_: Exception) {
                throw IllegalArgumentException("Invalid Afdian JSON configuration: $path")
            }
            val queryUrl = string(root, "queryUrl", DEFAULT_QUERY_URL)
            require(validHttpsUrl(queryUrl)) { "Afdian queryUrl must be an HTTPS URL" }
            val configuredPacks = root.get("packs")?.let {
                require(it.isJsonObject) { "Afdian packs must be an object" }
                it.asJsonObject
            } ?: JsonObject()
            require(configuredPacks.keySet().all { it in PACK_CREDITS.keys.map(Int::toString) }) {
                "Afdian packs only support 5, 10 and 20"
            }
            val packs = PACK_CREDITS.map { (amount, credits) ->
                val pack = configuredPacks.get(amount.toString())?.let {
                    require(it.isJsonObject) { "Afdian pack $amount must be an object" }
                    it.asJsonObject
                } ?: JsonObject()
                val checkoutUrl = string(pack, "checkoutUrl")
                if (checkoutUrl.isNotBlank()) {
                    require(validHttpsUrl(checkoutUrl) && URI(checkoutUrl).host in setOf("ifdian.net", "afdian.com", "afdian.net")) {
                        "Afdian pack $amount checkoutUrl must be an Afdian HTTPS URL"
                    }
                    require(!checkoutUrl.contains("custom_order_id") && URI(checkoutUrl).fragment == null) {
                        "Afdian pack $amount checkoutUrl must not include custom_order_id or a fragment"
                    }
                }
                CreditPack(amount, credits, string(pack, "skuId"), checkoutUrl)
            }
            return AfdianConfig(string(root, "userId"), string(root, "apiToken"), queryUrl, packs)
        }

        private fun string(obj: JsonObject, key: String, default: String = ""): String {
            val value = obj.get(key) ?: return default
            require(value.isJsonPrimitive && value.asJsonPrimitive.isString) { "Afdian $key must be a string" }
            return value.asString
        }

        private fun validHttpsUrl(value: String): Boolean = runCatching {
            val uri = URI(value)
            uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null
        }.getOrDefault(false)
    }
}

@Configuration
class AfdianConfiguration {
    @Bean
    fun afdianConfig(): AfdianConfig = AfdianConfig.fromFile(Path.of("data/afdian.json"))
}
