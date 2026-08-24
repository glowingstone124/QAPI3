package org.qo.services.playerStatistics

import com.google.gson.Gson
import com.google.gson.JsonObject
import org.qo.datas.Nodes
import org.qo.utils.ReturnInterface
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/qo/player-statistics")
class PlayerStatisticsController(
	private val service: PlayerStatisticsService,
	private val nodes: Nodes,
	private val ri: ReturnInterface,
) {
	private val gson = Gson()

	data class UploadRequest(val players: List<PlayerStatisticsSnapshot> = emptyList())

	@PostMapping("/upload")
	suspend fun upload(
		@RequestHeader("Token", required = false) token: String?,
		@RequestBody body: String,
	): ResponseEntity<String> {
		if (token == null || nodes.getServerFromToken(token) != SURVIVAL_SERVER_ID) {
			return ri.GeneralHttpHeader("{\"code\":401}", HttpStatus.UNAUTHORIZED)
		}
		val request = runCatching { gson.fromJson(body, UploadRequest::class.java) }.getOrNull()
			?: return ri.GeneralHttpHeader("{\"code\":400,\"error\":\"invalid payload\"}", HttpStatus.BAD_REQUEST)
		return ri.GeneralHttpHeader(JsonObject().apply {
			addProperty("code", 0)
			addProperty("updated", service.upload(request.players))
		}.toString())
	}

	private companion object {
		const val SURVIVAL_SERVER_ID = 1
	}
}
