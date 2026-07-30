package org.qo.datas

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals

class MessageInTest {
	@Test
	fun `message output keeps only safe image links`() {
		val message = MessageIn(
			message = "[图片]",
			from = 0,
			token = "secret",
			data = "qq_chat",
			time = 123L,
			sender = "10001",
			images = listOf(
				"http://baidu.com/1.jpg",
				"javascript:alert(1)",
				"http://baidu.com/1.jpg",
			),
		)

		val output = message.doHideToken()
		val images = output.getAsJsonArray("images")

		assertEquals(1, images.size())
		assertEquals("http://baidu.com/1.jpg", images[0].asString)
		assertEquals(false, output.has("token"))
	}

	@Test
	fun `legacy message output has an empty image list`() {
		val input = JsonParser.parseString(
			"""{"message":"hello","from":0,"token":"secret","type":"qq_chat","time":123,"sender":"10001"}"""
		).asJsonObject
		val message = com.google.gson.Gson().fromJson(input, MessageIn::class.java)

		assertEquals(0, message.doHideToken().getAsJsonArray("images").size())
	}
}
