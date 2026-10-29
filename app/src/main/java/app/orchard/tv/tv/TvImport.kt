package app.orchard.tv.tv

import java.net.URI
import java.nio.charset.Charset

/**
 * The list formats the box accepted. The rules are the box's (see the reference player's
 * M3uParser/SourceStore) so a playlist imported on the phone lands in the same channels it would
 * have on the TV.
 */
object TvImport {
    data class Entry(val name: String, val url: String)

    data class PreviewItem(
        val name: String,
        val url: String,
        val label: String,
        /** The channel this entry will be merged into, or null when it creates a new one. */
        val target: String?,
        val duplicate: Boolean,
    )

    data class Preview(val items: List<PreviewItem>)

    data class Result(
        val channels: List<TvChannel>,
        val addedChannels: Int,
        val addedSources: Int,
        val duplicates: Int,
    )

    /** `#EXTINF` name, bare URL, or the box's `name,url` line. Only `http` URLs are kept. */
    fun parseM3u(text: String): List<Entry> {
        val result = mutableListOf<Entry>()
        var pending: String? = null
        for (raw in text.removePrefix("\uFEFF").lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#EXTINF")) {
                pending = line.substringAfterLast(',', "").trim().ifEmpty { null }
            } else if (line.startsWith("#")) {
                continue
            } else {
                var name = pending
                var url = line
                val comma = line.indexOf(',')
                if (comma > 0 && !line.startsWith("http")) {
                    name = line.substring(0, comma).trim()
                    url = line.substring(comma + 1).trim()
                }
                if (url.startsWith("http", ignoreCase = true)) {
                    if (name.isNullOrBlank()) name = "频道${result.size + 1}"
                    result += Entry(name, url)
                }
                pending = null
            }
        }
        return result
    }

    /** A file name without its extension, or a URL's host, as the source label. */
    fun labelForFile(name: String): String =
        name.substringBeforeLast('.').trim().takeIf { it.isNotEmpty() } ?: "m3u"

    fun labelForUrl(url: String): String =
        runCatching { URI(url).host }.getOrNull()?.removePrefix("www.")?.takeIf { it.isNotBlank() } ?: "url"

    fun preview(channels: List<TvChannel>, entries: List<Entry>, label: String): Preview {
        val working = channels.toMutableList()
        val items = entries.map { entry ->
            val target = findByName(working, entry.name)
            val channel = target?.let { working[it] }
            val duplicate = channel?.sources?.any { it.url == entry.url } == true
            if (target == null) {
                working += TvChannel(entry.name, true, 0, listOf(TvSource(entry.url, label, true)))
            } else if (!duplicate) {
                working[target] = channel!!.copy(sources = channel.sources + TvSource(entry.url, label, true))
            }
            PreviewItem(entry.name, entry.url, label, channel?.name, duplicate)
        }
        return Preview(items)
    }

    fun merge(channels: List<TvChannel>, entries: List<Entry>, label: String): Result {
        val working = channels.toMutableList()
        var addedChannels = 0
        var addedSources = 0
        var duplicates = 0
        for (entry in entries) {
            val target = findByName(working, entry.name)
            if (target == null) {
                working += TvChannel(entry.name, true, 0, listOf(TvSource(entry.url, label, true)))
                addedChannels++
                continue
            }
            val channel = working[target]
            if (channel.sources.any { it.url == entry.url }) {
                duplicates++
                continue
            }
            working[target] = channel.copy(sources = channel.sources + TvSource(entry.url, label, true))
            addedSources++
        }
        return Result(reorderPreferred(working, "hnyd"), addedChannels, addedSources, duplicates)
    }

    /**
     * The box puts `hnyd`'s sources first. Keep the source that was current by URL, since the
     * reference only rewrites the order and would leave `currentSource` pointing elsewhere.
     */
    fun reorderPreferred(channels: List<TvChannel>, label: String): List<TvChannel> = channels.map { channel ->
        val preferred = channel.sources.filter { it.label == label }
        if (preferred.isEmpty()) return@map channel
        val rest = channel.sources.filterNot { it.label == label }
        val currentUrl = channel.sources.getOrNull(channel.currentSource)?.url
        val reordered = preferred + rest
        val current = reordered.indexOfFirst { it.url == currentUrl }.takeIf { it >= 0 } ?: channel.currentSource
        channel.copy(sources = reordered, currentSource = current)
    }

    fun normalizeName(name: String?): String {
        if (name == null) return ""
        val drop = " -_·+超清高电视频道"
        return name.lowercase().filterNot { it in drop }
    }

    /** Exact normalized match first, then a containment match as long as it is not a channel number. */
    fun findByName(channels: List<TvChannel>, name: String): Int? {
        val normalized = normalizeName(name)
        channels.forEachIndexed { index, channel ->
            if (normalizeName(channel.name) == normalized) return index
        }
        channels.forEachIndexed { index, channel ->
            val existing = normalizeName(channel.name)
            if (existing.isEmpty()) return@forEachIndexed
            val shorter = if (existing.length <= normalized.length) existing else normalized
            val longer = if (existing.length <= normalized.length) normalized else existing
            if (shorter.length < 3 || !longer.contains(shorter)) return@forEachIndexed
            val tail = longer.substring(shorter.length)
            if (tail.isNotEmpty() && tail.first().isDigit()) return@forEachIndexed
            return index
        }
        return null
    }

    /** M3U files in the wild are often GBK; UTF-8 first, then GBK when it produced replacement chars. */
    fun decode(bytes: ByteArray, charset: Charset? = null): String {
        if (charset != null) {
            return runCatching { bytes.toString(charset) }.getOrDefault(bytes.toString(Charsets.UTF_8))
                .removePrefix("\uFEFF")
        }
        val utf8 = bytes.toString(Charsets.UTF_8)
        if (!utf8.contains('\uFFFD')) return utf8.removePrefix("\uFEFF")
        return runCatching { bytes.toString(Charset.forName("GBK")) }.getOrDefault(utf8).removePrefix("\uFEFF")
    }

    fun exportM3u(channels: List<TvChannel>): String = buildString {
        append("#EXTM3U\n")
        for (channel in channels) {
            val source = channel.sources.getOrNull(channel.currentSource) ?: continue
            append("#EXTINF:-1,").append(channel.name).append('\n')
            append(source.url).append('\n')
        }
    }
}
