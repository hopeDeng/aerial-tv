package app.orchard.tv.tv

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object TvSources {
    const val CBN_FILE = "/sdcard/iptv_sources.json"
    const val FALLBACK_ASSET = "hnyd.m3u"

    /** Every named channel, including disabled ones and ones whose sources are all disabled. */
    fun parse(json: String): List<TvChannel> = parseOrNull(json) ?: emptyList()

    /**
     * Like [parse], but null when the text is not a channel file at all. An empty `channels` array
     * is a valid empty list, which is what lets [TvStore] tell "the user deleted everything" from
     * "this file is damaged".
     */
    fun parseOrNull(json: String): List<TvChannel>? {
        val root = runCatching { org.json.JSONObject(json) }.getOrNull() ?: return null
        val array = root.optJSONArray("channels") ?: return null
        val result = mutableListOf<TvChannel>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val sourcesJson = item.optJSONArray("sources") ?: continue
            val sources = mutableListOf<TvSource>()
            for (j in 0 until sourcesJson.length()) {
                val source = sourcesJson.optJSONObject(j) ?: continue
                val url = source.optString("url").trim()
                if (url.isNotEmpty()) {
                    sources += TvSource(url, source.optString("label"), source.optBoolean("enabled", true))
                }
            }
            val name = item.optString("name").trim()
            if (name.isNotEmpty()) {
                result += TvChannel(
                    name = name,
                    enabled = item.optBoolean("enabled", true),
                    currentSource = item.optInt("currentSource", 0),
                    sources = sources,
                )
            }
        }
        return result
    }

    fun parseM3u(text: String): List<TvChannel> {
        val result = mutableListOf<TvChannel>()
        var name: String? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("#EXTINF")) {
                name = line.substringAfter(',', "").trim().ifEmpty { null }
            } else if (line.isNotEmpty() && !line.startsWith("#") && name != null) {
                result += TvChannel(name, true, 0, listOf(TvSource(line, "", true)))
                name = null
            }
        }
        return result
    }

    /** Reads Aerial's private copy, migrating the box's file on first use. Never throws. */
    suspend fun load(context: Context): List<TvChannel> = withContext(Dispatchers.IO) {
        TvStore.load(context).channels
    }

    /** The private channel file's modification time, or 0 when it cannot be read. */
    fun stamp(context: Context): Long = TvStore.stamp(context)
}
