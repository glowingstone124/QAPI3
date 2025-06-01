package org.qo.services.llmServices

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.qo.redis.DatabaseType
import org.qo.redis.Redis
import org.qo.services.loginService.AuthorityNeededServicesImpl
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path

@Service
class LLMServices(private val authorityNeededServicesImpl: AuthorityNeededServicesImpl){
	val redis = Redis()
	val client = HttpClient(CIO)
	val token = Files.readString(Path.of("LLMAPITOKEN"))
	suspend fun generalPreProcess(token: String) : Boolean {
		val result = authorityNeededServicesImpl.internalAuthorityCheck(token)
		if (!result.second) {
			return false
		}
		result.first?.let {
			return (!it.frozen!!)
		}
		//This should never be reached
		return false
	}
	suspend fun prepareDocuments() {

	}
	suspend fun accessOpenAI() {
	}
	fun generateLLMStream(prompt: String, token: String): Pair<Flow<String>?, Boolean> {
		val flow = flow {
			val tokens = listOf("你好，", "我是一个", "流式输出的", "LLM模型", "。")
			for (token in tokens) {
				emit(token)
				delay(500)
			}
		}
		if (hasAlreadyRequested(token)) {
			return Pair(null, false)
		}
		return TODO("Provide the return value")
	}

	private fun hasAlreadyRequested(token: String): Boolean {
		val result = redis.exists(token, DatabaseType.QO_ASSISTANT_DATABASE.value).ignoreException()
		result?.let {
			if (!it) {
				redis.insert(token, "true", DatabaseType.QO_ASSISTANT_DATABASE.value, expires = 2)
				return false
			}
			return true
		}
		return false
	}
}