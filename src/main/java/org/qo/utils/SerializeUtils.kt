package org.qo.utils

import com.google.gson.Gson
import com.google.gson.JsonArray

object SerializeUtils {
	inline fun <reified T> List<T>.toJson(): String {
		return Gson().toJson(this)
	}

	fun <T> List<T>.convertToJsonArray(): JsonArray {
		val gson = Gson()
		val jsonArray = JsonArray()

		this.forEach { element ->
			val jsonElement = gson.toJsonTree(element)
			jsonArray.add(jsonElement)
		}

		return jsonArray
	}
}