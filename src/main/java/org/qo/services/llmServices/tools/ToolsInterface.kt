package org.qo.services.llmServices.tools

import kotlinx.serialization.json.JsonObject
import org.qo.services.llmServices.LLMToolContext

interface Tools {

	val id: String

	fun execute(args: JsonObject, context: LLMToolContext):String
}