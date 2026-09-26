package org.qo.services.llmServices

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.time.Instant

/** Records exception locations without persisting request bodies, responses, or API tokens. */
internal object LLMErrorLog {
	private val path = Path.of("data", "llm", "llm-error.log")
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
			synchronized(lock) {
				Files.createDirectories(path.parent)
				Files.write(path, entry.toByteArray(StandardCharsets.UTF_8), CREATE, APPEND)
			}
		}.onFailure { writeError ->
			System.err.println("[LLM] failed to write llm-error.log: ${writeError.javaClass.name}")
		}
	}
}
