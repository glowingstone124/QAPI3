package org.qo.services.metroServices

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import org.qo.datas.ConnectionPool
import org.qo.orm.SQL
import org.springframework.stereotype.Service

@Service
class MetroServiceImpl {
	data class Signal(
		val world: String,
		val x: Int,
		val y: Int,
		val z: Int
	)

	data class Section(
		val lid: Int,
		val station: Boolean,
		val dummy: String,
		val signal: List<JsonObject>
	)

	fun getMetroJson(): String {
		val gson: Gson = GsonBuilder().setPrettyPrinting().create()

		val sectionMap = mutableMapOf<String, Section>()
		val connection = ConnectionPool.getConnection()
		val stmt = connection.createStatement()
		val resultSet = stmt.executeQuery("SELECT * FROM sections")

		while (resultSet.next()) {
			val id = resultSet.getString("id")
			val lid = resultSet.getInt("lid")
			val station = resultSet.getBoolean("station")
			val dummy = resultSet.getString("dummy")
			val sigList: MutableList<JsonObject> = mutableListOf()
			val upStr: String? = resultSet.getString("signal_up")
			val downStr: String? = resultSet.getString("signal_down")

			upStr?.let {
				sigList.add(JsonParser.parseString(upStr) as JsonObject)
			}
			downStr?.let {
				sigList.add(JsonParser.parseString(downStr) as JsonObject)
			}

			val section = Section(
				lid = lid,
				station = station,
				dummy = dummy,
				signal = sigList
			)

			sectionMap[id] = section
		}

		return gson.toJson(sectionMap)
	}
}