package org.qo

import com.google.gson.Gson
import com.google.gson.JsonObject
import org.qo.orm.UserORM
import org.qo.server.Nodes
import org.springframework.stereotype.Service

@Service

class Status {
    lateinit var userORM: UserORM
    lateinit var nodes: Nodes

    init {
        userORM = UserORM()
        nodes = Nodes()
    }

    var currentStatus = JsonObject().apply {
        addProperty("code", 1)
        addProperty("reason", "no old status found")
    }
    val statusMap = hashMapOf<Int, JsonObject>(
        1 to currentStatus
    )

    fun upload(input: String, header: String) {
        /*val result = nodes.getServerFromToken(header)
        if (result == Pair("", 0)) {
            return
        }
        input.takeIf { it.isNotBlank() }?.let {
            statusMap[result.second] = input.asJsonObject()
        }*
         */
        currentStatus = input.asJsonObject()
    }

    fun download(server: String?): JsonObject {
        /*if (server.isNullOrEmpty()) {
            if (statusMap[1]!!.has("timestamp") && System.currentTimeMillis() - currentStatus.get("timestamp").asLong >= 3000L) {
                currentStatus.addProperty(
                    "reason",
                    "status expired: latest data was presented longer than 3000 milliseconds ago."
                )
                return currentStatus
            } else if (currentStatus.has("timestamp")) {
                return currentStatus.apply { addProperty("totalcount", userORM.count()) }
            }
            return currentStatus
        }
        var currentServer = nodes.getServerFromToken(server!!)
        if (!statusMap.containsKey(currentServer.second)) {
            return JsonObject().apply {
                addProperty("code", 1)
                addProperty("reason", "no old status found")
            }
        }
        if (statusMap[currentServer.second]!!.has("timestamp" )&& System.currentTimeMillis() - statusMap[currentServer.second]!!.get("timestamp").asLong >= 3000L) {
            return JsonObject().apply {
                currentStatus.addProperty(
                    "reason",
                    "status expired: latest data was presented longer than 3000 milliseconds ago."
                )
            }
        }
        return statusMap[currentServer.second]!!.apply { addProperty("totalcount", userORM.count()) }

         */
        return currentStatus.apply { addProperty("totalcount", userORM.count()) }
    }
    fun countOnline(): Int {
        return statusMap.values.count { it.has("code") && it.get("code").asInt == 0 }
    }
}

fun String.asJsonObject(): JsonObject {
    return Gson().fromJson(this, JsonObject::class.java)
}