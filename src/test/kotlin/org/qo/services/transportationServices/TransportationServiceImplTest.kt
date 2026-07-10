package org.qo.services.transportationServices

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.qo.datas.ConnectionPool
import org.sqlite.SQLiteDataSource
import java.nio.file.Path

class TransportationServiceImplTest {
	@TempDir
	lateinit var tempDir: Path

	private val service = TransportationServiceImpl()

	@BeforeEach
	fun setUp() {
		ConnectionPool.ds = SQLiteDataSource().apply {
			url = "jdbc:sqlite:${tempDir.resolve("transportation.db")}"
		}
		createSchema()
		insertLineOneFixture()
	}

	@AfterEach
	fun tearDown() {
		ConnectionPool.ds = null
	}

	@Test
	fun calculateRoute_from0111To0108_usesRapidLineAndTakes58Seconds() {
		val route = requireNotNull(service.calculateRoute("0111", "0108"))

		assertEquals(58, route.totalTime)
		assertEquals(listOf("0111", "0108"), route.stationIds)
		assertEquals(listOf(2), route.lineIds)
		assertEquals(1, route.segments.size)
		assertEquals("1号线-潜影贝农场方向-快速", route.segments.single().lineName)
		assertEquals(58, route.segments.single().time)
		assertEquals(0, route.transfers.size)
	}

	private fun createSchema() {
		ConnectionPool.getConnection().use { conn ->
			conn.createStatement().use { stmt ->
				stmt.executeUpdate(
					"""
					CREATE TABLE transportation_stations (
						id TEXT PRIMARY KEY,
						name TEXT NOT NULL,
						name_en TEXT NOT NULL,
						screen_location TEXT NOT NULL
					)
					""".trimIndent()
				)
				stmt.executeUpdate(
					"""
					CREATE TABLE transportation_lines (
						id INTEGER PRIMARY KEY,
						name TEXT NOT NULL,
						name_en TEXT NOT NULL,
						color TEXT NOT NULL,
						line_type TEXT NOT NULL,
						dimension TEXT NOT NULL,
						station_ids TEXT NOT NULL,
						station_times TEXT NOT NULL
					)
					""".trimIndent()
				)
			}
		}
	}

	private fun insertLineOneFixture() {
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
			"0101"
		)
		ConnectionPool.getConnection().use { conn ->
			conn.prepareStatement(
				"""
				INSERT INTO transportation_stations (id, name, name_en, screen_location)
				VALUES (?, ?, ?, ?)
				""".trimIndent()
			).use { stmt ->
				for (stationId in stationIds) {
					stmt.setString(1, stationId)
					stmt.setString(2, stationId)
					stmt.setString(3, stationId)
					stmt.setString(4, "[]")
					stmt.addBatch()
				}
				stmt.executeBatch()
			}

			conn.prepareStatement(
				"""
				INSERT INTO transportation_lines
					(id, name, name_en, color, line_type, dimension, station_ids, station_times)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?)
				""".trimIndent()
			).use { stmt ->
				stmt.setInt(1, 1)
				stmt.setString(2, "1号线-潜影贝农场方向-普通")
				stmt.setString(3, "Line 1 Local")
				stmt.setString(4, "#E4002B")
				stmt.setString(5, "0")
				stmt.setString(6, "OVERWORLD")
				stmt.setString(7, """["0111","0110","0109","0108","0107","0106","0112","0105","0104","0103","0102","0101"]""")
				stmt.setString(8, "[25,22,27,26,14,10,13,48,30,21,22]")
				stmt.addBatch()

				stmt.setInt(1, 2)
				stmt.setString(2, "1号线-潜影贝农场方向-快速")
				stmt.setString(3, "Line 1 Rapid")
				stmt.setString(4, "#E4002B")
				stmt.setString(5, "0")
				stmt.setString(6, "OVERWORLD")
				stmt.setString(7, """["0111","0108","0107","0106","0105","0104","0103","0102","0101"]""")
				stmt.setString(8, "[58,26,14,10,13,48,30,21,22]")
				stmt.addBatch()

				stmt.executeBatch()
			}
		}
	}
}
