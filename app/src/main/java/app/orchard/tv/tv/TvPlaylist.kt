package app.orchard.tv.tv

class TvPlaylist(val channels: List<TvChannel>) {
    fun first(): Pair<Int, TvSource>? =
        channels.indices.firstNotNullOfOrNull { i -> channels[i].playableSources().firstOrNull()?.let { i to it } }

    /** The next channel that has something to play, and its first source: Left/Right change channel. */
    fun nextChannel(channelIndex: Int): Pair<Int, TvSource>? {
        for (step in 1..channels.size) {
            val at = (channelIndex + step) % channels.size
            channels.getOrNull(at)?.playableSources()?.firstOrNull()?.let { return at to it }
        }
        return null
    }

    fun prevChannel(channelIndex: Int): Pair<Int, TvSource>? {
        for (step in 1..channels.size) {
            val at = (channelIndex - step + channels.size * step) % channels.size
            channels.getOrNull(at)?.playableSources()?.firstOrNull()?.let { return at to it }
        }
        return null
    }
}
