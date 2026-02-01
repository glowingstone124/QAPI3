package org.qo.utils

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.reflect.TypeToken

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
	inline fun <reified T> JsonArray.convertToArrayList(gson: Gson = Gson()): ArrayList<T> {
		val list = ArrayList<T>(this.size())
		val type = object : TypeToken<T>() {}.type
		for (element in this) {
			list.add(gson.fromJson(element, type))
		}
		return list
	}
}