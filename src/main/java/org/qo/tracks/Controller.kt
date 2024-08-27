package org.qo.tracks

import org.qo.ReturnInterface
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@SpringBootApplication
@RequestMapping("/track")
open class Controller {
    @PostMapping("/new")
    fun handleNewTrack(@RequestParam("type") type: String, @RequestParam("product") value: String, @RequestParam("description") description: String): ResponseEntity<String> {
        return ReturnInterface.success("OK")
    }
}