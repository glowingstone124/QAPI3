package org.qo.services.llmServices

import org.qo.services.loginService.AuthorityNeededServicesImpl
import org.springframework.stereotype.Service

@Service
class LLMServices(private val authorityNeededServicesImpl: AuthorityNeededServicesImpl){
	suspend fun generalPreProcess(token: String) : Boolean {
		val result = authorityNeededServicesImpl.internalAuthorityCheck(token)
		if (!result.second) {
			return false
		}
		result.first?.let {
			return (!it.frozen!!)
		}
		//This should never be reached
		return false
	}
	suspend fun prepareDocuments() {

	}
}