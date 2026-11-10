package dev.omnimirror.core

/** Just enough H.264 bitstream handling to find parameter sets and keyframes in Annex-B data. */
object H264 {
    const val NAL_SLICE = 1
    const val NAL_IDR = 5
    const val NAL_SEI = 6
    const val NAL_SPS = 7
    const val NAL_PPS = 8
    const val NAL_AUD = 9

    /** A NAL unit inside a larger buffer: [start, end) excludes the start code. */
    class Nal(val start: Int, val end: Int, val type: Int)

    fun split(buf: ByteArray, offset: Int = 0, length: Int = buf.size - offset): List<Nal> {
        val out = ArrayList<Nal>()
        val limit = offset + length
        var i = offset
        var nalStart = -1
        while (i + 2 < limit) {
            if (buf[i].toInt() == 0 && buf[i + 1].toInt() == 0 && buf[i + 2].toInt() == 1) {
                if (nalStart >= 0) {
                    var end = i
                    // A 4-byte start code leaves one zero that belongs to the code, not the NAL.
                    if (end > nalStart && buf[end - 1].toInt() == 0) end--
                    add(out, buf, nalStart, end)
                }
                nalStart = i + 3
                i += 3
            } else i++
        }
        if (nalStart in 0 until limit) add(out, buf, nalStart, limit)
        return out
    }

    private fun add(out: MutableList<Nal>, buf: ByteArray, start: Int, end: Int) {
        if (end > start) out.add(Nal(start, end, buf[start].toInt() and 0x1f))
    }

    /** Pulls SPS and PPS out of a MediaCodec codec-config buffer (or any Annex-B chunk). */
    fun parameterSets(buf: ByteArray, offset: Int = 0, length: Int = buf.size - offset): Pair<ByteArray, ByteArray>? {
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        for (n in split(buf, offset, length)) {
            if (n.type == NAL_SPS && sps == null) sps = buf.copyOfRange(n.start, n.end)
            if (n.type == NAL_PPS && pps == null) pps = buf.copyOfRange(n.start, n.end)
        }
        return if (sps != null && pps != null) sps to pps else null
    }

    fun containsIdr(buf: ByteArray, offset: Int = 0, length: Int = buf.size - offset): Boolean =
        split(buf, offset, length).any { it.type == NAL_IDR }
}
