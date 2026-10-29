package app.orchard.tv.tv

import android.content.Context

class TvPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("tv_window", Context.MODE_PRIVATE)

    var lastChannel: String?
        get() = prefs.getString("last_channel", null)
        set(value) = prefs.edit().putString("last_channel", value).apply()

    var lastSource: String?
        get() = prefs.getString("last_source", null)
        set(value) = prefs.edit().putString("last_source", value).apply()

    /** The storage permission was already asked for, so a denial is not asked again every start. */
    var storageAsked: Boolean
        get() = prefs.getBoolean("storage_asked", false)
        set(value) = prefs.edit().putBoolean("storage_asked", value).apply()

    fun clearLast() = prefs.edit().remove("last_channel").remove("last_source").apply()
}
