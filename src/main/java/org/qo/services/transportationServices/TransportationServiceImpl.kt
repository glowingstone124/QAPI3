package org.qo.services.transportationServices

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import org.qo.datas.ConnectionPool
import org.springframework.stereotype.Service
import java.sql.Statement
import java.util.PriorityQueue


data class Station(
	@SerializedName("name") val NAME: String,
	@SerializedName("id") val ID: String,
	@SerializedName("screen_location")val SCREEN_LOCATION: Array<Location>
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
	val name: String,
	val color: String,
) {
	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (javaClass != other?.javaClass) return false

		other as Line

		if (!stationIds.contentEquals(other.stationIds)) return false
		if (!stationTimes.contentEquals(other.stationTimes)) return false
		if (lineType != other.lineType) return false

		return true
	}

	override fun hashCode(): Int {
		var result = stationIds.contentHashCode()
		result = 31 * result + stationTimes.contentHashCode()
		result = 31 * result + lineType.hashCode()
		return result
	}
}

data class Location(
	val x: Double,
	val y: Double,
	val z: Double,
	val world: Dimension
)

enum class Dimension(name: String) {
	OVERWORLD("OVERWORLD"),
	NETHER("NETHER"),
	THE_END("THE_END"),
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
	val name: String,
	val color: String,
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
	val lineType: LineType,
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
			color VARCHAR(32) NOT NULL,
			line_type VARCHAR(32) NOT NULL,
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
								SCREEN_LOCATION = parseLocations(rs.getString("screen_location"))
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
							SCREEN_LOCATION = parseLocations(rs.getString("screen_location"))
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
								SCREEN_LOCATION = parseLocations(rs.getString("screen_location"))
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
			INSERT INTO transportation_lines (name, color, line_type, station_ids, station_times)
			VALUES (?, ?, ?, ?, ?)
		""".trimIndent()
		ConnectionPool.getConnection().use { conn ->
			conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS).use { stmt ->
				stmt.setString(1, line.name)
				stmt.setString(2, line.color)
				stmt.setString(3, line.lineType.name)
				stmt.setString(4, gson.toJson(line.stationIds))
				stmt.setString(5, gson.toJson(line.stationTimes))
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
			SET name = ?, color = ?, line_type = ?, station_ids = ?, station_times = ?
			WHERE id = ?
		""".trimIndent()
		ConnectionPool.getConnection().use { conn ->
			conn.prepareStatement(sql).use { stmt ->
				stmt.setString(1, line.name)
				stmt.setString(2, line.color)
				stmt.setString(3, line.lineType.name)
				stmt.setString(4, gson.toJson(line.stationIds))
				stmt.setString(5, gson.toJson(line.stationTimes))
				stmt.setInt(6, lineId)
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
								name = rs.getString("name"),
								color = rs.getString("color")
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
							name = rs.getString("name"),
							color = rs.getString("color")
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
								name = rs.getString("name"),
								color = rs.getString("color")
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
		val startStation = stationMap[startStationId] ?: return null
		val endStation = stationMap[endStationId] ?: return null
		if (!stationAllowed(startStation, constraints) || !stationAllowed(endStation, constraints)) {
			return null
		}

		val adjacency = mutableMapOf<String, MutableList<Edge>>()
		for (line in listLines()) {
			if (constraints.bannedLineTypes.contains(line.lineType)) continue
			if (line.stationIds.size < 2 || line.stationTimes.size != line.stationIds.size - 1) continue
			for (i in 0 until line.stationIds.size - 1) {
				val from = line.stationIds[i]
				val to = line.stationIds[i + 1]
				val time = line.stationTimes[i].coerceAtLeast(0)
				val fromStation = stationMap[from]
				val toStation = stationMap[to]
				if (!stationAllowed(fromStation, constraints) || !stationAllowed(toStation, constraints)) continue
				val edge = Edge(
					to = to,
					time = time,
					lineId = line.id,
					lineName = line.name,
					lineType = line.lineType,
					color = line.color
				)
				adjacency.getOrPut(from) { mutableListOf() }.add(edge)
				adjacency.getOrPut(to) { mutableListOf() }.add(
					edge.copy(to = from)
				)
			}
		}

		val dist = mutableMapOf<String, Int>()
		val prev = mutableMapOf<String, PrevEdge>()
		val pq = PriorityQueue<Node>(compareBy { it.dist })
		dist[startStationId] = 0
		pq.add(Node(startStationId, 0))

		while (pq.isNotEmpty()) {
			val current = pq.poll()
			val currentDist = dist[current.id] ?: continue
			if (current.dist != currentDist) continue
			if (current.id == endStationId) break
			val edges = adjacency[current.id] ?: continue
			for (edge in edges) {
				val newDist = currentDist + edge.time
				val oldDist = dist[edge.to]
				if (oldDist == null || newDist < oldDist) {
					dist[edge.to] = newDist
					prev[edge.to] = PrevEdge(from = current.id, edge = edge)
					pq.add(Node(edge.to, newDist))
				}
			}
		}

		if (startStationId != endStationId && !prev.containsKey(endStationId)) {
			return null
		}

		val stationPath = mutableListOf<String>()
		val edgePath = mutableListOf<Edge>()
		var currentId = endStationId
		stationPath.add(currentId)
		while (currentId != startStationId) {
			val prevEdge = prev[currentId] ?: return null
			edgePath.add(prevEdge.edge.copy(to = currentId))
			currentId = prevEdge.from
			stationPath.add(currentId)
		}
		stationPath.reverse()
		edgePath.reverse()

		val segments = mutableListOf<RouteSegment>()
		val transfers = mutableListOf<TransferPoint>()
		var transferTimeTotal = 0
		if (edgePath.isNotEmpty()) {
			var currentLineId = edgePath[0].lineId
			var currentLineName = edgePath[0].lineName
			var currentLineType = edgePath[0].lineType
			var currentColor = edgePath[0].color
			var segmentTime = 0
			var segmentStations = mutableListOf<String>()
			segmentStations.add(stationPath[0])
			for (i in edgePath.indices) {
				val edge = edgePath[i]
				if (edge.lineId != currentLineId) {
					segmentTime += TRANSFER_TIME_SECONDS
					transferTimeTotal += TRANSFER_TIME_SECONDS
					segments.add(
						RouteSegment(
							lineId = currentLineId,
							lineName = currentLineName,
							lineType = currentLineType,
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
					currentLineType = edge.lineType
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
					lineType = currentLineType,
					color = currentColor,
					stationIds = segmentStations.toList(),
					time = segmentTime
				)
			)
		}

		val totalTime = edgePath.sumOf { it.time } + transferTimeTotal
		val lineIds = segments.map { it.lineId }
		return RouteResult(
			stationIds = stationPath,
			stations = stationPath.mapNotNull { stationMap[it] },
			lineIds = lineIds,
			segments = segments,
			transfers = transfers,
			totalTime = totalTime,
			totalStops = stationPath.size - 1
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
							SCREEN_LOCATION = parseLocations(rs.getString("screen_location"))
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
		return runCatching { LineType.valueOf(value) }.getOrNull()
	}

	private fun validateLineOrThrow(line: Line) {
		require(line.stationIds.size >= 2) { "stationIds must have at least 2 stations" }
		require(line.stationTimes.size == line.stationIds.size - 1) { "stationTimes length must be stationIds.size - 1" }
	}

	private fun stationAllowed(station: Station?, constraints: RouteConstraints): Boolean {
		if (station == null) return false
		if (constraints.bannedDimensions.isEmpty()) return true
		if (station.SCREEN_LOCATION.isEmpty()) return true
		return station.SCREEN_LOCATION.any { !constraints.bannedDimensions.contains(it.world) }
	}

	private data class Edge(
		val to: String,
		val time: Int,
		val lineId: Int,
		val lineName: String,
		val lineType: LineType,
		val color: String,
	)

	private data class PrevEdge(
		val from: String,
		val edge: Edge,
	)

	private data class Node(
		val id: String,
		val dist: Int,
	)
}
