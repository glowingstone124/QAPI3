package org.qo.services.gitservices

import org.json.JSONArray
import org.json.JSONObject
import org.qo.services.messageServices.Msg
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/hooks")
class Controller {

    @PostMapping("/accept")
    fun accept(@RequestBody obj: String) {
        val githubEvent = JSONObject(obj)
        val sb = StringBuilder()
        if (githubEvent.has("action")) {
            if (githubEvent.getString("action") == "completed") {
                val runResult = githubEvent.getJSONObject("workflow_run")
                val times = runResult.getInt("run_number")
                val repository = runResult.getJSONObject("repository").getString("name")
                val title = runResult.getString("display_title")
                val status = runResult.getString("status")

                sb.append("===========Github Update===========\n")
                sb.append("Github Actions触发，以下是详细信息\n")
                sb.append("$repository 构建 $times\n")
                sb.append("简介：$title\n")
                sb.append("运行结果：$status\n")

                Msg.putSys(sb.toString())
            }
            return
        }

        val repoName = githubEvent.getJSONObject("repository").getString("name")
        val commitsArr: JSONArray = githubEvent.getJSONArray("commits")
        val sender = githubEvent.getJSONObject("sender").getString("login")

        sb.append("===========Github Update===========\n")
        sb.append("用户：$sender 上传了 ${commitsArr.length()} 个 commit 到仓库 $repoName\n")
        sb.append("--------------Summary--------------\n")

        commitsArr.forEach {
            it as JSONObject
            val msg = it.getString("message")
            val author = it.getJSONObject("author").getString("username")

            sb.append("作者：$author\n")
            sb.append("说明: $msg\n")
            sb.append("-----------------------------------\n")
        }
        Msg.putSys(sb.toString())
    }
}
