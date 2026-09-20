package org.qo.services.llmServices

import com.google.gson.Gson
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.CancellationException
import org.qo.utils.AuthTokens
import org.springframework.core.io.buffer.DataBufferLimitException
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/qo/builder/transfers", produces = [MediaType.APPLICATION_JSON_VALUE])
class BuilderTransferController(private val llm: LLMServices, private val store: BuilderTransferStore) {
    private val gson = Gson()

    @PostMapping
    suspend fun upload(
        @RequestHeader("Authorization", required = false) authorization: String?,
        @RequestHeader("X-Minecraft-Name") owner: String,
        request: ServerHttpRequest,
    ): ResponseEntity<String> {
        val token = AuthTokens.resolve(null, authorization)
        if (token == null || !llm.authenticateServerToken(token)) return error(HttpStatus.UNAUTHORIZED, "Invalid server token")
        return try {
            val buffer = DataBufferUtils.join(request.body, BuilderTransferStore.MAX_BYTES).awaitSingle()
            val document = try {
                val bytes = ByteArray(buffer.readableByteCount()); buffer.read(bytes); bytes.toString(Charsets.UTF_8)
            } finally { DataBufferUtils.release(buffer) }
            val code = store.put(owner, document)
            ResponseEntity.ok().header("Cache-Control", "no-store").body(gson.toJson(mapOf("code" to code, "expiresIn" to 600)))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: DataBufferLimitException) {
            error(HttpStatus.PAYLOAD_TOO_LARGE, "Selection exceeds 4 MB")
        } catch (_: IllegalStateException) {
            error(HttpStatus.TOO_MANY_REQUESTS, "Transfer queue unavailable")
        } catch (_: Exception) {
            error(HttpStatus.BAD_REQUEST, "Invalid Builder document")
        }
    }

    @GetMapping("/{code}")
    suspend fun download(
        @PathVariable code: String,
        @RequestHeader("Authorization", required = false) authorization: String?,
    ): ResponseEntity<String> {
        val token = AuthTokens.resolve(null, authorization) ?: return error(HttpStatus.UNAUTHORIZED, "Login required")
        val principal = llm.authenticateWeb(token) ?: return error(HttpStatus.UNAUTHORIZED, "Login required")
        if (!principal.hasAccount) return error(HttpStatus.NOT_FOUND, "Selection not found")
        val document = store.get(code, principal.displayName) ?: return error(HttpStatus.NOT_FOUND, "Selection not found")
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(document)
    }

    private fun error(status: HttpStatus, message: String) = ResponseEntity.status(status).body(gson.toJson(mapOf("error" to message)))
}
