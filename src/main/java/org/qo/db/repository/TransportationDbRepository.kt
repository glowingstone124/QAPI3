package org.qo.db.repository

import com.google.gson.Gson
import io.r2dbc.spi.Row
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.qo.datas.ReactiveDatabase
import org.qo.services.transportationServices.Dimension
import org.qo.services.transportationServices.Line
import org.qo.services.transportationServices.LineRecord
import org.qo.services.transportationServices.LineType
import org.qo.services.transportationServices.Location
import org.qo.services.transportationServices.Station
import org.springframework.stereotype.Repository
import java.util.Locale

@Repository
class TransportationDbRepository(
	private val database: ReactiveDatabase,
) {
	private val gson = Gson()
	private val schemaMutex = Mutex()
	@Volatile
	private var schemaReady = false

	private val createStationsTableSql = """
		CREATE TABLE IF NOT EXISTS transportation_stations (
			id VARCHAR(64) PRIMARY KEY,
			name VARCHAR(255) NOT NULL,
			name_en VARCHAR(255) NOT NULL,
			screen_location LONGTEXT NOT NULL
		)
	""".trimIndent()

	private val createLinesTableSql = """
		CREATE TABLE IF NOT EXISTS transportation_lines (
			id INT AUTO_INCREMENT PRIMARY KEY,
			name VARCHAR(255) NOT NULL,
			name_en VARCHAR(255) NOT NULL,
			color VARCHAR(32) NOT NULL,
			line_type VARCHAR(32) NOT NULL,
			dimension VARCHAR(32) NOT NULL DEFAULT 'OVERWORLD',
			station_ids LONGTEXT NOT NULL,
			station_times LONGTEXT NOT NULL
		)
	""".trimIndent()

	suspend fun ensureTables() {
		if (schemaReady) return
		schemaMutex.withLock {
			if (schemaReady) return
			database.execute(createStationsTableSql)
			database.execute(createLinesTableSql)
			addColumnIfMissing("transportation_stations", "name_en", "VARCHAR(255) NOT NULL DEFAULT ''")
			addColumnIfMissing("transportation_lines", "name_en", "VARCHAR(255) NOT NULL DEFAULT ''")
			addColumnIfMissing("transportation_lines", "dimension", "VARCHAR(32) NOT NULL DEFAULT 'OVERWORLD'")
			schemaReady = true
		}
	}

	private suspend fun addColumnIfMissing(table: String, column: String, definition: String) {
		val exists = database.one(
			"""
			SELECT 1
			FROM information_schema.columns
			WHERE (table_schema = DATABASE() OR table_catalog = DATABASE())
			  AND LOWER(table_name) = LOWER(?)
			  AND LOWER(column_name) = LOWER(?)
			LIMIT 1
			""".trimIndent(),
			listOf(table, column),
		) { true } != null
		if (!exists) {
			database.execute("ALTER TABLE $table ADD COLUMN $column $definition")
		}
	}

	suspend fun addStation(station: Station): Boolean {
		ensureTables()
		val sql = """
			INSERT INTO transportation_stations (id, name, name_en, screen_location)
			VALUES (?, ?, ?, ?)
		""".trimIndent()
		return database.execute(sql, listOf(station.ID, station.NAME, station.NAME_EN, gson.toJson(station.SCREEN_LOCATION))) > 0
	}

	suspend fun editStation(station: Station): Boolean {
		ensureTables()
		val sql = """
			UPDATE transportation_stations
			SET name = ?, name_en = ?, screen_location = ?
			WHERE id = ?
		""".trimIndent()
		return database.execute(sql, listOf(station.NAME, station.NAME_EN, gson.toJson(station.SCREEN_LOCATION), station.ID)) > 0
	}

	suspend fun removeStation(id: String): Boolean {
		ensureTables()
		return database.execute("DELETE FROM transportation_stations WHERE id = ?", listOf(id)) > 0
	}

	suspend fun listStations(): List<Station> {
		ensureTables()
		return database.all("SELECT * FROM transportation_stations", mapper = ::toStation)
	}

	suspend fun getStationById(id: String): Station? {
		ensureTables()
		return database.one(
			"SELECT * FROM transportation_stations WHERE id = ? LIMIT 1",
			listOf(id),
			::toStation,
		)
	}

	suspend fun queryStationsByName(name: String, fuzzy: Boolean = true): List<Station> {
		ensureTables()
		val sql = if (fuzzy) {
			"SELECT * FROM transportation_stations WHERE name LIKE ? OR name_en LIKE ?"
		} else {
			"SELECT * FROM transportation_stations WHERE name = ? OR name_en = ?"
		}
		val value = if (fuzzy) "%$name%" else name
		return database.all(sql, listOf(value, value), ::toStation)
	}

	suspend fun addLine(line: Line): Int? {
		ensureTables()
		return database.inTransaction {
			val sql = """
				INSERT INTO transportation_lines (name, name_en, color, line_type, dimension, station_ids, station_times)
				VALUES (?, ?, ?, ?, ?, ?, ?)
			""".trimIndent()
			val affected = database.execute(
				sql,
				listOf(
					line.name,
					line.nameEn,
					line.color,
					line.lineType.name,
					line.dimension.name,
					gson.toJson(line.stationIds),
					gson.toJson(line.stationTimes),
				),
			)
			if (affected == 0L) return@inTransaction null
			runCatching {
				database.one("SELECT LAST_INSERT_ID() AS id") { row -> numberValue(row, "id").toInt() }
			}.getOrNull() ?: database.one(
				"""
				SELECT id FROM transportation_lines
				WHERE name = ? AND name_en = ? AND color = ? AND line_type = ? AND dimension = ?
				ORDER BY id DESC
				LIMIT 1
				""".trimIndent(),
				listOf(line.name, line.nameEn, line.color, line.lineType.name, line.dimension.name),
			) { row -> numberValue(row, "id").toInt() }
		}
	}

	suspend fun editLine(lineId: Int, line: Line): Boolean {
		ensureTables()
		val sql = """
			UPDATE transportation_lines
			SET name = ?, name_en = ?, color = ?, line_type = ?, dimension = ?, station_ids = ?, station_times = ?
			WHERE id = ?
		""".trimIndent()
		return database.execute(
			sql,
			listOf(
				line.name,
				line.nameEn,
				line.color,
				line.lineType.name,
				line.dimension.name,
				gson.toJson(line.stationIds),
				gson.toJson(line.stationTimes),
				lineId,
			),
		) > 0
	}

	suspend fun removeLine(lineId: Int): Boolean {
		ensureTables()
		return database.execute("DELETE FROM transportation_lines WHERE id = ?", listOf(lineId)) > 0
	}

	suspend fun listLines(): List<LineRecord> {
		ensureTables()
		return database.all("SELECT * FROM transportation_lines") { row -> toLineRecordOrNull(row) }.mapNotNull { it }
	}

	suspend fun getLineById(lineId: Int): LineRecord? {
		ensureTables()
		return database.one(
			"SELECT * FROM transportation_lines WHERE id = ? LIMIT 1",
			listOf(lineId),
		) { row -> toLineRecordOrNull(row) }
	}

	suspend fun queryLinesByName(name: String, fuzzy: Boolean = true): List<LineRecord> {
		ensureTables()
		val sql = if (fuzzy) {
			"SELECT * FROM transportation_lines WHERE name LIKE ? OR name_en LIKE ?"
		} else {
			"SELECT * FROM transportation_lines WHERE name = ? OR name_en = ?"
		}
		val value = if (fuzzy) "%$name%" else name
		return database.all(sql, listOf(value, value)) { row -> toLineRecordOrNull(row) }.mapNotNull { it }
	}

	suspend fun fetchStationsByIds(ids: List<String>): Map<String, Station> {
		if (ids.isEmpty()) return emptyMap()
		ensureTables()
		val placeholders = ids.joinToString(", ") { "?" }
		return database.all(
			"SELECT * FROM transportation_stations WHERE id IN ($placeholders)",
			ids,
			::toStation,
		).associateBy { it.ID }
	}

	private fun toStation(row: Row): Station = Station(
		NAME = row.get("name", String::class.java).orEmpty(),
		ID = row.get("id", String::class.java)!!,
		SCREEN_LOCATION = parseLocations(row.get("screen_location", String::class.java)),
		NAME_EN = row.get("name_en", String::class.java).orEmpty(),
	)

	private fun toLineRecordOrNull(row: Row): LineRecord? {
		val lineType = parseLineType(row.get("line_type", String::class.java)) ?: return null
		return LineRecord(
			id = numberValue(row, "id").toInt(),
			stationIds = parseStringArray(row.get("station_ids", String::class.java)),
			stationTimes = parseIntArray(row.get("station_times", String::class.java)),
			lineType = lineType,
			dimension = parseDimension(row.get("dimension", String::class.java)) ?: Dimension.OVERWORLD,
			name = row.get("name", String::class.java).orEmpty(),
			color = row.get("color", String::class.java).orEmpty(),
			name_en = row.get("name_en", String::class.java).orEmpty(),
		)
	}

	private fun numberValue(row: Row, column: String): Long {
		val value = row.get(column) ?: error("Column $column is null")
		return when (value) {
			is Number -> value.toLong()
			else -> error("Column $column is not numeric: ${value::class.java.name}")
		}
	}

	private fun parseLocations(value: String?): Array<Location> {
		if (value.isNullOrBlank()) return emptyArray()
		return gson.fromJson(value, Array<Location>::class.java)
	}

	private fun parseIntArray(value: String?): Array<Int> {
		if (value.isNullOrBlank()) return emptyArray()
		return gson.fromJson(value, Array<Int>::class.java)
	}

	private fun parseStringArray(value: String?): Array<String> {
		if (value.isNullOrBlank()) return emptyArray()
		return gson.fromJson(value, Array<String>::class.java)
	}

	private fun parseLineType(value: String?): LineType? {
		if (value.isNullOrBlank()) return null
		val trimmed = value.trim()
		if (trimmed.all { it.isDigit() }) {
			val code = trimmed.toIntOrNull() ?: return null
			return LineType.entries.firstOrNull { it.ordinal == code }
		}
		return runCatching { LineType.valueOf(trimmed) }.getOrNull()
	}

	private fun parseDimension(value: String?): Dimension? {
		if (value.isNullOrBlank()) return null
		return when (
			value
				.substringAfter(':')
				.trim()
				.replace("-", "_")
				.replace(" ", "_")
				.uppercase(Locale.ROOT)
		) {
			"OVERWORLD" -> Dimension.OVERWORLD
			"NETHER" -> Dimension.NETHER
			"THE_END", "THEEND", "END" -> Dimension.THE_END
			else -> null
		}
	}
}
