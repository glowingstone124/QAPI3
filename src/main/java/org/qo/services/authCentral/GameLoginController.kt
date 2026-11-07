package org.qo.services.authCentral

import org.qo.utils.AuthTokens
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/qo/auth/game")
class GameLoginController(private val service: GameLoginService) {
    @PostMapping("/challenge")
    fun challenge(@RequestHeader("Token", required = false) token: String?, @RequestBody body: GameConnectionBody) =
        respond(service.challenge(token, body))

    @PostMapping("/approve")
    suspend fun approve(@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
        @RequestBody body: GameConnectionBody) = respond(service.approve(AuthTokens.resolve(null, authorization), body))

    @PostMapping("/redeem")
    suspend fun redeem(@RequestHeader("Token", required = false) token: String?, @RequestBody body: GameConnectionBody) =
        respond(service.redeem(token, body))

    private fun respond(result: GameLoginResult): ResponseEntity<GameLoginResult> = ResponseEntity.status(when {
        result.ok -> HttpStatus.OK
        result.code == "BAD_REQUEST" -> HttpStatus.BAD_REQUEST
        result.code == "UNAUTHORIZED_SERVER" || result.code == "INVALID_QMOD_SESSION" -> HttpStatus.UNAUTHORIZED
        else -> HttpStatus.FORBIDDEN
    }).header(HttpHeaders.CACHE_CONTROL, "no-store").body(result)
}
