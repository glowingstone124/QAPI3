package org.qo.services.metroServices

import org.qo.utils.ReturnInterface
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
@RestController
class MetroServiceController(private val serviceImpl: MetroServiceImpl, private val ri: ReturnInterface) {
	@GetMapping("/qo/metro/download")
	fun downloadMetro(): ResponseEntity<String> {
		return ri.GeneralHttpHeader(serviceImpl.getMetroJson())
	}

}