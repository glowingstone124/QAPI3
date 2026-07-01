package org.qo.services.transportationServices

import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.qo.TestApiApplication
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.reactive.server.WebTestClient

@SpringBootTest(
	classes = [TestApiApplication::class, TransportationServiceController::class],
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@AutoConfigureWebTestClient
class TransportationServiceControllerTest {
	@Autowired
	lateinit var webTestClient: WebTestClient

	@MockitoBean
	lateinit var service: TransportationServiceImpl

	@Test
	fun getStationById_returnsStation() {
		val station = Station(
			NAME = "Central",
			ID = "S1",
			SCREEN_LOCATION = arrayOf(Location(1.0, 2.0, 3.0, Dimension.OVERWORLD)),
			NAME_EN = "Central"
		)
		Mockito.`when`(service.getStationById("S1")).thenReturn(station)

		webTestClient.get()
			.uri("/qo/transportation/station/id?id=S1")
			.exchange()
			.expectStatus().isOk
			.expectBody()
			.jsonPath("$.id").isEqualTo("S1")
			.jsonPath("$.name").isEqualTo("Central")
			.jsonPath("$.dimensions[0]").isEqualTo("OVERWORLD")
	}

	@Test
	fun getStationById_returnsNotFoundPayloadWhenMissing() {
		Mockito.`when`(service.getStationById("missing")).thenReturn(null)

		webTestClient.get()
			.uri("/qo/transportation/station/id?id=missing")
			.exchange()
			.expectStatus().isOk
			.expectBody()
			.jsonPath("$.result").isEqualTo("-1")
	}

	@Test
	fun getAllDimensions_returnsEnumList() {
		webTestClient.get()
			.uri("/qo/transportation/dimension/all")
			.exchange()
			.expectStatus().isOk
			.expectBody()
			.jsonPath("$[0]").isEqualTo("OVERWORLD")
			.jsonPath("$[1]").isEqualTo("NETHER")
			.jsonPath("$[2]").isEqualTo("THE_END")
	}

	@Test
	fun getLineDetailById_returnsLineAndTransferLines() {
		val detail = LineDetail(
			line = TransferLineInfo(
				id = 1,
				lineType = LineType.METRO,
				dimension = Dimension.OVERWORLD,
				name = "Metro A",
				nameEn = "Metro A",
				color = "#ff0000"
			),
			stations = listOf(
				LineStationDetail(
					id = "S1",
					name = "Central",
					nameEn = "Central",
					transferLines = listOf(
						TransferLineInfo(
							id = 2,
							lineType = LineType.RAPID,
							dimension = Dimension.NETHER,
							name = "Rapid B",
							nameEn = "Rapid B",
							color = "#00ff00"
						)
					)
				)
			)
		)
		Mockito.`when`(service.queryLineDetailById(1)).thenReturn(detail)

		webTestClient.get()
			.uri("/qo/transportation/line/detail?id=1")
			.exchange()
			.expectStatus().isOk
			.expectBody()
			.jsonPath("$.line.name").isEqualTo("Metro A")
			.jsonPath("$.line.color").isEqualTo("#ff0000")
			.jsonPath("$.stations[0].name").isEqualTo("Central")
			.jsonPath("$.stations[0].transfer_lines[0].type").isEqualTo("RAPID")
			.jsonPath("$.stations[0].transfer_lines[0].dimension").isEqualTo("NETHER")
			.jsonPath("$.stations[0].transfer_lines[0].name").isEqualTo("Rapid B")
			.jsonPath("$.stations[0].transfer_lines[0].color").isEqualTo("#00ff00")
	}

	@Test
	fun calculateRoute_returnsRouteResult() {
		val expectedConstraints = RouteConstraints(
			bannedDimensions = setOf(Dimension.THE_END, Dimension.OVERWORLD),
			bannedLineTypes = setOf(LineType.WALK)
		)
		val routeResult = RouteResult(
			stationIds = listOf("S1", "S2"),
			stations = emptyList(),
			lineIds = listOf(1),
			segments = listOf(
				RouteSegment(
					lineId = 1,
					lineName = "Metro A",
					lineNameEn = "Metro A",
					lineType = LineType.METRO,
					color = "#000000",
					stationIds = listOf("S1", "S2"),
					time = 30
				)
			),
			transfers = emptyList(),
			totalTime = 30,
			totalStops = 1
		)
		Mockito.`when`(
			service.calculateRoute(
				"S1",
				"S2",
				expectedConstraints
			)
		).thenReturn(routeResult)

			val body = """
				{
					"start": "S1",
					"end": "S2",
					"exclude_dims": ["minecraft:the_end", "overworld"],
					"exclude_types": ["walk"]
				}
			""".trimIndent()

		webTestClient.post()
			.uri("/qo/transportation/calculate")
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue(body)
			.exchange()
			.expectStatus().isOk
			.expectBody()
			.jsonPath("$.totalStops").isEqualTo(1)
			.jsonPath("$.totalTime").isEqualTo(30)

		Mockito.verify(service).calculateRoute(
			"S1",
			"S2",
			expectedConstraints
		)
	}
}
