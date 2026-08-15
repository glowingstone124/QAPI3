package org.qo.services.transportationServices

import io.r2dbc.spi.ConnectionFactories
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
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
		val stationPlaceholders = stationIds.joinToString(", ") { "(?, ?, ?, ?)" }
		database.execute(
			"INSERT INTO transportation_stations (id, name, name_en, screen_location) VALUES $stationPlaceholders",
			stationIds.flatMap { stationId -> listOf(stationId, stationId, stationId, "[]") },
		)

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
}
