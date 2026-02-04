package org.qo.services.transportationServices

import com.google.gson.Gson
import com.google.gson.JsonObject
import org.qo.utils.ReturnInterface
import org.qo.utils.SerializeUtils.convertToArrayList
import org.springframework.http.ResponseEntity
import org.springframework.util.RouteMatcher
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

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
	*   banned_types: ["WALK","AIRPLANE"]
	* }
	* */
	data class CalcReq(
		val start: String,
		val end: String,
		val banned_dims: List<String> = emptyList(),
		val banned_types: List<String> = emptyList()
	)
	@PostMapping("/calculate", consumes = ["application/json"])
	fun calculateRoute(@RequestBody req: CalcReq): ResponseEntity<String> {
		val result = service.calculateRoute(
			req.start,
			req.end,
			RouteConstraints(
				convertToDimSet(ArrayList(req.banned_dims)),
				convertToLineTypeSet(ArrayList(req.banned_types))
			)
		) ?: return notFound()

		return ReturnInterface().GeneralHttpHeader(gson.toJson(result))
	}

	fun notFound(): ResponseEntity<String> {
		return ReturnInterface().GeneralHttpHeader(JsonObject().apply {
			addProperty("result", "-1")
		}.toString())
	}

	fun convertToDimSet(list: ArrayList<String>): Set<Dimension> {
		val result = mutableSetOf<Dimension>()
		list.forEach {
			result.add(Dimension.valueOf(it))
		}
		return result
	}fun convertToLineTypeSet(list: ArrayList<String>): Set<LineType> {
		val result = mutableSetOf<LineType>()
		list.forEach {
			result.add(LineType.valueOf(it))
		}
		return result
	}
}
