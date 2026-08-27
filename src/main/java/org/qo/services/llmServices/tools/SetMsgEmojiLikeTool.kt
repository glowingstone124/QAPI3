package org.qo.services.llmServices.tools

import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.qo.services.llmServices.LLMToolContext
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

data class EmojiReaction(
	val name: String,
	val id: String,
	val display: String,
)

@Component
class SetMsgEmojiLikeTool : Tools {
	override val id = "set_msg_emoji_like"
	override val definition = ToolSupport.functionTool(
		name = id,
		description = """
			为群里的一条消息设置表情回应（贴一贴），适合在用户发言有趣、值得庆祝或用户明确要求贴表情时使用，不要对自己发出的消息使用。
			emoji_id 参数支持表情语义名或数字 ID，当前支持的表情对照表：
			${catalogue()}
			message_id 是可选的：省略时默认回应触发本次对话的那条消息（通常是用户刚发给你的消息）；要回应群里其他消息时传入数字 ID，可通过 search_chat_history 查询历史消息并在其 source_id（形如 onebot:123456）中提取数字部分。
		""".trimIndent(),
		properties = linkedMapOf(
			"message_id" to ToolSupport.property(
				type = "number",
				description = "可选，要回应的目标消息 ID（数字）。省略时回应触发本次对话的消息。"
			),
			"emoji_id" to ToolSupport.property(
				type = "string",
				description = "表情语义名或数字 ID，见工具描述中的对照表，例如 monkey_head 或 128053。"
			),
		),
		required = listOf("emoji_id"),
	)

	override suspend fun execute(args: JsonObject, context: LLMToolContext): String {
		val messageId = args.get("message_id")
			?.takeIf { !it.isJsonNull && it.isJsonPrimitive }
			?.asString?.toLongOrNull()
			?: context.currentMessageId
		if (messageId == null) {
			return ToolSupport.errorResult("invalid_argument", "缺少 message_id，且无法确定触发本次对话的消息 ID。")
		}
		val rawEmojiId = args.get("emoji_id")
			?.takeIf { !it.isJsonNull && it.isJsonPrimitive }
			?.asString?.trim().orEmpty()
		val emoji = resolveEmoji(rawEmojiId)
			?: return ToolSupport.errorResult(
				"unsupported_emoji",
				"不支持的表情：$rawEmojiId。当前仅支持：${catalogue()}。"
			)
		val botEndpoint = System.getenv("QBOT_ENDPOINT")?.trim().orEmpty()
		val botToken = System.getenv("QBOT_TOKEN")?.trim().orEmpty()
		if (botEndpoint.isBlank() || botToken.isBlank()) {
			return ToolSupport.errorResult("not_configured", "表情回应功能未配置（QBOT_ENDPOINT / QBOT_TOKEN）。")
		}
		val payload = ToolSupport.gson.toJson(JsonObject().apply {
			addProperty("message_id", messageId)
			addProperty("emoji_id", emoji.id)
		})
		val response = withContext(Dispatchers.IO) {
			httpClient.send(
				HttpRequest.newBuilder()
					.uri(URI.create("${botEndpoint.removeSuffix("/")}/action/set_msg_emoji_like"))
					.timeout(Duration.ofSeconds(10))
					.header("Content-Type", "application/json; charset=UTF-8")
					.header("Authorization", botToken)
					.POST(HttpRequest.BodyPublishers.ofString(payload, Charsets.UTF_8))
					.build(),
				HttpResponse.BodyHandlers.ofString()
			)
		}
		if (response.statusCode() !in 200..299) {
			return ToolSupport.errorResult(
				"bot_request_failed",
				"Bot 返回 HTTP ${response.statusCode()}：${response.body().take(200)}"
			)
		}
		return ToolSupport.gson.toJson(JsonObject().apply {
			addProperty("tool", id)
			addProperty("message_id", messageId)
			addProperty("emoji_id", emoji.id)
			addProperty("result", "ok")
		})
	}

	private fun resolveEmoji(raw: String): EmojiReaction? {
		val normalized = raw.trim().lowercase()
		if (normalized.isEmpty()) return null
		return supportedEmojis.firstOrNull { it.name == normalized || it.id == normalized }
	}

	companion object {
		private val supportedEmojis = listOf(
			EmojiReaction("monkey_head", "128053", "🐵"),
		)

		private fun catalogue(): String =
			supportedEmojis.joinToString(separator = "；") { "${it.name}（${it.id}，${it.display}）" }

		private val httpClient: HttpClient = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(5))
			.build()
	}
}
