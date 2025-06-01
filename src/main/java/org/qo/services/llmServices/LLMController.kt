import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.reactor.asFlux
import org.qo.services.llmServices.LLMServices
import org.springframework.http.MediaType
import org.springframework.messaging.handler.annotation.Header
import org.springframework.web.bind.annotation.*
import reactor.core.publisher.Flux

@RestController
@RequestMapping("/qo/asking")
class LLMController(private val llmServices: LLMServices) {

	@PostMapping("/ask", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
	fun handleResponse(@Header("Authorization") requestToken: String, @RequestBody body: String): Flux<String> {
		val result = llmServices.generateLLMStream(body, requestToken)
		return if (!result.second) {
			Flux.just("Unpermitted access")
		} else {
			result.first!!.asFlux()
		}
	}


}
