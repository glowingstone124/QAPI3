package org.qo.services.authCentral

import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
import org.qo.datas.Nodes
import org.qo.datas.Role
import org.qo.db.repository.UserDbRepository
import org.qo.services.loginService.Login
import org.springframework.stereotype.Service
import java.util.Locale
import java.util.UUID

data class GameConnectionBody(
    @field:JsonSetter(nulls = Nulls.FAIL)
    val username: String = "",
    @field:JsonSetter(nulls = Nulls.FAIL)
    val playerUuid: String = "",
    @field:JsonSetter(nulls = Nulls.FAIL)
    val nonce: String = "",
    @field:JsonSetter(nulls = Nulls.FAIL)
    val nodeId: Int = -1,
    @field:JsonSetter(nulls = Nulls.FAIL)
    val challenge: String = "",
    @field:JsonSetter(nulls = Nulls.FAIL)
    val ticket: String = "",
)

data class GameLoginResult(val ok: Boolean, val code: String, val challenge: String? = null,
    val ticket: String? = null, val nodeId: Int? = null, val username: String? = null, val expiresIn: Int? = null)

@Service
class GameLoginService(private val login: Login, private val users: UserDbRepository,
    private val nodes: Nodes, private val tickets: GameLoginTicketStore) {
    private fun nodeId(token: String?): Int? = token?.takeIf { it.isNotBlank() }?.let {
        nodes.getNodeFromToken(it)?.takeIf { node -> node.role == Role.SERVER && node.id >= 0 }?.id
    }

    private fun valid(body: GameConnectionBody): Boolean =
        body.username.matches(Regex("[A-Za-z0-9_]{1,16}")) &&
            runCatching { UUID.fromString(body.playerUuid).toString() == body.playerUuid }.getOrDefault(false) &&
            body.nonce.matches(Regex("[A-Za-z0-9_-]{43}"))

    private fun binding(kind: String, node: Int, b: GameConnectionBody) =
        "qmod-game:$kind:v1:$node:${b.playerUuid}:${b.username.lowercase(Locale.ROOT)}:${b.nonce}" +
            if (kind == "proof") ":${b.challenge}" else ""

    fun challenge(serverToken: String?, body: GameConnectionBody): GameLoginResult {
        val node = nodeId(serverToken) ?: return GameLoginResult(false, "UNAUTHORIZED_SERVER")
        if (!valid(body)) return GameLoginResult(false, "BAD_REQUEST")
        val record = tickets.createChallenge(body.username, binding("challenge", node, body))
        return GameLoginResult(true, "CHALLENGE", challenge = record.ticket, nodeId = node, expiresIn = 60)
    }

    suspend fun approve(token: String?, body: GameConnectionBody): GameLoginResult {
        if (!valid(body) || body.nodeId < 0 || !validChallenge(body.challenge)) return GameLoginResult(false, "BAD_REQUEST")
        val session = token?.takeIf { it.isNotBlank() }?.let { login.loginTokenORM.read(it) }
            ?.takeIf { it.expires > System.currentTimeMillis() && it.clientId == "qmod" }
            ?: return GameLoginResult(false, "INVALID_QMOD_SESSION")
        val account = if (session.user.startsWith("qq:")) session.user.removePrefix("qq:").toLongOrNull()?.let { users.readAsync(it) }
            else users.readAsync(session.user)
        if (account == null || account.frozen == true || !account.username.equals(body.username, true))
            return GameLoginResult(false, "ACCOUNT_MISMATCH_OR_FROZEN")
        val challenge = tickets.validateAndConsume(body.challenge, binding("challenge", body.nodeId, body))
            ?: return GameLoginResult(false, "INVALID_CHALLENGE")
        if (!challenge.username.equals(account.username, true)) return GameLoginResult(false, "ACCOUNT_MISMATCH_OR_FROZEN")
        val proof = tickets.createProof(account.username, binding("proof", body.nodeId, body))
        return GameLoginResult(true, "APPROVED", ticket = proof.ticket, expiresIn = 30)
    }

    suspend fun redeem(serverToken: String?, body: GameConnectionBody): GameLoginResult {
        val node = nodeId(serverToken) ?: return GameLoginResult(false, "UNAUTHORIZED_SERVER")
        if (!valid(body) || !validChallenge(body.challenge) || !validProof(body.ticket)) return GameLoginResult(false, "BAD_REQUEST")
        val proof = tickets.validateAndConsume(body.ticket, binding("proof", node, body))
            ?: return GameLoginResult(false, "INVALID_PROOF")
        val account = users.readAsync(proof.username)
        if (account == null || account.frozen == true || !account.username.equals(body.username, true))
            return GameLoginResult(false, "ACCOUNT_MISMATCH_OR_FROZEN")
        // No web/game session token or IP auto-login grant is issued to the Minecraft server.
        return GameLoginResult(true, "AUTHENTICATED", username = account.username)
    }

    private fun validChallenge(value: String) = value.matches(Regex("GC-[A-Za-z0-9_-]{43}"))
    private fun validProof(value: String) = value.matches(Regex("GP-[A-Za-z0-9_-]{43}"))
}
