package org.qo.services.llmServices

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class LLMConfiguration {
	@Bean
	fun llmProvider(): LLMProvider = LLMProvider.fromEnvironment()
}
