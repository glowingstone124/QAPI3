package org.qo.services.llmServices

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.time.Instant

/**
 * Append-only diagnostics when the LLM returns no content / empty response.
 * Records the full request details and upstream response to llm_empty_response.log.
 */
internal object LLMEmptyResponseLog {
	private val lock = Any()
	private val gson = GsonBuilder()
		.disableHtmlEscaping()
		.serializeNulls()
		.setPrettyPrinting()
		.create()

	fun defaultPath(): Path {
		System.getenv("LLM_EMPTY_RESPONSE_LOG_PATH")?.trim()?.takeIf { it.isNotBlank() }?.let {
			return Path.of(it)
		}
		if (Files.isDirectory(Path.of("api"))) {
			return Path.of("api", "llm_empty_response.log")
		}
		if (Files.isDirectory(Path.of("QAPI3"))) {
			return Path.of("QAPI3", "llm_empty_response.log")
		}
		return Path.of("llm_empty_response.log")
	}

	fun record(
		stage: String,
		request: LLMServices.NormalizedRequest? = null,
		requester: LLMServices.LLMRequester? = null,
		provider: String? = null,
		model: String? = null,
		statusCode: Int? = null,
		rawResponse: String? = null,
		reason: String = "empty_content",
		accessRecordId: Long? = null,
		clientRequestId: String? = null,
		path: Path = defaultPath(),
	) {
		runCatching {
			val time = Instant.now().toString()
			val metadata = JsonObject().apply {
				addProperty("time", time)
				addProperty("stage", stage)
				addProperty("reason", reason)
				addProperty("provider", provider)
				addProperty("model", model ?: request?.model ?: request?.preset)
				addProperty("preset", request?.preset)
				statusCode?.let { addProperty("status_code", it) }
				accessRecordId?.takeIf { it > 0 }?.let { addProperty("access_record_id", it) }
				clientRequestId?.takeIf { it.isNotBlank() }?.let { addProperty("client_request_id", it) }
				requester?.let {
					addProperty("source", it.source)
					addProperty("uid", it.uid)
					addProperty("name", it.name)
					it.groupId?.let { gid -> addProperty("group_id", gid) }
					it.groupName?.let { gname -> addProperty("group_name", gname) }
					it.messageId?.let { mid -> addProperty("message_id", mid) }
					it.conversationId?.let { cid -> addProperty("conversation_id", cid) }
				}
				request?.currentUserText?.takeIf { it.isNotBlank() }?.let {
					addProperty("current_user_text", it)
				}
			}

			val entry = buildString {
				appendLine("===== LLM EMPTY RESPONSE [$time] =====")
				appendLine(gson.toJson(LLMLogRedaction.redactJson(metadata)))
				if (request != null && request.body.isNotBlank()) {
					appendLine("--- request_body ---")
					val formattedReq = LLMLogRedaction.formatBody(request.body)
					formattedReq.lineSequence().forEach { append("  ").appendLine(it) }
				}
				if (!rawResponse.isNullOrBlank()) {
					appendLine("--- upstream_response ---")
					val formattedResp = LLMLogRedaction.formatBody(rawResponse)
					formattedResp.lineSequence().forEach { append("  ").appendLine(it) }
				}
				appendLine("===== END LLM EMPTY RESPONSE =====")
				appendLine()
			}

			System.err.println("[LLM] empty response recorded (stage=$stage reason=$reason provider=$provider) log=$path")
			synchronized(lock) {
				path.parent?.let { Files.createDirectories(it) }
				Files.writeString(path, entry, StandardCharsets.UTF_8, CREATE, APPEND)
			}
		}.onFailure { writeError ->
			System.err.println("[LLM] failed to write llm_empty_response.log: ${writeError.javaClass.name}")
		}
	}
}
