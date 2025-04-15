package org.qo.utils

import com.google.gson.JsonObject
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service

@Service
class ReturnInterface {
    fun GeneralHttpHeader(input: String, type: HttpStatus = HttpStatus.OK): ResponseEntity<String> {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
        }
        return ResponseEntity(input, headers, type)
    }
    fun GeneralHttpHeader(input: String): ResponseEntity<String> {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
        }
        return ResponseEntity(input, headers, HttpStatus.OK)
    }

    fun failed(input: String): ResponseEntity<String> {
        val returnObject = JsonObject().apply {
            addProperty("code", -1)
            addProperty("message", input)
        }
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
        }
        return ResponseEntity(returnObject.toString(), headers, HttpStatus.NOT_ACCEPTABLE)
    }

    fun success(input: String): ResponseEntity<String> {
        val returnObject = JsonObject().apply {
            addProperty("code", 0)
            addProperty("message", input)
        }
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
        }
        return ResponseEntity(returnObject.toString(), headers, HttpStatus.OK)
    }

    fun denied(input: String): ResponseEntity<String> {
        val returnObject = JsonObject().apply {
            addProperty("code", 1)
            addProperty("message", input)
        }
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
        }
        return ResponseEntity(returnObject.toString(), headers, HttpStatus.FORBIDDEN)
    }
}