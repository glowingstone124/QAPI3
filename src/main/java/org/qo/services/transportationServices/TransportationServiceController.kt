package org.qo.services.transportationServices

import com.google.gson.Gson
import com.google.gson.JsonObject
import org.qo.utils.ReturnInterface
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.Locale

@RestController
@RequestMapping("/qo/transportation")
class TransportationServiceController(
	private val service: TransportationServiceImpl
) {
	val gson = Gson()

	@GetMapping("/station/id")
	fun getStationById(@RequestParam id: String): ResponseEntity<String> {
		val result = service.getStationById(id) ?: return notFound()
		return ReturnInterface().GeneralHttpHeader(gson.toJson(result))
	}

	@GetMapping("/station/all")
	fun getAllStations(): ResponseEntity<String> {
		return ReturnInterface().GeneralHttpHeader(gson.toJson(service.listStations()))
	}

	@GetMapping("/station/name")
	fun getStationByName(@RequestParam name: String): ResponseEntity<String> {
		return ReturnInterface().GeneralHttpHeader(
			gson.toJson(service.queryStationsByName(name))
		)
	}

	@GetMapping("/line/id")
	fun getLineStationsById(@RequestParam id: Int): ResponseEntity<String> {
		return ReturnInterface().GeneralHttpHeader(
			gson.toJson(service.queryStationsByLineId(id))
		)
	}

	@GetMapping("/line/name")
	fun getLineStationsByName(@RequestParam name: String): ResponseEntity<String> {
		return ReturnInterface().GeneralHttpHeader(
			gson.toJson(service.queryStationsByLineName(name))
		)
	}

	/*
	* {
	*   start: 1,
	*   end: 2,
	*   banned_dims: ["NETHER","THE_END"],
	*   banned_types: ["WALK","AIRPLANE"],
	*   exclude_dims: ["OVERWORLD"],
	*   exclude_types: ["boat"]
	* }
	* */
	data class CalcReq(
		val start: String,
		val end: String,
		val banned_dims: List<String>? = null,
		val banned_types: List<String>? = null,
		val exclude_dims: List<String>? = null,
		val exclude_types: List<String>? = null,
	)

	@PostMapping("/calculate", consumes = ["application/json"])
	fun calculateRoute(@RequestBody req: CalcReq): ResponseEntity<String> {
		val excludedDims = mergeFilters(req.banned_dims, req.exclude_dims)
		val excludedTypes = mergeFilters(req.banned_types, req.exclude_types)
		val result = service.calculateRoute(
			req.start,
			req.end,
			RouteConstraints(
				convertToDimSet(excludedDims),
				convertToLineTypeSet(excludedTypes)
			)
		) ?: return notFound()

		return ReturnInterface().GeneralHttpHeader(gson.toJson(result))
	}

	fun notFound(): ResponseEntity<String> {
		return ReturnInterface().GeneralHttpHeader(JsonObject().apply {
			addProperty("result", "-1")
		}.toString())
	}

	private fun mergeFilters(primary: List<String>?, secondary: List<String>?): List<String> {
		return linkedSetOf<String>().apply {
			addAll(primary.orEmpty())
			addAll(secondary.orEmpty())
		}.toList()
	}

	fun convertToDimSet(list: List<String>): Set<Dimension> {
		return list.map { parseDimension(it) }.toSet()
	}

	fun convertToLineTypeSet(list: List<String>): Set<LineType> {
		return list.map { parseLineType(it) }.toSet()
	}

	private fun parseDimension(value: String): Dimension {
		return when (normalizeEnumToken(value)) {
			"OVERWORLD" -> Dimension.OVERWORLD
			"NETHER" -> Dimension.NETHER
			"THE_END", "THEEND", "END" -> Dimension.THE_END
			else -> throw IllegalArgumentException("Unknown dimension: $value")
		}
	}

	private fun parseLineType(value: String): LineType {
		val normalized = normalizeEnumToken(value)
		return LineType.entries.firstOrNull {
			it.name == normalized || it.name.replace("_", "") == normalized.replace("_", "")
		} ?: throw IllegalArgumentException("Unknown line type: $value")
	}

	private fun normalizeEnumToken(value: String): String {
		return value
			.substringAfter(':')
			.trim()
			.replace("-", "_")
			.replace(" ", "_")
			.uppercase(Locale.ROOT)
	}
}
