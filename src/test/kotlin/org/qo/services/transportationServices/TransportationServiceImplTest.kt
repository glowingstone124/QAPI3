package org.qo.services.transportationServices

import io.r2dbc.spi.ConnectionFactories
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.qo.datas.ReactiveDatabase
import org.springframework.r2dbc.connection.R2dbcTransactionManager
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.transaction.reactive.TransactionalOperator

class TransportationServiceImplTest {
	private lateinit var database: ReactiveDatabase
	private lateinit var service: TransportationServiceImpl

	@BeforeEach
	fun setUp() = runTest {
		val connectionFactory = ConnectionFactories.get(
			"r2dbc:h2:mem:///transportation_${System.nanoTime()};MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
		)
		database = ReactiveDatabase(
			client = DatabaseClient.create(connectionFactory),
			transactionalOperator = TransactionalOperator.create(R2dbcTransactionManager(connectionFactory)),
		)
		service = TransportationServiceImpl(database)
		createSchema()
		insertLineOneFixture()
	}

	@Test
	fun calculateRoute_from0111To0108_usesRapidLineAndTakes58Seconds() = runTest {
		val route = requireNotNull(service.calculateRoute("0111", "0108"))

		assertEquals(58, route.totalTime)
		assertEquals(listOf("0111", "0108"), route.stationIds)
		assertEquals(listOf(2), route.lineIds)
		assertEquals(1, route.segments.size)
		assertEquals("1号线-潜影贝农场方向-快速", route.segments.single().lineName)
		assertEquals(58, route.segments.single().time)
		assertEquals(0, route.transfers.size)
	}

	@ParameterizedTest
	@EnumSource(value = LineType::class, names = ["RAPID", "WALK"])
	fun calculateRoute_preservesSegmentsAndChargesOnlyVehicleTransfers(middleType: LineType) = runTest {
		insertStations(listOf("S1", "S2", "S3", "S4", "S5"))
		database.execute(
			"""
			INSERT INTO transportation_lines (id, name, name_en, color, line_type, dimension, station_ids, station_times)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?), (?, ?, ?, ?, ?, ?, ?, ?), (?, ?, ?, ?, ?, ?, ?, ?)
			""".trimIndent(),
			listOf(
				21, "First", "First EN", "#111111", "METRO", "OVERWORLD", """["S1","S2","S3"]""", "[10,20]",
				22, "Middle", "Middle EN", "#222222", middleType.name, "NETHER", """["S3","S4"]""", "[30]",
				23, "Last", "Last EN", "#333333", "METRO", "THE_END", """["S4","S5"]""", "[5]",
			),
		)

		val route = requireNotNull(service.calculateRoute("S1", "S5"))
		val penalty = if (middleType == LineType.WALK) 0 else 15
		assertEquals(listOf("S1", "S2", "S3", "S4", "S5"), route.stationIds)
		assertEquals(route.stationIds, route.stations.map { it.ID })
		assertEquals(listOf(21, 22, 23), route.lineIds)
		assertEquals(
			listOf(
				RouteSegment(21, "First", "First EN", LineType.METRO, Dimension.OVERWORLD,
					"#111111", listOf("S1", "S2", "S3"), 30 + penalty),
				RouteSegment(22, "Middle", "Middle EN", middleType, Dimension.NETHER,
					"#222222", listOf("S3", "S4"), 30 + penalty),
				RouteSegment(23, "Last", "Last EN", LineType.METRO, Dimension.THE_END,
					"#333333", listOf("S4", "S5"), 5),
			),
			route.segments,
		)
		assertEquals(listOf(TransferPoint("S3", 21, 22), TransferPoint("S4", 22, 23)), route.transfers)
		assertEquals(65 + 2 * penalty, route.totalTime)
		assertEquals(route.totalTime, route.segments.sumOf { it.time })
		assertEquals(if (middleType == LineType.WALK) 3 else 4, route.totalStops)
	}

	@Test
	fun calculateRoute_sameStationReturnsEmptyJourney() = runTest {
		val route = requireNotNull(service.calculateRoute("0111", "0111"))
		assertEquals(listOf("0111"), route.stationIds)
		assertEquals(emptyList<RouteSegment>(), route.segments)
		assertEquals(emptyList<TransferPoint>(), route.transfers)
		assertEquals(0, route.totalTime)
		assertEquals(0, route.totalStops)
	}

	@Test
	fun ensureTables_addsLegacyColumnsAndRemainsIdempotentAfterRestart() = runTest {
		database.execute("DROP TABLE transportation_lines")
		database.execute("DROP TABLE transportation_stations")
		createLegacySchema()
		database.execute(
			"INSERT INTO transportation_stations (id, name, screen_location) VALUES (?, ?, ?)",
			listOf("S1", "Central", "[]"),
		)

		service.ensureTables()
		TransportationServiceImpl(database).ensureTables()

		val station = requireNotNull(service.getStationById("S1"))
		assertEquals("Central", station.NAME)
		assertEquals("", station.NAME_EN)
		assertEquals(0, station.SCREEN_LOCATION.size)
	}

	private suspend fun createSchema() {
		database.execute(
			"""
			CREATE TABLE transportation_stations (
				id VARCHAR(64) PRIMARY KEY,
				name VARCHAR(255) NOT NULL,
				name_en VARCHAR(255) NOT NULL,
				screen_location TEXT NOT NULL
			)
			""".trimIndent(),
		)
		database.execute(
			"""
			CREATE TABLE transportation_lines (
				id INT PRIMARY KEY,
				name VARCHAR(255) NOT NULL,
				name_en VARCHAR(255) NOT NULL,
				color VARCHAR(32) NOT NULL,
				line_type VARCHAR(32) NOT NULL,
				dimension VARCHAR(32) NOT NULL,
				station_ids TEXT NOT NULL,
				station_times TEXT NOT NULL
			)
			""".trimIndent(),
		)
	}

	private suspend fun createLegacySchema() {
		database.execute(
			"""
			CREATE TABLE transportation_stations (
				id VARCHAR(64) PRIMARY KEY,
				name VARCHAR(255) NOT NULL,
				screen_location TEXT NOT NULL
			)
			""".trimIndent(),
		)
		database.execute(
			"""
			CREATE TABLE transportation_lines (
				id INT PRIMARY KEY,
				name VARCHAR(255) NOT NULL,
				color VARCHAR(32) NOT NULL,
				line_type VARCHAR(32) NOT NULL,
				station_ids TEXT NOT NULL,
				station_times TEXT NOT NULL
			)
			""".trimIndent(),
		)
	}

	private suspend fun insertLineOneFixture() {
		val stationIds = listOf(
			"0111",
			"0110",
			"0109",
			"0108",
			"0107",
			"0106",
			"0112",
			"0105",
			"0104",
			"0103",
			"0102",
			"0101",
		)
		insertStations(stationIds)

		database.execute(
			"""
			INSERT INTO transportation_lines (id, name, name_en, color, line_type, dimension, station_ids, station_times)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?), (?, ?, ?, ?, ?, ?, ?, ?)
			""".trimIndent(),
			listOf(
				1,
				"1号线-潜影贝农场方向-普通",
				"Line 1 Local",
				"#E4002B",
				"0",
				"OVERWORLD",
				"""["0111","0110","0109","0108","0107","0106","0112","0105","0104","0103","0102","0101"]""",
				"[25,22,27,26,14,10,13,48,30,21,22]",
				2,
				"1号线-潜影贝农场方向-快速",
				"Line 1 Rapid",
				"#E4002B",
				"0",
				"OVERWORLD",
				"""["0111","0108","0107","0106","0105","0104","0103","0102","0101"]""",
				"[58,26,14,10,13,48,30,21,22]",
			),
		)
	}

	private suspend fun insertStations(ids: List<String>) {
		val placeholders = ids.joinToString(", ") { "(?, ?, ?, ?)" }
		database.execute(
			"INSERT INTO transportation_stations (id, name, name_en, screen_location) VALUES $placeholders",
			ids.flatMap { id -> listOf(id, id, id, "[]") },
		)
	}
}
