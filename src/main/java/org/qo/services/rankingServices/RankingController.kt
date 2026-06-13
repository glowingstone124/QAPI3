package org.qo.services.rankingServices

import org.qo.utils.ReturnInterface
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class RankingController(
	private val service: RankingService,
	private val ri: ReturnInterface
) {
	@GetMapping("/qo/place/download", "/qo/place/download/")
	fun downloadPlace(): ResponseEntity<String> {
		return ri.GeneralHttpHeader(service.download(RankingKind.PLACE))
	}

	@PostMapping("/qo/place/upload", "/qo/place/upload/")
	fun uploadPlace(@RequestBody body: String): ResponseEntity<String> {
		return ri.GeneralHttpHeader(service.upload(RankingKind.PLACE, body).toString())
	}

	@GetMapping("/qo/destroy/download", "/qo/destroy/download/")
	fun downloadDestroy(): ResponseEntity<String> {
		return ri.GeneralHttpHeader(service.download(RankingKind.DESTROY))
	}

	@PostMapping("/qo/destroy/upload", "/qo/destroy/upload/")
	fun uploadDestroy(@RequestBody body: String): ResponseEntity<String> {
		return ri.GeneralHttpHeader(service.upload(RankingKind.DESTROY, body).toString())
	}
}
