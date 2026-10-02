package org.qo

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito
import org.qo.datas.DatabaseHealth
import org.qo.datas.Nodes
import org.qo.services.gameStatusService.Status
import org.qo.services.loginService.IPWhitelistServices
import org.qo.services.loginService.Login
import org.qo.services.loginService.RecentLoginService
import org.qo.services.proxyRelatedServices.ProxyRelatedImpl
import org.qo.services.registrationServices.MinecraftRegistrationSessionService
import org.qo.services.registrationServices.RegistrationQuizService
import org.qo.utils.ReturnInterface
import org.qo.utils.UAUtil
import org.qo.utils.UserProcess
import org.springframework.http.MediaType
import reactor.core.publisher.Mono
import kotlin.test.assertEquals

class AccountConfirmationTest {
	private val nodes = mock<Nodes>()
	private val userProcess = mock<UserProcess>()
	private val controller = ApiApplication(
		mock<UAUtil>(), ReturnInterface(), mock<Status>(), mock<Login>(), mock<IPWhitelistServices>(),
		mock<ProxyRelatedImpl>(), userProcess, nodes, mock<RegistrationQuizService>(),
		mock<MinecraftRegistrationSessionService>(), mock<RecentLoginService>(), mock<DatabaseHealth>(), false,
	)

	@ParameterizedTest
	@CsvSource("0,true", "0,false", "1,true", "1,false")
	fun `registration and password confirmation use the selected proof and retain the result response`(task: Int, approved: Boolean) {
		Mockito.`when`(nodes.getServerFromToken("bot")).thenReturn(0)
		if (task == 0) Mockito.`when`(userProcess.validateMinecraftUser("proof", 123)).thenReturn(Mono.just(approved))
		else Mockito.`when`(userProcess.validatePasswordUpdateRequest("proof", 123)).thenReturn(Mono.just(approved))

		val response = requireNotNull(controller.verifyReg(ApiApplication.ConfirmationRequest("proof", 123, task), "Bearer bot").block())
		assertEquals(200, response.statusCode.value())
		assertEquals(MediaType.APPLICATION_JSON, response.headers.contentType)
		assertEquals("{\"result\":$approved}", response.body)
		if (task == 0) Mockito.verify(userProcess).validateMinecraftUser("proof", 123)
		else Mockito.verify(userProcess).validatePasswordUpdateRequest("proof", 123)
		Mockito.verifyNoMoreInteractions(userProcess)
	}

	@ParameterizedTest
	@ValueSource(strings = ["Bearer", "Bearer other-node"])
	fun `only the bot node can consume confirmation proofs`(authorization: String) {
		Mockito.`when`(nodes.getServerFromToken("other-node")).thenReturn(1)
		val response = requireNotNull(controller.verifyReg(ApiApplication.ConfirmationRequest("proof", 123, 0), authorization).block())
		assertEquals(401, response.statusCode.value())
		assertEquals(MediaType.APPLICATION_JSON, response.headers.contentType)
		assertEquals("{\"result\":false}", response.body)
		Mockito.verifyNoInteractions(userProcess)
	}

	@Test
	fun `unknown confirmation tasks return false without consuming a proof`() {
		Mockito.`when`(nodes.getServerFromToken("bot")).thenReturn(0)
		val response = requireNotNull(controller.verifyReg(ApiApplication.ConfirmationRequest("proof", 123, 2), "bot").block())
		assertEquals(200, response.statusCode.value())
		assertEquals("{\"result\":false}", response.body)
		Mockito.verifyNoInteractions(userProcess)
	}

	private inline fun <reified T> mock(): T = Mockito.mock(T::class.java)
}
