package org.qo.services.metroServices

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.qo.datas.Nodes
import org.qo.datas.ReactiveDatabase
import org.springframework.stereotype.Service

@Service
class MetroServiceImpl(
	private val nodes: Nodes,
	private val database: ReactiveDatabase,
) {
	private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

	data class Signal(
		val world: String,
		val x: Int,
		val y: Int,
		val z: Int,
	)

	data class Section(
		val lid: Int,
		val station: Boolean,
		val dummy: String,
		val signal: List<JsonObject>,
	)

	data class SectionWithId(
		val id: String,
		val lid: Int,
		val station: Boolean,
		val dummy: String,
		val signal: List<JsonObject>,
		val author: Long,
	)

	suspend fun getMetroJson(): String {
		val sectionMap = linkedMapOf<String, Section>()
		database.all("SELECT * FROM sections") { row ->
			val signal = buildList {
				row.get("signal_up", String::class.java)?.takeIf { it.isNotBlank() }?.let {
					add(JsonParser.parseString(it).asJsonObject)
				}
				row.get("signal_down", String::class.java)?.takeIf { it.isNotBlank() }?.let {
					add(JsonParser.parseString(it).asJsonObject)
				}
			}
			row.get("id", String::class.java)!! to Section(
				lid = row.get("lid", java.lang.Integer::class.java)!!.toInt(),
				station = row.get("station", java.lang.Boolean::class.java) == true,
				dummy = row.get("dummy", String::class.java).orEmpty(),
				signal = signal,
			)
		}.forEach { (id, section) -> sectionMap[id] = section }
		return gson.toJson(sectionMap)
	}

	suspend fun preInsertCheck(body: String, token: String): String {
		if (nodes.getServerFromToken(token) < 0) {
			return "Err: Not legal token"
		}
		val section = gson.fromJson(body, SectionWithId::class.java)
		val signalUp = section.signal.getOrNull(0)
		val signalDown = section.signal.getOrNull(1)
		return if (
			insertSection(
				id = section.id,
				lid = section.lid,
				station = section.station,
				dummy = section.dummy,
				signalUp = signalUp,
				signalDown = signalDown,
				author = section.author,
			)
		) {
			"OK"
		} else {
			"Err: Not valid json"
		}
	}

	suspend fun insertSection(
		id: String,
		lid: Int,
		station: Boolean,
		dummy: String,
		signalUp: JsonObject?,
		signalDown: JsonObject?,
		author: Long,
	): Boolean {
		val sql = """
			INSERT INTO sections (id, lid, station, dummy, signal_up, signal_down, author)
			VALUES (?, ?, ?, ?, ?, ?, ?)
		""".trimIndent()
		return database.execute(
			sql,
			listOf(id, lid, station, dummy, signalUp?.toString(), signalDown?.toString(), author.toString()),
		) > 0
	}
}
