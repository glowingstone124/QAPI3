package org.qo.services.authCentral

import org.qo.db.repository.UserDbRepository
import org.qo.services.loginService.LoginClientId
import org.qo.services.loginService.Login
import org.springframework.stereotype.Service

sealed interface TicketGrantResult {
	data class Success(val ticket: String, val redirectUrl: String, val expiresInSeconds: Long) : TicketGrantResult
	data object Unauthorized : TicketGrantResult
	data object MissingService : TicketGrantResult
	data object UntrustedService : TicketGrantResult
}

sealed interface ServiceValidateResult {
	data class Success(
		val user: String,
		val uid: Long?,
		val token: String,
		val clientId: String,
		val accountType: String,
		val score: Int,
		val frozen: Boolean
	) : ServiceValidateResult
	data object MissingParameters : ServiceValidateResult
	data object InvalidClientId : ServiceValidateResult
	data object InvalidTicket : ServiceValidateResult
	data object AccountFrozen : ServiceValidateResult
}

sealed interface SessionRefreshResult {
	data class Success(
		val user: String,
		val uid: Long?,
		val token: String,
		val clientId: String,
		val accountType: String,
		val score: Int,
		val frozen: Boolean,
		val expiresAt: Long,
	) : SessionRefreshResult
	data object InvalidToken : SessionRefreshResult
	data object AccountFrozen : SessionRefreshResult
}

@Service
class AuthCentralService(
	private val login: Login,
	private val serviceRegistry: ServiceRegistry,
	private val ticketStore: SsoTicketStore,
	private val userDbRepository: UserDbRepository,
) {
	suspend fun grantTicket(callerToken: String?, targetService: String?): TicketGrantResult {
		if (callerToken.isNullOrBlank()) return TicketGrantResult.Unauthorized
		val (username, validateCode) = login.validate(callerToken)
		if (username.isNullOrBlank() || validateCode != 0) {
			return TicketGrantResult.Unauthorized
		}

		val service = targetService?.trim().orEmpty()
		if (service.isEmpty()) return TicketGrantResult.MissingService
		if (!serviceRegistry.isAllowed(service)) return TicketGrantResult.UntrustedService

		val record = ticketStore.createTicket(username, service)
		val redirectUrl = buildRedirectUrl(service, record.ticket)
		return TicketGrantResult.Success(record.ticket, redirectUrl, SsoTicketStore.TICKET_TTL_SECONDS)
	}

	suspend fun validateTicket(
		ticket: String?,
		service: String?,
		clientId: String? = null,
	): ServiceValidateResult {
		val cleanTicket = ticket?.trim().orEmpty()
		val cleanService = service?.trim().orEmpty()
		if (cleanTicket.isEmpty() || cleanService.isEmpty()) {
			return ServiceValidateResult.MissingParameters
		}
		val loginClientId = LoginClientId.from(clientId)
			?: return ServiceValidateResult.InvalidClientId

		val record = ticketStore.validateAndConsume(cleanTicket, cleanService)
			?: return ServiceValidateResult.InvalidTicket

		val account = userDbRepository.readAsync(record.username)
		if (account?.frozen == true) {
			return ServiceValidateResult.AccountFrozen
		}

		val clientToken = login.generateToken(64)
		login.insertIntoAsync(clientToken, record.username, loginClientId)

		val hasAccount = account != null
		val accountType = if (hasAccount) "qo" else "guest"
		val uid = if (hasAccount) {
			account?.uid
		} else if (record.username.startsWith("qq:")) {
			record.username.removePrefix("qq:").toLongOrNull()
		} else {
			null
		}

		return ServiceValidateResult.Success(
			user = record.username,
			uid = uid,
			token = clientToken,
			clientId = loginClientId.id,
			accountType = accountType,
			score = account?.score ?: 0,
			frozen = false
		)
	}

	suspend fun refreshSession(token: String?): SessionRefreshResult {
		val cleanToken = token?.trim().orEmpty()
		if (cleanToken.isEmpty()) return SessionRefreshResult.InvalidToken
		val (username, errorCode) = login.validate(cleanToken)
		if (username.isNullOrBlank() || errorCode != 0) {
			return SessionRefreshResult.InvalidToken
		}

		val guestUid = username.takeIf { it.startsWith("qq:") }
			?.removePrefix("qq:")?.toLongOrNull()
		val account = if (guestUid != null) {
			userDbRepository.readAsync(guestUid)
		} else {
			userDbRepository.readAsync(username)
		}
		if (account == null && guestUid == null) {
			login.revokeToken(cleanToken)
			return SessionRefreshResult.InvalidToken
		}
		if (account?.frozen == true) {
			login.revokeToken(cleanToken)
			return SessionRefreshResult.AccountFrozen
		}

		val refreshedToken = login.refreshToken(cleanToken)
			?: return SessionRefreshResult.InvalidToken
		val accountType = if (account != null) "qo" else "guest"
		val uid = if (account != null) {
			account.uid
		} else {
			guestUid
		}

		return SessionRefreshResult.Success(
			user = account?.username ?: username,
			uid = uid,
			token = refreshedToken.token,
			clientId = refreshedToken.clientId,
			accountType = accountType,
			score = account?.score ?: 0,
			frozen = false,
			expiresAt = refreshedToken.expires,
		)
	}

	private fun buildRedirectUrl(serviceUrl: String, ticket: String): String {
		val (base, fragment) = if (serviceUrl.contains("#")) {
			val parts = serviceUrl.split("#", limit = 2)
			parts[0] to ("#" + parts[1])
		} else {
			serviceUrl to ""
		}
		val separator = if (base.contains("?")) "&" else "?"
		return "$base${separator}ticket=$ticket$fragment"
	}
}
