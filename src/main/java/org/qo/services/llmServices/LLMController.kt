package org.qo.services.llmServices

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.qo.utils.AuthTokens
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.*
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

@RestController
@RequestMapping("/qo/asking/")
class LLMController(private val llmServices: LLMServices) {
	private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

	@PostMapping("/ask", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
	fun handleResponse(
		@RequestHeader("token", required = false) token: String?,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@RequestBody body: String
	): SseEmitter {
		val emitter = SseEmitter(0L)
		val requestToken = AuthTokens.resolve(token, authorization)
		if (requestToken.isNullOrBlank()) {
			emitter.send(SseEmitter.event().data("缺少或无效的令牌").name("error"))
			emitter.complete()
			return emitter
		}

		scope.launch {
			val (flow, ok) = llmServices.generateLLMStream(body, requestToken)
			if (!ok || flow == null) {
				emitter.send(SseEmitter.event().data("权限验证失败或重复请求").name("error"))
				emitter.complete()
				return@launch
			}

			try {
				flow.collect { chunk ->
					emitter.send(SseEmitter.event().data(chunk))
				}
				emitter.send(SseEmitter.event().data("[DONE]").name("end"))
				emitter.complete()
			} catch (e: Exception) {
				emitter.send(SseEmitter.event().data("异常: ${e.message}").name("error"))
				emitter.completeWithError(e)
			}
		}

		return emitter
	}
}
