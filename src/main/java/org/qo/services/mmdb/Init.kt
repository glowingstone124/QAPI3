package org.qo.services.mmdb

import com.maxmind.geoip2.DatabaseReader
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.qo.utils.Logger
import org.qo.utils.Logger.LogLevel.*
import org.springframework.scheduling.annotation.Scheduled
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.time.Duration
import java.time.Instant
import kotlin.io.path.exists

object Init {
	private val geoipDbUrl = "https://cdn.jsdelivr.net/gh/Hackl0us/GeoIP2-CN@release/Country.mmdb"
	private val geoipDbLocal = "Country.mmdb"
	private val downloadMutex = Mutex()
	private var geoipEnabled = false
	private lateinit var reader: DatabaseReader

	suspend fun init() {
		Logger.log("GeoIP database loading...", INFO)

		val path = Path.of(geoipDbLocal)

		if (!path.exists()) {
			Logger.log("NO local GeoIP database found. Downloading...", INFO)
			downloadAndInitializeIfNeeded(force = true)
		} else {
			initializeDatabase()
		}
	}

	@Scheduled(fixedRate = 60 * 60 * 1000)
	fun scheduledRenewMmdb() {
		GlobalScope.launch {
			try {
				downloadAndInitializeIfNeeded()
			} catch (e: Exception) {
				Logger.log("Scheduled renew failed: ${e.message}", WARNING)
			}
		}
	}

	private suspend fun downloadAndInitializeIfNeeded(force: Boolean = false) {
		val path = Path.of(geoipDbLocal)

		if (!path.exists() || force || isFileOlderThanHours(path, 72)) {
			Logger.log("Downloading GeoIP database...", INFO)

			downloadMutex.withLock {
				if (!path.exists() || force || isFileOlderThanHours(path, 72)) {
					downloadMmdb()
				}
			}
		} else {
			Logger.log("GeoIP database is up-to-date.",INFO )
		}
	}

	private suspend fun downloadMmdb() = withContext(Dispatchers.IO) {
		try {
			val url = URI.create(geoipDbUrl).toURL()
			val connection = url.openConnection() as HttpURLConnection

			connection.inputStream.use { input ->
				val targetPath = Path.of(geoipDbLocal)
				Files.copy(input, targetPath, StandardCopyOption.REPLACE_EXISTING)
			}

			Logger.log("Download complete.", INFO)
			initializeDatabase()
		} catch (e: Exception) {
			Logger.log("Download failed: ${e.message}", ERROR)
			throw e
		}
	}

	private fun initializeDatabase() {
		try {
			reader = DatabaseReader.Builder(File(geoipDbLocal)).build()
			Logger.log("GeoIP database loaded successfully.", INFO)
			geoipEnabled = true
		} catch (e: Exception) {
			Logger.log("GeoIP database load failed: ${e.message}", WARNING)
			geoipEnabled = false
		}
	}

	private fun isFileOlderThanHours(path: Path, hours: Long): Boolean {
		return try {
			val attrs = Files.readAttributes(path, BasicFileAttributes::class.java)
			val lastModified = attrs.lastModifiedTime().toInstant()
			Duration.between(lastModified, Instant.now()).toHours() >= hours
		} catch (e: Exception) {
			Logger.log("检查文件时间失败：${e.message}", WARNING)
			true
		}
	}
}
