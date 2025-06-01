package org.qo.services.llmServices

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/qo/asking/")
class LLMController(private val llmServices: LLMServices) {
	@PostMapping("/ask", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
	fun handleResponse(@RequestHeader("Authorization") requestToken: String, @RequestBody body: String): Flow<String>? {
		val result = llmServices.generateLLMStream(body, requestToken)
		return if (!result.second) {
			null
		} else {
			result.first
		}
	}
}