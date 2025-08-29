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
	private val nodes: Nodes
) {
	val gson = Gson()
	val ri = ReturnInterface()

	data class AdvancementEventBody(
		val player: String,
		val advancement: Long
	)

	@PostMapping("/upload")
	fun handleAdvancementUpload(
		@RequestBody data: String,
		@RequestHeader("Token") token: String
	): ResponseEntity<String> {
		if (nodes.getServerFromToken(token) != 1 /* QO CODE */) {
			return  ri.GeneralHttpHeader(JsonObject().apply {
				addProperty("error", "invalid provider")
				addProperty("result", false)
			}.toString())
		}
		val eventBody = gson.fromJson(data, AdvancementEventBody::class.java)

		val advancementEnumeration = AdvancementsEnum.fromId(eventBody.advancement) ?: return ri.GeneralHttpHeader(
			JsonObject().apply {
				addProperty("error", "invalid advancement")
				addProperty("result", false)
			}.toString()
		)

		val result = advancementServiceImpl.addAdvancementCompletion(advancementEnumeration, eventBody.player)
		when (result) {
			AdvancementServiceImpl.AddAdvancementResult.SUCCESS -> return ri.GeneralHttpHeader(
				JsonObject().apply {
					addProperty("error", "")
					addProperty("result", true)
				}.toString()
			)
			AdvancementServiceImpl.AddAdvancementResult.FAILED -> return ri.GeneralHttpHeader(
				JsonObject().apply {
					addProperty("error", "failed")
					addProperty("result", false)
				}.toString()
			)
			AdvancementServiceImpl.AddAdvancementResult.INVALID_PLAYER -> return ri.GeneralHttpHeader(
				JsonObject().apply {
					addProperty("error", "player invalid")
					addProperty("result", false)
				}.toString()
			)
			AdvancementServiceImpl.AddAdvancementResult.ALREADY_EXISTS -> return ri.GeneralHttpHeader(
				JsonObject().apply {
					addProperty("error", "already achieved advancement")
					addProperty("result", false)
				}.toString()
			)
		}
	}
	@GetMapping("/all")
	fun getAllAdvancements(): ResponseEntity<String> {
		return ri.GeneralHttpHeader(advancementServiceImpl.getAllAdvancements().convertToJsonArray().toString())
	}

	@GetMapping("/completed")
	fun getCompletedAdvancements(@RequestParam name: String): ResponseEntity<String> {
		return ri.GeneralHttpHeader(advancementServiceImpl.getCompleteAdvancements(name).convertToJsonArray().toString())
	}
}