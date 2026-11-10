package dev.omnimirror.core

import java.io.Closeable

/** Wire constants shared by every implementation (Kotlin, Swift, JavaScript). See docs/PROTOCOL.md. */
object Proto {
    const val VERSION = 1

    /** First bytes a native peer sends after connecting, so the listener can tell it from HTTP. */
    val MAGIC = byteArrayOf('O'.code.toByte(), 'M'.code.toByte(), 'M'.code.toByte(), '1'.code.toByte())

    const val DEFAULT_PORT = 47800
    const val DEFAULT_TLS_PORT = 47801
    const val SERVICE_TYPE = "_omnimirror._tcp"

    const val ROLE_SENDER = "sender"
    const val ROLE_RECEIVER = "receiver"

    // Handshake
    const val HELLO = 0x01
    const val CHALLENGE = 0x02
    const val AUTH = 0x03
    const val AUTH_RESULT = 0x04

    // Sender -> receiver
    const val STREAM_INFO = 0x10
    const val VIDEO = 0x11
    const val PONG = 0x21

    // Receiver -> sender
    const val PING = 0x20
    const val STATS = 0x30
    const val REQUEST_KEY = 0x31

    const val BYE = 0x40

    const val VIDEO_FLAG_KEY = 0x01
    const val VIDEO_HEADER_SIZE = 9 // flags(1) + ptsUs(8)

    const val MAX_MESSAGE = 16 * 1024 * 1024
}

class Message(val type: Int, val payload: ByteArray)

/**
 * A bidirectional stream of typed messages. Implemented by the raw TCP framing
 * and by WebSocket, so the session logic does not care which one it is talking to.
 * One thread may read while another writes; writes must not be concurrent.
 */
interface MessageChannel : Closeable {
    val remote: String
    fun read(): Message
    fun write(type: Int, payload: ByteArray, offset: Int = 0, length: Int = payload.size)
    /** Bounds how long [read] may block; 0 means forever. */
    fun setReadTimeout(millis: Int)
}

/** Stream parameters a receiver needs before it can decode. */
data class StreamInfo(
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrate: Int,
    val sps: ByteArray,
    val pps: ByteArray,
) {
    fun toJson(): String = Json.encode(
        mapOf(
            "codec" to "h264",
            "width" to width,
            "height" to height,
            "fps" to fps,
            "bitrate" to bitrate,
            "sps" to java.util.Base64.getEncoder().encodeToString(sps),
            "pps" to java.util.Base64.getEncoder().encodeToString(pps),
        )
    )

    companion object {
        fun fromJson(text: String): StreamInfo {
            val m = Json.parseObject(text)
            val dec = java.util.Base64.getDecoder()
            return StreamInfo(
                (m["width"] as Number).toInt(), (m["height"] as Number).toInt(),
                (m["fps"] as Number).toInt(), (m["bitrate"] as Number).toInt(),
                dec.decode(m["sps"] as String), dec.decode(m["pps"] as String),
            )
        }
    }
}

fun putLong(buf: ByteArray, off: Int, v: Long) {
    for (i in 0 until 8) buf[off + i] = (v ushr (56 - 8 * i)).toByte()
}

fun getLong(buf: ByteArray, off: Int): Long {
    var v = 0L
    for (i in 0 until 8) v = (v shl 8) or (buf[off + i].toLong() and 0xff)
    return v
}

/** Builds a VIDEO payload: flags, capture timestamp (sender monotonic clock, µs), Annex-B access unit. */
fun videoPayload(data: ByteArray, offset: Int, length: Int, ptsUs: Long, key: Boolean): ByteArray {
    val out = ByteArray(Proto.VIDEO_HEADER_SIZE + length)
    out[0] = if (key) Proto.VIDEO_FLAG_KEY.toByte() else 0
    putLong(out, 1, ptsUs)
    System.arraycopy(data, offset, out, Proto.VIDEO_HEADER_SIZE, length)
    return out
}
