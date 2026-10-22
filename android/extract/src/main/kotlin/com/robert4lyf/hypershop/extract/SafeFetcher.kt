package com.robert4lyf.hypershop.extract

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * What the fetcher allows. Production is [STRICT]; tests loosen it to reach
 * MockWebServer on loopback over plain HTTP, and nothing else.
 */
data class FetchPolicy(
    val allowHttp: Boolean = false,
    val allowLoopback: Boolean = false,
    val allowedPorts: Set<Int> = setOf(443),
) {
    companion object {
        val STRICT = FetchPolicy()
    }
}

sealed interface FetchResult {
    data class Ok(
        val finalUrl: String,
        val contentType: String,
        val body: String,
    ) : FetchResult

    /** Refused before or during the fetch: scheme, port, raw IP, private address, too many redirects. */
    data class Blocked(
        val reason: String,
    ) : FetchResult

    data class WrongType(
        val contentType: String?,
    ) : FetchResult

    data object TooLarge : FetchResult

    data class HttpError(
        val code: Int,
    ) : FetchResult

    data object Network : FetchResult
}

/**
 * Safe fetch (PLAN §3.1): https only, re-checked after every redirect (at most
 * 5); a DNS lookup that refuses loopback, private, link-local, CGNAT, ULA and
 * multicast addresses; raw IP hosts and ports other than 443 refused; no cookies;
 * a browser user agent; no proxy (it would resolve names past the DNS check);
 * 10 s to connect and 30 s for the whole fetch, redirects included; `text/html` or
 * `application/ld+json` only, at most 3 MB after decompression.
 */
class SafeFetcher(
    private val policy: FetchPolicy = FetchPolicy.STRICT,
    base: OkHttpClient = OkHttpClient(),
) {
    private val client: OkHttpClient =
        base.newBuilder()
            .dns(SafeDns(policy))
            .proxy(Proxy.NO_PROXY)
            .followRedirects(false)
            .followSslRedirects(false)
            .cookieJar(okhttp3.CookieJar.NO_COOKIES)
            .connectTimeout(10, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_S, TimeUnit.SECONDS)
            .build()

    /** Null when [url] may be fetched, else why not. `file:`, `content:` and `intent:` never get this far. */
    fun check(url: String): String? {
        val u = url.toHttpUrlOrNull() ?: return "not an http(s) URL"
        return check(u)
    }

    private fun check(u: HttpUrl): String? {
        if (u.scheme != "https" && !(policy.allowHttp && u.scheme == "http")) return "not https"
        if (u.username.isNotEmpty() || u.password.isNotEmpty()) return "credentials in URL"
        if (u.port !in policy.allowedPorts) return "port ${u.port}"
        if (isIpLiteral(u.host)) {
            // Resolved only when loopback is allowed (tests): strict never looks the literal up.
            val loop = policy.allowLoopback && runCatching { InetAddress.getByName(u.host).isLoopbackAddress }.getOrDefault(false)
            if (!loop) return "raw IP address"
        }
        return null
    }

    /**
     * Fetches [url], following redirects itself. Runs on the IO dispatcher, and
     * cancelling the caller (a stopped import worker) cancels the request in
     * flight rather than leaving a thread blocked for the rest of the 30 s.
     */
    suspend fun fetch(url: String): FetchResult = withContext(Dispatchers.IO) { fetchBlocking(url) }

    private suspend fun fetchBlocking(url: String): FetchResult {
        var current = url.toHttpUrlOrNull() ?: return FetchResult.Blocked("not an http(s) URL")
        // One clock for the whole chain, so redirects can't stretch it to 6 × 30 s.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(CALL_TIMEOUT_S)
        repeat(MAX_REDIRECTS + 1) {
            check(current)?.let { return FetchResult.Blocked(it) }
            val req =
                Request.Builder()
                    .url(current)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/ld+json;q=0.9,*/*;q=0.1")
                    .get()
                    .build()
            val left = deadline - System.nanoTime()
            if (left <= 0) return FetchResult.Network
            val call = client.newCall(req)
            call.timeout().timeout(left, TimeUnit.NANOSECONDS)
            try {
                call.await().use { r ->
                    if (r.isRedirect) {
                        val next = r.header("Location")?.let { current.resolve(it) } ?: return FetchResult.HttpError(r.code)
                        current = next
                        return@repeat
                    }
                    if (!r.isSuccessful) return FetchResult.HttpError(r.code)
                    val ct = r.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase()
                    if (ct != "text/html" && ct != "application/ld+json") return FetchResult.WrongType(ct)
                    val body = r.body ?: return FetchResult.Network
                    // OkHttp gunzips transparently, so this counts decompressed bytes.
                    val source = body.source()
                    val buf = okio.Buffer()
                    while (source.read(buf, 64 * 1024) != -1L) {
                        if (buf.size > MAX_BYTES) return FetchResult.TooLarge
                    }
                    val charset = body.contentType()?.charset() ?: sniffCharset(buf) ?: Charsets.UTF_8
                    return FetchResult.Ok(current.toString(), ct, buf.readString(charset))
                }
            } catch (e: BlockedAddress) {
                return FetchResult.Blocked(e.message ?: "blocked address")
            } catch (e: IOException) {
                return FetchResult.Network
            }
        }
        return FetchResult.Blocked("too many redirects")
    }

    /** The response, or the IOException the call failed with; cancelling the coroutine cancels the call. */
    private suspend fun Call.await(): Response =
        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { cancel() }
            enqueue(
                object : Callback {
                    override fun onFailure(
                        call: Call,
                        e: IOException,
                    ) {
                        cont.resumeWithException(e)
                    }

                    override fun onResponse(
                        call: Call,
                        response: Response,
                    ) {
                        // Resumed after a cancellation, the body would leak: close it instead.
                        if (!cont.isActive) response.close() else cont.resume(response)
                    }
                },
            )
        }

    private class BlockedAddress(
        msg: String,
    ) : UnknownHostException(msg)

    /** Resolves through the system DNS and refuses any answer in a blocked range. */
    private class SafeDns(
        private val policy: FetchPolicy,
    ) : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val all = Dns.SYSTEM.lookup(hostname)
            val ok = all.filter { isPublic(it) || (policy.allowLoopback && it.isLoopbackAddress) }
            if (ok.isEmpty()) throw BlockedAddress("$hostname resolves to a private address")
            return ok
        }
    }

    /**
     * A page served without a charset in its header may still declare one in its
     * head (`<meta charset>` or `http-equiv`), as older recipe blogs do; decoding
     * such a page as UTF-8 turns every "½" and "é" into a replacement character.
     * Looked for in the first 1024 bytes, as browsers do; an unknown name is ignored.
     */
    private fun sniffCharset(buf: okio.Buffer): java.nio.charset.Charset? {
        val head = buf.copy().readString(minOf(buf.size, 1024L), Charsets.ISO_8859_1)
        val name = META_CHARSET.find(head)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() } ?: return null
        return runCatching { java.nio.charset.Charset.forName(name) }.getOrNull()
    }

    companion object {
        private val META_CHARSET =
            Regex(
                """<meta[^>]+charset\s*=\s*["']?([A-Za-z0-9_\-:.]+)|<meta[^>]+content\s*=\s*["'][^"']*charset=([A-Za-z0-9_\-:.]+)""",
                RegexOption.IGNORE_CASE,
            )
        const val MAX_REDIRECTS = 5
        const val CALL_TIMEOUT_S = 30L
        const val MAX_BYTES = 3L * 1024 * 1024
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0 Mobile Safari/537.36"

        /**
         * IPv6, or any host whose last label is a number: that covers dotted
         * quads and the forms resolvers also accept (2130706433, 0x7f.1,
         * 127.1, 0177.0.0.1). No real top-level domain is numeric.
         */
        fun isIpLiteral(host: String): Boolean {
            if (host.contains(':')) return true
            val last = host.trimEnd('.').substringAfterLast('.')
            return NUMERIC_LABEL.matches(last)
        }

        private val NUMERIC_LABEL = Regex("(?i)\\d+|0x[0-9a-f]*")

        /** Public unicast only: not loopback, RFC1918, link-local, CGNAT, ULA, multicast, unspecified or reserved. */
        fun isPublic(a: InetAddress): Boolean {
            val special = a.isLoopbackAddress || a.isAnyLocalAddress || a.isLinkLocalAddress || a.isSiteLocalAddress
            if (special || a.isMulticastAddress) return false
            val b = a.address.map { it.toInt() and 0xff }
            return when (a) {
                is Inet4Address -> isPublicV4(b)
                is Inet6Address -> {
                    // IPv4-mapped (::ffff:a.b.c.d), NAT64 (64:ff9b::/96) and 6to4 (2002::/16) carry a v4 address.
                    val mapped = b.subList(0, 10).all { it == 0 } && b[10] == 0xff && b[11] == 0xff
                    if (mapped) return isPublicV4(b.subList(12, 16))
                    val nat64 = b[0] == 0 && b[1] == 0x64 && b[2] == 0xff && b[3] == 0x9b
                    if (nat64 && b.subList(4, 12).all { it == 0 }) return isPublicV4(b.subList(12, 16))
                    if (b[0] == 0x20 && b[1] == 0x02) return isPublicV4(b.subList(2, 6))
                    when {
                        nat64 -> false // local-use NAT64 64:ff9b:1::/48
                        (b[0] and 0xfe) == 0xfc -> false // ULA fc00::/7
                        b[0] == 0xfe && (b[1] and 0xc0) == 0x80 -> false // fe80::/10
                        b[0] == 0x20 && b[1] == 0x01 && b[2] == 0x0d && b[3] == 0xb8 -> false // documentation
                        b.subList(0, 12).all { it == 0 } -> false // ::/96, IPv4-compatible
                        else -> true
                    }
                }
                else -> false
            }
        }

        private fun isPublicV4(b: List<Int>): Boolean =
            when {
                b[0] == 0 || b[0] == 10 || b[0] == 127 -> false
                b[0] == 100 && b[1] in 64..127 -> false // CGNAT 100.64/10
                b[0] == 169 && b[1] == 254 -> false
                b[0] == 172 && b[1] in 16..31 -> false
                b[0] == 192 && b[1] == 168 -> false
                b[0] == 192 && b[1] == 0 && b[2] == 0 -> false
                b[0] >= 224 -> false // multicast and reserved
                else -> true
            }
    }
}
