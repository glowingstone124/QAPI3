package org.qo.services.gitservices

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.qo.services.messageServices.Msg
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/hooks")
class Controller {

    @PostMapping("/accept")
    fun accept(@RequestBody obj: String) {
        val githubEvent = JsonParser.parseString(obj).asJsonObject
        val sb = StringBuilder()
        if (githubEvent.has("action")) {
            if (githubEvent.get("action").asString == "completed") {
                val runResult = githubEvent.get("workflow_run").asJsonObject
                val times = runResult.get("run_number").asInt
                val repository = runResult.get("repository").asJsonObject.get("name").asString
                val title = runResult.get("display_title").asString
                val status = runResult.get("status").asString

                sb.append("===========Github Update===========\n")
                sb.append("Github Actions触发，以下是详细信息\n")
                sb.append("$repository 构建 $times\n")
                sb.append("简介：$title\n")
                sb.append("运行结果：$status\n")

                Msg.putSys(sb.toString())
            }
            return
        }

        val repoName = githubEvent.get("repository").asJsonObject.get("name").asString
        val commitsArr: JsonArray = githubEvent.get("commits").asJsonArray
        val sender = githubEvent.get("sender").asJsonObject.get("login").asString

        sb.append("===========Github Update===========\n")
        sb.append("用户：$sender 上传了 ${commitsArr.size()} 个 commit 到仓库 $repoName\n")
        sb.append("--------------Summary--------------\n")

        commitsArr.forEach {
            it as JsonObject
            val msg = it.get("message").asString
            val author = it.get("author").asJsonObject.get("username").asString

            sb.append("作者：$author\n")
            sb.append("说明: $msg\n")
            sb.append("-----------------------------------\n")
        }
        Msg.putSys(sb.toString())
    }
}
