package org.qo.services.metroServices

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController("/qo/metro")
class MetroServiceController(private val serviceImpl: MetroServiceImpl) {
	@GetMapping("/download")
	fun downloadMetro(): String {
		return serviceImpl.getMetroJson()
	}

}