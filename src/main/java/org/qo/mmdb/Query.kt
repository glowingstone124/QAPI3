package org.qo.mmdb

import org.qo.mmdb.Init.Companion.reader
import java.net.InetAddress

object Query {
	fun getIpInfo(ip: String): IPInfo {
		val ip = InetAddress.getByName(ip)
		val response = reader.city(ip)
		return IPInfo(
			response.country.names["zh-CN"],
			response.mostSpecificSubdivision.names["zh-CN"],
			response.city.names["zh-CN"]
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