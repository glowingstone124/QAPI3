package org.qo.services.metroServices

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.qo.datas.ConnectionPool
import org.qo.datas.Nodes
import org.springframework.stereotype.Service

@Service
class MetroServiceImpl(private val nodes:Nodes) {
	val gson: Gson = GsonBuilder().setPrettyPrinting().create()
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
	data class SectionWithId(
		val id: String,
		val lid: Int,
		val station: Boolean,
		val dummy: String,
		val signal: List<JsonObject>,
		val author: Long
	)


	fun getMetroJson(): String {

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


	fun preInsertCheck(body: String, token: String): String {
		if (nodes.getServerFromToken(token) < 0){
			return "Err: Not legal token"
		}
		val section = gson.fromJson(body, SectionWithId::class.java)

		val signalUp = section.signal.getOrNull(0)
		val signalDown = section.signal.getOrNull(1)

		val result = insertSection(
			id = section.id,
			lid = section.lid,
			station = section.station,
			dummy = section.dummy,
			signalUp = signalUp,
			signalDown = signalDown,
			author = section.author
		)
		if (result){
			return "OK"
		}
		return "Err: Not valid json"
	}
	/**
	 * @param id 十六进制id
	 * @param lid 线路编号
	 * @param station 站点编号
	 * @param dummy 名字
	 * @param signalUp 上行地标位置
	 * @param signalDown 下行地标位置
	 * @param author 来源
	 */
	fun insertSection(id: String, lid: Int, station: Boolean, dummy: String, signalUp: JsonObject?, signalDown: JsonObject?, author: Long): Boolean {

		val connection = ConnectionPool.getConnection()
		val sql = """
		INSERT INTO sections (id, lid, station, dummy, signal_up, signal_down, author)
		VALUES (?, ?, ?, ?, ?, ?, ?)
	""".trimIndent()

		connection.use { conn ->
			conn.prepareStatement(sql).use { stmt ->
				stmt.setString(1, id)
				stmt.setInt(2, lid)
				stmt.setBoolean(3, station)
				stmt.setString(4, dummy)
				stmt.setString(5, signalUp?.toString())
				stmt.setString(6, signalDown?.toString())
				stmt.setString(7, author.toString())
				return stmt.executeUpdate() > 0
			}
		}
	}

}