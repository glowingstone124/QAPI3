package org.qo.services.eliteWeaponServices

import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.qo.datas.Nodes
import org.qo.utils.ReturnInterface
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RequestHeader

@RestController
@RequestMapping("/qo/elite")
class EliteWeaponController(
	private val impl: EliteWeaponImpl,
	private val ri: ReturnInterface,
	private val nodes: Nodes
) {
	@GetMapping("/download")
	suspend fun download(@RequestParam username: String): ResponseEntity<String> {
		return withContext(Dispatchers.IO) {
			ri.GeneralHttpHeader(impl.getEliteWeaponsFromUsername(username))
		}
	}

	@PostMapping("/create")
	suspend fun create(@RequestParam owner: String, @RequestParam type: String, @RequestParam description: String,@RequestParam name: String,
	                  @RequestHeader("Token") token: String): ResponseEntity<String> {
		if (nodes.getServerFromToken(token) < 0) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("{\"code\":401}")
		val uuid = withContext(Dispatchers.IO) {
			impl.handleEliteWeaponRequest(owner, type, description, name)
		}
		uuid?.let {
			return ri.GeneralHttpHeader(JsonObject().apply {
				addProperty("result", true)
				addProperty("uuid", it)
			}.toString())
		}
		return  ri.GeneralHttpHeader(JsonObject().apply {
			addProperty("result", false)
		}.toString())
	}

	@PostMapping("/batch")
	suspend fun batch(
		@RequestParam requester: String,
		@RequestParam uuid: String,
		@RequestParam damage: Long,
		@RequestParam kills: Long,
		@RequestHeader("Token") token: String
	): ResponseEntity<String> {
		if (nodes.getServerFromToken(token) < 0) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("{\"code\":401}")
		if (damage !in 0..MAX_UPDATE_AMOUNT || kills !in 0..MAX_UPDATE_AMOUNT || damage == 0L && kills == 0L) {
			return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("{\"code\":400,\"message\":\"invalid stats\"}")
		}
		return withContext(Dispatchers.IO) {
			ri.GeneralHttpHeader(impl.addEliteWeaponStats(uuid, requester, damage, kills))
		}
	}

	@GetMapping("/query")
	suspend fun query(@RequestParam uuid: String): ResponseEntity<String> {
		return withContext(Dispatchers.IO) {
			ri.GeneralHttpHeader(impl.queryEliteUuid(uuid))
		}
	}

	companion object {
		private const val MAX_UPDATE_AMOUNT = 1_000_000_000L
	}
}
