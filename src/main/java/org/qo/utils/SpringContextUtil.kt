package org.qo.utils

import jakarta.annotation.PostConstruct
import org.springframework.context.ApplicationContext
import org.springframework.stereotype.Component

@Component
class SpringContextUtil(private val context: ApplicationContext) {
	companion object {
		lateinit var ctx: ApplicationContext
			private set
	}

	@PostConstruct
	fun init() {
		ctx = context
	}
}
