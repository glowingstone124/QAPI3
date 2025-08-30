package org.qo.services.combatzoneServices

import kotlinx.io.IOException
import org.qo.datas.Nodes
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.nio.file.Files
import java.nio.file.Path

@RestController
@RequestMapping("/qo/combatzone")
class CombatZoneServiceImpl(private val nodes: Nodes) {
	val TEMPDIR = Path.of("combatzone_data.json")
	@RequestMapping("/download")
	fun getCombatZoneData(): String {
		return try {
			if (Files.exists(TEMPDIR)) {
				Files.readString(TEMPDIR)
			} else {
				""
			}
		} catch (e: IOException) {
			""
		}
	}
	@RequestMapping("/upload")
	fun setCombatZoneData(@RequestBody body: String, token: String) {
		if(nodes.getServerFromToken(token) != 1) {
			return
		}
		try {
			Files.createDirectories(TEMPDIR.parent)
			Files.writeString(TEMPDIR, body)
		} catch (e: IOException) {
			e.printStackTrace()
		}
	}
}