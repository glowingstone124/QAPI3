package org.qo.server

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import org.qo.Logger
import org.qo.Msg
import java.io.FileReader
import java.io.IOException

data class Node(
    @SerializedName("name") val name: String,
    @SerializedName("id") val id: Int,
    @SerializedName("role") val role: Role,
    @SerializedName("token") val token: String
) {
    fun validate(id: Int, token: String): Boolean {
        return id == this.id && token == this.token
    }
}

data class MessageIn(
    @SerializedName("message") val message: String,
    @SerializedName("from") val from: Int,
    @SerializedName("token") val token: String
)

enum class Role {
    SERVER,
    ROOT_NODE,
    CHILD_NODE
}

class Nodes {
    private val SERVER_NODES = "nodes.json"
    private val gson = Gson()
    var nodesData: List<Node> = try {
        val reader = FileReader(SERVER_NODES)
        val nodeListType = object : TypeToken<List<Node>>() {}.type
        gson.fromJson(reader, nodeListType)
    } catch (e: IOException) {
        Logger.log("No nodes data found.", Logger.LogLevel.ERROR)
        emptyList()
    }

    fun validate_message(input: String): Boolean {
        return try {
            val messageIn = gson.fromJson(input, MessageIn::class.java)
            nodesData.any { node ->
                if (node.validate(messageIn.from, messageIn.token)) {
                    Msg.put("[${node.name}] ${messageIn.message}")
                    true
                } else {
                    false
                }
            }
        } catch (e: com.google.gson.JsonSyntaxException) {
            Logger.log("Invalid message format: ${e.message}", Logger.LogLevel.ERROR)
            false
        }
    }
}