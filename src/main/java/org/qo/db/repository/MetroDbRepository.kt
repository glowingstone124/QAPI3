package org.qo.db.repository

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.qo.datas.ReactiveDatabase
import org.qo.services.metroServices.MetroServiceImpl
import org.springframework.stereotype.Repository

@Repository
class MetroDbRepository(
	private val database: ReactiveDatabase,
) {
	suspend fun getAllSections(): Map<String, MetroServiceImpl.Section> = database.all("SELECT * FROM sections") { row ->
		val signal = listOf("signal_up", "signal_down").mapNotNull { column ->
			row.get(column, String::class.java)?.takeIf { it.isNotBlank() }?.let {
				JsonParser.parseString(it).asJsonObject
			}
		}
		row.get("id", String::class.java)!! to MetroServiceImpl.Section(
			lid = row.get("lid", java.lang.Integer::class.java)!!.toInt(),
			station = row.get("station", java.lang.Boolean::class.java) == true,
			dummy = row.get("dummy", String::class.java).orEmpty(),
			signal = signal,
		)
	}.toMap(linkedMapOf())

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
