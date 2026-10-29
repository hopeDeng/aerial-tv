package app.orchard.tv.tv

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import kotlin.math.roundToInt

/**
 * Estimates whether a source has enough bandwidth to play. The box's own player has no such check,
 * and a playlist's HTTP 200 says nothing about whether the video behind it is watchable.
 *
 * A source is sampled as if it were being watched: for HLS, the first variant and then the first
 * segment (not just the playlist); for a direct stream, the first two megabytes. Speed includes
 * connection setup, so it is a conservative number, not a peak.
 */
object TvSpeedTest {
    const val SAMPLE_BYTES = 2 * 1024 * 1024
    const val PLAYLIST_BYTES = 256 * 1024
    const val TIMEOUT_MS = 8_000
    private const val MAX_PLAYLIST_DEPTH = 3
    private const val USER_AGENT = "VLC/3.0.20 LibVLC/3.0.20"

    enum class Verdict { HD, SMOOTH, SLOW, UNUSABLE, FAILED, ENCRYPTED }

    data class Result(
        val url: String,
        val verdict: Verdict,
        /** Megabits per second actually read, or null when nothing could be measured. */
        val mbps: Double?,
        val ms: Long,
        val status: Int?,
        /** Machine-readable detail: http, timeout, html, no-segment, encrypted, error text. */
        val note: String?,
        val finalUrl: String?,
        /** Declared bandwidth, or measured from the first segment and its EXTINF duration. */
        val bitrateKbps: Int? = null,
        val width: Int? = null,
        val height: Int? = null,
    )

    data class Fetch(
        val url: String,
        val status: Int,
        val contentType: String?,
        val bytes: ByteArray,
        val elapsedMs: Long,
        val timedOut: Boolean = false,
        val error: String? = null,
        /** Full resource length when the server reports it (not just the range we sampled). */
        val contentLength: Long? = null,
    )

    fun interface Fetcher {
        fun get(url: String, limit: Int, timeoutMs: Int): Fetch
    }

    fun test(url: String, fetcher: Fetcher = HttpFetcher): Result {
        var fetch = fetcher.get(url, SAMPLE_BYTES, TIMEOUT_MS)
        if (fetch.error != null) return failed(url, fetch, "error")
        if (fetch.status !in 200..399) return failed(url, fetch, "http")
        if (fetch.bytes.isEmpty()) return failed(url, fetch, if (fetch.timedOut) "timeout" else "empty")

        val text = runCatching { fetch.bytes.toString(Charsets.UTF_8) }.getOrDefault("")
        if (!text.trimStart('\uFEFF', ' ', '\t', '\r', '\n').startsWith("#EXTM3U")) {
            if (fetch.contentType?.contains("text/html", ignoreCase = true) == true ||
                text.trimStart().startsWith("<")
            ) {
                return failed(url, fetch, "html")
            }
            return result(url, fetch, fetch.bytes.size, encrypted = false, note = if (fetch.timedOut) "timeout" else null)
        }

        var playlist = text
        var base = fetch.url
        var encrypted = false
        var bitrateKbps: Int? = null
        var width: Int? = null
        var height: Int? = null
        var durationSeconds: Double? = null
        repeat(MAX_PLAYLIST_DEPTH) {
            encrypted = encrypted || isEncrypted(playlist)
            val master = isMaster(playlist)
            val target: String
            if (master) {
                val variant = firstVariant(playlist) ?: return failed(url, fetch, "no-segment")
                bitrateKbps = variant.bandwidthKbps ?: bitrateKbps
                width = variant.width ?: width
                height = variant.height ?: height
                target = variant.url
            } else {
                val segment = firstSegment(playlist) ?: return failed(url, fetch, "no-segment")
                durationSeconds = segment.durationSeconds ?: durationSeconds
                target = segment.url
            }
            val nextUrl = resolve(base, target)
            fetch = fetcher.get(nextUrl, if (master) PLAYLIST_BYTES else SAMPLE_BYTES, TIMEOUT_MS)
            if (fetch.error != null) return failed(url, fetch, "error")
            if (fetch.status !in 200..399) return failed(url, fetch, "http")
            if (fetch.bytes.isEmpty()) return failed(url, fetch, if (fetch.timedOut) "timeout" else "empty")
            if (master) {
                playlist = fetch.bytes.toString(Charsets.UTF_8)
                base = fetch.url
                return@repeat
            }
            val duration = durationSeconds
            if (bitrateKbps == null && duration != null && duration > 0) {
                val segmentBytes = fetch.contentLength?.takeIf { it > 0 } ?: fetch.bytes.size.toLong()
                bitrateKbps = (segmentBytes * 8.0 / duration / 1000.0).roundToInt()
            }
            if (width == null || height == null) {
                TsVideoSize.from(fetch.bytes)?.let {
                    width = width ?: it.width
                    height = height ?: it.height
                }
            }
            val note = buildString {
                if (encrypted) append("encrypted")
                if (fetch.timedOut) {
                    if (isNotEmpty()) append(',')
                    append("timeout")
                }
            }.ifEmpty { null }
            return result(url, fetch, fetch.bytes.size, encrypted, note, bitrateKbps, width, height)
        }
        return failed(url, fetch, "playlist-depth")
    }

    private fun result(
        original: String,
        fetch: Fetch,
        bytes: Int,
        encrypted: Boolean,
        note: String?,
        bitrateKbps: Int? = null,
        width: Int? = null,
        height: Int? = null,
    ): Result {
        val mbps = bytes * 8.0 / fetch.elapsedMs.coerceAtLeast(1) / 1000.0
        val verdict = if (encrypted) {
            Verdict.ENCRYPTED
        } else {
            when {
                mbps >= 4.0 -> Verdict.HD
                mbps >= 2.0 -> Verdict.SMOOTH
                mbps >= 1.0 -> Verdict.SLOW
                else -> Verdict.UNUSABLE
            }
        }
        return Result(
            url = original,
            verdict = verdict,
            mbps = if (fetch.elapsedMs > 0) mbps else null,
            ms = fetch.elapsedMs,
            status = fetch.status,
            note = note,
            finalUrl = fetch.url,
            bitrateKbps = bitrateKbps,
            width = width,
            height = height,
        )
    }

    private fun failed(original: String, fetch: Fetch, note: String): Result = Result(
        url = original,
        verdict = Verdict.FAILED,
        mbps = null,
        ms = fetch.elapsedMs,
        status = fetch.status.takeIf { it > 0 },
        note = fetch.error ?: note,
        finalUrl = fetch.url,
    )

    private fun isMaster(playlist: String): Boolean =
        playlist.lineSequence().any { it.trimStart().startsWith("#EXT-X-STREAM-INF") }

    private data class Variant(
        val url: String,
        val bandwidthKbps: Int?,
        val width: Int?,
        val height: Int?,
    )

    private data class Segment(val url: String, val durationSeconds: Double?)

    private fun firstVariant(playlist: String): Variant? {
        var attributes: String? = null
        for (raw in playlist.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                attributes = line.substringAfter(':')
                continue
            }
            if (attributes != null && !line.startsWith("#")) {
                val bandwidth = attr(attributes, "BANDWIDTH") ?: attr(attributes, "AVERAGE-BANDWIDTH")
                val resolution = attr(attributes, "RESOLUTION")?.split('x')
                return Variant(
                    url = line,
                    bandwidthKbps = bandwidth?.toLongOrNull()?.let { (it / 1000).toInt() },
                    width = resolution?.getOrNull(0)?.toIntOrNull(),
                    height = resolution?.getOrNull(1)?.toIntOrNull(),
                )
            }
            if (!line.startsWith("#")) attributes = null
        }
        return null
    }

    private fun attr(attributes: String, name: String): String? =
        Regex("(?:^|,)\\s*" + Regex.escape(name) + "=(\"[^\"]*\"|[^,]*)")
            .find(attributes)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.trim('"')

    private fun firstSegment(playlist: String): Segment? {
        var duration: Double? = null
        for (raw in playlist.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("#EXTINF:")) {
                duration = line.substringAfter(':').substringBefore(',').toDoubleOrNull()
            } else if (line.isNotEmpty() && !line.startsWith("#")) {
                return Segment(line, duration)
            }
        }
        return null
    }

    private fun isEncrypted(playlist: String): Boolean =
        playlist.lineSequence().any {
            val line = it.trim()
            line.startsWith("#EXT-X-KEY") && !line.contains("METHOD=NONE", ignoreCase = true)
        }

    private fun resolve(base: String, target: String): String =
        runCatching { URI(base).resolve(target).toString() }.getOrDefault(target)

    /** ZTE/hnyd HLS sources 400 when a bare trailing "?" is lost; VLC keeps a query. */
    internal fun requestUrl(url: String): String = if (url.endsWith("?")) url + "x=1" else url

    /** Reads up to [limit] bytes or [timeoutMs], whichever comes first. Keeps partial data. */
    object HttpFetcher : Fetcher {
        override fun get(url: String, limit: Int, timeoutMs: Int): Fetch {
            val start = System.nanoTime()
            fun elapsed() = (System.nanoTime() - start) / 1_000_000
            var connection: HttpURLConnection? = null
            val requested = requestUrl(url)
            return try {
                connection = (URL(requested).openConnection() as HttpURLConnection).apply {
                    connectTimeout = minOf(3_000, timeoutMs)
                    readTimeout = timeoutMs
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", USER_AGENT)
                    setRequestProperty("Accept", "*/*")
                    setRequestProperty("Range", "bytes=0-${limit - 1}")
                }
                val status = connection.responseCode
                val input = if (status in 200..299) connection.inputStream else connection.errorStream
                val out = ByteArrayOutputStream(minOf(limit, 128 * 1024))
                var timedOut = false
                if (input != null) {
                    val buffer = ByteArray(16 * 1024)
                    val deadline = start + timeoutMs * 1_000_000L
                    while (out.size() < limit) {
                        val read = try {
                            input.read(buffer, 0, minOf(buffer.size, limit - out.size()))
                        } catch (_: SocketTimeoutException) {
                            timedOut = true
                            break
                        }
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        if (System.nanoTime() > deadline) {
                            timedOut = true
                            break
                        }
                    }
                    runCatching { input.close() }
                }
                val contentLength = connection.getHeaderField("Content-Range")
                    ?.substringAfterLast('/')
                    ?.toLongOrNull()
                    ?: connection.getHeaderField("Content-Length")?.toLongOrNull()
                Fetch(
                    url = connection.url?.toString() ?: requested,
                    status = status,
                    contentType = connection.contentType,
                    bytes = out.toByteArray(),
                    elapsedMs = elapsed(),
                    timedOut = timedOut,
                    contentLength = contentLength,
                )
            } catch (e: Exception) {
                Fetch(requested, 0, null, ByteArray(0), elapsed(), error = e.javaClass.simpleName)
            } finally {
                runCatching { connection?.disconnect() }
            }
        }
    }
}
