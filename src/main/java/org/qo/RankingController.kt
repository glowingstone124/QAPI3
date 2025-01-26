package org.qo

import com.google.gson.Gson
import org.qo.RankingImpl.Rank
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
val gson = Gson()
@RestController
class RankingController(private val rankingImpl: RankingImpl) {
	@GetMapping("/qo/place/download")
	suspend fun getPlaceRanking(): ResponseEntity<String> {
		return ReturnInterface().GeneralHttpHeader(rankingImpl.getRanking(Rank.PLACE).toJsonObject())
	}
	@GetMapping("/qo/destroy/download")
	suspend fun getDestroyRanking(): ResponseEntity<String> {
		return ReturnInterface().GeneralHttpHeader(rankingImpl.getRanking(Rank.DESTROY).toJsonObject())
	}
	@PostMapping("/qo/destroy/upload")
	suspend fun insertDestroyRanking(@RequestBody body: String) {
		rankingImpl.insertDestroyRanking(body)
	}
	@PostMapping("/qo/place/upload")
	suspend fun insertPlaceRanking(@RequestBody body: String) {
		rankingImpl.insertPlaceRanking(body)
	}
}
fun Map<*,*>.toJsonObject(): String {
	return gson.toJson(this)
}