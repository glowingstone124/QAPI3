package org.qo.services.fallenServices

import com.google.gson.JsonObject
import org.qo.datas.Nodes
import org.qo.utils.AuthTokens
import org.qo.utils.ReturnInterface
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/qo")
class FallenTeamController(
	private val fallenTeamService: FallenTeamService,
	private val nodes: Nodes,
	private val ri: ReturnInterface
) {
	@GetMapping("/authorization/fallen/team")
	suspend fun currentSelection(
		@RequestHeader("token", required = false) token: String?,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?
	): ResponseEntity<String> {
		val resolved = AuthTokens.resolve(token, authorization) ?: return unauthorized()
		val (username, selection) = fallenTeamService.selectionForToken(resolved)
		if (username == null) return unauthorized()
		return ri.GeneralHttpHeader(selectionJson(selection).toString())
	}

	@PostMapping("/authorization/fallen/team")
	suspend fun selectTeam(
		@RequestHeader("token", required = false) token: String?,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@RequestBody body: String
	): ResponseEntity<String> {
		val resolved = AuthTokens.resolve(token, authorization) ?: return unauthorized()
		val (username, result) = fallenTeamService.select(resolved, body)
		if (username == null || result == null) return unauthorized()
		return when (result) {
			is FallenSelectionResult.Selected -> ri.GeneralHttpHeader(selectionJson(result.selection).apply {
				addProperty("message", "阵营选择成功，选择后不可更改。")
			}.toString())
			is FallenSelectionResult.AlreadySelected -> ri.GeneralHttpHeader(selectionJson(result.selection).apply {
				addProperty("code", "already_selected")
				addProperty("message", "你已经选择过阵营，无法再次更改。")
			}.toString(), HttpStatus.CONFLICT)
			FallenSelectionResult.InvalidTeam -> ri.GeneralHttpHeader(JsonObject().apply {
				addProperty("code", "invalid_team")
				addProperty("message", "阵营只能是 A、B 或 C。")
			}.toString(), HttpStatus.BAD_REQUEST)
			FallenSelectionResult.RegistrationClosed -> ri.GeneralHttpHeader(JsonObject().apply {
				addProperty("code", "registration_closed")
				addProperty("message", "阵营意向登记已结束，正式阵营正在分配。")
			}.toString(), HttpStatus.GONE)
		}
	}

	@GetMapping("/fallen/team")
	suspend fun serverSelection(
		@RequestParam username: String,
		@RequestHeader("token", required = false) token: String?,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?
	): ResponseEntity<String> {
		val resolved = AuthTokens.resolve(token, authorization)
		if (resolved == null || nodes.getServerFromToken(resolved) != 1) return unauthorized()
		return ri.GeneralHttpHeader(selectionJson(fallenTeamService.selectionForUsername(username)).toString())
	}

	private fun selectionJson(selection: FallenTeamSelection?): JsonObject = JsonObject().apply {
		addProperty("selected", selection != null)
		if (selection != null) {
			addProperty("username", selection.username)
			addProperty("team", selection.team.name)
			addProperty("expectedTeam", selection.expectedTeam.name)
			addProperty("finalized", selection.finalized)
			addProperty("selectedAt", selection.selectedAt)
			selection.assignedAt?.let { addProperty("assignedAt", it) }
		}
	}

	private fun unauthorized(): ResponseEntity<String> = ri.GeneralHttpHeader(JsonObject().apply {
		addProperty("code", "unauthorized")
		addProperty("message", "登录状态已失效，请重新登录。")
	}.toString(), HttpStatus.UNAUTHORIZED)
}
