package org.qo.services.gitservices

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.qo.services.messageServices.Msg
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.beans.factory.annotation.Value
import org.springframework.web.bind.annotation.*
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@RestController
@RequestMapping("/hooks")
class Controller(@Value("\${qapi.github.webhook-secret:\${GITHUB_WEBHOOK_SECRET:}}") private val webhookSecret: String) {

    @PostMapping("/accept")
    fun accept(@RequestBody obj: String, @RequestHeader("X-Hub-Signature-256", required = false) signature: String?): ResponseEntity<Void> {
        val secret = webhookSecret.takeIf { it.isNotBlank() }
            ?: return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build()
        if (!validSignature(obj, signature, secret)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        }
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
            return ResponseEntity.noContent().build()
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
        return ResponseEntity.noContent().build()
    }

    private fun validSignature(body: String, supplied: String?, secret: String): Boolean {
        if (supplied == null || !supplied.startsWith("sha256=")) return false
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        val expected = "sha256=" + mac.doFinal(body.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return MessageDigest.isEqual(expected.toByteArray(StandardCharsets.US_ASCII), supplied.toByteArray(StandardCharsets.US_ASCII))
    }
}
