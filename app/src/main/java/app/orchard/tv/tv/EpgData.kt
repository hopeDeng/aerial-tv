package app.orchard.tv.tv

import app.orchard.tv.system.DiagLog
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.regex.Pattern

data class EpgProgram(val title: String, val start: Long, val end: Long)

/**
 * Programme guide data: CCTV from the CNTV JSON API, satellite and local channels from tvmao's
 * HTML schedule pages. Same two sources and the same channel map as the reference player
 * (`EpgData.java`).
 */
object EpgData {
    private const val API = "https://zy.api.cntv.cn/epg/getEpgInfoByChannelNew"
    private const val TVMAO = "https://www.tvmao.com"
    private const val UA =
        "Mozilla/5.0 (Linux; Android 10; SP300 Build/CMDCSP300;) AppleWebKit/537.36"

    private val tz = TimeZone.getTimeZone("Asia/Shanghai")

    private var cacheSource: String? = null
    private var cacheDay: String? = null
    private var cacheList: List<EpgProgram>? = null

    /** Set when the last [load] could not reach or parse its source (empty list, not "no guide"). */
    @Volatile
    var lastError: String? = null
        private set

    /** Blocking; call from an IO dispatcher. Empty means no guide (or the fetch failed). */
    @Synchronized
    fun load(name: String): List<EpgProgram> {
        lastError = null
        val source = sourceFor(name) ?: return emptyList()
        val now = System.currentTimeMillis()
        val day = dayOf(now)
        if (source == cacheSource && day == cacheDay) cacheList?.let { return it }

        var list: List<EpgProgram> = request(source, day) ?: run {
            lastError = "request failed: $source $day"
            return emptyList()
        }
        val expired = list.isEmpty() || list.last().end < now
        if (expired) {
            val tomorrow = dayOf(now + 86_400_000L)
            request(source, tomorrow)?.takeIf { it.isNotEmpty() }?.let { list = it }
        }
        cacheSource = source
        cacheDay = day
        cacheList = list
        return list
    }

    /** Channel name -> data source, CCTV first, then tvmao; null when neither knows the channel. */
    fun sourceFor(name: String): String? {
        codeFor(name)?.let { return "c:$it" }
        slugFor(name)?.let { return "t:$it" }
        return null
    }

    private fun request(source: String, day: String): List<EpgProgram>? =
        if (source.startsWith("t:")) requestTvmao(source.substring(2), day)
        else requestCntv(source.substring(2), day)

    private fun requestCntv(code: String, day: String): List<EpgProgram>? {
        val text = httpGet("$API?c=$code&serviceId=tvcctv&d=$day&t=json") ?: return null
        return try {
            val data = JSONObject(text).optJSONObject("data") ?: return emptyList()
            val channel = data.optJSONObject(code) ?: data.keys().asSequence()
                .mapNotNull { data.optJSONObject(it) }
                .firstOrNull() ?: return emptyList()
            val array = channel.optJSONArray("list") ?: return emptyList()
            val out = mutableListOf<EpgProgram>()
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val title = item.optString("title").trim()
                val start = item.optLong("startTime", 0L) * 1000L
                val end = item.optLong("endTime", 0L) * 1000L
                if (title.isEmpty() || start <= 0L || end <= start) continue
                out += EpgProgram(title, start, end)
            }
            out.sortBy { it.start }
            out
        } catch (t: Throwable) {
            DiagLog.i("tv: epg cntv $code $day parse failed: ${t.message}")
            null
        }
    }

    private val timePattern =
        Pattern.compile("<span class=\"(?:am|pm)\">\\s*([0-9]{1,2}):([0-9]{2})")
    private val titlePattern = Pattern.compile("<span class=\"p_show\">(.*?)</span>", Pattern.DOTALL)
    private val tagPattern = Pattern.compile("<[^>]+>")

    /** tvmao gives start times only: each end is the next start, the last one runs to midnight. */
    private fun requestTvmao(slug: String, day: String): List<EpgProgram>? {
        val text = httpGet(tvmaoUrl(slug, day)) ?: return null
        val base = parseDay(day)
        if (base <= 0L) return emptyList()
        return try {
            val starts = mutableListOf<Long>()
            val titles = mutableListOf<String>()
            val m = timePattern.matcher(text)
            while (m.find()) {
                val from = m.end()
                val seg = text.substring(from, minOf(text.length, from + 600))
                val t = titlePattern.matcher(seg)
                if (!t.find()) continue
                val title = cleanText(t.group(1) ?: continue)
                if (title.isEmpty()) continue
                val hh = m.group(1)?.toIntOrNull() ?: continue
                val mm = m.group(2)?.toIntOrNull() ?: continue
                if (hh > 23 || mm > 59) continue
                starts += base + (hh * 60L + mm) * 60_000L
                titles += title
            }
            val out = mutableListOf<EpgProgram>()
            for (i in starts.indices) {
                val start = starts[i]
                var end = if (i + 1 < starts.size) starts[i + 1] else base + 86_400_000L
                if (end <= start) end = start + 60_000L
                out += EpgProgram(titles[i], start, end)
            }
            out
        } catch (t: Throwable) {
            DiagLog.i("tv: epg tvmao $slug $day parse failed: ${t.message}")
            null
        }
    }

    private fun tvmaoUrl(slug: String, day: String): String {
        val cut = slug.indexOf('/')
        if (cut <= 0) return TVMAO
        val category = slug.substring(0, cut)
        val code = slug.substring(cut + 1)
        val path = if (category == "local") "/program/" else "/program_$category/"
        return "$TVMAO$path$code-${tvmaoDayToken(day)}.html"
    }

    /** tvmao numbers days from this week's Monday: w1..w7 Monday..Sunday, then w8..w14. */
    private fun tvmaoDayToken(day: String): String {
        val requested = parseDay(day)
        val today = parseDay(dayOf(System.currentTimeMillis()))
        if (requested <= 0L || today <= 0L) return "w1"
        val calendar = Calendar.getInstance(tz)
        calendar.timeInMillis = today
        val dow = calendar.get(Calendar.DAY_OF_WEEK)
        val iso = if (dow == Calendar.SUNDAY) 7 else dow - 1
        val monday = today - (iso - 1) * 86_400_000L
        val token = ((requested - monday) / 86_400_000L).toInt() + 1
        return "w${token.coerceIn(1, 14)}"
    }

    private fun cleanText(raw: String): String {
        var s = tagPattern.matcher(raw).replaceAll("")
        s = s.replace("&nbsp;", " ").replace("&middot;", "·").replace("&amp;", "&")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&#39;", "'").replace('\u00a0', ' ')
        val sb = StringBuilder()
        var pendingSpace = false
        for (c in s) {
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                pendingSpace = true
                continue
            }
            if (pendingSpace && sb.isNotEmpty()) sb.append(' ')
            pendingSpace = false
            sb.append(c)
        }
        return sb.toString().trim()
    }

    private fun httpGet(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 8_000
            conn.readTimeout = 10_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", UA)
            if (conn.responseCode != 200) {
                DiagLog.i("tv: epg http ${conn.responseCode} $url")
                null
            } else {
                BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { it.readText() }
            }
        } catch (t: Throwable) {
            DiagLog.i("tv: epg http ${t.javaClass.simpleName}: ${t.message} $url")
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun codeFor(name: String): String? {
        val s = simpleName(name)
        val p = s.indexOf("cctv")
        if (p < 0) return null
        val tail = s.substring(p + 4)
        if (tail.startsWith("5plus") || tail.startsWith("5+")) return "cctv5plus"
        if (tail.startsWith("4k")) return "cctv4k"
        if (tail.startsWith("8k")) return "cctv8k"
        var i = 0
        while (i < tail.length && tail[i].isDigit()) i++
        if (i == 0) return null
        val num = tail.substring(0, i).toIntOrNull() ?: return null
        if (num < 1 || num > 17) return null
        if (num == 9) return "cctvjilu"
        if (num == 14) return "cctvchild"
        return "cctv$num"
    }

    private fun slugFor(name: String): String? {
        val key = simpleName(name)
        if (key.isEmpty()) return null
        return tvmaoMap.firstOrNull { simpleName(it.first) == key }?.second
    }

    private fun simpleName(name: String): String {
        var s = name.lowercase(Locale.ROOT)
        s = s.replace(Regex("\\([^)]*\\)|（[^）]*）|\\[[^\\]]*\\]|【[^】]*】"), "")
        val star = s.indexOf('*')
        if (star >= 0) s = s.substring(0, star)
        s = s.replace('衛', '卫').replace('視', '视').replace('樂', '乐')
        val sb = StringBuilder()
        for (c in s) {
            if (c == ' ' || c == '\t' || c == '-' || c == '_' || c == '·' || c == '.') continue
            sb.append(c)
        }
        return sb.toString().replace("频道", "").trim()
    }

    private fun dayOf(millis: Long): String {
        val fmt = SimpleDateFormat("yyyyMMdd", Locale.US)
        fmt.timeZone = tz
        return fmt.format(Date(millis))
    }

    private fun parseDay(day: String): Long {
        val fmt = SimpleDateFormat("yyyyMMdd", Locale.US)
        fmt.timeZone = tz
        return try {
            fmt.parse(day)?.time ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    private val tvmaoMap = listOf(
        "安徽卫视" to "satellite/AHTV1",
        "北京卫视" to "satellite/BTV1",
        "卡酷少儿" to "satellite/BTV10",
        "重庆卫视" to "satellite/CCQTV1",
        "东南卫视" to "satellite/FJTV2",
        "厦门卫视" to "satellite/XMTV5",
        "甘肃卫视" to "satellite/GSTV1",
        "广东卫视" to "satellite/GDTV1",
        "深圳卫视" to "satellite/SZTV1",
        "广西卫视" to "satellite/GUANXI1",
        "贵州卫视" to "satellite/GUIZOUTV1",
        "海南卫视" to "satellite/TCTC1",
        "河北卫视" to "satellite/HEBEI1",
        "黑龙江卫视" to "satellite/HLJTV1",
        "河南卫视" to "satellite/HNTV1",
        "湖北卫视" to "satellite/HUBEI1",
        "湖南卫视" to "satellite/HUNANTV1",
        "湖南金鹰卡通频道" to "satellite/HUNANTV2",
        "江苏卫视" to "satellite/JSTV1",
        "江西卫视" to "satellite/JXTV1",
        "吉林卫视" to "satellite/JILIN1",
        "辽宁卫视" to "satellite/LNTV1",
        "内蒙卫视" to "satellite/NMGTV1",
        "宁夏卫视" to "satellite/NXTV2",
        "山西卫视" to "satellite/SXTV1",
        "山东卫视" to "satellite/SDTV1",
        "东方卫视" to "satellite/DONGFANG1",
        "哈哈炫动" to "satellite/TOONMAX1",
        "陕西卫视" to "satellite/SHXITV1",
        "四川卫视" to "satellite/SCTV1",
        "天津卫视" to "satellite/TJTV1",
        "新疆卫视" to "satellite/XJTV1",
        "云南卫视" to "satellite/YNTV1",
        "浙江卫视" to "satellite/ZJTV1",
        "青海卫视" to "satellite/QHTV1",
        "藏语卫视" to "satellite/XIZANGTV1",
        "西藏卫视" to "satellite/XIZANGTV2",
        "延边卫视" to "satellite/YANBIAN1",
        "兵团卫视" to "satellite/BINGTUAN",
        "海峡卫视" to "satellite/HXTV",
        "黄河卫视" to "satellite/HHWS",
        "康巴卫视" to "satellite/KAMBA-TV",
        "三沙卫视" to "satellite/SANSHATV",
        "湖南娱乐频道" to "local/HUNANTV-HUNANTV3",
        "湖南电视剧频道" to "local/HUNANTV-HUNANTV4",
        "湖南爱晚频道" to "local/HUNANTV-HUNANTV5",
        "湖南电影频道" to "local/HUNANTV-HUNANTV6",
        "湖南卫视国际频道" to "local/HUNANTV-HUNANTV7",
        "湖南经视" to "local/HNETV-HNETV1",
        "金鹰纪实频道" to "local/HNETV-HNETV3",
        "长沙文旅频道" to "local/CHANGSHAWUMON-CHANGSHAWUMON1",
        "长沙政法频道" to "local/CHANGSHAWUMON-CHANGSHAWUMON2",
        "长沙影视频道" to "local/CHANGSHAWUMON-CHANGSHAWUMON3",
        "长沙新闻频道" to "local/CHANGSHAWUMON-CHANGSHAWUMON4",
        "长沙公共频道" to "local/CHANGSHAWUMON-CHANGSHAWUMON5",
        "郴州新闻综合" to "local/HNCZTV-HNCZTV1",
        "郴州公共频道" to "local/HNCZTV-HNCZTV2",
        "郴州南岭生活" to "local/HNCZTV-HNCZTV3",
        "湘潭新闻" to "local/HNXTTV-HNXTTV1",
        "株洲公共频道" to "local/HNZZTV-HNZZTV2",
        "常德新闻频道" to "local/HNCDTV-HNCDTV1",
        "常德图文频道" to "local/HNCDTV-HNCDTV4",
        "鼎城电视频道" to "local/HNCDTV-CDDC",
        "益阳新闻综合频道" to "local/YYRTV-YYRTVXW",
        "益阳乡村频道" to "local/YYRTV-YYRTVXC",
        "岳阳新闻综合频道" to "local/HNYYTV-HBYYTV1",
        "长株潭三套" to "local/HNXTTV-HNXTTV5",
        "岳阳科教频道" to "local/HNYYTV-HBYYTV3",
        "张家界新闻频道" to "local/ZJJTV-ZJJTV1",
        "张家界都市频道" to "local/ZJJTV-ZJJTV2",
        "张家界旅游频道" to "local/ZJJTV-ZJJTV3",
        "张家界娱乐频道" to "local/ZJJTV-ZJJTV4",
        "娄底综合频道" to "local/LDTV-LDTV1",
        "娄底公共频道" to "local/LDTV-LDTV2",
        "长株潭一套" to "local/CHANGSHAWUMON-CZT1",
        "邵阳新闻频道" to "local/SHYTV-SHAOYANGXW",
        "邵阳文旅民生" to "local/SHYTV-SYZFMS",
        "嘉丽购物频道" to "local/CHANGSHAWUMON-JLGOUWU",
        "金鹰卡通" to "satellite/HUNANTV2",
        "黑龙江" to "satellite/HLJTV1",
        "湖南国际" to "local/HUNANTV-HUNANTV7",
        "内蒙古卫视" to "satellite/NMGTV1",
    )
}
