package app.orchard.tv.tv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Aerial's own channel configuration. It starts as a one-time copy of the set-top box's file found
 * on older installs, but from then on lives in the app's private directory: the box may rewrite
 * its file at any time, and one app must not edit another app's data.
 *
 * The layout stays compatible with the box's `{"channels":[...]}` JSON. The pool of every source
 * ever imported lives beside it, so deleting a channel or a source does not lose its URL.
 */
object TvStore {
    const val DIR = "tv"
    const val FILE = "iptv_sources.json"
    const val POOL_FILE = "source_pool.json"
    const val BACKUP_DIR = "backup"
    const val BACKUP_KEEP = 5

    /** Examples shown on a first install; owners may rename, delete or add their own. */
    val DEFAULT_GROUPS = listOf("央视频道", "卫视频道", "县市频道", "高清频道", "影视频道")

    data class Data(val channels: List<TvChannel>, val pool: List<TvSource>, val groups: List<String>)

    fun dir(context: Context): File = File(context.filesDir, DIR).apply { mkdirs() }

    fun file(context: Context): File = File(dir(context), FILE)

    fun poolFile(context: Context): File = File(dir(context), POOL_FILE)

    fun backupDir(context: Context): File = File(dir(context), BACKUP_DIR).apply { mkdirs() }

    fun stamp(context: Context, file: File = file(context)): Long =
        runCatching { file.lastModified() }.getOrDefault(0L)

    /** Reads the private copy, migrating the box's file once. Never throws. */
    fun load(
        context: Context,
        dir: File = dir(context),
        cbnFile: File = File(TvSources.CBN_FILE),
    ): Data {
        val main = File(dir, FILE)
        if (main.isFile) {
            val text = runCatching { main.readText(Charsets.UTF_8) }.getOrNull()
            val parsed = text?.let { TvSources.parseOrNull(it) }
            if (parsed != null) {
                val pool = parsePool(runCatching { File(dir, POOL_FILE).readText(Charsets.UTF_8) }.getOrNull())
                return Data(
                    parsed,
                    pool.ifEmpty { poolFromChannels(parsed) },
                    parseGroups(text) ?: DEFAULT_GROUPS,
                )
            }
            runCatching {
                main.copyTo(File(File(dir, BACKUP_DIR).apply { mkdirs() }, "corrupt-${System.currentTimeMillis()}.json"))
            }
        }

        // A damaged file goes back to the newest readable backup, not to the box's file: the user's
        // latest edits are more likely to be there.
        latestBackup(dir)?.let { backup ->
            val restored = runCatching { backup.readText(Charsets.UTF_8) }.getOrNull()?.let { parseBackup(it) }
            if (restored != null) {
                save(context, restored.channels, restored.pool, dir, restored.groups)
                return restored
            }
        }

        val seed = readCbn(cbnFile) ?: readAsset(context)
        val pool = poolFromChannels(seed)
        if (seed.isNotEmpty()) save(context, seed, pool, dir, DEFAULT_GROUPS)
        return Data(seed, pool, DEFAULT_GROUPS)
    }

    /** Writes both files atomically and keeps a rotation of the previous configuration. */
    fun save(
        context: Context,
        channels: List<TvChannel>,
        pool: List<TvSource>,
        dir: File = dir(context),
        groups: List<String> = DEFAULT_GROUPS,
    ) {
        val main = File(dir, FILE)
        val existing = if (main.isFile) {
            val text = runCatching { main.readText(Charsets.UTF_8) }.getOrNull()
            val parsed = text?.let { TvSources.parseOrNull(it) }
            if (parsed != null) {
                Data(
                    parsed,
                    parsePool(runCatching { File(dir, POOL_FILE).readText(Charsets.UTF_8) }.getOrNull()),
                    parseGroups(text) ?: DEFAULT_GROUPS,
                )
            } else {
                null
            }
        } else {
            null
        }
        if (existing != null) backup(context, existing, dir)
        writeAtomic(main, encodeChannels(channels, groups))
        writeAtomic(File(dir, POOL_FILE), encodePool(mergePool(pool, emptyList())))
    }

    /** Adds [added] to [existing] by URL; the first non-blank label wins. */
    fun mergePool(existing: List<TvSource>, added: List<TvSource>): List<TvSource> {
        val seen = LinkedHashMap<String, TvSource>()
        for (source in existing + added) {
            val url = source.url.trim()
            if (url.isEmpty()) continue
            val old = seen[url]
            seen[url] = if (old == null) {
                source.copy(url = url)
            } else {
                old.copy(label = old.label.ifBlank { source.label })
            }
        }
        return seen.values.toList()
    }

    fun poolFromChannels(channels: List<TvChannel>): List<TvSource> =
        mergePool(emptyList(), channels.flatMap { it.sources })

    fun encodeChannels(channels: List<TvChannel>, groups: List<String> = DEFAULT_GROUPS): String {
        val array = JSONArray()
        for (channel in channels) {
            val sources = JSONArray()
            for (source in channel.sources) {
                sources.put(
                    JSONObject()
                        .put("url", source.url)
                        .put("label", source.label)
                        .put("enabled", source.enabled),
                )
            }
            array.put(
                JSONObject()
                    .put("name", channel.name)
                    .put("enabled", channel.enabled)
                    .put("currentSource", channel.currentSource)
                    .put("group", channel.group ?: JSONObject.NULL)
                    .put("sources", sources),
            )
        }
        return JSONObject()
            .put("channels", array)
            .put("groups", JSONArray().also { array -> normalizeGroups(groups).forEach { array.put(it) } })
            .toString()
    }

    /** The export format; the private channel file itself has no pool field. */
    fun encodeExport(
        channels: List<TvChannel>,
        pool: List<TvSource>,
        groups: List<String> = DEFAULT_GROUPS,
    ): String =
        JSONObject()
            .put("channels", JSONObject(encodeChannels(channels, groups)).getJSONArray("channels"))
            .put("sourcePool", JSONArray().also { array -> pool.forEach { array.put(poolJson(it)) } })
            .put("groups", JSONArray().also { array -> normalizeGroups(groups).forEach { array.put(it) } })
            .toString()

    fun parsePool(json: String?): List<TvSource> = runCatching {
        val root = JSONObject(json.orEmpty())
        // The export format calls it sourcePool; the pool's own file calls it sources.
        val array = root.optJSONArray("sourcePool") ?: root.optJSONArray("sources") ?: return emptyList()
        buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val url = item.optString("url").trim()
                if (url.isNotEmpty()) add(TvSource(url, item.optString("label"), item.optBoolean("enabled", true)))
            }
        }
    }.getOrDefault(emptyList())

    /** Validates an imported JSON file or URL; null means "do not touch the current data". */
    fun parseImport(json: String): Data? {
        val channels = TvSources.parseOrNull(json) ?: return null
        val pool = parsePool(json).ifEmpty { poolFromChannels(channels) }
        return Data(channels, pool, parseGroups(json) ?: DEFAULT_GROUPS)
    }

    fun encodePool(pool: List<TvSource>): String =
        JSONObject().put("sources", JSONArray().also { array -> pool.forEach { array.put(poolJson(it)) } }).toString()

    private fun poolJson(source: TvSource): JSONObject =
        JSONObject().put("url", source.url).put("label", source.label).put("enabled", source.enabled)

    private fun parseBackup(json: String): Data? {
        val channels = TvSources.parseOrNull(json) ?: return null
        return Data(
            channels,
            parsePool(json).ifEmpty { poolFromChannels(channels) },
            parseGroups(json) ?: DEFAULT_GROUPS,
        )
    }

    /** Null when the file predates groups, so a first load can still seed the examples. */
    private fun parseGroups(json: String?): List<String>? = runCatching {
        val array = JSONObject(json.orEmpty()).optJSONArray("groups") ?: return null
        buildList {
            for (i in 0 until array.length()) {
                array.optString(i).takeIf { it.isNotBlank() }?.let { add(it) }
            }
        }.let(::normalizeGroups)
    }.getOrNull()

    /** Trimmed, single-line, non-blank and unique, keeping the owner's order. */
    fun normalizeGroups(groups: List<String>): List<String> {
        val seen = LinkedHashSet<String>()
        for (group in groups) {
            val name = group.trim().replace(Regex("\\s+"), " ").take(40)
            if (name.isNotEmpty()) seen += name
        }
        return seen.toList()
    }

    /** Drops [urls] from the pool. Callers merge the remaining channels back, so a shared URL stays. */
    fun removePoolSources(pool: List<TvSource>, urls: Set<String>): List<TvSource> =
        pool.filterNot { it.url in urls }

    private fun readCbn(file: File): List<TvChannel>? {
        if (!file.isFile || !file.canRead()) return null
        return runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
            ?.let { TvSources.parseOrNull(it) }
            ?.takeIf { it.isNotEmpty() }
    }

    private fun readAsset(context: Context): List<TvChannel> = runCatching {
        context.assets.open(TvSources.FALLBACK_ASSET).bufferedReader().use { it.readText() }
    }.getOrNull().orEmpty().let { TvSources.parseM3u(it) }

    private fun latestBackup(dir: File): File? =
        File(dir, BACKUP_DIR).listFiles()
            ?.filter { it.isFile && it.name.endsWith(".json") }
            ?.filterNot { it.name.startsWith("corrupt-") }
            ?.maxByOrNull { it.lastModified() }

    private fun backup(context: Context, data: Data, dir: File) {
        val backups = File(dir, BACKUP_DIR).apply { mkdirs() }
        val file = File.createTempFile("aerial-", ".json", backups)
        runCatching { file.writeText(encodeExport(data.channels, data.pool, data.groups), Charsets.UTF_8) }
        backups.listFiles()
            ?.filter { it.name.startsWith("aerial-") && it.name.endsWith(".json") }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(BACKUP_KEEP)
            ?.forEach { it.delete() }
    }

    private fun writeAtomic(file: File, text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        runCatching {
            tmp.outputStream().use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            if (!tmp.renameTo(file)) {
                file.delete()
                if (!tmp.renameTo(file)) {
                    file.writeText(text, Charsets.UTF_8)
                    tmp.delete()
                }
            }
        }.onFailure {
            tmp.delete()
        }
    }
}
