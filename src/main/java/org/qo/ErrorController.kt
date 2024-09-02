package org.qo

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.json.JSONObject
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.RequestMapping

@Controller
class ErrorController {
    @RequestMapping("/error")
    fun error(response: HttpServletResponse, request: HttpServletRequest): String = with(UAUtil()) {
        val returnObj = JSONObject()
        returnObj.put("timestamp", System.currentTimeMillis())
        returnObj.put("error", response.status)
        returnObj.put("code", -1)
        return ResponseEntity<String>(returnObj.toString(), HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
        }, HttpStatus.OK).body
    }
}
