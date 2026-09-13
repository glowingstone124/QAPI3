package org.qo.services.llmServices

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.net.URI

enum class LLMProtocol(val wireValue: String, val endpointKey: String) {
	CHAT_COMPLETIONS("chat-completions", "chatCompletionsUrl"),
	RESPONSES("responses", "responsesUrl"),
	ANTHROPIC("anthropic", "anthropicUrl");

	companion object {
		fun parse(value: String): LLMProtocol = when (value.trim().lowercase(Locale.ROOT)) {
			"chat-completions", "chat_completions" -> CHAT_COMPLETIONS
			"responses" -> RESPONSES
			"anthropic", "antrophic" -> ANTHROPIC
			else -> throw IllegalArgumentException("Unknown LLM protocol '$value'")
		}
	}
}

data class LLMModelConfig(
	val model: String,
	val protocol: LLMProtocol,
	val thinkingMode: String = "enabled",
	val pricing: LLMModelPricing? = null,
)

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
	val endpointUrl: String,
	val apiToken: String,
	val model: String,
	val contextWindow: Int,
	val protocol: LLMProtocol,
	val thinkingMode: String,
	val pricing: LLMModelPricing? = null,
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
	val anthropicUrl: String,
	val apiToken: String,
	val models: Map<String, LLMModelConfig>,
	val fastModel: String,
	val thinkingModel: String,
	val contextWindow: Int,
	val summary: LLMSummaryConfig,
	val compact: LLMCompactConfig,
	val balanceRelated: BalanceRelated,
	val routes: Map<String, LLMProvider> = emptyMap(),
) {
	fun forMode(mode: String): LLMProvider = routes[mode.lowercase(Locale.ROOT)] ?: this

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

	fun modelName(preset: String): String? = models[preset.lowercase(Locale.ROOT)]?.model

	fun modelConfig(preset: String): LLMModelConfig = models.getValue(preset.lowercase(Locale.ROOT))

	fun protocol(preset: String): LLMProtocol = modelConfig(preset).protocol

	fun endpoint(protocol: LLMProtocol): String = when (protocol) {
		LLMProtocol.CHAT_COMPLETIONS -> chatCompletionsUrl
		LLMProtocol.RESPONSES -> responsesUrl
		LLMProtocol.ANTHROPIC -> anthropicUrl
	}.also { require(!isUnavailable(it)) { "${protocol.wireValue} endpoint is unavailable for provider '$name'" } }

	fun resolvePreset(value: String): String? {
		val normalized = value.trim().lowercase(Locale.ROOT)
		return models.keys.firstOrNull { it.equals(normalized, ignoreCase = true) }
			?: models.entries.firstOrNull { it.value.model.equals(value.trim(), ignoreCase = true) }?.key
	}

	fun supportsResponses(model: LLMServices.MODELS): Boolean = supportsResponses(model.alias)

	fun supportsResponses(preset: String): Boolean = protocol(preset) == LLMProtocol.RESPONSES

	companion object {
		fun fromEnvironment(): LLMProvider {
			val configPath = Path.of(System.getenv("LLM_PROVIDERS_FILE") ?: "data/llm/providers.json")
			val explicitlySelected = System.getenv("LLM_PROVIDER")?.trim()?.takeIf { it.isNotBlank() }
			return fromConfig(configPath, explicitlySelected)
		}

		fun fromConfig(configPath: Path, explicitlySelected: String? = null): LLMProvider = loadConfig(configPath, explicitlySelected, true)

		private fun loadConfig(configPath: Path, explicitlySelected: String?, loadRoutes: Boolean, root: JsonObject? = readConfig(configPath)): LLMProvider {
			val providers = root?.getAsJsonObject("providers")
			// Validate every provider, including those that are not currently selected.
			providers?.entrySet()?.forEach { (name, value) ->
				require(value.isJsonObject) { "provider '$name' must be an object" }
				readModels(value.asJsonObject, name)
			}
			val selectedName = explicitlySelected
				?: root?.get("defaultProvider")?.asString?.takeIf { it.isNotBlank() }
				?: providers?.keySet()?.firstOrNull() ?: throw Exception("provider not found")
			val configured = providers?.get(selectedName)?.takeIf { it.isJsonObject }?.asJsonObject
			if (configured == null) {
				error("LLM provider '$selectedName' is not defined in $configPath")
			}
			val token = readToken(configured, selectedName)
			val balanceUrl = configured.get("balanceUrl")?.takeIf { !it.isJsonNull }?.asString
				?.takeUnless(::isUnavailable)
			val models = readModels(configured, selectedName)
			val fastModel = models["fast"]?.model
				?: throw Exception("fastModel not defined in $configPath")
			val thinkingModel = models["thinking"]?.model
				?: throw Exception("thinkingModel not defined in $configPath")
			val contextWindow = readContextWindow(configured, "contextWindow", DEFAULT_CONTEXT_WINDOW)
			val summary = readSummaryConfig(configured, providers, selectedName, contextWindow)
			val compact = readCompactConfig(configured)

			return LLMProvider(
				name = selectedName,
				routes = if (loadRoutes) root?.getAsJsonObject("routes")?.entrySet()?.associate { (mode, provider) ->
					require(mode in setOf("fast", "thinking")) { "Unknown capability route" }
					mode to loadConfig(configPath, provider.asString, false, root)
				} ?: emptyMap() else emptyMap(),
				chatCompletionsUrl = readEndpoint(configured, selectedName, LLMProtocol.CHAT_COMPLETIONS),
				responsesUrl = readEndpoint(configured, selectedName, LLMProtocol.RESPONSES),
				anthropicUrl = readEndpoint(configured, selectedName, LLMProtocol.ANTHROPIC),
				apiToken = token,
				models = models,
				fastModel = fastModel,
				thinkingModel = thinkingModel,
				contextWindow = contextWindow,
				summary = summary,
				compact = compact,
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
			val model = configuredModel?.let { requested ->
				models[requested.lowercase(Locale.ROOT)]
					?: models.values.firstOrNull { it.model == requested }
					?: throw IllegalArgumentException("summary model '$requested' must be declared in provider '$providerName' models")
			} ?: models.getValue("fast")
			return LLMSummaryConfig(
				providerName = providerName,
				endpointUrl = readEndpoint(summaryProvider, providerName, model.protocol),
				apiToken = readToken(summaryProvider, providerName),
				model = model.model,
				contextWindow = readContextWindow(summary, "contextWindow", mainContextWindow),
				protocol = model.protocol,
				thinkingMode = model.thinkingMode,
				pricing = model.pricing,
			)
		}

		private fun readModels(configured: JsonObject, providerName: String): Map<String, LLMModelConfig> {
			val endpoints = LLMProtocol.entries.associateWith { readEndpoint(configured, providerName, it) }
			require(!configured.has("responsesModels")) { "provider '$providerName': replace responsesModels with models.<preset>.protocol" }
			val models = configured.getAsJsonObject("models")
				?: throw IllegalArgumentException("models not defined in provider '$providerName'")
			return models.entrySet().associate { (alias, value) ->
				require(value.isJsonObject) { "provider '$providerName' model '$alias' must declare model and protocol" }
				val obj = value.asJsonObject
				val model = obj.get("model")?.asString?.trim().orEmpty()
				require(model.isNotBlank()) { "provider '$providerName' model '$alias' has no model name" }
				val protocol = LLMProtocol.parse(obj.get("protocol")?.asString
					?: throw IllegalArgumentException("provider '$providerName' model '$alias' has no protocol"))
				require(!isUnavailable(endpoints.getValue(protocol))) {
					"provider '$providerName' model '$alias' selects unavailable ${protocol.endpointKey}"
				}
				val thinkingMode = obj.get("thinkingMode")?.asString ?: "enabled"
				require(thinkingMode in setOf("enabled", "adaptive", "disabled")) { "Invalid thinkingMode for '$providerName/$alias'" }
				val normalized = alias.trim().lowercase(Locale.ROOT)
				require(normalized.isNotBlank()) { "Empty model preset in provider '$providerName'" }
				normalized to LLMModelConfig(model, protocol, thinkingMode, obj.getAsJsonObject("pricing")?.let(LLMModelPricing::fromJson))
			}.also {
				require(it.containsKey("fast") && it.containsKey("thinking")) { "provider '$providerName' must declare fast and thinking models" }
				require(it.size == models.size()) { "Duplicate normalized model presets in provider '$providerName'" }
			}
		}

		fun isUnavailable(value: String): Boolean = value.trim().lowercase(Locale.ROOT) in setOf("unavaliable", "unavailable")

		private fun readEndpoint(configured: JsonObject, providerName: String, protocol: LLMProtocol): String {
			val value = (configured.get(protocol.endpointKey)
				?: if (protocol == LLMProtocol.ANTHROPIC) configured.get("antrophicUrl") else null)
				?.asString?.trim()
				?: throw IllegalArgumentException("${protocol.endpointKey} not defined in provider '$providerName'")
			if (!isUnavailable(value)) {
				val uri = runCatching { URI(value) }.getOrNull()
				require(uri != null && uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()) {
					"Invalid ${protocol.endpointKey} in provider '$providerName'; use an HTTP URL or unavaliable"
				}
			}
			return value
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

	}
}
