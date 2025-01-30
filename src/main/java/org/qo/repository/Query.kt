package org.qo.mmdb

import org.qo.config.Init.reader
import java.net.InetAddress

object Query {
	fun getIpInfo(ip: String): IPInfo {
		val inetAddress = InetAddress.getByName(ip)
		val response = reader.country(inetAddress)

		val country = response.country.names["zh-CN"]
		return IPInfo(
			country = country,
		)
	}

	fun isCN(ip: String): Boolean {
		return try {
			val inetAddress = InetAddress.getByName(ip)
			val response = reader.country(inetAddress)

			response.country.isoCode == "CN"
		} catch (e: Exception) {
			false 
		}
	}
}

data class IPInfo(
	val country: String? = "",
	val subdivision: String? = "",
	val city: String? = ""
) {
	fun humanReadable(): String {
		return "$country $subdivision $city"
	}
}
