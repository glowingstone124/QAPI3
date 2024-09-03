package org.qo.tracks

import com.google.gson.JsonObject
import org.json.JSONArray
import org.json.JSONObject
import org.qo.Msg
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
    fun accept(@RequestBody obj: String) {
        val githubEvent: JSONObject = JSONObject(obj)
        println(githubEvent)
        if (githubEvent.has("action")) {
            return;
        }
        val repoName =githubEvent.getJSONObject("repository").getString("name")
        val commitsArr: JSONArray = githubEvent.getJSONArray("commits")
        val sender = githubEvent.getJSONObject("sender").getString("login")
        val sb:StringBuilder = StringBuilder()
        sb.append("===========Github Update===========")
        sb.append("用户：$sender 上传了 ${commitsArr.length()} 个commit到仓库 $repoName")
        sb.append("--------------Summery--------------")
        commitsArr.forEach {
            it as JSONObject
            val msg = it.getString("message")
            val author = it.getJSONObject("author").getString("username")
            sb.append("作者：$author")
            sb.append("说明: $msg")
            sb.append("-----------------------------------")
        }
        Msg.put(sb.toString())
    }
}