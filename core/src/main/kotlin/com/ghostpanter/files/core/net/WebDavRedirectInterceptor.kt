package com.ghostpanter.files.core.net

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Marks a [okhttp3.RequestBody] that can be written exactly once (streaming uploads fed from a
 * bounded queue). Re-sending such a body after a redirect would block forever on an empty queue,
 * so the redirect interceptor refuses to replay it and asks the caller to retry instead.
 */
interface OneShotBody

/**
 * The 30x chain ran past `maxHops`. Retrying through the gateway would loop the same way, so this
 * is never treated as a stale-hop failure worth a second attempt.
 */
class TooManyRedirectsException(message: String) : IOException(message)

/**
 * A streaming body had already been written when a 30x arrived, so it cannot be replayed. The hop
 * is cached; the transfer layer is expected to retry. Never retried internally — the queue behind
 * the body is already drained and re-sending would block forever.
 */
class BodyNotReplayableException(message: String) : IOException(message)

/**
 * Process-wide hops learned from WebDAV gateways.
 *
 * Browsing reuses one client instance, but every transfer job opens a *dedicated* connection
 * (`FsManager.openDedicated`), so a per-instance cache would be cold each time and every upload or
 * download would pay an extra gateway round trip just to re-learn the hop. [RedirectRouteCache]
 * keys by origin, so different servers never collide.
 *
 * [com.ghostpanter.files.core.WebDavFs.reset] clears it, which is the intended way to forget
 * everything after a backend move.
 */
object SharedRedirectRoutes {
    val cache = RedirectRouteCache()
}

/**
 * Remembers `gateway → backend` hops learned from 30x responses.
 *
 * The WebDAV client talks to a public gateway that 307s every request to the real backend. GETs and
 * PROPFINDs can simply follow that hop each time, but a streaming PUT cannot: its body is already
 * consumed. Recording the hop lets [com.ghostpanter.files.core.WebDavFs] probe once and then aim the
 * PUT straight at the backend.
 */
class RedirectRouteCache {
    private val exact = ConcurrentHashMap<String, HttpUrl>()
    private val origin = ConcurrentHashMap<String, HttpUrl>()

    fun record(from: HttpUrl, to: HttpUrl) {
        exact[from.toString()] = to
        // Same path, different scheme/host/port → the hop is a pure origin swap, so it can be
        // applied to any sibling path of the same origin.
        if (from.encodedPath == to.encodedPath) origin[originKey(from)] = to
    }

    /** Final URL for [url], or null when this hop is still unknown. */
    fun resolve(url: HttpUrl): HttpUrl? {
        exact[url.toString()]?.let { return it }
        val o = origin[originKey(url)] ?: return null
        return url.newBuilder().scheme(o.scheme).host(o.host).port(o.port).build()
    }

    /**
     * Forgets the hop recorded for [url].
     *
     * Called when a request aimed straight at the learned backend failed at the connection level:
     * the backend may have moved or the gateway may now point somewhere else, so the next attempt
     * must go through the gateway again and re-learn the hop.
     */
    fun invalidate(url: HttpUrl) {
        exact.remove(url.toString())
        origin.remove(originKey(url))
    }

    fun clear() {
        exact.clear()
        origin.clear()
    }

    private fun originKey(u: HttpUrl) = "${u.scheme}://${u.host}:${u.port}"
}

/**
 * Manual redirect handling for WebDAV.
 *
 * OkHttp's built-in follow-up happens *below* application interceptors and deliberately drops
 * credentials whenever the origin changes (OkHttp 4.12
 * `RetryAndFollowUpInterceptor.buildRedirectRequest`: "When redirecting across hosts, drop all
 * authentication headers"). Our gateway redirects to a different host/port, so Basic Auth vanished
 * and every redirected PUT came back 401. Application interceptors never re-run for OkHttp's
 * internal follow-up either, so re-adding the header there could not fix it.
 *
 * This interceptor therefore owns the whole flow:
 *  - the client is built with `followRedirects(false)` / `followSslRedirects(false)`
 *  - 301/302/303/307/308 are followed here, re-issuing the request with **every** original header
 *    copied verbatim, `Authorization` included
 *  - the method and body are preserved (a PUT must never be downgraded to GET — the server would
 *    answer 200 while nothing was written)
 *  - a body that cannot be replayed is never re-sent; the hop is recorded and the call fails fast
 *  - each hop is logged with URL / method / auth state / Location / final status
 */
class WebDavRedirectInterceptor(
    private val routeCache: RedirectRouteCache = RedirectRouteCache(),
    /** Known credentials, only used to make logs readable — never used to sign requests here. */
    private val username: String = "",
    private val password: String = "",
    private val maxHops: Int = 5,
    /**
     * Guard against handing Basic Auth to whatever host the gateway names. Defaults to forwarding,
     * which the SNI + 307 gateway needs; tighten it if the backend host set is known.
     */
    private val forwardAuthTo: (target: HttpUrl) -> Boolean = { true },
) : Interceptor {

    fun routes(): RedirectRouteCache = routeCache

    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        var hop = 0
        while (true) {
            NetLog.d { "-> ${request.method} ${request.url} | ${authState(request)}" }
            val startedAt = System.nanoTime()
            val response = chain.proceed(request)
            val elapsed = (System.nanoTime() - startedAt) / 1_000_000

            if (response.code !in REDIRECT_CODES) {
                NetLog.d { "<- ${response.code} ${request.method} ${request.url} (${elapsed}ms)" }
                if (response.code == 401) {
                    NetLog.w { "!! 401 ${request.method} ${request.url} | ${authState(request)} — 服务器拒绝了凭据" }
                }
                return response
            }

            val location = response.header("Location")
            if (location.isNullOrBlank()) {
                NetLog.d { "<- ${response.code} ${request.method} ${request.url} (${elapsed}ms) 无 Location，按最终响应返回" }
                return response
            }
            val target = request.url.resolve(location)
            if (target == null) {
                NetLog.w { "!! ${response.code} Location 无法解析: $location" }
                return response
            }

            routeCache.record(request.url, target)
            hop++
            val body = request.body
            val replayable = body == null || body !is OneShotBody
            NetLog.d {
                "=> ${response.code} ${request.url.host} -> ${target.host} | Location=$target " +
                    "| hop=$hop/$maxHops method=${request.method} auth=${if (request.header("Authorization") != null) "kept" else "none"} " +
                    "replayable=$replayable"
            }

            response.close()

            if (hop > maxHops) {
                throw TooManyRedirectsException("重定向次数超过 $maxHops: ${request.url} (最后目标 $target)")
            }
            if (!replayable) {
                // Body is a one-shot stream that has already been written. Silently retrying would
                // hang on the drained queue; surface it so the transfer layer re-runs openWrite(),
                // which now finds the cached route and goes straight to the backend.
                throw BodyNotReplayableException(
                    "HTTP ${response.code} 重定向到 $target，但流式上传的 body 无法重放；已缓存跳转地址，请重试该传输"
                )
            }
            request = followUp(request, target)
        }
    }

    /** Rebuilds [original] for [target], keeping method, body and every header. */
    private fun followUp(original: Request, target: HttpUrl): Request {
        val keepAuth = forwardAuthTo(target)
        if (!keepAuth) NetLog.w { "!! 目标主机 ${target.host} 不在信任范围，已丢弃 Authorization" }
        return retarget(original, target, keepAuth)
    }

    private fun authState(request: Request): String {
        val header = request.header("Authorization") ?: return "auth=MISSING"
        val scheme = header.substringBefore(' ')
        val user = username.ifEmpty { "?" }
        // Basic credentials are base64 — log who and a masked secret, never the raw header.
        return "auth=$scheme user=$user pw=${NetLog.mask(password)}"
    }

    companion object {
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}

/**
 * Rebuilds [original] so it points at [target], keeping method, body and every header.
 *
 * Shared by the redirect interceptor (a 30x hop) and by `WebDavFs` (aiming a request straight at a
 * backend learned from an earlier hop) so both paths move `Authorization` and `Destination` alike.
 *
 * - `Authorization` is re-asserted explicitly: that is precisely the header OkHttp used to drop, and
 *   `header()` replaces rather than appends, so it never appears twice.
 * - `Destination` is absolute in WebDAV MOVE/COPY. It points at the gateway in the original request,
 *   so on the backend hop it must be rewritten too, otherwise the server treats it as cross-server.
 */
internal fun retarget(original: Request, target: HttpUrl, keepAuth: Boolean = true): Request {
    // newBuilder() already copies all headers.
    val builder = original.newBuilder().url(target)

    val auth = original.header("Authorization")
    when {
        auth == null -> Unit
        keepAuth -> builder.header("Authorization", auth)
        else -> builder.removeHeader("Authorization")
    }

    original.header("Destination")?.let { dest ->
        val d = dest.toHttpUrlOrNull()
        if (sameOrigin(original.url, d)) {
            builder.header(
                "Destination",
                d!!.newBuilder().scheme(target.scheme).host(target.host).port(target.port).build().toString(),
            )
        }
    }

    return builder.build()
}

private fun sameOrigin(source: HttpUrl, dest: HttpUrl?): Boolean =
    dest != null && dest.scheme == source.scheme && dest.host == source.host && dest.port == source.port
