package org.qo.services.llmServices

import com.google.gson.JsonParser
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import jakarta.annotation.PreDestroy
import org.springframework.stereotype.Service

@Service
class LLMBalanceService(
	private val providers: ReloadableLLMProvider,
) {
	private val client = HttpClient(CIO) {
		install(HttpTimeout) {
			requestTimeoutMillis = 120 * 1000
			socketTimeoutMillis = 120 * 1000
			connectTimeoutMillis = 10 * 1000
		}
	}

	@PreDestroy
	fun shutdown() {
		client.close()
	}

	suspend fun getBalance(): Pair<Boolean, Double> {
		val provider = providers.current()
		val balance = provider.balanceRelated
		if (balance.balanceStruct == BalanceStructParse.NONE || balance.balanceUrl == null) {
			return false to -1.0
		}

		val response = client.get(balance.balanceUrl) {
			header(HttpHeaders.Authorization, "Bearer ${provider.apiToken}")
		}
		val result = JsonParser.parseString(response.bodyAsText()).asJsonObject

		return when (balance.balanceStruct) {
			BalanceStructParse.DEEPSEEK -> true to (
				result.getAsJsonArray("balance_infos")
					.firstOrNull { it.asJsonObject.get("currency").asString == "CNY" }
					?.asJsonObject
					?.get("total_balance")
					?.asDouble
					?: 0.0
			)

			BalanceStructParse.TEAMOROUTER ->
				true to result.getAsJsonObject("balance").get("value").asDouble

			BalanceStructParse.NONE -> false to -1.0
		}
	}
}
