package org.qo.services.advancementServices

import com.google.gson.Gson
import com.google.gson.JsonObject
import org.qo.datas.Enumerations.AdvancementsEnum
import org.qo.datas.Nodes
import org.qo.utils.ReturnInterface
import org.qo.utils.SerializeUtils.convertToJsonArray
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/qo/advancement")
class AdvancementServiceController(
	private val advancementServiceImpl: AdvancementServiceImpl,
	private val nodes: Nodes,
) {
	private val gson = Gson()
	private val ri = ReturnInterface()

	data class AdvancementEventBody(
		val player: String,
		val advancement: Long,
	)

	@PostMapping("/upload")
	suspend fun handleAdvancementUpload(
		@RequestBody data: String,
		@RequestHeader("Token") token: String,
	): ResponseEntity<String> {
		if (nodes.getServerFromToken(token) != 1) {
			return response("invalid provider")
		}
		val eventBody = gson.fromJson(data, AdvancementEventBody::class.java)
		val advancementEnumeration = AdvancementsEnum.fromId(eventBody.advancement)
			?: return response("invalid advancement")

		return when (advancementServiceImpl.addAdvancementCompletion(advancementEnumeration, eventBody.player)) {
			AdvancementServiceImpl.AddAdvancementResult.SUCCESS -> response("", true)
			AdvancementServiceImpl.AddAdvancementResult.FAILED -> response("failed")
			AdvancementServiceImpl.AddAdvancementResult.INVALID_PLAYER -> response("player invalid")
			AdvancementServiceImpl.AddAdvancementResult.ALREADY_EXISTS -> response("already achieved advancement")
		}
	}

	private fun response(error: String, result: Boolean = false): ResponseEntity<String> =
		ri.GeneralHttpHeader(JsonObject().apply {
			addProperty("error", error)
			addProperty("result", result)
		}.toString())

	@GetMapping("/all")
	suspend fun getAllAdvancements(): ResponseEntity<String> =
		ri.GeneralHttpHeader(advancementServiceImpl.getAllAdvancements().convertToJsonArray().toString())

	@GetMapping("/completed")
	suspend fun getCompletedAdvancements(@RequestParam name: String): ResponseEntity<String> =
		ri.GeneralHttpHeader(advancementServiceImpl.getCompleteAdvancements(name).convertToJsonArray().toString())
}
