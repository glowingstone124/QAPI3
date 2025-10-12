package org.qo.services.eliteWeaponServices

import com.google.gson.JsonObject
import org.qo.utils.ReturnInterface
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/qo/elite")
class EliteWeaponController(
	private val impl: EliteWeaponImpl,
	private val ri: ReturnInterface
) {
	@GetMapping("/download")
	fun download(@RequestParam username: String): ResponseEntity<String> {
		return ri.GeneralHttpHeader(impl.getEliteWeaponsFromUsername(username))
	}

	@GetMapping("/create")
	fun create(@RequestParam owner: String, @RequestParam type: String, @RequestParam description: String): ResponseEntity<String> {
		impl.handleEliteWeaponRequest(owner, type, description)?.let {
			return ri.GeneralHttpHeader(JsonObject().apply {
				addProperty("result", true)
				addProperty("uuid", it)
			}.toString())
		}
		return  ri.GeneralHttpHeader(JsonObject().apply {
			addProperty("result", false)
		}.toString())
	}


}