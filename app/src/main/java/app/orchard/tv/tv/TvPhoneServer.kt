package app.orchard.tv.tv

import android.content.Context
import android.os.Handler
import android.os.Looper
import app.orchard.tv.wallpaper.PhoneServer
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URL
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * The phone/computer page for the channel list, on its own port so it never fights the wallpaper
 * page's server. It runs only while UP's QR panel is open (and a few minutes after, so a slow phone
 * can finish), and every address carries a token that is only shown in the QR code.
 */
class TvPhoneServer private constructor(
    private val context: Context,
    private val token: String,
) : NanoHTTPD(PORT) {
    @Volatile
    var controller: TvController? = null

    @Volatile
    private var poolCache: List<TvSource>? = null

    override fun serve(session: IHTTPSession): Response {
        val prefix = "/$token"
        if (!session.uri.startsWith(prefix)) return text(Response.Status.NOT_FOUND, "Not found")
        val path = session.uri.removePrefix(prefix).ifEmpty { "/" }
        return try {
            route(session, path)
        } catch (e: Exception) {
            errorResponse(Response.Status.INTERNAL_ERROR, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun route(session: IHTTPSession, path: String): Response = when {
        path == "/" -> page()
        path == "/api/state" -> json(state())
        path == "/api/pool" -> json(JSONObject().put("sources", poolJson()))
        path == "/api/export/json" -> download(
            TvStore.encodeExport(channels(), pool()),
            "application/json; charset=utf-8",
            "aerial-channels.json",
        )
        path == "/api/export/m3u" -> download(
            TvImport.exportM3u(channels()),
            "audio/x-mpegurl; charset=utf-8",
            "aerial-channels.m3u",
        )
        session.method == Method.POST && path == "/api/channel/add" -> {
            val f = form(session)
            edit { channels, pool ->
                val name = f["name"].orEmpty().trim()
                val url = f["url"].orEmpty().trim()
                val label = f["label"].orEmpty().trim().ifBlank { "manual" }
                when {
                    name.isEmpty() -> "missing name"
                    !url.startsWith("http", ignoreCase = true) -> "bad url"
                    TvImport.findByName(channels, name) != null -> "channel exists"
                    else -> {
                        channels += TvChannel(name, true, 0, listOf(TvSource(url, label, true)))
                        pool += TvSource(url, label, true)
                        null
                    }
                }
            }
        }
        session.method == Method.POST && path == "/api/channel/rename" -> {
            val f = form(session)
            edit { channels, _ ->
                val index = f["index"]?.toIntOrNull()
                val name = f["name"].orEmpty().trim()
                val channel = index?.let { channels.getOrNull(it) }
                val other = TvImport.findByName(channels, name)
                when {
                    channel == null -> "bad channel"
                    name.isEmpty() -> "missing name"
                    other != null && other != index -> "channel exists"
                    else -> {
                        channels[index] = channel.copy(name = name)
                        null
                    }
                }
            }
        }
        session.method == Method.POST && path == "/api/channel/move" -> {
            val f = form(session)
            edit { channels, _ ->
                val from = f["from"]?.toIntOrNull()
                val to = f["to"]?.toIntOrNull()
                when {
                    from == null || to == null -> "bad index"
                    from !in channels.indices || to !in channels.indices -> "bad index"
                    from == to -> null
                    else -> {
                        channels.add(to, channels.removeAt(from))
                        null
                    }
                }
            }
        }
        session.method == Method.POST && path == "/api/channel/enable" -> {
            val f = form(session)
            edit { channels, _ ->
                val index = f["index"]?.toIntOrNull()
                val channel = index?.let { channels.getOrNull(it) } ?: return@edit "bad channel"
                channels[index] = channel.copy(enabled = f["enabled"] == "1")
                null
            }
        }
        session.method == Method.POST && path == "/api/channel/delete" -> {
            val f = form(session)
            edit { channels, pool ->
                val index = f["index"]?.toIntOrNull()
                val channel = index?.let { channels.getOrNull(it) } ?: return@edit "bad channel"
                if (f["confirm"] != "1") return@edit "confirm required"
                channels.removeAt(index)
                pool += channel.sources
                null
            }
        }
        session.method == Method.POST && path == "/api/source/test-play" -> testPlay(session)
        session.method == Method.POST && path == "/api/source/add" -> {
            val f = form(session)
            edit { channels, pool ->
                val channelIndex = f["channel"]?.toIntOrNull()
                val channel = channelIndex?.let { channels.getOrNull(it) } ?: return@edit "bad channel"
                val url = f["url"].orEmpty().trim()
                val label = f["label"].orEmpty().trim().ifBlank { "manual" }
                when {
                    !url.startsWith("http", ignoreCase = true) -> "bad url"
                    channel.sources.any { it.url == url } -> "duplicate source"
                    else -> {
                        channels[channelIndex] = channel.copy(sources = channel.sources + TvSource(url, label, true))
                        pool += TvSource(url, label, true)
                        null
                    }
                }
            }
        }
        session.method == Method.POST && path == "/api/source/move" -> {
            val f = form(session)
            edit { channels, _ ->
                val channelIndex = f["channel"]?.toIntOrNull()
                val from = f["from"]?.toIntOrNull()
                val to = f["to"]?.toIntOrNull()
                val channel = channelIndex?.let { channels.getOrNull(it) } ?: return@edit "bad channel"
                if (from == null || to == null || from !in channel.sources.indices || to !in channel.sources.indices) {
                    return@edit "bad index"
                }
                if (from == to) return@edit null
                val currentUrl = channel.sources.getOrNull(channel.currentSource)?.url
                val sources = channel.sources.toMutableList()
                sources.add(to, sources.removeAt(from))
                val current = sources.indexOfFirst { it.url == currentUrl }.takeIf { it >= 0 } ?: channel.currentSource
                channels[channelIndex] = channel.copy(sources = sources, currentSource = current)
                null
            }
        }
        session.method == Method.POST && path == "/api/source/enable" -> {
            val f = form(session)
            edit { channels, _ ->
                val channelIndex = f["channel"]?.toIntOrNull()
                val sourceIndex = f["index"]?.toIntOrNull()
                val channel = channelIndex?.let { channels.getOrNull(it) } ?: return@edit "bad channel"
                val source = sourceIndex?.let { channel.sources.getOrNull(it) } ?: return@edit "bad source"
                val sources = channel.sources.toMutableList()
                sources[sourceIndex] = source.copy(enabled = f["enabled"] == "1")
                channels[channelIndex] = channel.copy(sources = sources)
                null
            }
        }
        session.method == Method.POST && path == "/api/source/delete" -> {
            val f = form(session)
            edit { channels, pool ->
                val channelIndex = f["channel"]?.toIntOrNull()
                val sourceIndex = f["index"]?.toIntOrNull()
                val channel = channelIndex?.let { channels.getOrNull(it) } ?: return@edit "bad channel"
                val source = sourceIndex?.let { channel.sources.getOrNull(it) } ?: return@edit "bad source"
                if (f["confirm"] != "1") return@edit "confirm required"
                if (channel.sources.size <= 1) return@edit "last source"
                val sources = channel.sources.toMutableList()
                sources.removeAt(sourceIndex)
                val current = if (channel.currentSource >= sources.size) sources.lastIndex
                else if (channel.currentSource > sourceIndex) channel.currentSource - 1
                else channel.currentSource
                channels[channelIndex] = channel.copy(sources = sources, currentSource = current.coerceAtLeast(0))
                pool += source
                null
            }
        }
        session.method == Method.POST && path == "/api/source/current" -> {
            val f = form(session)
            edit { channels, _ ->
                val channelIndex = f["channel"]?.toIntOrNull()
                val sourceIndex = f["index"]?.toIntOrNull()
                val channel = channelIndex?.let { channels.getOrNull(it) } ?: return@edit "bad channel"
                val source = sourceIndex?.let { channel.sources.getOrNull(it) } ?: return@edit "bad source"
                if (!source.enabled) return@edit "source disabled"
                channels[channelIndex] = channel.copy(currentSource = sourceIndex)
                null
            }
        }
        session.method == Method.POST && path == "/api/import/preview" -> previewImport(session)
        session.method == Method.POST && path == "/api/import/apply" -> applyImport(session)
        session.method == Method.POST && path == "/api/import/json" -> importJson(session)
        session.method == Method.POST && path == "/api/test/start" -> startTest(session)
        path == "/api/test/status" -> testStatus(session)
        else -> text(Response.Status.NOT_FOUND, "Not found")
    }

    private fun channels(): List<TvChannel> = onMain { controller?.channels ?: emptyList() }

    private fun current(): Pair<Int, TvSource>? = onMain { controller?.current }

    private fun pool(): List<TvSource> {
        poolCache?.let { return it }
        val loaded = TvStore.load(context).pool
        poolCache = loaded
        return loaded
    }

    private fun state(): JSONObject {
        val channels = channels()
        val current = current()
        return JSONObject()
            .put("channels", JSONArray().apply {
                channels.forEach { put(channelJson(it)) }
            })
            .put("current", JSONObject().apply {
                put("channel", current?.first ?: -1)
                put("url", current?.second?.url.orEmpty())
            })
            .put("pool", poolJson())
            .put("revision", TvStore.stamp(context))
    }

    private fun poolJson(): JSONArray = JSONArray().apply {
        pool().forEach { put(sourceJson(it)) }
    }

    private fun channelJson(channel: TvChannel): JSONObject = JSONObject()
        .put("name", channel.name)
        .put("enabled", channel.enabled)
        .put("currentSource", channel.currentSource)
        .put("sources", JSONArray().apply {
            channel.sources.forEach { put(sourceJson(it)) }
        })

    private fun sourceJson(source: TvSource): JSONObject = JSONObject()
        .put("url", source.url)
        .put("label", source.label)
        .put("enabled", source.enabled)

    /**
     * Applies one edit to a copy of the list, writes it, and tells the TV. The block returns an
     * error message, or null after changing the list.
     */
    @Synchronized
    private fun edit(block: (MutableList<TvChannel>, MutableList<TvSource>) -> String?): Response {
        val channels = channels().toMutableList()
        val pool = pool().toMutableList()
        val error = block(channels, pool)
        if (error != null) return errorResponse(Response.Status.BAD_REQUEST, error)
        val merged = TvStore.mergePool(pool, channels.flatMap { it.sources })
        TvStore.save(context, channels, merged)
        poolCache = merged
        onMain { controller?.applyChannels(channels) }
        return json(state())
    }

    private fun previewImport(session: IHTTPSession): Response {
        if (tooLarge(session)) return errorResponse(Response.Status.BAD_REQUEST, "too large")
        val (text, label) = readImport(session, "m3u") ?: return errorResponse(Response.Status.BAD_REQUEST, "no playlist")
        val entries = TvImport.parseM3u(text)
        if (entries.isEmpty()) return errorResponse(Response.Status.BAD_REQUEST, "no entries")
        val preview = TvImport.preview(channels(), entries, label)
        purgeImports()
        val id = newToken()
        pendingImports[id] = PendingImport(entries, label, System.currentTimeMillis())
        return json(JSONObject()
            .put("id", id)
            .put("label", label)
            .put("items", JSONArray().apply {
                preview.items.forEach { item ->
                    put(JSONObject()
                        .put("name", item.name)
                        .put("url", item.url)
                        .put("target", item.target)
                        .put("duplicate", item.duplicate))
                }
            }))
    }

    @Synchronized
    private fun applyImport(session: IHTTPSession): Response {
        val f = form(session)
        val id = f["id"].orEmpty()
        val pending = pendingImports[id] ?: return errorResponse(Response.Status.BAD_REQUEST, "preview expired")
        val selected = if (f.containsKey("selected")) {
            f["selected"].orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()
        } else {
            pending.entries.indices.toSet()
        }
        if (selected.isEmpty()) return errorResponse(Response.Status.BAD_REQUEST, "none selected")
        val entries = pending.entries.filterIndexed { index, _ -> index in selected }
        val result = TvImport.merge(channels(), entries, pending.label)
        val mergedPool = TvStore.mergePool(pool(), entries.map { TvSource(it.url, pending.label, true) })
        TvStore.save(context, result.channels, mergedPool)
        poolCache = mergedPool
        pendingImports.remove(id)
        onMain { controller?.applyChannels(result.channels) }
        return json(state()
            .put("addedChannels", result.addedChannels)
            .put("addedSources", result.addedSources)
            .put("duplicates", result.duplicates))
    }

    @Synchronized
    private fun importJson(session: IHTTPSession): Response {
        if (tooLarge(session)) return errorResponse(Response.Status.BAD_REQUEST, "too large")
        // Read the body once: NanoHTTPD consumes it in parseBody, so confirm must be in the query.
        if (session.parameters["confirm"]?.firstOrNull() != "1") {
            return errorResponse(Response.Status.BAD_REQUEST, "confirm required")
        }
        val (text, _) = readImport(session, "json") ?: return errorResponse(Response.Status.BAD_REQUEST, "no json")
        val imported = TvStore.parseImport(text) ?: return errorResponse(Response.Status.BAD_REQUEST, "bad json")
        TvStore.save(context, imported.channels, imported.pool)
        poolCache = imported.pool
        onMain { controller?.applyChannels(imported.channels) }
        return json(state())
    }

    /**
     * Text from a multipart file, a URL, or a form field. Returns the text and the source label.
     * The body is read once per request; NanoHTTPD has already parsed the multipart parts.
     */
    private fun readImport(session: IHTTPSession, label: String): Pair<String, String>? {
        val type = session.headers["content-type"].orEmpty()
        if (type.startsWith("multipart/form-data", ignoreCase = true)) {
            val files = HashMap<String, String>()
            session.parseBody(files)
            val path = files.values.firstOrNull() ?: return null
            val bytes = runCatching { File(path).readBytes() }.getOrNull() ?: return null
            val filename = session.parameters["file"]?.firstOrNull()
                ?: File(path).name.ifBlank { "$label.m3u" }
            return TvImport.decode(bytes) to TvImport.labelForFile(filename)
        }
        val f = form(session)
        f["url"]?.trim()?.takeIf { it.isNotEmpty() }?.let { url ->
            val remote = fetchRemote(url) ?: return null
            return TvImport.decode(remote.first) to TvImport.labelForUrl(url)
        }
        val text = f["text"] ?: return null
        return text to (f["name"]?.takeIf { it.isNotBlank() }?.let(TvImport::labelForFile) ?: label)
    }

    private fun fetchRemote(url: String): Pair<ByteArray, String>? {
        val protocol = runCatching { URL(url).protocol }.getOrNull() ?: return null
        if (protocol != "http" && protocol != "https") return null
        val fetch = TvSpeedTest.HttpFetcher.get(url, 5 * 1024 * 1024, 10_000)
        if (fetch.error != null || fetch.status !in 200..399 || fetch.bytes.isEmpty()) return null
        return fetch.bytes to fetch.url
    }

    @Synchronized
    private fun startTest(session: IHTTPSession): Response {
        val f = form(session)
        val active = activeJob
        if (active != null && jobs[active]?.finished == false) {
            return errorResponse(Response.Status.CONFLICT, "test running")
        }
        purgeJobs()
        val channels = channels()
        val items = mutableListOf<JobItem>()
        val channelIndex = f["channel"]?.toIntOrNull()
        val sourceIndex = f["source"]?.toIntOrNull()
        if (channelIndex != null) {
            val channel = channels.getOrNull(channelIndex) ?: return errorResponse(Response.Status.BAD_REQUEST, "bad channel")
            if (sourceIndex != null) {
                val source = channel.sources.getOrNull(sourceIndex)
                    ?: return errorResponse(Response.Status.BAD_REQUEST, "bad source")
                items += JobItem(channelIndex, sourceIndex, channel.name, source.url)
            } else {
                channel.sources.forEachIndexed { index, source ->
                    items += JobItem(channelIndex, index, channel.name, source.url)
                }
            }
        } else if (f["all"] == "1") {
            channels.forEachIndexed { ci, channel ->
                channel.sources.forEachIndexed { si, source -> items += JobItem(ci, si, channel.name, source.url) }
            }
        } else {
            return errorResponse(Response.Status.BAD_REQUEST, "nothing to test")
        }
        if (items.isEmpty()) return errorResponse(Response.Status.BAD_REQUEST, "no sources")
        if (items.size > MAX_TEST_ITEMS) return errorResponse(Response.Status.BAD_REQUEST, "too many sources")
        val job = SpeedJob(newToken(), items)
        jobs[job.id] = job
        activeJob = job.id
        items.forEachIndexed { index, item ->
            speedExecutor.submit {
                if (job.cancelled.get()) return@submit
                val result = runCatching { TvSpeedTest.test(item.url) }.getOrElse {
                    TvSpeedTest.Result(item.url, TvSpeedTest.Verdict.FAILED, null, 0, null, it.javaClass.simpleName, null)
                }
                job.results[index] = result
                if (job.results.size == job.items.size) {
                    job.finished = true
                    if (activeJob == job.id) activeJob = null
                }
            }
        }
        return json(JSONObject().put("id", job.id).put("total", items.size))
    }

    private fun testPlay(session: IHTTPSession): Response {
        val f = form(session)
        val channel = f["channel"]?.toIntOrNull()
        val source = f["source"]?.toIntOrNull()
        if (channel == null || source == null) {
            return errorResponse(Response.Status.BAD_REQUEST, "bad source")
        }
        val ok = onMain { controller?.testSource(channel, source) ?: false }
        return if (ok) json(JSONObject().put("ok", true))
        else errorResponse(Response.Status.BAD_REQUEST, "bad source")
    }

    private fun testStatus(session: IHTTPSession): Response {
        val id = session.parameters["id"]?.firstOrNull().orEmpty()
        val job = jobs[id] ?: return errorResponse(Response.Status.NOT_FOUND, "unknown test")
        return json(JSONObject()
            .put("id", job.id)
            .put("finished", job.finished)
            .put("done", job.results.size)
            .put("total", job.items.size)
            .put("items", JSONArray().apply {
                job.items.forEachIndexed { index, item ->
                    val result = job.results[index]
                    put(JSONObject()
                        .put("channel", item.channel)
                        .put("source", item.source)
                        .put("name", item.name)
                        .put("url", item.url)
                        .put("done", result != null)
                        .apply {
                            if (result != null) {
                                put("verdict", result.verdict.name.lowercase())
                                put("mbps", result.mbps ?: JSONObject.NULL)
                                put("ms", result.ms)
                                put("note", result.note ?: JSONObject.NULL)
                                put("bitrateKbps", result.bitrateKbps ?: JSONObject.NULL)
                                put("width", result.width ?: JSONObject.NULL)
                                put("height", result.height ?: JSONObject.NULL)
                            }
                        })
                }
            }))
    }

    private fun form(session: IHTTPSession): Map<String, String> {
        session.parseBody(HashMap())
        return session.parameters.mapValues { it.value.firstOrNull().orEmpty() }
    }

    private fun page(): Response = runCatching {
        val html = context.assets.open("web/tv.html").bufferedReader().use { it.readText() }
        newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html).apply {
            addHeader("Cache-Control", "no-store")
        }
    }.getOrElse { text(Response.Status.INTERNAL_ERROR, "page missing") }

    private fun asset(name: String, type: String): Response = runCatching {
        val bytes = context.assets.open(name).readBytes()
        newFixedLengthResponse(Response.Status.OK, type, ByteArrayInputStream(bytes), bytes.size.toLong()).apply {
            addHeader("Cache-Control", "public, max-age=86400")
        }
    }.getOrElse { text(Response.Status.NOT_FOUND, "asset missing") }

    private fun json(o: JSONObject): Response =
        newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", o.toString())

    private fun errorResponse(status: Response.Status, message: String): Response =
        newFixedLengthResponse(status, "application/json; charset=utf-8", JSONObject().put("error", message).toString())

    private fun text(status: Response.Status, message: String): Response =
        newFixedLengthResponse(status, "text/plain; charset=utf-8", message)

    private fun download(body: String, type: String, name: String): Response =
        newFixedLengthResponse(Response.Status.OK, type, body).apply {
            addHeader("Content-Disposition", "attachment; filename=\"$name\"")
            addHeader("Cache-Control", "no-store")
        }

    private fun tooLarge(session: IHTTPSession): Boolean =
        (session.headers["content-length"]?.toLongOrNull() ?: 0L) > MAX_UPLOAD

    private fun <T> onMain(block: () -> T): T = runBlocking(Dispatchers.Main) { block() }

    private data class PendingImport(val entries: List<TvImport.Entry>, val label: String, val at: Long)

    private data class JobItem(val channel: Int, val source: Int, val name: String, val url: String)

    private class SpeedJob(val id: String, val items: List<JobItem>) {
        val at = System.currentTimeMillis()
        val results = ConcurrentHashMap<Int, TvSpeedTest.Result>()
        val cancelled = AtomicBoolean(false)
        @Volatile var finished = false
    }

    private val pendingImports = ConcurrentHashMap<String, PendingImport>()
    private val jobs = ConcurrentHashMap<String, SpeedJob>()
    @Volatile private var activeJob: String? = null

    private fun purgeImports() {
        val cutoff = System.currentTimeMillis() - IMPORT_TTL_MS
        pendingImports.entries.removeIf { it.value.at < cutoff }
    }

    private fun purgeJobs() {
        val cutoff = System.currentTimeMillis() - JOB_TTL_MS
        jobs.entries.removeIf { it.value.finished && it.value.at < cutoff && it.value.id != activeJob }
        // Finished jobs are small; keep them long enough for a phone that was asleep to read them.
        if (jobs.size > 20) jobs.entries.removeIf { it.value.finished && it.value.id != activeJob }
    }

    companion object {
        const val PORT = 8791
        private const val TAG = "AerialTvPhone"
        private const val MAX_UPLOAD = 6L * 1024 * 1024
        private const val MAX_TEST_ITEMS = 500
        private const val IMPORT_TTL_MS = 10 * 60 * 1000L
        private const val JOB_TTL_MS = 10 * 60 * 1000L
        private const val STOP_DELAY_MS = 5 * 60 * 1000L

        private val handler = Handler(Looper.getMainLooper())
        private val stopRunnable = Runnable { stop() }
        @Volatile private var running: TvPhoneServer? = null

        private val speedExecutor = Executors.newFixedThreadPool(3) { runnable ->
            Thread(runnable, "aerial-tv-speed").apply { isDaemon = true }
        }

        /** Starts if needed and returns the QR address, or null when the TV has no network. */
        @Synchronized
        fun start(context: Context, controller: TvController): String? {
            handler.removeCallbacks(stopRunnable)
            val ip = PhoneServer.lanAddress(context) ?: return null
            val server = running ?: TvPhoneServer(context.applicationContext, newToken()).also { candidate ->
                runCatching { candidate.start(SOCKET_READ_TIMEOUT, true) }.onFailure {
                    runCatching { candidate.stop() }
                    return null
                }
                running = candidate
            }
            server.controller = controller
            return "http://$ip:$PORT/${server.token}/"
        }

        @Synchronized
        fun stop() {
            handler.removeCallbacks(stopRunnable)
            running?.jobs?.values?.forEach { it.cancelled.set(true) }
            running?.stop()
            running = null
        }

        /** Keeps serving for a while after the panel closes, so a page can finish saving. */
        @Synchronized
        fun stopAfterDelay() {
            handler.removeCallbacks(stopRunnable)
            handler.postDelayed(stopRunnable, STOP_DELAY_MS)
        }

        fun qr(text: String): android.graphics.Bitmap = PhoneServer.qr(text, scale = 6)

        fun lanAddress(context: Context): String? = PhoneServer.lanAddress(context)

        private fun newToken(): String {
            val alphabet = "abcdefghjkmnpqrstuvwxyz23456789"
            val random = SecureRandom()
            return (1..8).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
        }
    }
}
