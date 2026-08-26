package org.qo.services.llmServices

/** Parses the explicit profile-persistence command. It does not infer intent. */
internal object LLMRememberProtocol {
	private const val COMMAND = "/remember"

	fun payload(message: String?): String? {
		val input = message?.trim() ?: return null
		if (!input.startsWith(COMMAND)) return null
		if (input.length == COMMAND.length || !input[COMMAND.length].isWhitespace()) return null
		return input.substring(COMMAND.length).trim().takeIf { it.isNotEmpty() }
	}
}
