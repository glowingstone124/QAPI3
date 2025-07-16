package org.qo.services.mmdb

import com.maxmind.geoip2.DatabaseReader
import org.qo.utils.Logger
import org.qo.utils.Logger.LogLevel.*
import org.qo.utils.Request
import org.springframework.scheduling.annotation.Scheduled
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.time.Duration
import java.time.Instant
import kotlin.io.path.deleteExisting
import kotlin.io.path.exists

object Init {
	val geoip_db_url = "https://cdn.jsdelivr.net/gh/Hackl0us/GeoIP2-CN@release/Country.mmdb"
	val geoip_db_local = "Country.mmdb"
	var geoip_enabled = false
	lateinit var reader: DatabaseReader

	fun init() {
		Logger.log("GeoIP database loading...", INFO)
		if (!Path.of(geoip_db_local).exists()) {
			Logger.log("NO local GeoIP database found.", INFO)
			Logger.log("Downloading...", INFO)
			downloadMmdb()
		} else {
			initializeDatabase()
		}
		renewMmdb()
	}

	private fun downloadMmdb() {
		Request().download(geoip_db_url, geoip_db_local).completeAsync {
			Logger.log("Download complete.", INFO)
			initializeDatabase()
		}
	}
	@Scheduled(fixedRate = 60 * 60 * 1000)
	private fun renewMmdb() {
		val path = File(geoip_db_local).toPath()

		if (!Files.exists(path)) {
			downloadMmdb()
			return
		}

		val attrs = Files.readAttributes(path, BasicFileAttributes::class.java)
		val hoursSinceLastModified = Duration.between(attrs.lastModifiedTime().toInstant(), Instant.now()).toHours()

		if (hoursSinceLastModified >= 72) {
			Files.deleteIfExists(path)
			downloadMmdb()
		}

		Logger.log("Renewed mmdb database.", INFO)
	}

	private fun initializeDatabase() {
		try {
			reader = DatabaseReader.Builder(File(geoip_db_local)).build()
			Logger.log("GeoIP database loaded successfully.", INFO)
		} catch (e: IOException) {
			Logger.log("GeoIP database error: ${e.message}", WARNING)
		}
	}
}
