package org.qo.services.eliteWeaponServices

import com.google.gson.JsonObject
import org.qo.utils.ReturnInterface
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
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
	fun create(@RequestParam owner: String, @RequestParam type: String, @RequestParam description: String,@RequestParam name: String): ResponseEntity<String> {
		impl.handleEliteWeaponRequest(owner, type, description, name)?.let {
			return ri.GeneralHttpHeader(JsonObject().apply {
				addProperty("result", true)
				addProperty("uuid", it)
			}.toString())
		}
		return  ri.GeneralHttpHeader(JsonObject().apply {
			addProperty("result", false)
		}.toString())
	}

	@PostMapping("/add")
	fun add(@RequestParam type: String,@RequestParam requester: String, @RequestParam uuid: String, @RequestParam amount: Int): ResponseEntity<String> {
		if (type == "dmg") {
			impl.addEliteWeaponDMG(requester, uuid, amount)
		} else if (type == "kill") {
			impl.addEliteWeaponKill(requester, uuid, amount)
		} else {
			return ri.GeneralHttpHeader("Could not process request: type error")
		}
		return ri.GeneralHttpHeader("ok")
	}

	@GetMapping("/query")
	fun query(@RequestParam uuid: String): ResponseEntity<String> {
		return ri.GeneralHttpHeader(impl.queryEliteUuid(uuid))
	}

}