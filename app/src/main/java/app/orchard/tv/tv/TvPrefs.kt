package app.orchard.tv.tv

import android.content.Context
import org.json.JSONObject

class TvPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("tv_window", Context.MODE_PRIVATE)

    var lastChannel: String?
        get() = prefs.getString("last_channel", null)
        set(value) = prefs.edit().putString("last_channel", value).apply()

    var lastSource: String?
        get() = prefs.getString("last_source", null)
        set(value) = prefs.edit().putString("last_source", value).apply()

    /** The channel list's last picker, as [TvGroupFilter.storageKey]. */
    var channelGroup: String
        get() = prefs.getString("channel_group", "all") ?: "all"
        set(value) = prefs.edit().putString("channel_group", value).apply()

    var watchStats: Map<String, WatchStat>
        get() {
            val text = prefs.getString("watch_stats", null) ?: return emptyMap()
            return runCatching {
                val root = JSONObject(text)
                buildMap {
                    for (name in root.keys()) {
                        val item = root.optJSONObject(name) ?: continue
                        put(name, WatchStat(item.optLong("total"), item.optLong("last")))
                    }
                }
            }.getOrDefault(emptyMap())
        }
        set(value) {
            val root = JSONObject()
            for ((name, stat) in value) {
                root.put(name, JSONObject().put("total", stat.totalMs).put("last", stat.lastWatchedMs))
            }
            prefs.edit().putString("watch_stats", root.toString()).apply()
        }

    /** The storage permission was already asked for, so a denial is not asked again every start. */
    var storageAsked: Boolean
        get() = prefs.getBoolean("storage_asked", false)
        set(value) = prefs.edit().putBoolean("storage_asked", value).apply()

    fun clearLast() = prefs.edit().remove("last_channel").remove("last_source").apply()
}
