package org.qo.utils

import com.google.gson.JsonObject
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class ErrorController {
    @RequestMapping("/error")
    fun error(response: HttpServletResponse, request: HttpServletRequest): String = with(UAUtil()) {
        val returnObj = JsonObject()
        returnObj.addProperty("timestamp", System.currentTimeMillis())
        returnObj.addProperty("error", response.status)
        returnObj.addProperty("code", -1)
        return ResponseEntity(returnObj.toString(), HttpHeaders().apply {
	        contentType = MediaType.APPLICATION_JSON
        }, HttpStatus.OK).body!!
    }
}