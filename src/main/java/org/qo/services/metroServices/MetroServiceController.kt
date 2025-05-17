package org.qo.services.metroServices

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
@RestController
class MetroServiceController(private val serviceImpl: MetroServiceImpl) {
	@GetMapping("/qo/metro/download")
	fun downloadMetro(): String {
		return serviceImpl.getMetroJson()
	}

}