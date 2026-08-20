package org.qo.services.llmServices.tools

import com.google.gson.JsonObject
import org.qo.services.llmServices.LLMToolContext

interface Tools {

	val id: String

	val definition: JsonObject

	suspend fun execute(args: JsonObject, context: LLMToolContext): String
}