package org.qo.services.llmServices

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.codec.ServerSentEvent

internal object LLMResponseHelper {
	fun streamResponse(result: LLMStreamResult): ResponseEntity<Flow<ServerSentEvent<String>>> {
		val builder = ResponseEntity.status(result.status).contentType(MediaType.TEXT_EVENT_STREAM)
		builder.header(HttpHeaders.CACHE_CONTROL, "no-cache, no-transform")
		builder.header("X-Accel-Buffering", "no")
		applyQuotaHeaders(builder, result.quota, result.status)
		return builder.body(streamEvents(result.chunks))
	}

	fun streamEvents(chunks: Flow<String>): Flow<ServerSentEvent<String>> = flow {
		try {
			chunks.collect { chunk ->
				emit(sse(chunk))
			}
			emit(sse("[DONE]"))
		} catch (e: Exception) {
			if (e is kotlinx.coroutines.CancellationException) throw e
			emit(sse(e.message ?: "LLM stream failed", "error"))
		}
	}

	fun sse(data: String, event: String? = null): ServerSentEvent<String> {
		val builder = ServerSentEvent.builder(data)
		if (event != null) builder.event(event)
		return builder.build()
	}

	fun jsonResponse(body: String, status: HttpStatus, quota: LLMQuotaView? = null): ResponseEntity<String> {
		val builder = ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
		applyQuotaHeaders(builder, quota, if (body.contains("weekly_quota_exceeded")) status.value() else 200)
		return builder.body(body)
	}

	fun applyQuotaHeaders(builder: ResponseEntity.BodyBuilder, quota: LLMQuotaView?, status: Int) {
		if (quota == null) return
		builder.header("X-Quota-Period", "weekly")
		builder.header("X-Paid-Credits", quota.paidCredits.toString())
		builder.header("X-RateLimit-Limit", quota.limit.toString())
		builder.header("X-RateLimit-Remaining", quota.remaining.toString())
		builder.header("X-RateLimit-Reset", quota.resetAtEpochSeconds.toString())
		if (status == HttpStatus.TOO_MANY_REQUESTS.value()) {
			val retryAfter = (quota.resetAtEpochSeconds - System.currentTimeMillis() / 1000L).coerceAtLeast(1L)
			builder.header(HttpHeaders.RETRY_AFTER, retryAfter.toString())
		}
	}
}
