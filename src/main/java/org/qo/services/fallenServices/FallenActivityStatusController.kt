package org.qo.services.fallenServices

import com.google.gson.JsonObject
import org.qo.datas.Nodes
import org.qo.utils.AuthTokens
import org.qo.utils.ReturnInterface
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/qo/fallen/status")
class FallenActivityStatusController(
	private val statusService: FallenActivityStatusService,
	private val fallenTeamService: FallenTeamService,
	private val nodes: Nodes,
	private val ri: ReturnInterface
) {
	@GetMapping
	suspend fun status(): ResponseEntity<String> = ResponseEntity.ok()
		.cacheControl(CacheControl.noStore())
		.contentType(MediaType.APPLICATION_JSON)
		.body(statusService.statusJson(fallenTeamService.finalizedRoster()).toString())

	@PostMapping
	fun upload(
		@RequestHeader("token", required = false) token: String?,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@RequestBody body: String
	): ResponseEntity<String> {
		val resolved = AuthTokens.resolve(token, authorization)
		if (resolved == null || nodes.getServerFromToken(resolved) != SURVIVAL_SERVER_ID) {
			return response("unauthorized", "仅生存服可以上报活动状态。", HttpStatus.UNAUTHORIZED)
		}
		if (!statusService.update(body)) {
			return response("invalid_snapshot", "活动状态格式无效。", HttpStatus.BAD_REQUEST)
		}
		return ri.GeneralHttpHeader(JsonObject().apply { addProperty("ok", true) }.toString())
	}

	private fun response(code: String, message: String, status: HttpStatus): ResponseEntity<String> {
		return ri.GeneralHttpHeader(JsonObject().apply {
			addProperty("code", code)
			addProperty("message", message)
		}.toString(), status)
	}

	private companion object {
		const val SURVIVAL_SERVER_ID = 1
	}
}
