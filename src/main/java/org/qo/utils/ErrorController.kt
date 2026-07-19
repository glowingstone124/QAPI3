package org.qo.utils

import com.google.gson.JsonObject
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange

@RestController
class ErrorController {
    @RequestMapping("/error")
    fun error(exchange: ServerWebExchange): ResponseEntity<String> {
        val returnObj = JsonObject()
        returnObj.addProperty("timestamp", System.currentTimeMillis())
        returnObj.addProperty("error", exchange.response.statusCode?.value() ?: 500)
        returnObj.addProperty("code", -1)
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .body(returnObj.toString())
    }
}
