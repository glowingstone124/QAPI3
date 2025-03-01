package org.qo.proxyRelatedServices

import com.google.gson.Gson
import org.qo.Logger
import org.qo.Logger.LogLevel
import org.qo.utils.FileUpdateHook
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.io.File
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY

@Service
class ProxyRelatedImpl(private val fileUpdateHook: FileUpdateHook) {
    private val PROXY_LIST = "proxies.json"
    private val gson = Gson()
    private val proxyList: MutableList<Proxy> = mutableListOf()

    init {
        loadProxies()
        fileUpdateHook.addHook(File(PROXY_LIST), {
            loadProxies()
        },ENTRY_MODIFY)
    }
    private fun loadProxies() {
        val file = File(PROXY_LIST)
        if (file.exists()) {
            val newProxies = gson.fromJson(file.readText(), Array<Proxy>::class.java).toList()

            newProxies.forEach { newProxy ->
                val existingProxy = proxyList.find { it.name == newProxy.name }
                if (existingProxy != null) {
                    val index = proxyList.indexOf(existingProxy)
                    proxyList[index] = newProxy
                } else {
                    proxyList.add(newProxy)
                }
            }
        } else {
            Logger.log("[Proxy Initializer]Could not find proxies.json!", LogLevel.ERROR)
        }
    }

    fun getProxies(type: ProxyStatus): List<Proxy> {
        return proxyList.filter { it.stat == type }
    }

    fun heartBeatUpdate(token: String) {
        proxyList.find { it.token == token }?.latestHeartBeat = System.currentTimeMillis()
    }

    @Scheduled(fixedDelay = 5000)
    fun detectProxiesStatus() {
        proxyList.forEach { proxy ->
            if (System.currentTimeMillis() - proxy.latestHeartBeat >= 5000) {
                proxy.stat = ProxyStatus.DIED
            }
        }
    }
}


data class Proxy(
    val name: String,
    val url: String,
    val token: String,
    var stat: ProxyStatus = ProxyStatus.DIED,
    var latestHeartBeat: Long = 0,
)
enum class ProxyStatus {
    DIED,
    ALIVE,
    MAINTAINING
}
