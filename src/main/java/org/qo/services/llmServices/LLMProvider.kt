package org.qo.services.llmServices

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

enum class BalanceStructParse(val provider: String) {
	DEEPSEEK("deepseek"),
	TEAMOROUTER("teamorouter"),
	NONE("none");

	companion object {
		fun fromProvider(provider: String?): BalanceStructParse {
			if (provider.isNullOrBlank()) {
				return NONE
			}

			return entries.firstOrNull {
				it.provider.equals(provider.trim(), ignoreCase = true)
			} ?: NONE
		}
	}
}

data class BalanceRelated(
	val balanceUrl: String?,
	val balanceStruct: BalanceStructParse,
)

data class LLMProvider(
	val name: String,
	val chatCompletionsUrl: String,
	val responsesUrl: String,
	val apiToken: String,
	val fastModel: String,
	val thinkingModel: String,
	val responsesModels: Set<LLMServices.MODELS>,
	val balanceRelated: BalanceRelated,
) {
	fun modelName(model: LLMServices.MODELS): String = when (model) {
		LLMServices.MODELS.FAST -> fastModel
		LLMServices.MODELS.THINKING -> thinkingModel
	}

	fun supportsResponses(model: LLMServices.MODELS): Boolean = model in responsesModels

	companion object {
		fun fromEnvironment(): LLMProvider {
			val configPath = Path.of(System.getenv("LLM_PROVIDERS_FILE") ?: "data/llm/providers.json")
			val explicitlySelected = System.getenv("LLM_PROVIDER")?.trim()?.takeIf { it.isNotBlank() }
			return fromConfig(configPath, explicitlySelected)
		}

		fun fromConfig(configPath: Path, explicitlySelected: String? = null): LLMProvider {
			val root = readConfig(configPath)
			val providers = root?.getAsJsonObject("providers")
			val selectedName = explicitlySelected
				?: root?.get("defaultProvider")?.asString?.takeIf { it.isNotBlank() }
				?: providers?.keySet()?.firstOrNull() ?: throw Exception("provider not found")
			val configured = providers?.get(selectedName)?.takeIf { it.isJsonObject }?.asJsonObject
			if (configured == null) {
				error("LLM provider '$selectedName' is not defined in $configPath")
			}
			val token = configured.get("token")?.asString?.takeIf { it.isNotBlank() }
				?: configured.get("tokenFile")?.asString?.let { tokenFile ->
					runCatching { Files.readString(Path.of(tokenFile)).trim() }.getOrDefault("")
				} ?: throw Exception("token param not found in provider $selectedName")
			val balanceUrl = configured.get("balanceUrl").asString ?: throw Exception("$selectedName balanceUrl not defined in $configPath")

			return LLMProvider(
				name = selectedName,
				chatCompletionsUrl = configured.get("chatCompletionsUrl")?.asString ?: throw Exception("chatCompletionsUrl not defined in $configPath"),
				responsesUrl = configured.get("responsesUrl")?.asString ?: throw Exception("responsesUrl not defined in $configPath"),
				apiToken = token,
				fastModel = configured.getAsJsonObject("models")?.get("fast")?.asString ?: throw Exception("fastModel not defined in $configPath"),
				thinkingModel = configured.getAsJsonObject("models")?.get("thinking")?.asString ?: throw Exception("thinkingModel not defined in $configPath"),
				responsesModels = readResponsesModels(configured),
				balanceRelated = BalanceRelated(
					balanceUrl = balanceUrl,
					balanceStruct = BalanceStructParse.fromProvider(selectedName),
				)
			)
		}

		private fun readConfig(path: Path): JsonObject? {
			if (!Files.isRegularFile(path)) return null
			return try {
				JsonParser.parseString(Files.readString(path)).asJsonObject
			} catch (error: Exception) {
				throw IllegalStateException("Invalid LLM provider configuration: $path", error)
			}
		}

		private fun readResponsesModels(configured: JsonObject?): Set<LLMServices.MODELS> {
			val values = configured?.getAsJsonArray("responsesModels")
				?: return setOf(LLMServices.MODELS.FAST)
			return values.mapNotNull { item ->
				val normalized = item.asString.trim().lowercase(Locale.ROOT)
				LLMServices.MODELS.entries.firstOrNull {
					it.name.lowercase(Locale.ROOT) == normalized ||
						it.alias == normalized ||
						it.apiName.lowercase(Locale.ROOT) == normalized
				}
			}.toSet()
		}
	}
}
