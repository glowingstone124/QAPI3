package org.qo.services.llmServices

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.file.Path

@Configuration
class LLMConfiguration {
	@Bean
	fun llmProvider(): ReloadableLLMProvider = ReloadableLLMProvider(
		Path.of(System.getenv("LLM_PROVIDERS_FILE") ?: "data/llm/providers.json"),
	)
}
