package org.qo.services.flightServices

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import org.springframework.stereotype.Service
import java.lang.reflect.Type
import java.util.concurrent.ConcurrentHashMap

data class ReportObject(
	val name: String,
	val x: Double,
	val z: Double,
	val speedKmh: Float,
	val destination: String,
	val flightSince: Long = 0L
)

@Service
class FlightService {
	val gson: Gson = GsonBuilder()
		.setPrettyPrinting()
		.create()
	private val flightStatusMap = ConcurrentHashMap<String, ReportObject>()
	fun getAllActiveFlights(): String {
		return gson.toJson(flightStatusMap.values.toList())
	}
	fun updateRecords(jsonListData: String) {
		try {
			val incomingList: List<ReportObject> = gson.fromJson(jsonListData, object : com.google.gson.reflect.TypeToken<List<ReportObject>>() {}.type)

			val now = System.currentTimeMillis()
			val incomingNames = incomingList.map { it.name }.toSet()

			flightStatusMap.keys.retainAll(incomingNames)

			incomingList.forEach { incoming ->
				val existingRecord = flightStatusMap[incoming.name]
				val finalSince = existingRecord?.flightSince ?: now

				val recordToSave = incoming.copy(flightSince = finalSince)
				flightStatusMap[recordToSave.name] = recordToSave
			}

		} catch (e: Exception) {
			println("同步数据失败: ${e.message}")
		}
	}
}