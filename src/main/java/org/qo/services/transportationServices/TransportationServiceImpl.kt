package org.qo.services.transportationServices

import org.qo.datas.ReactiveDatabase
import org.qo.db.repository.TransportationDbRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.util.PriorityQueue

@Service
class TransportationServiceImpl @Autowired constructor(
	private val repository: TransportationDbRepository,
) {
	constructor(database: ReactiveDatabase) : this(TransportationDbRepository(database))

	private val transferTimeSeconds = 15

	suspend fun ensureTables() = repository.ensureTables()

	suspend fun addStation(station: Station): Boolean = repository.addStation(station)

	suspend fun editStation(station: Station): Boolean = repository.editStation(station)

	suspend fun removeStation(id: String): Boolean = repository.removeStation(id)

	suspend fun listStations(): List<Station> = repository.listStations()

	suspend fun getStationById(id: String): Station? = repository.getStationById(id)

	suspend fun queryStationsByName(name: String, fuzzy: Boolean = true): List<Station> =
		repository.queryStationsByName(name, fuzzy)

	suspend fun addLine(line: Line): Int? {
		validateLineOrThrow(line)
		return repository.addLine(line)
	}

	suspend fun editLine(lineId: Int, line: Line): Boolean {
		validateLineOrThrow(line)
		return repository.editLine(lineId, line)
	}

	suspend fun removeLine(lineId: Int): Boolean = repository.removeLine(lineId)

	suspend fun listLines(): List<LineRecord> = repository.listLines()

	suspend fun getLineById(lineId: Int): LineRecord? = repository.getLineById(lineId)

	suspend fun queryLinesByName(name: String, fuzzy: Boolean = true): List<LineRecord> =
		repository.queryLinesByName(name, fuzzy)

	suspend fun queryStationsByLineId(lineId: Int): List<Station> {
		val line = getLineById(lineId) ?: return emptyList()
		return stationsForLine(line)
	}

	suspend fun queryStationsByLineName(name: String, fuzzy: Boolean = true): List<LineStations> =
		queryLinesByName(name, fuzzy).map { line ->
			LineStations(line, stationsForLine(line))
		}

	private suspend fun stationsForLine(line: LineRecord): List<Station> {
		val stationMap = repository.fetchStationsByIds(line.stationIds.toList())
		return line.stationIds.mapNotNull { stationMap[it] }
	}

	suspend fun queryLineDetailById(lineId: Int): LineDetail? {
		val line = getLineById(lineId) ?: return null
		val stationMap = repository.fetchStationsByIds(line.stationIds.toList())
		val transferLinesByStation = listLines()
			.asSequence()
			.filter { it.id != line.id }
			.flatMap { transferLine -> transferLine.stationIds.asSequence().map { stationId -> stationId to transferLine } }
			.groupBy(
				keySelector = { it.first },
				valueTransform = { it.second.toTransferLineInfo() },
			)
			.mapValues { (_, transferLines) ->
				transferLines.distinctBy { it.id }.sortedBy { it.id }
			}

		return LineDetail(
			line = line.toTransferLineInfo(),
			stations = line.stationIds.mapNotNull { stationId ->
				stationMap[stationId]?.let { station ->
					LineStationDetail(
						id = station.ID,
						name = station.NAME,
						nameEn = station.NAME_EN,
						transferLines = transferLinesByStation[station.ID].orEmpty(),
					)
				}
			},
		)
	}

	suspend fun calculateRoute(
		startStationId: String,
		endStationId: String,
		constraints: RouteConstraints = RouteConstraints(),
	): RouteResult? {
		val stationMap = listStations().associateBy { it.ID }
		if (!stationMap.containsKey(startStationId) || !stationMap.containsKey(endStationId)) return null

		val adjacency = mutableMapOf<String, MutableList<Edge>>()
		val lineTypeById = mutableMapOf<Int, LineType>()
		for (line in listLines()) {
			if (constraints.bannedLineTypes.contains(line.lineType)) continue
			if (constraints.bannedDimensions.contains(line.dimension)) continue
			if (line.stationIds.size < 2 || line.stationTimes.size < line.stationIds.size - 1) continue
			lineTypeById[line.id] = line.lineType
			for (i in 0 until line.stationIds.size - 1) {
				val from = line.stationIds[i]
				val to = line.stationIds[i + 1]
				val time = line.stationTimes[i].coerceAtLeast(0)
				if (!stationMap.containsKey(from) || !stationMap.containsKey(to)) continue
				adjacency.getOrPut(from) { mutableListOf() }.add(Edge(to, time, line))
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
				val transferPenalty = if (current.state.lineId != null && current.state.lineId != edge.line.id) {
					val fromType = lineTypeById[current.state.lineId]
					transferCost(fromType, edge.line.lineType)
				} else {
					0
				}
				val newDist = currentDist + edge.time + transferPenalty
				val nextState = State(edge.to, edge.line.id)
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
			edgePath.add(prevEdge.edge)
			currentState = prevEdge.from
			stationPath.add(currentState.stationId)
		}
		stationPath.reverse()
		edgePath.reverse()

		val segments = mutableListOf<RouteSegment>()
		val transfers = mutableListOf<TransferPoint>()
		var transferTimeTotal = 0
		if (edgePath.isNotEmpty()) {
			var currentLine = edgePath[0].line
			var segmentTime = 0
			var segmentStations = mutableListOf(stationPath[0])
			for (i in edgePath.indices) {
				val edge = edgePath[i]
				if (edge.line.id != currentLine.id) {
					val transferPenalty = transferCost(currentLine.lineType, edge.line.lineType)
					segmentTime += transferPenalty
					transferTimeTotal += transferPenalty
					segments.add(currentLine.toRouteSegment(segmentStations, segmentTime))
					val transferStationId = stationPath[i]
					transfers.add(TransferPoint(transferStationId, currentLine.id, edge.line.id))
					currentLine = edge.line
					segmentTime = 0
					segmentStations = mutableListOf(transferStationId)
				}
				segmentTime += edge.time
				segmentStations.add(edge.to)
			}
			segments.add(currentLine.toRouteSegment(segmentStations, segmentTime))
		}

		return RouteResult(
			stationIds = stationPath,
			stations = stationPath.mapNotNull { stationMap[it] },
			lineIds = segments.map { it.lineId },
			segments = segments,
			transfers = transfers,
			totalTime = edgePath.sumOf { it.time } + transferTimeTotal,
			totalStops = edgePath.count { it.line.lineType != LineType.WALK },
		)
	}

	private fun transferCost(from: LineType?, to: LineType): Int =
		if (from == LineType.WALK || to == LineType.WALK) 0 else transferTimeSeconds

	private fun LineRecord.toRouteSegment(stations: List<String>, time: Int): RouteSegment = RouteSegment(
		lineId = id,
		lineName = name,
		lineNameEn = name_en,
		lineType = lineType,
		dimension = dimension,
		color = color,
		stationIds = stations.toList(),
		time = time,
	)

	private fun validateLineOrThrow(line: Line) {
		require(line.stationIds.size >= 2) { "stationIds must have at least 2 stations" }
		require(line.stationTimes.size == line.stationIds.size - 1) { "stationTimes length must be stationIds.size - 1" }
	}

	private fun LineRecord.toTransferLineInfo(): TransferLineInfo {
		return TransferLineInfo(
			id = id,
			lineType = lineType,
			dimension = dimension,
			name = name,
			nameEn = name_en,
			color = color,
		)
	}

	private data class Edge(
		val to: String,
		val time: Int,
		val line: LineRecord,
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
