package org.qo.tracks

import org.json.JSONObject
import org.qo.ReturnInterface
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@SpringBootApplication
@RequestMapping("/hooks")
open class Controller {
    @PostMapping("/accept")
    fun accept(@RequestBody obj: String){
        val githubEvent: JSONObject = JSONObject(obj)
        println(githubEvent)
    }
}