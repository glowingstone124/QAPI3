package org.qo.services.llmServices.tools

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.qo.orm.UserORM
import org.qo.services.llmServices.CreditDeductionResult
import org.qo.services.llmServices.LLMDailyQuotaService
import org.qo.services.llmServices.LLMImageGenerationConfig
import org.qo.services.llmServices.LLMToolContext
import org.qo.services.llmServices.ReloadableLLMProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

@Component
class GenerateImageTool @Autowired constructor(
	private val providers: ReloadableLLMProvider?,
	private val quotaService: LLMDailyQuotaService?,
) : Tools {
	internal var httpClient: HttpClient = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(15))
		.build()

	internal var userORM: UserORM = UserORM()

	private var staticConfig: LLMImageGenerationConfig? = null

	// Overload for testing with a standalone config
	constructor(
		config: LLMImageGenerationConfig?,
		quotaService: LLMDailyQuotaService? = null,
		httpClient: HttpClient = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(15))
			.build(),
	) : this(
		providers = null,
		quotaService = quotaService,
	) {
		this.staticConfig = config
		this.httpClient = httpClient
	}

	// Overload for testing with HttpClient only
	constructor(httpClient: HttpClient) : this(
		config = null,
		quotaService = null,
		httpClient = httpClient,
	)

	// Overload for testing parameterless
	constructor() : this(
		config = null,
		quotaService = null,
	)

	fun resolveConfig(): LLMImageGenerationConfig? =
		staticConfig ?: providers?.current()?.imageGeneration

	override val id = "generate_image"

	override val definition = ToolSupport.functionTool(
		name = id,
		description = """
			使用 OpenAI 最新 GPT-Image-2.5 模型生成图片。
			单次调用图片消耗 25 点额度（优先扣除每周免费额度，不足部分扣除 Paid Credits）。当前仅对 QQ 用户开放。
			当用户要求画图、作画、绘制插图、设计视觉图片或需要图像展示时自主调用该工具。
			prompt 参数应当详细描述画面内容，包括画面主体、风格类型（如写实、插画、二次元、赛博朋克等）、色彩基调、光影效果、构图视角等。如用户输入的是中文或简短需求，请将其扩展为生动详细的高质量提示词以达到最佳生成效果。
		""".trimIndent(),
		properties = linkedMapOf(
			"prompt" to ToolSupport.property(
				type = "string",
				description = "生成图片的详细提示词。请详细描述主体、风格、光影、构图与细节。",
			),
			"size" to ToolSupport.property(
				type = "string",
				description = "可选，图片分辨率尺寸，如 1024x1024, 1536x1024, 1024x1536 等，默认 1024x1024。",
			),
			"quality" to ToolSupport.property(
				type = "string",
				description = "可选，生成质量，可选 auto, low, medium, high, xhigh, max，默认 auto。",
			),
		),
		required = listOf("prompt"),
	)

	override suspend fun execute(args: JsonObject, context: LLMToolContext): String {
		if (context.source != "qq") {
			return ToolSupport.errorResult("qq_only_tool", "图片生成工具当前仅对 QQ 用户开放。")
		}

		val qqUid = context.uid?.toLongOrNull()
		if (qqUid == null || qqUid <= 0L) {
			return ToolSupport.errorResult("invalid_user", "未识别到有效的 QQ 用户身份，无法扣除图片生成点数。")
		}

		val prompt = args.stringArgument("prompt")
		if (prompt.isNullOrBlank()) {
			return ToolSupport.errorResult("invalid_argument", "缺少必填参数 prompt（图片提示词）。")
		}

		val config = resolveConfig()
		if (config == null || !config.enabled || config.apiToken.isBlank()) {
			return ToolSupport.errorResult(
				"not_configured",
				"图片生成功能未在 providers.json 中配置有效的 OpenAI API Key 或未启用。"
			)
		}

		val cost = config.creditsCost
		val referenceId = "img:${context.groupId ?: "p"}:$qqUid:${UUID.randomUUID().toString().take(12)}"
		val deduction: CreditDeductionResult? = if (quotaService != null && cost > 0) {
			val hasAccount = runCatching { userORM.readAsync(qqUid) }.getOrNull() != null
			val res = quotaService.deductQuota(qqUid, cost, referenceId, hasAccount = hasAccount)
			if (!res.success) {
				return ToolSupport.errorResult(
					"insufficient_credits",
					res.error ?: "账户剩余额度不足（本次生成图片需要：${cost} 点）。请充值点数或等待下周免费额度重置。"
				)
			}
			res
		} else {
			null
		}

		suspend fun refundOnError() {
			if (cost > 0 && deduction != null) {
				runCatching {
					quotaService?.refundQuota(
						qqUid = qqUid,
						weeklyAmount = deduction.weeklyDeducted,
						paidAmount = deduction.paidDeducted,
						referenceId = referenceId,
					)
				}
			}
		}

		val size = args.stringArgument("size")?.takeIf { it.isNotBlank() } ?: config.size
		val quality = args.stringArgument("quality")?.takeIf { it.isNotBlank() } ?: config.quality
		val apiUrl = config.endpointUrl
		val model = config.model

		val requestPayload = JsonObject().apply {
			addProperty("model", model)
			addProperty("prompt", prompt)
			addProperty("size", size)
			addProperty("quality", quality)
			addProperty("n", 1)
		}

		val requestJson = ToolSupport.gson.toJson(requestPayload)

		val imageResponse = try {
			withContext(Dispatchers.IO) {
				httpClient.send(
					HttpRequest.newBuilder()
						.uri(URI.create(apiUrl))
						.timeout(Duration.ofSeconds(config.timeoutSeconds))
						.header("Content-Type", "application/json; charset=UTF-8")
						.header("Authorization", "Bearer ${config.apiToken}")
						.POST(HttpRequest.BodyPublishers.ofString(requestJson, Charsets.UTF_8))
						.build(),
					HttpResponse.BodyHandlers.ofString()
				)
			}
		} catch (ex: Exception) {
			refundOnError()
			return ToolSupport.errorResult("upstream_error", "调用 OpenAI Image Generations API 失败：${ex.message}")
		}

		if (imageResponse.statusCode() !in 200..299) {
			refundOnError()
			val errBody = imageResponse.body()
			val parsedMsg = runCatching {
				JsonParser.parseString(errBody).asJsonObject
					.getAsJsonObject("error")?.get("message")?.asString
			}.getOrNull() ?: errBody.take(300)
			return ToolSupport.errorResult(
				"generation_failed",
				"OpenAI 图片生成失败 (HTTP ${imageResponse.statusCode()}): $parsedMsg"
			)
		}

		val responseJson = try {
			JsonParser.parseString(imageResponse.body()).asJsonObject
		} catch (ex: Exception) {
			refundOnError()
			return ToolSupport.errorResult("parse_error", "解析 OpenAI 图片生成响应失败：${ex.message}")
		}

		val dataArray = responseJson.getAsJsonArray("data")
		if (dataArray == null || dataArray.size() == 0) {
			refundOnError()
			return ToolSupport.errorResult("empty_response", "OpenAI 未返回图片数据。")
		}

		val firstItem = dataArray.get(0).asJsonObject
		val b64Json = firstItem.get("b64_json")?.takeIf { !it.isJsonNull }?.asString?.trim()
		val imageUrl = firstItem.get("url")?.takeIf { !it.isJsonNull }?.asString?.trim()
		val revisedPrompt = firstItem.get("revised_prompt")?.takeIf { !it.isJsonNull }?.asString?.trim()

		if (b64Json.isNullOrBlank() && imageUrl.isNullOrBlank()) {
			refundOnError()
			return ToolSupport.errorResult("empty_image_data", "响应中未包含有效图片数据（缺少 b64_json 或 url）。")
		}

		val imagePayload = when {
			!b64Json.isNullOrBlank() -> "base64://$b64Json"
			else -> imageUrl!!
		}

		val groupId = context.groupId
		if (groupId != null) {
			val botEndpoint = config.qbotEndpoint ?: System.getenv("QBOT_ENDPOINT")?.trim().orEmpty()
			val botToken = config.qbotToken ?: System.getenv("QBOT_TOKEN")?.trim().orEmpty()
			if (botEndpoint.isNotBlank() && botToken.isNotBlank()) {
				val sendResult = sendImageToBot(botEndpoint, botToken, groupId, imagePayload, prompt)
				if (!sendResult.success) {
					refundOnError()
					return ToolSupport.errorResult(
						"bot_send_failed",
						"图片已生成，但推送至 QQ 群失败：${sendResult.error}"
					)
				}
				return ToolSupport.result(id) {
					addProperty("status", "success")
					addProperty("message", "图片已成功使用 $model 生成，并已直接发送至当前 QQ 群聊天中。")
					addProperty("prompt", prompt)
					revisedPrompt?.let { addProperty("revised_prompt", it) }
					addProperty("credits_consumed", cost)
					if (deduction != null) {
						addProperty("weekly_units_deducted", deduction.weeklyDeducted)
						addProperty("paid_credits_deducted", deduction.paidDeducted)
						addProperty("remaining_credits", deduction.remainingCredits)
					}
				}
			}
		}

		return ToolSupport.result(id) {
			addProperty("status", "success")
			addProperty("message", "图片已成功使用 $model 生成。")
			addProperty("prompt", prompt)
			revisedPrompt?.let { addProperty("revised_prompt", it) }
			addProperty("credits_consumed", cost)
			if (deduction != null) {
				addProperty("weekly_units_deducted", deduction.weeklyDeducted)
				addProperty("paid_credits_deducted", deduction.paidDeducted)
				addProperty("remaining_credits", deduction.remainingCredits)
			}
			if (!imageUrl.isNullOrBlank()) {
				addProperty("image_url", imageUrl)
			}
			if (!b64Json.isNullOrBlank()) {
				addProperty("b64_json_preview", b64Json.take(64) + "...")
			}
		}
	}

	private data class BotSendResult(val success: Boolean, val error: String? = null)

	private suspend fun sendImageToBot(
		botEndpoint: String,
		botToken: String,
		groupId: Long,
		imagePayload: String,
		prompt: String,
	): BotSendResult {
		val payload = ToolSupport.gson.toJson(JsonObject().apply {
			addProperty("group_id", groupId)
			addProperty("image", imagePayload)
			addProperty("prompt", prompt)
		})
		return withContext(Dispatchers.IO) {
			try {
				val response = httpClient.send(
					HttpRequest.newBuilder()
						.uri(URI.create("${botEndpoint.removeSuffix("/")}/action/send_image"))
						.timeout(Duration.ofSeconds(15))
						.header("Content-Type", "application/json; charset=UTF-8")
						.header("Authorization", botToken)
						.POST(HttpRequest.BodyPublishers.ofString(payload, Charsets.UTF_8))
						.build(),
					HttpResponse.BodyHandlers.ofString()
				)
				if (response.statusCode() in 200..299) {
					BotSendResult(true)
				} else {
					BotSendResult(false, "HTTP ${response.statusCode()}: ${response.body().take(200)}")
				}
			} catch (ex: Exception) {
				BotSendResult(false, ex.message ?: "网络异常")
			}
		}
	}
}
