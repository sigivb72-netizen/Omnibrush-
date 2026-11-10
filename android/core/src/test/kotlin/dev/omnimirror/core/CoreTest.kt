package dev.omnimirror.core

import java.net.ServerSocket
import java.net.Socket
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val PIN = "314159"
private val SPS = byteArrayOf(0x67, 0x42, 0xc0.toByte(), 0x1f, 1, 2, 3)
private val PPS = byteArrayOf(0x68, 0xce.toByte(), 0x3c, 0x80.toByte())
private val INFO = StreamInfo(1280, 720, 60, 8_000_000, SPS, PPS)

private fun frame(key: Boolean, size: Int = 1000, pts: Long = 1L): ByteArray {
    val nal = ByteArray(size).also { it[0] = 0; it[1] = 0; it[2] = 0; it[3] = 1; it[4] = if (key) 0x65 else 0x41 }
    return videoPayload(nal, 0, nal.size, pts, key)
}

/** Plays the receiver's side of the protocol over the native framing. */
private class FakeReceiver(val channel: MessageChannel) {
    fun pairAsConnector(pin: String): Boolean {
        val nonce = Auth.newNonce()
        channel.write(Proto.HELLO, Json.encode(mapOf("v" to 1, "role" to "receiver", "name" to "Test iPad", "nonce" to nonce)).toByteArray())
        val challenge = Json.parseObject(String(channel.read().payload))
        assertEquals("sender", challenge["role"])
        channel.write(Proto.AUTH, Json.encode(mapOf("proof" to Auth.proof(pin, nonce, challenge["nonce"] as String))).toByteArray())
        return Json.parseObject(String(channel.read().payload))["ok"] == true
    }

    fun next(type: Int): Message {
        while (true) {
            val m = channel.read()
            if (m.type == type) return m
        }
    }
}

class CoreTest {
    private fun hubWithServer(listener: SenderHub.Listener = object : SenderHub.Listener {}): Triple<SenderHub, SenderServer, Int> {
        val hub = SenderHub("Test Phone", { PIN }, listener)
        val server = SenderServer(hub)
        server.start(0)
        return Triple(hub, server, server.port)
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for $what" }
            Thread.sleep(10)
        }
    }

    @Test
    fun jsonRoundTrip() {
        val text = Json.encode(mapOf("a" to 1, "b" to "x\"y\n", "c" to true, "d" to null, "e" to 1.5, "f" to listOf(1, 2)))
        val parsed = Json.parseObject(text)
        assertEquals(1L, parsed["a"])
        assertEquals("x\"y\n", parsed["b"])
        assertEquals(true, parsed["c"])
        assertNull(parsed["d"])
        assertEquals(1.5, parsed["e"])
        assertEquals(listOf(1L, 2L), parsed["f"])
        assertFailsWith<IllegalArgumentException> { Json.parseObject("{\"a\":") }
    }

    @Test
    fun pairingProof() {
        // Fixed vector shared with the Swift and JavaScript implementations (docs/PROTOCOL.md):
        // HMAC-SHA256(key = "123456", message = "aa:bb")
        assertEquals("d3b5227b6f459b1cfeb36c4ccfb75be4aaf0edbaabbf25a7667d3a7c5c0fb2c8", Auth.proof("123456", "aa", "bb"))
        assertTrue(Auth.verify("123456", "aa", "bb", Auth.proof("123456", "aa", "bb")))
        assertFalse(Auth.verify("123457", "aa", "bb", Auth.proof("123456", "aa", "bb")))
        assertTrue(Auth.newPin().matches(Regex("\\d{6}")))
    }

    @Test
    fun splitsAnnexB() {
        val data = byteArrayOf(0, 0, 0, 1) + SPS + byteArrayOf(0, 0, 1) + PPS + byteArrayOf(0, 0, 0, 1, 0x65, 9, 9)
        val nals = H264.split(data)
        assertEquals(listOf(7, 8, 5), nals.map { it.type })
        assertEquals(SPS.size, nals[0].end - nals[0].start)
        val sets = assertNotNull(H264.parameterSets(data))
        assertTrue(sets.first.contentEquals(SPS) && sets.second.contentEquals(PPS))
        assertTrue(H264.containsIdr(data))
    }

    @Test
    fun streamInfoRoundTrip() {
        val back = StreamInfo.fromJson(INFO.toJson())
        assertEquals(1280, back.width)
        assertTrue(back.sps.contentEquals(SPS) && back.pps.contentEquals(PPS))
    }

    @Test
    fun receiverPairsAndGetsStreamFromKeyframe() {
        val keyRequests = AtomicInteger()
        val (hub, server, port) = hubWithServer(object : SenderHub.Listener {
            override fun onKeyFrameNeeded() { keyRequests.incrementAndGet() }
        })
        hub.setStreamInfo(INFO)
        val rx = FakeReceiver(Outbound.open("127.0.0.1", port))
        assertTrue(rx.pairAsConnector(PIN))
        val info = StreamInfo.fromJson(String(rx.next(Proto.STREAM_INFO).payload))
        assertEquals(720, info.height)
        waitFor("receiver registration") { hub.receiverCount == 1 }
        assertTrue(keyRequests.get() >= 1, "a new receiver must trigger a keyframe request")

        hub.publishFrame(frame(key = false, pts = 1), false) // not decodable yet: must be withheld
        hub.publishFrame(frame(key = true, pts = 2), true)
        hub.publishFrame(frame(key = false, pts = 3), false)
        val first = rx.next(Proto.VIDEO)
        assertEquals(Proto.VIDEO_FLAG_KEY, first.payload[0].toInt())
        assertEquals(2L, getLong(first.payload, 1))
        assertEquals(3L, getLong(rx.next(Proto.VIDEO).payload, 1))

        // Clock sync: PING is echoed with the sender's clock.
        val ping = ByteArray(8).also { putLong(it, 0, 777) }
        rx.channel.write(Proto.PING, ping)
        val pong = rx.next(Proto.PONG).payload
        assertEquals(777L, getLong(pong, 0))
        assertTrue(kotlin.math.abs(getLong(pong, 8) - System.nanoTime() / 1000) < 2_000_000)

        rx.channel.write(Proto.STATS, """{"fps":59,"latencyMs":41.5,"decoder":"VideoToolbox"}""".toByteArray())
        waitFor("stats") { hub.receivers().firstOrNull()?.latencyMs == 41.5 }

        rx.channel.close()
        waitFor("disconnect") { hub.receiverCount == 0 }
        server.stop()
    }

    @Test
    fun wrongCodeIsRejected() {
        val (hub, server, port) = hubWithServer()
        val rx = FakeReceiver(Outbound.open("127.0.0.1", port))
        assertFalse(rx.pairAsConnector("000000"))
        assertEquals(0, hub.receiverCount)
        server.stop()
    }

    /** A receiver app that listens (like the iPad app) and pairs one incoming sender. */
    private fun listeningReceiver(gotVideo: CountDownLatch): ServerSocket {
        val listener = ServerSocket(0)
        thread(isDaemon = true) {
            val socket = listener.accept()
            assertTrue(socket.getInputStream().readNBytes(4).contentEquals(Proto.MAGIC))
            val ch = TcpChannel(socket)
            val hello = Json.parseObject(String(ch.read().payload))
            assertEquals("sender", hello["role"])
            val nonce = Auth.newNonce()
            ch.write(Proto.CHALLENGE, Json.encode(mapOf("v" to 1, "role" to "receiver", "name" to "Test iPad", "nonce" to nonce)).toByteArray())
            val proof = Json.parseObject(String(ch.read().payload))["proof"] as String
            val ok = Auth.verify(PIN, hello["nonce"] as String, nonce, proof)
            ch.write(Proto.AUTH_RESULT, Json.encode(mapOf("ok" to ok, "reason" to "wrong code")).toByteArray())
            if (ok) {
                while (ch.read().type != Proto.VIDEO) Unit
                gotVideo.countDown()
            }
            ch.close()
        }
        return listener
    }

    @Test
    fun senderCanDialAListeningReceiver() {
        val hub = SenderHub("Test Phone", { "unused" })
        hub.setStreamInfo(INFO)
        val gotVideo = CountDownLatch(1)

        listeningReceiver(gotVideo).use { listener ->
            assertFailsWith<PairingException> { hub.connectOutgoing(Outbound.open("127.0.0.1", listener.localPort), "999999") }
            assertEquals(0, hub.receiverCount)
        }

        listeningReceiver(gotVideo).use { listener ->
            val paired = CountDownLatch(1)
            thread(isDaemon = true) {
                hub.connectOutgoing(Outbound.open("127.0.0.1", listener.localPort), PIN) { name ->
                    assertEquals("Test iPad", name)
                    paired.countDown()
                }
            }
            assertTrue(paired.await(5, TimeUnit.SECONDS))
            waitFor("session") { hub.receiverCount == 1 }
            hub.publishFrame(frame(key = true), true)
            assertTrue(gotVideo.await(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun slowReceiverGetsFramesDroppedNotDelayed() {
        val keyRequests = AtomicInteger()
        val (hub, server, port) = hubWithServer(object : SenderHub.Listener {
            override fun onKeyFrameNeeded() { keyRequests.incrementAndGet() }
        })
        hub.setStreamInfo(INFO)
        // A receiver that pairs and then stops reading, like a tablet on a stalled Wi-Fi link.
        val socket = Socket("127.0.0.1", port).apply { receiveBufferSize = 4096 }
        socket.getOutputStream().write(Proto.MAGIC)
        val rx = FakeReceiver(TcpChannel(socket))
        assertTrue(rx.pairAsConnector(PIN))
        waitFor("session") { hub.receiverCount == 1 }
        val before = keyRequests.get()

        hub.publishFrame(frame(true, 200_000), true)
        val deadline = System.currentTimeMillis() + 4000
        var dropped = 0
        var maxLag = 0L
        while (System.currentTimeMillis() < deadline && dropped == 0) {
            repeat(10) { hub.publishFrame(frame(false, 200_000), false) }
            Thread.sleep(50)
            val c = hub.drainCongestion()
            dropped += c.droppedFrames
            maxLag = maxOf(maxLag, c.maxQueueLagMs)
        }
        assertTrue(dropped > 0, "backlog should be dropped once it is older than the queue limit")
        assertTrue(maxLag >= 150, "queue lag should be reported, was $maxLag ms")
        assertTrue(keyRequests.get() > before, "dropping frames must request a keyframe")
        socket.close()
        server.stop()
    }

    @Test
    fun bitrateBacksOffFastAndRecoversSlowly() {
        val c = BitrateController(500_000, 8_000_000)
        assertEquals(8_000_000, c.target)
        assertNull(c.update(2, 0))
        assertEquals(5_200_000, c.update(300, 4))
        assertNotNull(c.update(80, 0))
        val low = c.target
        repeat(4) { assertNull(c.update(1, 0), "must hold after congestion") }
        var changed = false
        repeat(40) { if (c.update(1, 0) != null) changed = true }
        assertTrue(changed && c.target > low)
        assertEquals(8_000_000, c.target)
        repeat(50) { c.update(500, 10) }
        assertEquals(500_000, c.target)
    }

    @Test
    fun subnetHostsExcludesSelfAndBroadcast() {
        val hosts = Outbound.subnetHosts(InetAddress.getByName("192.168.1.23") as Inet4Address, 24)
        assertEquals(253, hosts.size)
        assertTrue("192.168.1.1" in hosts && "192.168.1.254" in hosts)
        assertFalse("192.168.1.23" in hosts || "192.168.1.0" in hosts || "192.168.1.255" in hosts)
        assertEquals(1021, Outbound.subnetHosts(InetAddress.getByName("10.0.0.5") as Inet4Address, 8).size)
    }

    @Test
    fun webSocketAcceptKeyMatchesRfc6455() {
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", WebSocketChannel.acceptKey("dGhlIHNhbXBsZSBub25jZQ=="))
    }

    @Test
    fun servesReceiverPageAndInfo() {
        val (_, server, port) = hubWithServer()
        fun get(path: String): String = Socket("127.0.0.1", port).use { s ->
            s.getOutputStream().write("GET $path HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
            String(s.getInputStream().readBytes())
        }
        assertTrue(get("/").let { it.startsWith("HTTP/1.1 200") && it.contains("OmniMirror") })
        assertTrue(get("/app.js").contains("text/javascript"))
        assertTrue(get("/info").contains("\"name\":\"Test Phone\""))
        assertTrue(get("/../secret").startsWith("HTTP/1.1 404"))
        server.stop()
    }
}
