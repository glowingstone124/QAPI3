package org.qo.services.gameStatusService

import com.google.gson.Gson
import com.google.gson.JsonObject
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.reactor.asFlux
import kotlinx.coroutines.reactor.mono
import org.qo.datas.Nodes
import org.qo.orm.UserORM
import org.springframework.http.codec.ServerSentEvent
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.util.concurrent.ConcurrentHashMap

@Service
class Status(
    var userORM: UserORM = UserORM(),
    var nodes: Nodes = Nodes(),
    private val heartbeatIntervalMs: Long = 15_000L,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {
    var fallbackStatus: JsonObject = JsonObject().apply {
        addProperty("code", 1)
        addProperty("reason", "no old status found")
    }

    val statusMap = ConcurrentHashMap<Int, JsonObject>()

    data class ServerStatusUpdate(
        val serverId: Int,
        val payload: JsonObject
    )

    private val statusUpdates = MutableSharedFlow<ServerStatusUpdate>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    fun upload(input: String, header: String) {
        val serverId = nodes.getServerFromToken(header)
        if (serverId != -1) {
            publishStatus(serverId, input.asJsonObject())
        }
    }

    suspend fun publishStatusAsync(serverId: Int, json: JsonObject) {
        statusMap[serverId] = json
        try {
            val snapshot = downloadAsync(serverId)
            statusUpdates.emit(ServerStatusUpdate(serverId, snapshot))
        } catch (_: Exception) {
        }
    }

    fun publishStatus(serverId: Int, json: JsonObject) {
        statusMap[serverId] = json
        scope.launch {
            publishStatusAsync(serverId, json)
        }
    }

    suspend fun downloadAsync(id: Int): JsonObject {
        val totalCount = try {
            userORM.countAsync()
        } catch (_: Exception) {
            0L
        }
        return statusMap[id]?.deepCopy()?.apply {
            addProperty("totalcount", totalCount)
        } ?: fallbackStatus.deepCopy()
    }

    fun downloadReactive(id: Int): Mono<JsonObject> = mono { downloadAsync(id) }

    fun countOnline(): Int {
        return statusMap.size
    }

    @JvmOverloads
    fun streamStatus(
        id: Int = 1,
        eventName: String? = null
    ): Flow<ServerSentEvent<String>> = flow {
        // 1. Send initial status snapshot immediately upon connection
        val initial = downloadAsync(id)
        emit(buildSse(initial.toString(), eventName))

        // 2. Stream live updates for the requested serverId (or all servers if id <= 0)
        val updatesFlow = statusUpdates
            .filter { id <= 0 || it.serverId == id }
            .map { update ->
                buildSse(update.payload.toString(), eventName)
            }

        // 3. Heartbeat comments every interval to prevent reverse proxy/firewall timeouts
        val heartbeatFlow = flow {
            while (currentCoroutineContext().isActive) {
                delay(heartbeatIntervalMs)
                emit(
                    ServerSentEvent.builder<String>()
                        .comment("keep-alive")
                        .build()
                )
            }
        }

        emitAll(merge(updatesFlow, heartbeatFlow))
    }

    @JvmOverloads
    fun streamStatusFlux(
        id: Int = 1,
        eventName: String? = null
    ): Flux<ServerSentEvent<String>> = streamStatus(id, eventName).asFlux()

    private fun buildSse(data: String, eventName: String?): ServerSentEvent<String> {
        val builder = ServerSentEvent.builder(data)
            .id(System.currentTimeMillis().toString())
        if (!eventName.isNullOrBlank()) {
            builder.event(eventName)
        }
        return builder.build()
    }

    @PreDestroy
    fun destroy() {
        scope.cancel()
    }
}

fun String.asJsonObject(): JsonObject {
    return Gson().fromJson(this, JsonObject::class.java)
}
