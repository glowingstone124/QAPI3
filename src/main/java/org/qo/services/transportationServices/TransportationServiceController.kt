package org.qo.services.transportationServices

import com.google.gson.Gson
import com.google.gson.JsonObject
import org.qo.utils.ReturnInterface
import org.qo.utils.SerializeUtils.convertToArrayList
import org.springframework.http.ResponseEntity
import org.springframework.util.RouteMatcher
import org.springframework.web.bind.annotation.GetMapping
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
	@GetMapping("/calculate")
	fun calculateRoute(@RequestBody data: String): ResponseEntity<String> {
		val obj = gson.toJsonTree(data).asJsonObject
		val startStationid = obj.get("start").asString
		val endStationid = obj.get("end").asString
		val rawDArr = obj.getAsJsonArray("banned_dims").asJsonArray.convertToArrayList<String>()
		val rawLArr = obj.getAsJsonArray("banned_types").asJsonArray.convertToArrayList<String>()
		val result = service.calculateRoute(
			startStationid,
			endStationid,
			RouteConstraints(
				convertToDimSet(rawDArr),
				convertToLineTypeSet(rawLArr)
			)
		)
		if (result == null) {
			return notFound()
		}
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
