package org.qo.services.llmServices

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.time.Instant

/** Append-only exception diagnostics and redacted upstream failure responses. */
internal object LLMErrorLog {
	private val defaultPath = Path.of("data", "llm", "llm-error.log")
	private val lock = Any()

	fun record(
		stage: String,
		error: Throwable,
		provider: String? = null,
		requester: LLMServices.LLMRequester? = null,
		accessRecordId: Long? = null,
	) {
		runCatching {
			val entry = buildString {
				append(Instant.now()).append(" stage=").append(stage)
				provider?.let { append(" provider=").append(it) }
				requester?.let {
					append(" uid=").append(it.uid)
					it.groupId?.let { groupId -> append(" group_id=").append(groupId) }
				}
				accessRecordId?.takeIf { it > 0 }?.let { append(" access_record_id=").append(it) }
				appendLine()
				val seen = mutableSetOf<Throwable>()
				var current: Throwable? = error
				while (current != null && seen.add(current)) {
					append(current.javaClass.name)
					if (current is java.nio.charset.MalformedInputException) {
						append(" input_length=").append(current.inputLength)
					}
					appendLine()
					current.stackTrace.take(80).forEach { frame ->
						append("  at ").append(frame).appendLine()
					}
					current = current.cause
					if (current != null) appendLine("Caused by:")
				}
				appendLine()
			}
			append(entry, defaultPath)
		}.onFailure { writeError ->
			System.err.println("[LLM] failed to write llm-error.log: ${writeError.javaClass.name}")
		}
	}

	fun recordResponse(
		stage: String,
		responseBody: String,
		provider: String? = null,
		requester: LLMServices.LLMRequester? = null,
		model: String? = null,
		round: Int? = null,
		httpStatus: Int? = null,
		accessRecordId: Long? = null,
		path: Path = defaultPath,
	) {
		runCatching {
			val entry = buildString {
				appendLine("===== LLM UPSTREAM FAILURE [${Instant.now()}] =====")
				append("stage=").append(stage)
				provider?.let { append(" provider=").append(it) }
				model?.let { append(" model=").append(it) }
				round?.let { append(" round=").append(it) }
				httpStatus?.let { append(" http_status=").append(it) }
				requester?.let {
					append(" source=").append(it.source).append(" uid=").append(it.uid)
					it.groupId?.let { groupId -> append(" group_id=").append(groupId) }
					it.messageId?.let { messageId -> append(" message_id=").append(messageId) }
				}
				accessRecordId?.takeIf { it > 0 }?.let { append(" access_record_id=").append(it) }
				appendLine()
				appendLine("--- upstream_response ---")
				LLMLogRedaction.formatBody(responseBody).lineSequence().forEach { append("  ").appendLine(it) }
				appendLine("===== END LLM UPSTREAM FAILURE =====")
				appendLine()
			}
			append(entry, path)
		}.onFailure { writeError ->
			System.err.println("[LLM] failed to write llm-error.log: ${writeError.javaClass.name}")
		}
	}

	private fun append(entry: String, path: Path) {
		synchronized(lock) {
			path.parent?.let { Files.createDirectories(it) }
			Files.writeString(path, entry, StandardCharsets.UTF_8, CREATE, APPEND)
		}
	}
}
