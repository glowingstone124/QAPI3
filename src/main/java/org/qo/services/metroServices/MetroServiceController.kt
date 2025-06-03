package org.qo.services.metroServices

import org.qo.datas.Nodes
import org.qo.utils.ReturnInterface
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

@RestController
class MetroServiceController(private val serviceImpl: MetroServiceImpl, private val ri: ReturnInterface, private val nodes: Nodes) {
	@GetMapping("/qo/metro/download")
	fun downloadMetro(): ResponseEntity<String> {
		return ri.GeneralHttpHeader(serviceImpl.getMetroJson())
	}
	@PostMapping("/qo/metro/upload")
	fun uploadMetro(@RequestBody data: String, @RequestHeader("Token") token: String): ResponseEntity<String> {
		return ri.GeneralHttpHeader(serviceImpl.preInsertCheck(data, token))
	}
}