package org.qo.mmdb

import org.qo.mmdb.Init.reader
import java.net.InetAddress

object Query {
	fun getIpInfo(ip: String): IPInfo {
		val ip = InetAddress.getByName(ip)
		val response = reader.country(ip)
		return IPInfo(
			response.country.names["zh-CN"],
		)
	}
}

data class IPInfo(
	val country: String? = "",
	val subdivision: String? = "",
	val city: String? = "",
) {
	fun humanReadable(): String {
		return "$country $subdivision $city"
	}
}