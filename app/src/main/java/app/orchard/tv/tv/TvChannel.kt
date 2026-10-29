package app.orchard.tv.tv

data class TvSource(val url: String, val label: String, val enabled: Boolean)

data class TvChannel(
    val name: String,
    val enabled: Boolean,
    val currentSource: Int,
    val sources: List<TvSource>,
) {
    /** The saved source first, then every other enabled source in file order. */
    fun playableSources(): List<TvSource> {
        if (!enabled) return emptyList()
        val saved = sources.getOrNull(currentSource)?.takeIf { it.enabled }
        val rest = sources.filterIndexed { index, source -> source.enabled && index != currentSource }
        return listOfNotNull(saved) + rest
    }
}
