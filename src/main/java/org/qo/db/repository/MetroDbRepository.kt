package org.qo.db.repository

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.r2dbc.spi.Row
import org.qo.datas.ReactiveDatabase
import org.qo.services.metroServices.MetroServiceImpl
import org.springframework.stereotype.Repository

@Repository
class MetroDbRepository(
	private val database: ReactiveDatabase,
) {
	suspend fun getAllSections(): Map<String, MetroServiceImpl.Section> {
		val sectionMap = linkedMapOf<String, MetroServiceImpl.Section>()
		database.all("SELECT * FROM sections") { row ->
			val signal = buildList {
				row.get("signal_up", String::class.java)?.takeIf { it.isNotBlank() }?.let {
					add(JsonParser.parseString(it).asJsonObject)
				}
				row.get("signal_down", String::class.java)?.takeIf { it.isNotBlank() }?.let {
					add(JsonParser.parseString(it).asJsonObject)
				}
			}
			row.get("id", String::class.java)!! to MetroServiceImpl.Section(
				lid = row.get("lid", java.lang.Integer::class.java)!!.toInt(),
				station = row.get("station", java.lang.Boolean::class.java) == true,
				dummy = row.get("dummy", String::class.java).orEmpty(),
				signal = signal,
			)
		}.forEach { (id, section) -> sectionMap[id] = section }
		return sectionMap
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
