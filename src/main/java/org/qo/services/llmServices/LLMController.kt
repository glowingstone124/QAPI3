package org.qo.services.llmServices

import kotlinx.coroutines.reactor.asFlux
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.*
import reactor.core.publisher.Flux

@RestController
@RequestMapping("/qo/asking/")
class LLMController(private val llmServices: LLMServices) {

	@PostMapping("/ask", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
	fun handleResponse(
		@RequestHeader("Authorization") requestToken: String,
		@RequestBody body: String
	): Flux<String> {
		return kotlinx.coroutines.reactor.mono {
			val result = llmServices.generateLLMStream(body, requestToken)
			if (!result.second) {
				Flux.just("Unpermitted access")
			} else {
				result.first!!.asFlux()
			}
		}.flatMapMany { it }
	}
}

