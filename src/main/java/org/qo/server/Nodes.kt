package org.qo.server

import kotlinx.serialization.*
import kotlinx.serialization.json.*
import org.qo.Logger
import org.qo.Msg
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.io.IOException

@Serializable
data class Node(val name: String, val id: Int, val role: Role, val token: String) {
    fun validate(id: Int, token: String): Boolean {
        return id == this.id && token == this.token
    }
}

@Serializable
data class MessageIn(val message: String, val from: Int, val token: String)

enum class Role {
    SERVER,
    ROOT_NODE,
    CHILD_NODE
}

class Nodes {
    val SERVER_NODES = "nodes.json"
    val nodes_data: List<Node> = try {
        val jsonContent = Files.readString(Paths.get(SERVER_NODES))
        Json.decodeFromString(jsonContent)
    } catch (e: IOException) {
        Logger.log("No nodes data found.", Logger.LogLevel.ERROR)
        emptyList()
    }

    fun validate_message(input: String): Boolean {
        return try {
            val jsonContent = Json.decodeFromString<MessageIn>(input)
            nodes_data.any { node ->
                if (node.validate(jsonContent.from, jsonContent.token)) {
                    Msg.put("[${node.name}] ${jsonContent.message}")
                    true
                } else {
                    false
                }
            }
        } catch (e: SerializationException) {
            Logger.log("Invalid message format: ${e.message}", Logger.LogLevel.ERROR)
            false
        }
    }
}
