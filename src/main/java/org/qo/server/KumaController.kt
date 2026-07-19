package org.qo.server

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.ResponseEntity
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class KumaController {
    @Autowired
    lateinit var kumaService: KumaService

    @PostMapping("/kuma/upload")
    fun handleKumaUpload(@RequestBody input: String, request: ServerHttpRequest): ResponseEntity<String> {
        return kumaService.handleMessage(input, request)
    }
    
}
