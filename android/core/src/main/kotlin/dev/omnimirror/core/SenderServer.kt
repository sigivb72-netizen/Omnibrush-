package dev.omnimirror.core

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import javax.net.ssl.SSLContext
import kotlin.concurrent.thread

/**
 * Listens for receivers on the sender device.
 *
 * The plain port speaks three things, told apart by the first four bytes:
 *  - the native framing (starts with "OMM1") used by the iPad app,
 *  - HTTP GET for the browser receiver's files,
 *  - an HTTP WebSocket upgrade on /ws carrying the same messages for the browser.
 *
 * The optional TLS port serves the same HTTP/WebSocket content over HTTPS. Browsers only
 * expose WebCodecs (the low-latency hardware decoder API) to secure origins.
 */
class SenderServer(
    private val hub: SenderHub,
    private val assets: (String) -> ByteArray? = ::classpathAsset,
    private val log: (String) -> Unit = {},
) {
    private var plain: ServerSocket? = null
    private var tls: ServerSocket? = null

    val port: Int get() = plain?.localPort ?: 0
    val tlsPort: Int get() = tls?.localPort ?: 0

    fun start(port: Int, tlsPort: Int = 0, ssl: SSLContext? = null) {
        plain = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(port))
        }.also { acceptLoop(it, "omm-accept") { s -> handlePlain(s) } }
        if (ssl != null) {
            try {
                tls = ssl.serverSocketFactory.createServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(tlsPort))
                }.also { acceptLoop(it, "omm-accept-tls") { s -> handleHttp(s, s.getInputStream()) } }
            } catch (e: Exception) {
                log("HTTPS listener unavailable: ${e.message}")
            }
        }
    }

    fun stop() {
        runCatching { plain?.close() }
        runCatching { tls?.close() }
        plain = null
        tls = null
    }

    private fun acceptLoop(server: ServerSocket, name: String, handler: (Socket) -> Unit) {
        thread(name = name, isDaemon = true) {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (e: SocketException) { break } catch (e: Exception) { continue }
                thread(name = "omm-conn", isDaemon = true) {
                    try {
                        tuneSocket(socket)
                        socket.soTimeout = SenderHub.HANDSHAKE_TIMEOUT_MS
                        handler(socket)
                    } catch (e: Exception) {
                        log("connection from ${socket.inetAddress?.hostAddress}: ${e.message ?: e.javaClass.simpleName}")
                    } finally {
                        runCatching { socket.close() }
                    }
                }
            }
        }
    }

    private fun handlePlain(socket: Socket) {
        val input = PushbackInputStream(socket.getInputStream(), 4)
        val first = ByteArray(4)
        var n = 0
        while (n < 4) {
            val r = input.read(first, n, 4 - n)
            if (r < 0) return
            n += r
        }
        if (first.contentEquals(Proto.MAGIC)) {
            hub.serveIncoming(TcpChannel(socket, input), "app")
        } else {
            input.unread(first)
            handleHttp(socket, input)
        }
    }

    private fun handleHttp(socket: Socket, rawInput: InputStream) {
        val input = BufferedInputStream(rawInput, 16 * 1024)
        val output = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
        val head = readHead(input) ?: return
        val lines = head.split("\r\n")
        val request = lines[0].split(" ")
        if (request.size < 2) return
        val headers = HashMap<String, String>()
        for (line in lines.drop(1)) {
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        val path = request[1].substringBefore('?')

        if (headers["upgrade"]?.lowercase() == "websocket") {
            val key = headers["sec-websocket-key"]
            if (path != "/ws" || key == null) return respond(output, 400, "text/plain", "bad websocket request".toByteArray())
            output.write(
                ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                    "Sec-WebSocket-Accept: ${WebSocketChannel.acceptKey(key)}\r\n\r\n").toByteArray()
            )
            output.flush()
            hub.serveIncoming(WebSocketChannel(socket, input, output), "web")
            return
        }

        if (request[0] != "GET") return respond(output, 405, "text/plain", "method not allowed".toByteArray())
        if (path == "/info") {
            val info = mapOf("name" to hub.deviceName, "v" to Proto.VERSION, "port" to port, "tlsPort" to tlsPort)
            return respond(output, 200, "application/json", Json.encode(info).toByteArray())
        }
        val name = if (path == "/") "index.html" else path.removePrefix("/")
        val body = if (name.contains("..") || name.contains('/')) null else assets(name)
        if (body == null) respond(output, 404, "text/plain", "not found".toByteArray())
        else respond(output, 200, contentType(name), body)
    }

    private fun readHead(input: InputStream): String? {
        val sb = StringBuilder()
        while (sb.length < 16 * 1024) {
            val b = input.read()
            if (b < 0) return null
            sb.append(b.toChar())
            if (sb.endsWith("\r\n\r\n")) return sb.toString()
        }
        return null
    }

    private fun respond(output: OutputStream, status: Int, type: String, body: ByteArray) {
        val reason = when (status) { 200 -> "OK"; 400 -> "Bad Request"; 404 -> "Not Found"; else -> "Error" }
        output.write(
            ("HTTP/1.1 $status $reason\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n" +
                "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray()
        )
        output.write(body)
        output.flush()
    }

    private fun contentType(name: String): String = when (name.substringAfterLast('.')) {
        "html" -> "text/html; charset=utf-8"
        "js" -> "text/javascript; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "json", "webmanifest" -> "application/json"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        else -> "application/octet-stream"
    }

    companion object {
        fun classpathAsset(name: String): ByteArray? =
            SenderServer::class.java.getResourceAsStream("/web/$name")?.use { it.readBytes() }
    }
}
