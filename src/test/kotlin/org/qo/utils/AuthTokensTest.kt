package org.qo.utils

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class AuthTokensTest {
	@Test
	fun `resolves standard bearer authorization`() {
		assertEquals("login-token", AuthTokens.resolve(null, "Bearer login-token"))
		assertEquals("login-token", AuthTokens.resolve(null, "bearer   login-token  "))
	}

	@Test
	fun `keeps legacy token header compatibility`() {
		assertEquals("legacy-token", AuthTokens.resolve(" legacy-token ", "Bearer ignored"))
		assertEquals("legacy-token", AuthTokens.resolve(null, "legacy-token"))
	}

	@Test
	fun `rejects missing token values`() {
		assertNull(AuthTokens.resolve(null, null))
		assertNull(AuthTokens.resolve(" ", "Bearer  "))
	}
}
