package org.qo.services.registrationServices

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RegistrationVerificationMethodTest {
	@Test
	fun `missing method keeps legacy quiz behavior`() {
		assertEquals(RegistrationVerificationMethod.QUIZ, RegistrationVerificationMethod.parse(null))
		assertEquals(RegistrationVerificationMethod.QUIZ, RegistrationVerificationMethod.parse(""))
		assertEquals(RegistrationVerificationMethod.QUIZ, RegistrationVerificationMethod.parse("quiz"))
	}

	@Test
	fun `minecraft is reserved and unknown methods are rejected`() {
		assertEquals(RegistrationVerificationMethod.MINECRAFT, RegistrationVerificationMethod.parse("minecraft"))
		assertNull(RegistrationVerificationMethod.parse("unknown"))
	}
}
