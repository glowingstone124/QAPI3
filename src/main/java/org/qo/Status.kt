package org.qo

import com.google.gson.Gson
import com.google.gson.JsonObject
import org.qo.orm.UserORM
import org.springframework.stereotype.Service

@Service

class Status {
    lateinit var userORM: UserORM
    init {
        userORM = UserORM()
    }
    var currentStatus = JsonObject().apply {
        addProperty("code", 1)
        addProperty("reason", "no old status found")
    }
    fun upload(input: String){
        input.takeIf { it.isNotBlank() }?.let { currentStatus = input.asJsonObject() }
    }
    fun download(): JsonObject {
        if (currentStatus.has("timestamp") &&System.currentTimeMillis() - currentStatus.get("timestamp").asLong >= 3000L) {
            currentStatus.addProperty("reason", "status expired: latest data was presented longer than 3000 milliseconds ago.")
            return currentStatus
        } else if (currentStatus.has("timestamp")) {
            return currentStatus.apply { addProperty("totalcount", userORM.count()) }
        }
        return currentStatus
    }
}

fun String.asJsonObject(): JsonObject {
    return Gson().fromJson(this, JsonObject::class.java)
}