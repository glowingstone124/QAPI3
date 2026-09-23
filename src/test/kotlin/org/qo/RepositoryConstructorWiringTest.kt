package org.qo

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.qo.datas.Nodes
import org.qo.db.repository.LoginSecurityDbRepository
import org.qo.db.repository.UserDbRepository
import org.qo.services.loginService.AuthorityNeededServicesImpl
import org.qo.services.loginService.FortuneTools
import org.qo.services.loginService.Login
import org.qo.services.playerStatistics.PlayerStatisticsService
import org.qo.utils.ReturnInterface
import org.qo.utils.UserProcessReactiveStore
import org.springframework.context.annotation.AnnotationConfigApplicationContext

class RepositoryConstructorWiringTest {
	@Test
	fun `repository backed services select their production constructors`() {
		AnnotationConfigApplicationContext().use { context ->
			context.beanFactory.registerSingleton("userDbRepository", mock(UserDbRepository::class.java))
			context.beanFactory.registerSingleton("loginSecurityDbRepository", mock(LoginSecurityDbRepository::class.java))
			context.beanFactory.registerSingleton("login", mock(Login::class.java))
			context.beanFactory.registerSingleton("returnInterface", mock(ReturnInterface::class.java))
			context.beanFactory.registerSingleton("fortuneTools", mock(FortuneTools::class.java))
			context.beanFactory.registerSingleton("nodes", mock(Nodes::class.java))
			context.beanFactory.registerSingleton("playerStatisticsService", mock(PlayerStatisticsService::class.java))
			context.register(UserProcessReactiveStore::class.java, AuthorityNeededServicesImpl::class.java)
			context.refresh()
			assertNotNull(context.getBean(UserProcessReactiveStore::class.java))
			assertNotNull(context.getBean(AuthorityNeededServicesImpl::class.java))
		}
	}
}
