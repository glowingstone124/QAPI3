package org.qo.utils

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito
import org.qo.datas.Mapping
import org.qo.services.loginService.AffiliatedAccountServices
import org.qo.services.loginService.Login
import org.springframework.test.util.ReflectionTestUtils
import reactor.core.publisher.Mono

class UserProcessPlayerActivityTest {
	private val login = Mockito.mock(Login::class.java)
	private val reactiveStore = Mockito.mock(UserProcessReactiveStore::class.java)
	private val userProcess = UserProcess(login, reactiveStore)
	private val affiliatedAccountServices = Mockito.mock(AffiliatedAccountServices::class.java)

	init {
		ReflectionTestUtils.setField(userProcess, "affiliatedAccountServices", affiliatedAccountServices)
	}

	@Test
	fun queryRegIncludesLastLoginTimestamp() {
		val lastLogin = 1_787_558_400_000L
		val user = Mapping.Users(
			username = "alex",
			uid = 1234L,
			profile_id = "profile-id",
			last_login = lastLogin,
		)
		Mockito.`when`(reactiveStore.readUser("alex")).thenReturn(Mono.just(user))
		Mockito.`when`(affiliatedAccountServices.validateAffiliatedAccountReactive("alex"))
			.thenReturn(Mono.empty())

		val response = JsonParser.parseString(userProcess.queryReg("alex").block()).asJsonObject

		assertEquals(0, response["code"].asInt)
		assertEquals(lastLogin, response["last_login"].asLong)
	}

	@Test
	fun recordPlayerOnlinePersistsServerTimestamp() {
		Mockito.doReturn(Mono.just(true)).`when`(reactiveStore)
			.updateLastLogin(Mockito.anyString(), Mockito.anyLong())
		val before = System.currentTimeMillis()

		userProcess.recordPlayerOnline("alex", "203.0.113.8").block()

		val timestampCaptor = ArgumentCaptor.forClass(Long::class.java)
		Mockito.verify(reactiveStore).updateLastLogin(Mockito.anyString(), timestampCaptor.capture())
		assertTrue(timestampCaptor.value in before..System.currentTimeMillis())
	}
}
