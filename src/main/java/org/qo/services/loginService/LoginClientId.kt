package org.qo.services.loginService

import java.util.Locale

enum class LoginClientId(val id: String) {
	GAME("game"),
	QCOMMUNITY("qcommunity"),
	QMOD("qmod");

	companion object {
		@JvmStatic
		fun from(value: String?): LoginClientId? {
			if (value == null) return GAME
			val normalized = value.trim().lowercase(Locale.ROOT)
			return entries.firstOrNull { it.id == normalized }
		}
	}
}
