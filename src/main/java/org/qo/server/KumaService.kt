package org.qo.server;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName
import jakarta.servlet.http.HttpServletRequest
import org.apache.coyote.Response
import org.qo.IPUtil
import org.qo.Logger
import org.qo.Logger.LogLevel
import org.qo.Msg
import org.qo.ReturnInterface
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import java.io.File;
import java.nio.file.Files;

@Service
class KumaService {
    val KUMA_NODE_CONFIGURATION = "data/kuma.json";
    val NODES: HashMap<String, String> = HashMap();
    fun init() {
        if (!File(KUMA_NODE_CONFIGURATION).exists() || !File(KUMA_NODE_CONFIGURATION).isFile ) {
            createDefaultConfig();
            Logger.log("Could not found Kuma Config. Adding....", LogLevel.WARNING)
        }
        readConfig();
    }
    private fun createKumaConfig(ip: String, name: String): JsonObject {
        return JsonObject().apply {
            addProperty("ip", ip);
            addProperty("name", name);
        };
    }
    private fun createDefaultConfig() {
        val gson = Gson();
        val kuma_cfg = File(KUMA_NODE_CONFIGURATION);
        val configs = listOf(
            createKumaConfig("10.0.0.1", "node1"),
            createKumaConfig("192.168.1.10", "node2")
        );
        val arr = JsonArray();
        configs.forEach {
            arr.add(it)
        }
        Files.write(kuma_cfg.toPath(), gson.toJson(arr).toByteArray());
    }
    fun readConfig() {
        val gson = Gson();
        val kuma_cfg = File(KUMA_NODE_CONFIGURATION).readText();
        val configs = gson.fromJson(kuma_cfg, JsonArray::class.java);
        configs.forEach {
            val obj = it.asJsonObject
            val ip = obj.get("ip").asString
            val name = obj.get("name").asString
            NODES[ip] = name;
        }
    }
    fun handleMessage(input: String, request: HttpServletRequest): ResponseEntity<String> {
        if (!NODES.containsKey(IPUtil.getIpAddr(request))) {
            val headers = HttpHeaders().apply {
                contentType = MediaType.APPLICATION_JSON
            }
            return ResponseEntity("please invoke request in a recognized IP.", headers, HttpStatus.FORBIDDEN)
        }
        val webhookRequest = runCatching {
            Gson().fromJson(input, WebhookRequest::class.java)
        }.onSuccess { result ->
            val message = """
                服务状态更新：
                Service ${result.monitor.name} 的状态为 ${result.monitor.status}
                最新的heartbeat状态为： ${result.heartbeat.status} 延迟 ${result.heartbeat.ping}ms
            """.trimIndent()
            Msg.put(message)
        }.onFailure { error ->
            Logger.log("Error while parsing request from uptime kuma node ${IPUtil.getIpAddr(request)}", LogLevel.WARNING)
        }
        return ReturnInterface().GeneralHttpHeader("OK")
    }
}
data class Heartbeat(
    @SerializedName("status") val status: String,
    @SerializedName("time") val time: String,
    @SerializedName("ping") val ping: Int
)

data class Monitor(
    @SerializedName("name") val name: String,
    @SerializedName("status") val status: String,
    @SerializedName("url") val url: String
)

data class WebhookRequest(
    @SerializedName("msg") val msg: String,
    @SerializedName("monitor") val monitor: Monitor,
    @SerializedName("heartbeat") val heartbeat: Heartbeat
)