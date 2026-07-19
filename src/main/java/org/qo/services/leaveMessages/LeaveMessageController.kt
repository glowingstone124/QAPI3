package org.qo.services.leaveMessages

import com.google.gson.Gson
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
@RequestMapping("/qo/leavemessage")
class LeaveMessageController(private val service: LeaveMessageService, private val nodes: Nodes) {
	val returnInterface = ReturnInterface()
	@PostMapping("/upload")
	suspend fun handleLeaveMessage(
		@RequestParam from: String,
		@RequestParam to: String,
		@RequestParam message: String,
		@RequestHeader("Token") token: String) : ResponseEntity<String> {
		if (nodes.getServerFromToken(token) < 0) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("{\"code\":401}")
		val result = service.insert(from, to, message).toInt()
		return returnInterface.GeneralHttpHeader(Gson().toJson(returnStat(result)))
	}
	@GetMapping("/get")
	suspend fun handleGetLeaveMessage(@RequestParam receiver: String, @RequestHeader("Token") token: String) : ResponseEntity<String> {
		if (nodes.getServerFromToken(token) < 0) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("{\"code\":401}")
		val result = service.getTargetReceivers(receiver)
		val jsonResponse = if (result.isEmpty()) "[]" else Gson().toJson(result)
		return returnInterface.GeneralHttpHeader(jsonResponse)
	}
	
}

data class returnStat(val code:Int)
