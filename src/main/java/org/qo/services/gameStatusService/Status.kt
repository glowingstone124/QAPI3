package org.qo.services.gameStatusService

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.reactor.mono
import org.qo.orm.UserORM
import org.qo.datas.Nodes
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import java.util.concurrent.ConcurrentHashMap

@Service
class Status() {
    lateinit var userORM: UserORM
    lateinit var nodes: Nodes

    init {
        nodes = Nodes()
        userORM = UserORM()
    }

    var fallbackStatus = JsonObject().apply {
        addProperty("code", 1)
        addProperty("reason", "no old status found")
    }

    val statusMap = ConcurrentHashMap<Int, JsonObject>()

    fun upload(input: String, header: String) {
        val serverId = nodes.getServerFromToken(header)
        if (serverId != -1) {
            statusMap[serverId] = input.asJsonObject()
        }
    }

    suspend fun downloadAsync(id: Int): JsonObject {
        return statusMap[id]?.deepCopy()?.apply {
            addProperty("totalcount", userORM.countAsync())
        } ?: fallbackStatus.deepCopy()
    }

    fun downloadReactive(id: Int): Mono<JsonObject> = mono { downloadAsync(id) }

    fun countOnline(): Int {
        return statusMap.size
    }
}

fun String.asJsonObject(): JsonObject {
    return Gson().fromJson(this, JsonObject::class.java)
}
