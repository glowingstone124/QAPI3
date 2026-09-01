package org.qo

import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import kotlin.test.Test
import kotlin.test.assertEquals

class AvatarPublicBaseUrlTest {
    @Test
    fun `forwarded https scheme is used for avatar urls`() {
        val request = MockServerHttpRequest
            .get("http://api.qoriginal.vip/qo/download/avatar?name=Koishi")
            .header("X-Forwarded-Proto", "https")
            .build()

        assertEquals("https://api.qoriginal.vip", ApiApplication.avatarPublicBaseUrl(request))
    }

    @Test
    fun `direct request scheme is retained without proxy header`() {
        val request = MockServerHttpRequest
            .get("https://api.qoriginal.vip/qo/download/avatar?name=Koishi")
            .build()

        assertEquals("https://api.qoriginal.vip", ApiApplication.avatarPublicBaseUrl(request))
    }
}
