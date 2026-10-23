package com.robert4lyf.hypershop.extract

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.GzipSink
import okio.buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetAddress

class SafeFetcherTest {
    private lateinit var server: MockWebServer
    private lateinit var fetcher: SafeFetcher

    @BeforeEach
    fun up() {
        server = MockWebServer()
        server.start()
        // Tests reach MockWebServer over http on loopback, on its own port; nothing else is loosened.
        fetcher = SafeFetcher(FetchPolicy(allowHttp = true, allowLoopback = true, allowedPorts = setOf(server.port)))
    }

    @AfterEach
    fun down() = server.shutdown()

    private fun html(body: String = "<html><title>ok</title></html>") =
        MockResponse().setHeader(
            "Content-Type",
            "text/html; charset=utf-8",
        ).setBody(body)

    @Test
    fun aPageDeclaringItsCharsetOnlyInItsHeadIsDecodedWithIt() {
        // An older blog: no charset in the header, windows-1252 bytes, the charset in a meta tag.
        val page = "<html><head><meta charset=\"windows-1252\"><title>ok</title></head><body>\u00bd cup cr\u00e8me</body></html>"
        val bytes = page.toByteArray(charset("windows-1252"))
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody(okio.Buffer().write(bytes)))
        val r = runBlocking { fetcher.fetch(server.url("/old").toString()) } as FetchResult.Ok
        assertTrue(r.body.contains("\u00bd cup cr\u00e8me"), r.body)
        // The header still wins when it says.
        val declared = "<meta charset=\"windows-1252\">\u00bd"
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html; charset=utf-8").setBody(declared))
        assertEquals(declared, (runBlocking { fetcher.fetch(server.url("/new").toString()) } as FetchResult.Ok).body)
    }

    @Test
    fun fetchesHtml() {
        server.enqueue(html())
        val r = runBlocking { fetcher.fetch(server.url("/r").toString()) } as FetchResult.Ok
        assertEquals("text/html", r.contentType)
        val req = server.takeRequest()
        assertTrue(req.getHeader("User-Agent")!!.contains("Mozilla"))
        assertNull(req.getHeader("Cookie"))
    }

    @Test
    fun redirectToAPrivateIpIsRefused() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://10.0.0.1/admin"))
        assertTrue(runBlocking { fetcher.fetch(server.url("/").toString()) } is FetchResult.Blocked)
        server.enqueue(MockResponse().setResponseCode(301).setHeader("Location", "http://192.168.1.1:${server.port}/"))
        assertTrue(runBlocking { fetcher.fetch(server.url("/").toString()) } is FetchResult.Blocked)
    }

    @Test
    fun redirectsAreFollowedAndCappedAtFive() {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/next")) }
        server.enqueue(html())
        assertTrue(runBlocking { fetcher.fetch(server.url("/").toString()) } is FetchResult.Ok)
        repeat(6) { server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/again")) }
        assertEquals(FetchResult.Blocked("too many redirects"), runBlocking { fetcher.fetch(server.url("/").toString()) })
    }

    @Test
    fun strictPolicyRefusesWithoutConnecting() {
        val strict = SafeFetcher()
        assertNotNull(strict.check("file:///etc/passwd"))
        assertNotNull(strict.check("content://com.android.contacts/1"))
        assertNotNull(strict.check("intent://scan/#Intent;scheme=zxing;end"))
        assertNotNull(strict.check("http://example.com/recipe"))
        assertNotNull(strict.check("https://93.184.216.34/recipe"), "raw IPv4")
        assertNotNull(strict.check("https://[2606:2800:220:1::248]/"), "raw IPv6")
        assertNotNull(strict.check("https://example.com:8443/recipe"), "port")
        assertNotNull(strict.check("https://user:pw@example.com/"))
        assertNull(strict.check("https://www.bbcgoodfood.com/recipes/easy-chocolate-cake"))
        assertTrue(runBlocking { strict.fetch("file:///etc/passwd") } is FetchResult.Blocked)
        // Loopback through a name is refused by the DNS filter.
        assertTrue(runBlocking { strict.fetch("https://localhost/") } is FetchResult.Blocked)
    }

    @Test
    fun addressRanges() {
        val blocked =
            listOf(
                "127.0.0.1", "10.1.2.3", "172.16.0.1", "172.31.255.255", "192.168.0.10", "169.254.169.254", "100.64.0.1",
                "100.127.255.255", "0.0.0.0", "224.0.0.1", "255.255.255.255", "::1", "fe80::1", "fc00::1", "fd12:3456::1",
                "::ffff:10.0.0.1", "ff02::1",
            )
        for (a in blocked) assertFalse(SafeFetcher.isPublic(InetAddress.getByName(a)), a)
        for (a in listOf("93.184.216.34", "8.8.8.8", "100.128.0.1", "2606:2800:220:1::248")) {
            assertTrue(SafeFetcher.isPublic(InetAddress.getByName(a)), a)
        }
    }

    @Test
    fun numericHostFormsAreRawIps() {
        val strict = SafeFetcher()
        for (h in listOf("2130706433", "0x7f000001", "0x7f.1", "127.1", "0177.0.0.1", "10.0.0.1.")) {
            assertNotNull(strict.check("https://$h/"), h)
        }
        assertNull(strict.check("https://123recipes.com/"))
        assertNull(strict.check("https://www.food52.com/"))
    }

    @Test
    fun v4InsideV6IsCheckedAsV4() {
        for (a in listOf("64:ff9b::a00:1", "64:ff9b::7f00:1", "64:ff9b:1::808:808", "2002:a00:1::", "2002:7f00:1::1")) {
            assertFalse(SafeFetcher.isPublic(InetAddress.getByName(a)), a)
        }
        for (a in listOf("64:ff9b::808:808", "2002:808:808::1")) assertTrue(SafeFetcher.isPublic(InetAddress.getByName(a)), a)
    }

    @Test
    fun nonHtmlIsRefused() {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/pdf").setBody("%PDF"))
        assertEquals(FetchResult.WrongType("application/pdf"), runBlocking { fetcher.fetch(server.url("/").toString()) })
        server.enqueue(MockResponse().setHeader("Content-Type", "application/ld+json").setBody("{}"))
        assertTrue(runBlocking { fetcher.fetch(server.url("/").toString()) } is FetchResult.Ok)
    }

    @Test
    fun gzipBombIsCappedAfterDecompression() {
        val zipped = Buffer()
        GzipSink(zipped).buffer().use { sink ->
            val zeros = ByteArray(1024 * 1024)
            repeat(20) { sink.write(zeros) }
        }
        assertTrue(zipped.size < 100_000)
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setHeader("Content-Encoding", "gzip").setBody(zipped))
        assertEquals(FetchResult.TooLarge, runBlocking { fetcher.fetch(server.url("/").toString()) })
    }

    @Test
    fun httpErrors() {
        server.enqueue(MockResponse().setResponseCode(403))
        assertEquals(FetchResult.HttpError(403), runBlocking { fetcher.fetch(server.url("/").toString()) })
    }

    @Test
    fun aRecipePhotoIsFetchedAsAnImageOnly() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3)
        server.enqueue(MockResponse().setHeader("Content-Type", "image/jpeg").setBody(Buffer().write(jpeg)))
        val r = runBlocking { fetcher.fetchImage(server.url("/p.jpg").toString()) } as FetchResult.Image
        assertEquals("image/jpeg", r.contentType)
        assertTrue(r.bytes.contentEquals(jpeg))
        // A page is not a photo, a photo is not a page.
        server.enqueue(html())
        assertTrue(runBlocking { fetcher.fetchImage(server.url("/page").toString()) } is FetchResult.WrongType)
        server.enqueue(MockResponse().setHeader("Content-Type", "image/svg+xml").setBody("<svg/>"))
        assertTrue(runBlocking { fetcher.fetchImage(server.url("/x.svg").toString()) } is FetchResult.WrongType)
        server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(jpeg)))
        assertTrue(runBlocking { fetcher.fetch(server.url("/p.png").toString()) } is FetchResult.WrongType)
        // Capped.
        server.enqueue(
            MockResponse().setHeader(
                "Content-Type",
                "image/png",
            ).setBody(Buffer().write(ByteArray((SafeFetcher.MAX_IMAGE_BYTES + 1).toInt()))),
        )
        assertEquals(FetchResult.TooLarge, runBlocking { fetcher.fetchImage(server.url("/big.png").toString()) })
        // And held to the same rules as a page.
        assertTrue(runBlocking { SafeFetcher().fetchImage("http://example.com/p.jpg") } is FetchResult.Blocked)
        assertTrue(runBlocking { SafeFetcher().fetchImage("https://10.0.0.1/p.jpg") } is FetchResult.Blocked)
    }
}
