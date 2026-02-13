package org.qo.services.transportationServices

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import com.google.gson.annotations.SerializedName
import com.google.gson.annotations.JsonAdapter
import org.qo.datas.ConnectionPool
import org.springframework.stereotype.Service
import java.sql.ResultSet
import java.sql.Statement
import java.util.Locale
import java.util.PriorityQueue


data class Station(
	@SerializedName("name") val NAME: String,
	@SerializedName("id") val ID: String,
	@SerializedName("screen_location") val SCREEN_LOCATION: Array<Location>,
	@SerializedName("name_en") val NAME_EN: String,
) {
	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (javaClass != other?.javaClass) return false

		other as Station

		if (NAME != other.NAME) return false
		if (ID != other.ID) return false
		if (!SCREEN_LOCATION.contentEquals(other.SCREEN_LOCATION)) return false

		return true
	}

	override fun hashCode(): Int {
		var result = NAME.hashCode()
		result = 31 * result + ID.hashCode()
		result = 31 * result + SCREEN_LOCATION.contentHashCode()
		return result
	}
}

data class Line(
	val stationIds: Array<String>,
	val stationTimes: Array<Int>,
	val lineType: LineType,
	val dimension: Dimension = Dimension.OVERWORLD,
	val name: String,
	val nameEn: String,
	val color: String,
) {
	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (javaClass != other?.javaClass) return false

		other as Line

		if (!stationIds.contentEquals(other.stationIds)) return false
		if (!stationTimes.contentEquals(other.stationTimes)) return false
		if (lineType != other.lineType) return false
		if (dimension != other.dimension) return false

		return true
	}

	override fun hashCode(): Int {
		var result = stationIds.contentHashCode()
		result = 31 * result + stationTimes.contentHashCode()
		result = 31 * result + lineType.hashCode()
		result = 31 * result + dimension.hashCode()
		return result
	}
}

@JsonAdapter(LocationAdapter::class)
data class Location(
	val x: Double,
	val y: Double,
	val z: Double,
	val world: Dimension,
	val rotation: Int = 0,
)

enum class Dimension(name: String) {
	OVERWORLD("OVERWORLD"),
	NETHER("NETHER"),
	THE_END("THE_END"),
}

class LocationAdapter : JsonSerializer<Location>, JsonDeserializer<Location> {
	override fun serialize(src: Location, typeOfSrc: java.lang.reflect.Type, context: JsonSerializationContext): JsonElement {
		return JsonArray().apply {
			add(worldToCode(src.world))
			add(src.x)
			add(src.y)
			add(src.z)
			add(src.rotation.coerceIn(0, 3))
		}
	}

	override fun deserialize(json: JsonElement, typeOfT: java.lang.reflect.Type, context: JsonDeserializationContext): Location {
		return when {
			json.isJsonArray -> parseTuple(json.asJsonArray)
			json.isJsonObject -> parseObject(json.asJsonObject)
			else -> throw JsonParseException("Location must be an array [world,x,y,z,rotation] or object")
		}
	}

	private fun parseTuple(raw: JsonArray): Location {
		if (raw.size() != 5) throw JsonParseException("Location tuple must contain 5 elements")
		val world = parseWorld(raw[0])
		val x = raw[1].asDouble
		val y = raw[2].asDouble
		val z = raw[3].asDouble
		val rotation = parseRotation(raw[4])
		return Location(x = x, y = y, z = z, world = world, rotation = rotation)
	}

	private fun parseObject(raw: JsonObject): Location {
		val worldElement = raw.get("world") ?: raw.get("dim")
			?: throw JsonParseException("Location object missing world")
		val x = raw.getAsJsonPrimitive("x")?.asDouble ?: throw JsonParseException("Location object missing x")
		val y = raw.getAsJsonPrimitive("y")?.asDouble ?: throw JsonParseException("Location object missing y")
		val z = raw.getAsJsonPrimitive("z")?.asDouble ?: throw JsonParseException("Location object missing z")
		val rotation = parseRotation(raw.get("rotation") ?: JsonPrimitive(0))
		return Location(x = x, y = y, z = z, world = parseWorld(worldElement), rotation = rotation)
	}

	private fun parseWorld(element: JsonElement): Dimension {
		if (!element.isJsonPrimitive) throw JsonParseException("world must be number/string")
		val primitive = element.asJsonPrimitive
		if (primitive.isNumber) return codeToWorld(primitive.asInt)
		val token = primitive.asString.trim()
		val numeric = token.toIntOrNull()
		if (numeric != null) return codeToWorld(numeric)
		return when (
			token
				.substringAfter(':')
				.replace("-", "_")
				.replace(" ", "_")
				.uppercase(Locale.ROOT)
		) {
			"OVERWORLD" -> Dimension.OVERWORLD
			"NETHER" -> Dimension.NETHER
			"THE_END", "THEEND", "END" -> Dimension.THE_END
			else -> throw JsonParseException("Unsupported world value: $token")
		}
	}

	private fun parseRotation(element: JsonElement): Int {
		if (!element.isJsonPrimitive) throw JsonParseException("rotation must be number")
		val value = element.asInt
		if (value !in 0..3) throw JsonParseException("rotation must be 0..3")
		return value
	}

	private fun worldToCode(world: Dimension): Int {
		return when (world) {
			Dimension.OVERWORLD -> 0
			Dimension.NETHER -> -1
			Dimension.THE_END -> 1
		}
	}

	private fun codeToWorld(code: Int): Dimension {
		return when (code) {
			0 -> Dimension.OVERWORLD
			-1 -> Dimension.NETHER
			1 -> Dimension.THE_END
			else -> throw JsonParseException("Unsupported world code: $code")
		}
	}
}

enum class LineType(private val lineType: Int, name:String) {
	METRO(0,"METRO"),
	RAPID(1, "RAPID"),
	BLUEICE(2, "BLUEICE"),
	CITYMETRO(3, "CITYMETRO"),
	NETHER(4, "NETHER"),
	PEARL(5, "PEARL"),
	AIRPLANE(6, "AIRPLANE"),
	BOAT(7, "BOAT"),
	WALK(8, "WALK"),
}

data class LineRecord(
	val id: Int,
	val stationIds: Array<String>,
	val stationTimes: Array<Int>,
	val lineType: LineType,
	val dimension: Dimension = Dimension.OVERWORLD,
	val name: String,
	val color: String,
	val name_en: String,
)

data class LineStations(
	val line: LineRecord,
	val stations: List<Station>,
)

data class RouteConstraints(
	val bannedDimensions: Set<Dimension> = emptySet(),
	val bannedLineTypes: Set<LineType> = emptySet(),
)

data class TransferPoint(
	val stationId: String,
	val fromLineId: Int,
	val toLineId: Int,
)

data class RouteSegment(
	val lineId: Int,
	val lineName: String,
	@SerializedName("name_en") val lineNameEn: String,
	val lineType: LineType,
	val dimension: Dimension = Dimension.OVERWORLD,
	val color: String,
	val stationIds: List<String>,
	val time: Int,
)

data class RouteResult(
	val stationIds: List<String>,
	val stations: List<Station>,
	val lineIds: List<Int>,
	val segments: List<RouteSegment>,
	val transfers: List<TransferPoint>,
	val totalTime: Int,
	val totalStops: Int,
)

@Service
class TransportationServiceImpl {
	private val gson = Gson()
	private val TRANSFER_TIME_SECONDS = 15

	private val CREATE_STATIONS_TABLE_SQL = """
		CREATE TABLE IF NOT EXISTS transportation_stations (
			id VARCHAR(64) PRIMARY KEY,
			name VARCHAR(255) NOT NULL,
			screen_location LONGTEXT NOT NULL
		)
	""".trimIndent()

	private val CREATE_LINES_TABLE_SQL = """
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


	fun ensureTables() {
		ConnectionPool.getConnection().use { conn ->
			conn.createStatement().use { stmt ->
				stmt.executeUpdate(CREATE_STATIONS_TABLE_SQL)
				stmt.executeUpdate(CREATE_LINES_TABLE_SQL)
			}
		}
	}

	fun addStation(station: Station): Boolean {
		val sql = """
			INSERT INTO transportation_stations (id, name, screen_location)
			VALUES (?, ?, ?)
		""".trimIndent()
		ConnectionPool.getConnection().use { conn ->
			conn.prepareStatement(sql).use { stmt ->
				stmt.setString(1, station.ID)
				stmt.setString(2, station.NAME)
				stmt.setString(3, gson.toJson(station.SCREEN_LOCATION))
				return stmt.executeUpdate() > 0
			}
		}
	}

	fun editStation(station: Station): Boolean {
		val sql = """
			UPDATE transportation_stations
			SET name = ?, screen_location = ?
			WHERE id = ?
		""".trimIndent()
		ConnectionPool.getConnection().use { conn ->
			conn.prepareStatement(sql).use { stmt ->
				stmt.setString(1, station.NAME)
				stmt.setString(2, gson.toJson(station.SCREEN_LOCATION))
				stmt.setString(3, station.ID)
				return stmt.executeUpdate() > 0
			}
		}
	}

	fun removeStation(id: String): Boolean {
		val sql = "DELETE FROM transportation_stations WHERE id = ?"
		ConnectionPool.getConnection().use { conn ->
			conn.prepareStatement(sql).use { stmt ->
				stmt.setString(1, id)
				return stmt.executeUpdate() > 0
			}
		}
	}

	fun listStations(): List<Station> {
		val sql = "SELECT * FROM transportation_stations"
		val stations = mutableListOf<Station>()
		ConnectionPool.getConnection().use { conn ->
			conn.prepareStatement(sql).use { stmt ->
				stmt.executeQuery().use { rs ->
					while (rs.next()) {
						stations.add(
							Station(
								NAME = rs.getString("name"),
								ID = rs.getString("id"),
								SCREEN_LOCATION = parseLocations(rs.getString("screen_location")),
								NAME_EN = rs.getString("name_en"),
							)
						)
					}
				}
			}
		}
		return stations
	}

	fun getStationById(id: String): Station? {
		val sql = "SELECT * FROM transportation_stations WHERE id = ? LIMIT 1"
		ConnectionPool.getConnection().use { conn ->
			conn.prepareStatement(sql).use { stmt ->
				stmt.setString(1, id)
				stmt.executeQuery().use { rs ->
					if (rs.next()) {
						return Station(
							NAME = rs.getString("name"),
							ID = rs.getString("id"),
							SCREEN_LOCATION = parseLocations(rs.getString("screen_location")),
							NAME_EN = rs.getString("name_en"),
						)
					}
				}
			}
		}
		return null
	}

	fun queryStationsByName(name: String, fuzzy: Boolean = true): List<Station> {
		val sql = if (fuzzy) {
			"SELECT * FROM transportation_stations WHERE name LIKE ?"
		} else {
			"SELECT * FROM transportation_stations WHERE name = ?"
		}
		val stations = mutableListOf<Station>()
		ConnectionPool.getConnection().use { conn ->
			conn.prepareStatement(sql).use { stmt ->
				stmt.setString(1, if (fuzzy) "%$name%" else name)
				stmt.executeQuery().use { rs ->
					while (rs.next()) {
						stations.add(
							Station(
								NAME = rs.getString("name"),
								ID = rs.getString("id"),
								SCREEN_LOCATION = parseLocations(rs.getString("screen_location")),
								NAME_EN = rs.getString("name_en"),
							)
						)
					}
				}
			}
		}
		return stations
	}

	fun addLine(line: Line): Int? {
		validateLineOrThrow(line)
		val sql = """
			INSERT INTO transportation_lines (name, name_en, color, line_type, dimension, station_ids, station_times)
			VALUES (?, ?, ?, ?, ?, ?, ?)
		""".trimIndent()
		ConnectionPool.getConnection().use { conn ->
			conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS).use { stmt ->
				stmt.setString(1, line.name)
				stmt.setString(2, line.nameEn)
				stmt.setString(3, line.color)
				stmt.setString(4, line.lineType.name)
				stmt.setString(5, line.dimension.name)
				stmt.setString(6, gson.toJson(line.stationIds))
				stmt.setString(7, gson.toJson(line.stationTimes))
				val affected = stmt.executeUpdate()
				if (affected == 0) return null
				stmt.generatedKeys.use { rs ->
					if (rs.next()) {
						return rs.getInt(1)
					}
				}
			}
		}
		return null
	}

	fun editLine(lineId: Int, line: Line): Boolean {
		validateLineOrThrow(line)
		val sql = """
			UPDATE transportation_lines
			SET name = ?, name_en = ?, color = ?, line_type = ?, dimension = ?, station_ids = ?, station_times = ?
			WHERE id = ?
		""".trimIndent()
		ConnectionPool.getConnection().use { conn ->
			conn.prepareStatement(sql).use { stmt ->
				stmt.setString(1, line.name)
				stmt.setString(2, line.nameEn)
				stmt.setString(3, line.color)
				stmt.setString(4, line.lineType.name)
				stmt.setString(5, line.dimension.name)
				stmt.setString(6, gson.toJson(line.stationIds))
				stmt.setString(7, gson.toJson(line.stationTimes))
				stmt.setInt(8, lineId)
				return stmt.executeUpdate() > 0
			}
		}
	}

	fun removeLine(lineId: Int): Boolean {
		val sql = "DELETE FROM transportation_lines WHERE id = ?"
		ConnectionPool.getConnection().use { conn ->
			conn.prepareStatement(sql).use { stmt ->
				stmt.setInt(1, lineId)
				return stmt.executeUpdate() > 0
			}
		}
	}

	fun listLines(): List<LineRecord> {
		val sql = "SELECT * FROM transportation_lines"
		val lines = mutableListOf<LineRecord>()
		ConnectionPool.getConnection().use { conn ->
			conn.prepareStatement(sql).use { stmt ->
				stmt.executeQuery().use { rs ->
					while (rs.next()) {
						val lineType = parseLineType(rs.getString("line_type")) ?: continue
						lines.add(
							LineRecord(
								id = rs.getInt("id"),
								stationIds = parseStringArray(rs.getString("station_ids")),
								stationTimes = parseIntArray(rs.getString("station_times")),
								lineType = lineType,
								dimension = readLineDimension(rs),
								name = rs.getString("name"),
								color = rs.getString("color"),
								name_en = rs.getString("name_en"),
							)
						)
					}
				}
			}
		}
		return lines
	}

	fun getLineById(lineId: Int): LineRecord? {
		val sql = "SELECT * FROM transportation_lines WHERE id = ? LIMIT 1"
		ConnectionPool.getConnection().use { conn ->
			conn.prepareStatement(sql).use { stmt ->
				stmt.setInt(1, lineId)
				stmt.executeQuery().use { rs ->
					if (rs.next()) {
						val lineType = parseLineType(rs.getString("line_type")) ?: return null
							return LineRecord(
								id = rs.getInt("id"),
								stationIds = parseStringArray(rs.getString("station_ids")),
								stationTimes = parseIntArray(rs.getString("station_times")),
								lineType = lineType,
								dimension = readLineDimension(rs),
								name = rs.getString("name"),
								color = rs.getString("color"),
								name_en = rs.getString("name_en"),
							)
					}
				}
			}
		}
		return null
	}

	fun queryLinesByName(name: String, fuzzy: Boolean = true): List<LineRecord> {
		val sql = if (fuzzy) {
			"SELECT * FROM transportation_lines WHERE name LIKE ?"
		} else {
			"SELECT * FROM transportation_lines WHERE name = ?"
		}
		val lines = mutableListOf<LineRecord>()
		ConnectionPool.getConnection().use { conn ->
			conn.prepareStatement(sql).use { stmt ->
				stmt.setString(1, if (fuzzy) "%$name%" else name)
				stmt.executeQuery().use { rs ->
					while (rs.next()) {
						val lineType = parseLineType(rs.getString("line_type")) ?: continue
						lines.add(
							LineRecord(
								id = rs.getInt("id"),
								stationIds = parseStringArray(rs.getString("station_ids")),
								stationTimes = parseIntArray(rs.getString("station_times")),
								lineType = lineType,
								dimension = readLineDimension(rs),
								name = rs.getString("name"),
								color = rs.getString("color"),
								name_en = rs.getString("name_en"),
							)
						)
					}
				}
			}
		}
		return lines
	}

	fun queryStationsByLineId(lineId: Int): List<Station> {
		val line = getLineById(lineId) ?: return emptyList()
		val stationMap = fetchStationsByIds(line.stationIds.toList())
		return line.stationIds.mapNotNull { stationMap[it] }
	}

	fun queryStationsByLineName(name: String, fuzzy: Boolean = true): List<LineStations> {
		return queryLinesByName(name, fuzzy).map { line ->
			val stationMap = fetchStationsByIds(line.stationIds.toList())
			LineStations(
				line = line,
				stations = line.stationIds.mapNotNull { stationMap[it] }
			)
		}
	}

	fun calculateRoute(
		startStationId: String,
		endStationId: String,
		constraints: RouteConstraints = RouteConstraints()
	): RouteResult? {
		val stationMap = listStations().associateBy { it.ID }
		if (!stationMap.containsKey(startStationId) || !stationMap.containsKey(endStationId)) return null

		val adjacency = mutableMapOf<String, MutableList<Edge>>()
		val lineTypeById = mutableMapOf<Int, LineType>()
		for (line in listLines()) {
			if (constraints.bannedLineTypes.contains(line.lineType)) continue
			if (constraints.bannedDimensions.contains(line.dimension)) continue
			if (line.stationIds.size < 2 || line.stationTimes.size != line.stationIds.size - 1) continue
			lineTypeById[line.id] = line.lineType
			for (i in 0 until line.stationIds.size - 1) {
				val from = line.stationIds[i]
				val to = line.stationIds[i + 1]
				val time = line.stationTimes[i].coerceAtLeast(0)
				if (!stationMap.containsKey(from) || !stationMap.containsKey(to)) continue
					val edge = Edge(
						to = to,
						time = time,
						lineId = line.id,
						lineName = line.name,
						lineNameEn = line.name_en,
						lineType = line.lineType,
						dimension = line.dimension,
						color = line.color
					)
				adjacency.getOrPut(from) { mutableListOf() }.add(edge)
			}
		}

		val dist = mutableMapOf<State, Int>()
		val prev = mutableMapOf<State, PrevEdge>()
		val pq = PriorityQueue<Node>(compareBy { it.dist })
		val startState = State(startStationId, null)
		dist[startState] = 0
		pq.add(Node(startState, 0))
		while (pq.isNotEmpty()) {
			val current = pq.poll()
			val currentDist = dist[current.state] ?: continue
			if (current.dist != currentDist) continue
			val edges = adjacency[current.state.stationId] ?: continue
			for (edge in edges) {
				val transferCost = if (current.state.lineId != null && current.state.lineId != edge.lineId) {
					val fromType = lineTypeById[current.state.lineId]
					if (fromType == LineType.WALK || edge.lineType == LineType.WALK) 0 else TRANSFER_TIME_SECONDS
				} else {
					0
				}
				val newDist = currentDist + edge.time + transferCost
				val nextState = State(edge.to, edge.lineId)
				val oldDist = dist[nextState]
				if (oldDist == null || newDist < oldDist) {
					dist[nextState] = newDist
					prev[nextState] = PrevEdge(from = current.state, edge = edge)
					pq.add(Node(nextState, newDist))
				}
			}
		}

		val endState = dist.keys
			.filter { it.stationId == endStationId }
			.minByOrNull { dist[it] ?: Int.MAX_VALUE }
			?: return null

		val stationPath = mutableListOf<String>()
		val edgePath = mutableListOf<Edge>()
		var currentState = endState
		stationPath.add(currentState.stationId)
		while (currentState.stationId != startStationId) {
			val prevEdge = prev[currentState] ?: return null
			edgePath.add(prevEdge.edge.copy(to = currentState.stationId))
			currentState = prevEdge.from
			stationPath.add(currentState.stationId)
		}
		stationPath.reverse()
		edgePath.reverse()

		val segments = mutableListOf<RouteSegment>()
		val transfers = mutableListOf<TransferPoint>()
		var transferTimeTotal = 0
		if (edgePath.isNotEmpty()) {
			var currentLineId = edgePath[0].lineId
			var currentLineName = edgePath[0].lineName
			var currentLineNameEn = edgePath[0].lineNameEn
			var currentLineType = edgePath[0].lineType
			var currentDimension = edgePath[0].dimension
			var currentColor = edgePath[0].color
			var segmentTime = 0
			var segmentStations = mutableListOf<String>()
			segmentStations.add(stationPath[0])
			for (i in edgePath.indices) {
				val edge = edgePath[i]
				if (edge.lineId != currentLineId) {
					if (currentLineType != LineType.WALK && edge.lineType != LineType.WALK) {
						segmentTime += TRANSFER_TIME_SECONDS
						transferTimeTotal += TRANSFER_TIME_SECONDS
					}
					segments.add(
						RouteSegment(
							lineId = currentLineId,
							lineName = currentLineName,
							lineNameEn = currentLineNameEn,
							lineType = currentLineType,
							dimension = currentDimension,
							color = currentColor,
							stationIds = segmentStations.toList(),
							time = segmentTime
						)
					)
					val transferStationId = stationPath[i]
					transfers.add(
						TransferPoint(
							stationId = transferStationId,
							fromLineId = currentLineId,
							toLineId = edge.lineId
						)
					)
					currentLineId = edge.lineId
					currentLineName = edge.lineName
					currentLineNameEn = edge.lineNameEn
					currentLineType = edge.lineType
					currentDimension = edge.dimension
					currentColor = edge.color
					segmentTime = 0
					segmentStations = mutableListOf()
					segmentStations.add(transferStationId)
				}
				segmentTime += edge.time
				segmentStations.add(edge.to)
			}
			segments.add(
				RouteSegment(
						lineId = currentLineId,
						lineName = currentLineName,
						lineNameEn = currentLineNameEn,
						lineType = currentLineType,
						dimension = currentDimension,
						color = currentColor,
						stationIds = segmentStations.toList(),
						time = segmentTime
					)
				)
		}

		val totalTime = edgePath.sumOf { it.time } + transferTimeTotal
		val lineIds = segments.map { it.lineId }
		val totalStops = edgePath.count { it.lineType != LineType.WALK }
		return RouteResult(
			stationIds = stationPath,
			stations = stationPath.mapNotNull { stationMap[it] },
			lineIds = lineIds,
			segments = segments,
			transfers = transfers,
			totalTime = totalTime,
			totalStops = totalStops
		)
	}

	private fun fetchStationsByIds(ids: List<String>): Map<String, Station> {
		if (ids.isEmpty()) return emptyMap()
		val placeholders = ids.joinToString(",") { "?" }
		val sql = "SELECT * FROM transportation_stations WHERE id IN ($placeholders)"
		val result = mutableMapOf<String, Station>()
		ConnectionPool.getConnection().use { conn ->
			conn.prepareStatement(sql).use { stmt ->
				ids.forEachIndexed { index, id -> stmt.setString(index + 1, id) }
				stmt.executeQuery().use { rs ->
					while (rs.next()) {
						val station = Station(
							NAME = rs.getString("name"),
							ID = rs.getString("id"),
							SCREEN_LOCATION = parseLocations(rs.getString("screen_location")),
							NAME_EN = rs.getString("name_en"),
						)
						result[station.ID] = station
					}
				}
			}
		}
		return result
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

	private fun readLineDimension(rs: ResultSet): Dimension {
		val raw = runCatching { rs.getString("dimension") }.getOrNull()
		return parseDimension(raw) ?: Dimension.OVERWORLD
	}

	private fun validateLineOrThrow(line: Line) {
		require(line.stationIds.size >= 2) { "stationIds must have at least 2 stations" }
		require(line.stationTimes.size == line.stationIds.size - 1) { "stationTimes length must be stationIds.size - 1" }
	}


	private data class Edge(
		val to: String,
		val time: Int,
		val lineId: Int,
		val lineName: String,
		val lineNameEn: String,
		val lineType: LineType,
		val dimension: Dimension,
		val color: String,
	)

	private data class PrevEdge(
		val from: State,
		val edge: Edge,
	)

	private data class Node(
		val state: State,
		val dist: Int,
	)

	private data class State(
		val stationId: String,
		val lineId: Int?,
	)
}
