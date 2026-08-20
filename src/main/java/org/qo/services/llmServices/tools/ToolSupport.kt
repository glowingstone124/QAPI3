package org.qo.services.llmServices.tools

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject

internal object ToolSupport {
	val gson: Gson = GsonBuilder().disableHtmlEscaping().create()

	fun functionTool(
		name: String,
		description: String,
		properties: Map<String, JsonObject>,
		required: List<String> = emptyList(),
	): JsonObject = JsonObject().apply {
		addProperty("type", "function")
		add("function", JsonObject().apply {
			addProperty("name", name)
			addProperty("description", description)
			add("parameters", JsonObject().apply {
				addProperty("type", "object")
				add("properties", JsonObject().apply {
					properties.forEach { (propertyName, schema) -> add(propertyName, schema) }
				})
				if (required.isNotEmpty()) {
					add("required", JsonArray().apply { required.forEach(::add) })
				}
			})
		})
	}

	fun property(type: String, description: String): JsonObject = JsonObject().apply {
		addProperty("type", type)
		addProperty("description", description)
	}

	fun arrayProperty(itemType: String, description: String): JsonObject = JsonObject().apply {
		addProperty("type", "array")
		addProperty("description", description)
		add("items", JsonObject().apply { addProperty("type", itemType) })
	}

	fun errorResult(code: String, message: String): String =
		gson.toJson(JsonObject().apply {
			addProperty("error", code)
			addProperty("message", message)
		})
}
