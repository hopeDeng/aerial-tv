package app.orchard.tv.tv

import java.io.ByteArrayOutputStream

/**
 * Reads the H.264 SPS out of an MPEG-TS sample. LibVLC reports a 0x0 video track for the hnyd/ZTE
 * HLS streams even while the decoder is showing a picture, so this is the fallback for resolution.
 */
internal object TsVideoSize {
    data class Size(val width: Int, val height: Int)

    private const val PACKET = 188
    private const val SYNC = 0x47
    private const val PAT_PID = 0
    private const val H264 = 0x1b

    fun from(bytes: ByteArray): Size? = runCatching {
        val start = firstSync(bytes) ?: return null
        val pmtPid = findPmtPid(bytes, start) ?: return null
        val videoPid = findVideoPid(bytes, start, pmtPid) ?: return null
        parseSpsFromPes(videoPayload(bytes, start, videoPid))
    }.getOrNull()

    /** Exposed for the unit test: [nal] starts with the H.264 NAL header (0x67 for SPS). */
    internal fun fromSps(nal: ByteArray): Size? = runCatching {
        val rbsp = unescape(nal)
        val bits = Bits(rbsp)
        bits.skip(8)
        val profile = bits.u(8)
        bits.skip(8)
        bits.skip(8)
        bits.ue()
        var chroma = 1
        var separateColourPlane = false
        if (profile in HIGH_PROFILES) {
            chroma = bits.ue()
            if (chroma == 3) separateColourPlane = bits.u(1) == 1
            bits.ue()
            bits.ue()
            bits.skip(1)
            if (bits.u(1) == 1) {
                repeat(if (chroma == 3) 12 else 8) { list ->
                    if (bits.u(1) == 1) scalingList(bits, if (list < 6) 16 else 64)
                }
            }
        }
        bits.ue()
        when (bits.ue()) {
            0 -> bits.ue()
            1 -> {
                bits.skip(1)
                bits.se()
                bits.se()
                repeat(bits.ue()) { bits.se() }
            }
        }
        bits.ue()
        bits.skip(1)
        val widthInMbs = bits.ue() + 1
        val heightInMapUnits = bits.ue() + 1
        val frameMbsOnly = bits.u(1) == 1
        if (!frameMbsOnly) bits.skip(1)
        bits.skip(1)
        var cropLeft = 0
        var cropRight = 0
        var cropTop = 0
        var cropBottom = 0
        if (bits.u(1) == 1) {
            cropLeft = bits.ue()
            cropRight = bits.ue()
            cropTop = bits.ue()
            cropBottom = bits.ue()
        }
        val chromaArrayType = if (separateColourPlane) 0 else chroma
        val subWidth = if (chromaArrayType == 1 || chromaArrayType == 2) 2 else 1
        val subHeight = if (chromaArrayType == 1) 2 else 1
        val cropUnitX = if (chromaArrayType == 0) 1 else subWidth
        val cropUnitY = if (chromaArrayType == 0) {
            2 - (if (frameMbsOnly) 1 else 0)
        } else {
            subHeight * (2 - (if (frameMbsOnly) 1 else 0))
        }
        val heightFactor = 2 - (if (frameMbsOnly) 1 else 0)
        val width = widthInMbs * 16 - cropUnitX * (cropLeft + cropRight)
        val height = heightFactor * heightInMapUnits * 16 - cropUnitY * (cropTop + cropBottom)
        if (width > 0 && height > 0) Size(width, height) else null
    }.getOrNull()

    private val HIGH_PROFILES = setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134)

    private fun firstSync(bytes: ByteArray): Int? {
        if (bytes.size < PACKET * 2) return null
        for (offset in 0 until PACKET) {
            if (bytes[offset] != SYNC.toByte()) continue
            if (offset + PACKET < bytes.size && bytes[offset + PACKET] != SYNC.toByte()) continue
            return offset
        }
        return null
    }

    private fun pid(bytes: ByteArray, packet: Int): Int =
        ((bytes[packet + 1].toInt() and 0x1f) shl 8) or (bytes[packet + 2].toInt() and 0xff)

    private fun payloadStart(bytes: ByteArray, packet: Int): Int {
        val control = (bytes[packet + 3].toInt() shr 4) and 3
        if (control and 1 == 0) return -1
        var offset = packet + 4
        if (control and 2 != 0) {
            if (offset >= packet + PACKET) return -1
            offset += 1 + (bytes[offset].toInt() and 0xff)
        }
        return if (offset < packet + PACKET) offset else -1
    }

    /** The PSI section body without its CRC, or null when it crosses into another packet. */
    private fun section(bytes: ByteArray, packet: Int): IntRange? {
        val payload = payloadStart(bytes, packet)
        if (payload < 0) return null
        val pointer = bytes[payload].toInt() and 0xff
        val start = payload + 1 + pointer
        val end = packet + PACKET
        if (start + 3 > end) return null
        val length = ((bytes[start + 1].toInt() and 0x0f) shl 8) or (bytes[start + 2].toInt() and 0xff)
        val sectionEnd = start + 3 + length
        if (sectionEnd > end || sectionEnd - 4 < start + 3) return null
        return start until sectionEnd - 4
    }

    private fun findPmtPid(bytes: ByteArray, start: Int): Int? {
        for (packet in start until bytes.size - PACKET + 1 step PACKET) {
            if (bytes[packet] != SYNC.toByte() || pid(bytes, packet) != PAT_PID) continue
            val section = section(bytes, packet) ?: continue
            if (bytes[section.first].toInt() and 0xff != 0x00) continue
            var at = section.first + 8
            while (at + 4 <= section.last + 1) {
                val program = ((bytes[at].toInt() and 0xff) shl 8) or (bytes[at + 1].toInt() and 0xff)
                val pmt = ((bytes[at + 2].toInt() and 0x1f) shl 8) or (bytes[at + 3].toInt() and 0xff)
                if (program != 0) return pmt
                at += 4
            }
        }
        return null
    }

    private fun findVideoPid(bytes: ByteArray, start: Int, pmtPid: Int): Int? {
        for (packet in start until bytes.size - PACKET + 1 step PACKET) {
            if (bytes[packet] != SYNC.toByte() || pid(bytes, packet) != pmtPid) continue
            val section = section(bytes, packet) ?: continue
            if (bytes[section.first].toInt() and 0xff != 0x02 || section.first + 12 > section.last + 1) continue
            val programInfo = ((bytes[section.first + 10].toInt() and 0x0f) shl 8) or
                (bytes[section.first + 11].toInt() and 0xff)
            var at = section.first + 12 + programInfo
            while (at + 5 <= section.last + 1) {
                val type = bytes[at].toInt() and 0xff
                val video = ((bytes[at + 1].toInt() and 0x1f) shl 8) or (bytes[at + 2].toInt() and 0xff)
                if (type == H264) return video
                val info = ((bytes[at + 3].toInt() and 0x0f) shl 8) or (bytes[at + 4].toInt() and 0xff)
                at += 5 + info
            }
        }
        return null
    }

    private fun videoPayload(bytes: ByteArray, start: Int, videoPid: Int): ByteArray {
        val out = ByteArrayOutputStream()
        for (packet in start until bytes.size - PACKET + 1 step PACKET) {
            if (bytes[packet] != SYNC.toByte() || pid(bytes, packet) != videoPid) continue
            var from = payloadStart(bytes, packet)
            if (from < 0) continue
            val end = packet + PACKET
            if (bytes[packet + 1].toInt() and 0x40 != 0 &&
                from + 9 <= end &&
                bytes[from].toInt() == 0 && bytes[from + 1].toInt() == 0 && bytes[from + 2].toInt() == 1
            ) {
                from += 9 + (bytes[from + 8].toInt() and 0xff)
            }
            if (from < end) out.write(bytes, from, end - from)
        }
        return out.toByteArray()
    }

    private fun parseSpsFromPes(data: ByteArray): Size? {
        var at = 0
        while (at + 3 < data.size) {
            val prefix = when {
                data[at].toInt() == 0 && data[at + 1].toInt() == 0 && data[at + 2].toInt() == 1 -> 3
                at + 4 < data.size && data[at].toInt() == 0 && data[at + 1].toInt() == 0 &&
                    data[at + 2].toInt() == 0 && data[at + 3].toInt() == 1 -> 4
                else -> 0
            }
            if (prefix == 0) {
                at++
                continue
            }
            val nal = at + prefix
            val next = nextStartCode(data, nal + 1) ?: data.size
            if (data[nal].toInt() and 0x1f == 7) return fromSps(data.copyOfRange(nal, next))
            at = next
        }
        return null
    }

    private fun nextStartCode(data: ByteArray, from: Int): Int? {
        for (at in from until data.size - 2) {
            if (data[at].toInt() == 0 && data[at + 1].toInt() == 0 &&
                (data[at + 2].toInt() == 1 || (data[at + 2].toInt() == 0 &&
                    at + 3 < data.size && data[at + 3].toInt() == 1))
            ) {
                return at
            }
        }
        return null
    }

    private fun unescape(nal: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(nal.size)
        var zeros = 0
        for (byte in nal) {
            val value = byte.toInt() and 0xff
            if (zeros >= 2 && value == 3) {
                zeros = 0
                continue
            }
            out.write(value)
            zeros = if (value == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    private fun scalingList(bits: Bits, count: Int) {
        var last = 8
        var next = 8
        repeat(count) {
            if (next != 0) next = (last + bits.se() + 256) % 256
            if (next != 0) last = next
        }
    }

    private class Bits(private val bytes: ByteArray) {
        private var bit = 0

        fun skip(count: Int) {
            bit += count
            check(bit <= bytes.size * 8)
        }

        fun u(count: Int): Int {
            var value = 0
            repeat(count) {
                check(bit < bytes.size * 8)
                val set = (bytes[bit shr 3].toInt() shr (7 - (bit and 7))) and 1
                value = (value shl 1) or set
                bit++
            }
            return value
        }

        fun ue(): Int {
            var zeros = 0
            while (u(1) == 0) {
                zeros++
                if (zeros > 31) error("bad exp-golomb")
            }
            if (zeros == 0) return 0
            return (1 shl zeros) - 1 + u(zeros)
        }

        fun se(): Int {
            val value = ue()
            return if (value and 1 == 1) (value + 1) / 2 else -(value / 2)
        }
    }
}
