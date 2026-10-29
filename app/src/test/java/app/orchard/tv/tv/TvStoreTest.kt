package app.orchard.tv.tv

import android.content.Context
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TvStoreTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val dir = File(context.cacheDir, "tv-test-${System.nanoTime()}")

    private fun channel(name: String, vararg urls: String) =
        TvChannel(name, true, 0, urls.map { TvSource(it, "L", true) })

    @Test fun `migrates the box file once and then ignores it`() {
        val cbn = File(dir, "cbn.json").apply {
            parentFile?.mkdirs()
            writeText("""{"channels":[{"name":"湖南卫视","sources":[{"url":"http://a/1"}]}]}""")
        }
        val first = TvStore.load(context, dir, cbn)
        assertEquals(listOf("湖南卫视"), first.channels.map { it.name })
        assertTrue(File(dir, TvStore.FILE).isFile)

        cbn.writeText("""{"channels":[{"name":"改成别的","sources":[{"url":"http://b/2"}]}]}""")
        val second = TvStore.load(context, dir, cbn)
        assertEquals(listOf("湖南卫视"), second.channels.map { it.name })
    }

    @Test fun `saves channels and the pool and reads them back`() {
        val channels = listOf(
            channel("A", "http://a/1", "http://a/2"),
            TvChannel("B", false, 0, listOf(TvSource("http://b/1", "", false))),
        )
        val pool = TvStore.poolFromChannels(channels)
        TvStore.save(context, channels, pool, dir)

        val loaded = TvStore.load(context, dir, File(dir, "missing.json"))
        assertEquals(listOf("A", "B"), loaded.channels.map { it.name })
        assertEquals(listOf("http://a/1", "http://a/2", "http://b/1"), loaded.pool.map { it.url })
        assertFalse(File(dir, TvStore.FILE).readText().contains("sourcePool"))
        assertTrue(TvStore.encodeExport(channels, pool).contains("sourcePool"))
    }

    @Test fun `a damaged file goes back to the newest backup`() {
        val one = listOf(channel("A", "http://a/1"))
        val two = listOf(channel("A", "http://a/1"), channel("B", "http://b/1"))
        TvStore.save(context, one, TvStore.poolFromChannels(one), dir)
        TvStore.save(context, two, TvStore.poolFromChannels(two), dir)

        File(dir, TvStore.FILE).writeText("not json")
        val loaded = TvStore.load(context, dir, File(dir, "missing.json"))
        assertEquals(listOf("A"), loaded.channels.map { it.name })
        assertTrue(File(dir, TvStore.BACKUP_DIR).listFiles()!!.any { it.name.startsWith("corrupt-") })
    }

    @Test fun `an empty configuration stays empty instead of migrating again`() {
        val cbn = File(dir, "cbn.json").apply {
            parentFile?.mkdirs()
            writeText("""{"channels":[{"name":"湖南卫视","sources":[{"url":"http://a/1"}]}]}""")
        }
        TvStore.save(context, emptyList(), emptyList(), dir)
        val loaded = TvStore.load(context, dir, cbn)
        assertTrue(loaded.channels.isEmpty())
    }

    @Test fun `the pool keeps one entry per URL and the first label`() {
        val merged = TvStore.mergePool(
            listOf(TvSource("http://a", "first", true), TvSource("http://b", "", true)),
            listOf(TvSource("http://a", "second", true), TvSource("http://b", "B", true)),
        )
        assertEquals(listOf("http://a", "http://b"), merged.map { it.url })
        assertEquals(listOf("first", "B"), merged.map { it.label })
    }

    @Test fun `keeps only five backups`() {
        repeat(7) { i ->
            val channels = listOf(channel("A$i", "http://a/$i"))
            TvStore.save(context, channels, TvStore.poolFromChannels(channels), dir)
        }
        val backups = File(dir, TvStore.BACKUP_DIR).listFiles()!!.filter { it.name.startsWith("aerial-") }
        assertEquals(TvStore.BACKUP_KEEP, backups.size)
    }
}
