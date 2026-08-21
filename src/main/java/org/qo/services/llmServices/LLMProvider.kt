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

data class LLMSummaryConfig(
	val providerName: String,
	val chatCompletionsUrl: String,
	val apiToken: String,
	val model: String,
	val contextWindow: Int,
)

data class LLMCompactConfig(
	val enabled: Boolean = true,
	val triggerTurns: Int = 12,
	val triggerPercent: Int = 70,
	val keepTurns: Int = 4,
	val maxSummaryChars: Int = 8_000,
)

data class LLMProvider(
	val name: String,
	val chatCompletionsUrl: String,
	val responsesUrl: String,
	val apiToken: String,
	val models: Map<String, String>,
	val fastModel: String,
	val thinkingModel: String,
	val contextWindow: Int,
	val summary: LLMSummaryConfig,
	val compact: LLMCompactConfig,
	val responsesModels: Set<String>,
	val balanceRelated: BalanceRelated,
) {
	val mainContextWindow: Int
		get() = contextWindow

	val summaryModel: String
		get() = summary.model

	val summaryContextWindow: Int
		get() = summary.contextWindow

	fun modelName(model: LLMServices.MODELS): String = when (model) {
		LLMServices.MODELS.FAST -> fastModel
		LLMServices.MODELS.THINKING -> thinkingModel
	}

	fun modelName(preset: String): String? = models[preset.lowercase(Locale.ROOT)]

	fun resolvePreset(value: String): String? {
		val normalized = value.trim().lowercase(Locale.ROOT)
		return models.keys.firstOrNull { it.equals(normalized, ignoreCase = true) }
			?: models.entries.firstOrNull { it.value.equals(value.trim(), ignoreCase = true) }?.key
	}

	fun supportsResponses(model: LLMServices.MODELS): Boolean = supportsResponses(model.alias)

	fun supportsResponses(preset: String): Boolean = preset.lowercase(Locale.ROOT) in responsesModels

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
			val token = readToken(configured, selectedName)
			val balanceUrl = configured.get("balanceUrl").asString ?: throw Exception("$selectedName balanceUrl not defined in $configPath")
			val models = readModels(configured, selectedName)
			val fastModel = models["fast"]
				?: throw Exception("fastModel not defined in $configPath")
			val thinkingModel = models["thinking"]
				?: throw Exception("thinkingModel not defined in $configPath")
			val contextWindow = readContextWindow(configured, "contextWindow", DEFAULT_CONTEXT_WINDOW)
			val summary = readSummaryConfig(configured, providers, selectedName, contextWindow)
			val compact = readCompactConfig(configured)

			return LLMProvider(
				name = selectedName,
				chatCompletionsUrl = configured.get("chatCompletionsUrl")?.asString ?: throw Exception("chatCompletionsUrl not defined in $configPath"),
				responsesUrl = configured.get("responsesUrl")?.asString ?: throw Exception("responsesUrl not defined in $configPath"),
				apiToken = token,
				models = models,
				fastModel = fastModel,
				thinkingModel = thinkingModel,
				contextWindow = contextWindow,
				summary = summary,
				compact = compact,
				responsesModels = readResponsesModels(configured),
				balanceRelated = BalanceRelated(
					balanceUrl = balanceUrl,
					balanceStruct = BalanceStructParse.fromProvider(selectedName),
				)
			)
		}

		private fun readSummaryConfig(
			configured: JsonObject,
			providers: JsonObject?,
			selectedName: String,
			mainContextWindow: Int,
		): LLMSummaryConfig {
			val summary = configured.getAsJsonObject("summary")
			val providerName = summary?.get("provider")?.asString?.trim()?.takeIf { it.isNotBlank() } ?: selectedName
			val summaryProvider = providers?.get(providerName)?.takeIf { it.isJsonObject }?.asJsonObject
				?: throw IllegalArgumentException("summary provider '$providerName' is not defined")
			val models = readModels(summaryProvider, providerName)
			val configuredModel = summary?.get("model")?.asString?.trim()?.takeIf { it.isNotBlank() }
			val model = configuredModel?.let { models[it.lowercase(Locale.ROOT)] ?: it } ?: models.getValue("fast")
			return LLMSummaryConfig(
				providerName = providerName,
				chatCompletionsUrl = summaryProvider.get("chatCompletionsUrl")?.asString
					?: throw IllegalArgumentException("chatCompletionsUrl not defined in summary provider '$providerName'"),
				apiToken = readToken(summaryProvider, providerName),
				model = model,
				contextWindow = readContextWindow(summary, "contextWindow", mainContextWindow),
			)
		}

		private fun readModels(configured: JsonObject, providerName: String): Map<String, String> {
			val models = configured.getAsJsonObject("models")
				?: throw IllegalArgumentException("models not defined in provider '$providerName'")
			return models.entrySet().associate { (alias, value) ->
				alias.trim().lowercase(Locale.ROOT) to value.asString.trim()
			}.filterValues { it.isNotBlank() }
		}

		private fun readToken(configured: JsonObject, providerName: String): String =
			configured.get("token")?.asString?.takeIf { it.isNotBlank() }
				?: configured.get("tokenFile")?.asString?.let { tokenFile ->
					runCatching { Files.readString(Path.of(tokenFile)).trim() }.getOrDefault("")
				} ?: throw Exception("token param not found in provider $providerName")

		private fun readCompactConfig(configured: JsonObject): LLMCompactConfig {
			val compact = configured.getAsJsonObject("compact")
			return LLMCompactConfig(
				enabled = readBoolean(compact, "enabled", true),
				triggerTurns = readInt(compact, "triggerTurns", DEFAULT_COMPACT_TRIGGER_TURNS).also {
					require(it in 1..30) { "compact.triggerTurns must be between 1 and 30" }
				},
				triggerPercent = readInt(compact, "triggerPercent", DEFAULT_COMPACT_TRIGGER_PERCENT).also {
					require(it in 10..95) { "compact.triggerPercent must be between 10 and 95" }
				},
				keepTurns = readInt(compact, "keepTurns", DEFAULT_COMPACT_KEEP_TURNS).also {
					require(it in 1..30) { "compact.keepTurns must be between 1 and 30" }
				},
				maxSummaryChars = readInt(compact, "maxSummaryChars", DEFAULT_COMPACT_MAX_SUMMARY_CHARS).also {
					require(it >= 500) { "compact.maxSummaryChars must be at least 500" }
				},
			)
		}

		private fun readContextWindow(configured: JsonObject?, key: String, defaultValue: Int): Int {
			val value = readInt(configured, key, defaultValue)
			if (value <= 0) {
				throw IllegalArgumentException("$key must be greater than zero")
			}
			return value
		}

		private fun readInt(configured: JsonObject?, key: String, defaultValue: Int): Int {
			val configuredValue = configured?.get(key)?.takeIf { !it.isJsonNull } ?: return defaultValue
			return configuredValue.asString.trim().toIntOrNull()
				?: throw IllegalArgumentException("$key must be an integer")
		}

		private fun readBoolean(configured: JsonObject?, key: String, defaultValue: Boolean): Boolean {
			val configuredValue = configured?.get(key)?.takeIf { !it.isJsonNull } ?: return defaultValue
			return configuredValue.asString.trim().lowercase(Locale.ROOT).toBooleanStrictOrNull()
				?: throw IllegalArgumentException("$key must be true or false")
		}

		private const val DEFAULT_CONTEXT_WINDOW = 524_288
		private const val DEFAULT_COMPACT_TRIGGER_TURNS = 12
		private const val DEFAULT_COMPACT_TRIGGER_PERCENT = 70
		private const val DEFAULT_COMPACT_KEEP_TURNS = 4
		private const val DEFAULT_COMPACT_MAX_SUMMARY_CHARS = 8_000

		private fun readConfig(path: Path): JsonObject? {
			if (!Files.isRegularFile(path)) return null
			return try {
				JsonParser.parseString(Files.readString(path)).asJsonObject
			} catch (error: Exception) {
				throw IllegalStateException("Invalid LLM provider configuration: $path", error)
			}
		}

		private fun readResponsesModels(configured: JsonObject?): Set<String> {
			val values = configured?.getAsJsonArray("responsesModels")
				?: return setOf("fast")
			return values.mapNotNull { item ->
				val normalized = item.asString.trim().lowercase(Locale.ROOT)
				normalized.takeIf { it.isNotBlank() }
			}.toSet()
		}
	}
}
