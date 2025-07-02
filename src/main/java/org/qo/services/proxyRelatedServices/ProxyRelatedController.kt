package org.qo.services.proxyRelatedServices

import com.google.gson.Gson
import com.google.gson.JsonObject
import org.qo.utils.ReturnInterface
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/qo/proxies")
class ProxyRelatedController(private val proxyRelatedImpl: ProxyRelatedImpl) {
    val gson = Gson()
    @PostMapping("/accept")
    fun acceptProxyHeartbeat(@RequestBody token: String) : ResponseEntity<String> {
        proxyRelatedImpl.heartBeatUpdate(token)
        return ReturnInterface().GeneralHttpHeader("ok")
    }
    @GetMapping("/status")
    fun status(): ResponseEntity<String> {
        return ReturnInterface().GeneralHttpHeader(gson.toJson(proxyRelatedImpl.getSimplifiedProxies()));
    }
    @GetMapping("/query")
    fun queryProxyInfo(@RequestParam token: String): ResponseEntity<String> {
        val returnObj = JsonObject()
        val proxy = proxyRelatedImpl.getProxy(token)
        if (proxy == null) {
            returnObj.apply {
                addProperty("code", -1)
            }
        } else {
            returnObj.apply {
                addProperty("code", 0)
                addProperty("name", proxy.name)
            }
        }
        return ReturnInterface().GeneralHttpHeader(returnObj.toString())
    }
}