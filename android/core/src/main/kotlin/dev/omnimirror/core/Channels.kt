package dev.omnimirror.core

import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64

/** Latency matters more than throughput here: no Nagle, and a small send buffer so that
 *  congestion shows up in our own queue (where we can drop frames) instead of hiding in the kernel. */
fun tuneSocket(socket: Socket) {
    socket.tcpNoDelay = true
    socket.keepAlive = true
    runCatching { socket.sendBufferSize = 128 * 1024 }
    runCatching { socket.trafficClass = 0x10 } // IPTOS_LOWDELAY
}

/** Native framing: [u32 length][u8 type][payload], length counts type + payload. */
class TcpChannel(private val socket: Socket, input: InputStream = socket.getInputStream()) : MessageChannel {
    private val input = DataInputStream(input)
    private val output: OutputStream = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
    private val header = ByteArray(5)

    override val remote: String = socket.inetAddress?.hostAddress ?: "?"

    override fun read(): Message {
        val len = input.readInt()
        if (len < 1 || len > Proto.MAX_MESSAGE) throw IOException("bad message length $len")
        val type = input.readUnsignedByte()
        val payload = ByteArray(len - 1)
        input.readFully(payload)
        return Message(type, payload)
    }

    override fun write(type: Int, payload: ByteArray, offset: Int, length: Int) {
        val len = length + 1
        header[0] = (len ushr 24).toByte(); header[1] = (len ushr 16).toByte()
        header[2] = (len ushr 8).toByte(); header[3] = len.toByte()
        header[4] = type.toByte()
        output.write(header)
        output.write(payload, offset, length)
        output.flush()
    }

    override fun setReadTimeout(millis: Int) { socket.soTimeout = millis }

    override fun close() { runCatching { socket.close() } }
}

/**
 * Server side of a WebSocket (RFC 6455) for the browser receiver. Each binary message is
 * [u8 type][payload], the same body the native framing carries.
 */
class WebSocketChannel(private val socket: Socket, input: InputStream, private val output: OutputStream) : MessageChannel {
    private val input = DataInputStream(input)
    private val writeLock = Any()

    override val remote: String = socket.inetAddress?.hostAddress ?: "?"

    override fun read(): Message {
        var assembled: ByteArray? = null
        while (true) {
            val b0 = input.readUnsignedByte()
            val b1 = input.readUnsignedByte()
            val fin = b0 and 0x80 != 0
            val opcode = b0 and 0x0f
            val masked = b1 and 0x80 != 0
            var len = (b1 and 0x7f).toLong()
            if (len == 126L) len = input.readUnsignedShort().toLong()
            else if (len == 127L) len = input.readLong()
            if (len < 0 || len > Proto.MAX_MESSAGE) throw IOException("bad websocket frame length")
            val mask = ByteArray(4)
            if (masked) input.readFully(mask)
            val data = ByteArray(len.toInt())
            input.readFully(data)
            if (masked) for (i in data.indices) data[i] = (data[i].toInt() xor mask[i and 3].toInt()).toByte()
            when (opcode) {
                0x8 -> throw EOFException("websocket closed")
                0x9 -> frame(0xA, data, 0, data.size, null) // ping -> pong
                0xA -> {}
                0x0, 0x1, 0x2 -> {
                    assembled = if (assembled == null) data else assembled + data
                    if (assembled.size > Proto.MAX_MESSAGE) throw IOException("websocket message too large")
                    if (fin) {
                        if (assembled.isEmpty()) throw IOException("empty message")
                        return Message(assembled[0].toInt() and 0xff, assembled.copyOfRange(1, assembled.size))
                    }
                }
                else -> throw IOException("unsupported websocket opcode $opcode")
            }
        }
    }

    override fun write(type: Int, payload: ByteArray, offset: Int, length: Int) =
        frame(0x2, payload, offset, length, type)

    private fun frame(opcode: Int, payload: ByteArray, offset: Int, length: Int, type: Int?) {
        val bodyLen = length + if (type != null) 1 else 0
        val head = ByteArray(11)
        var n = 0
        head[n++] = (0x80 or opcode).toByte()
        when {
            bodyLen < 126 -> head[n++] = bodyLen.toByte()
            bodyLen < 65536 -> { head[n++] = 126; head[n++] = (bodyLen ushr 8).toByte(); head[n++] = bodyLen.toByte() }
            else -> { head[n++] = 127; putLong(head, n, bodyLen.toLong()); n += 8 }
        }
        if (type != null) head[n++] = type.toByte()
        synchronized(writeLock) {
            output.write(head, 0, n)
            output.write(payload, offset, length)
            output.flush()
        }
    }

    override fun setReadTimeout(millis: Int) { socket.soTimeout = millis }

    override fun close() { runCatching { socket.close() } }

    companion object {
        fun acceptKey(clientKey: String): String = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1")
                .digest((clientKey.trim() + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.ISO_8859_1))
        )
    }
}
