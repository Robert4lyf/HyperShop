package com.robert4lyf.hypershop.extract

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Step 0.4: proves MockWebServer + OkHttp work in :extract. */
class HelloMockWebServerTest {
    @Test
    fun servesAResponse() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("hello"))
            server.start()
            val body =
                OkHttpClient()
                    .newCall(Request.Builder().url(server.url("/")).build())
                    .execute()
                    .use { it.body!!.string() }
            assertEquals("hello", body)
        }
    }
}
