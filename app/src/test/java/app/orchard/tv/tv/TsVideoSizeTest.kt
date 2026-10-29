package app.orchard.tv.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TsVideoSizeTest {
    private val sps = hex("67640029ac2b403c0223ef011000003e80000c350e000001ee6200002b4626ef2e0f8e1350")

    @Test
    fun readsResolutionFromH264Sps() {
        assertEquals(TsVideoSize.Size(1920, 1080), TsVideoSize.fromSps(sps))
    }

    @Test
    fun readsResolutionFromTransportStream() {
        val pat = packet(0, byteArrayOf(0) + hex("00B00D0001C100000001F00000000000"))
        val pmt = packet(0x1000, byteArrayOf(0) + hex("02B0120001C10000E12CF0001BE12CF00000000000"))
        val pes = packet(0x012C, hex("000001E00000800000") + byteArrayOf(0, 0, 0, 1) + sps)
        assertEquals(TsVideoSize.Size(1920, 1080), TsVideoSize.from(pat + pmt + pes))
    }

    @Test
    fun ignoresUnrelatedBytes() {
        assertNull(TsVideoSize.from(ByteArray(400) { 0x55 }))
    }

    private fun packet(pid: Int, payload: ByteArray): ByteArray {
        val packet = ByteArray(188)
        packet[0] = 0x47
        packet[1] = (0x40 or ((pid shr 8) and 0x1f)).toByte()
        packet[2] = (pid and 0xff).toByte()
        packet[3] = 0x10
        payload.copyInto(packet, 4, endIndex = minOf(payload.size, 184))
        return packet
    }

    private fun hex(value: String): ByteArray =
        ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
