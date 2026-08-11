package org.qo.services.rankingServices

import org.qo.datas.Nodes
import org.qo.utils.ReturnInterface
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam

@RestController
class RankingController(
	private val service: RankingService,
	private val ri: ReturnInterface,
	private val nodes: Nodes
) {
	@GetMapping("/qo/rankings", "/qo/rankings/")
	fun leaderboards(
		@RequestParam(required = false, defaultValue = "50") limit: Int
	): ResponseEntity<String> {
		return ri.GeneralHttpHeader(service.leaderboards(limit).toString())
	}

	@GetMapping("/qo/place/download", "/qo/place/download/")
	fun downloadPlace(): ResponseEntity<String> {
		return ri.GeneralHttpHeader(service.download(RankingKind.PLACE))
	}

	@PostMapping("/qo/place/upload", "/qo/place/upload/")
	fun uploadPlace(@RequestBody body: String, @RequestHeader("Token") token: String): ResponseEntity<String> {
		if (nodes.getServerFromToken(token) < 0) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("{\"code\":401}")
		return ri.GeneralHttpHeader(service.upload(RankingKind.PLACE, body).toString())
	}

	@GetMapping("/qo/destroy/download", "/qo/destroy/download/")
	fun downloadDestroy(): ResponseEntity<String> {
		return ri.GeneralHttpHeader(service.download(RankingKind.DESTROY))
	}

	@GetMapping("/qo/playtime/download", "/qo/playtime/download/")
	fun downloadPlaytime(): ResponseEntity<String> {
		return ri.GeneralHttpHeader(service.download(RankingKind.PLAYTIME))
	}

	@PostMapping("/qo/destroy/upload", "/qo/destroy/upload/")
	fun uploadDestroy(@RequestBody body: String, @RequestHeader("Token") token: String): ResponseEntity<String> {
		if (nodes.getServerFromToken(token) < 0) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("{\"code\":401}")
		return ri.GeneralHttpHeader(service.upload(RankingKind.DESTROY, body).toString())
	}
}
