package org.qo.messages

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.qo.ReturnInterface
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/qo/leavemessage")
class LeaveMessageController(private val service: LeaveMessageService) {
	val returnInterface = ReturnInterface()
	@PostMapping("/upload")
	suspend fun handleLeaveMessage(
		@RequestParam from: String,
		@RequestParam to: String,
		@RequestParam message: String) : ResponseEntity<String> {
		val result = withContext(Dispatchers.IO) {
			service.insert(from, to, message).toInt()
		}
		return returnInterface.GeneralHttpHeader(Gson().toJson(returnStat(result)))
	}
	@GetMapping("/get")
	suspend fun handleGetLeaveMessage(@RequestParam receiver: String) : ResponseEntity<String> {
		val result = withContext(Dispatchers.IO) {
			service.getTargetReceivers(receiver)
		}
		val jsonResponse = if (result.isEmpty()) "[]" else Gson().toJson(result)
		return returnInterface.GeneralHttpHeader(jsonResponse)
	}
	
}

data class returnStat(val code:Int)