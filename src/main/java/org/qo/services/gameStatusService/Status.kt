package org.qo.services.gameStatusService

import com.google.gson.Gson
import com.google.gson.JsonObject
import org.qo.orm.UserORM
import org.qo.datas.Nodes
import org.springframework.stereotype.Service

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

    val statusMap = hashMapOf<Int, JsonObject>()

    fun upload(input: String, header: String) {
        val serverId = nodes.getServerFromToken(header)
        if (serverId != -1) {
            statusMap[serverId] = input.asJsonObject()
        }
    }

    fun download(id: Int): JsonObject {
        return statusMap[id]?.apply {
            addProperty("totalcount", userORM.count())
        } ?: fallbackStatus
    }

    fun countOnline(): Int {
        return statusMap.size
    }
}

fun String.asJsonObject(): JsonObject {
    return Gson().fromJson(this, JsonObject::class.java)
}
