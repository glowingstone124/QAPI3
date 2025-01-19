package org.qo.server

import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class KumaController {
    @Autowired
    lateinit var kumaService: KumaService

    @PostMapping("/kuma/upload")
    fun handleKumaUpload(@RequestBody input: String, request: HttpServletRequest): ResponseEntity<String> {
        return kumaService.handleMessage(input, request)
    }
    
}