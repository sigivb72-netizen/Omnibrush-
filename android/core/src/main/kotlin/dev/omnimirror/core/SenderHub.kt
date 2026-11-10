package dev.omnimirror.core

import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

class PairingException(message: String) : IOException(message)

/** What the sender UI shows about one connected receiver. */
data class ReceiverSnapshot(
    val id: Int,
    val name: String,
    val remote: String,
    val transport: String,
    val fps: Double,
    val latencyMs: Double,
    val decoder: String,
)

class Congestion(val maxQueueLagMs: Long, val droppedFrames: Int)

/**
 * The sender's fan-out point. The encoder publishes stream parameters and encoded frames
 * here; every authenticated receiver gets its own bounded queue and writer thread, so a
 * slow receiver can only delay (and then drop) its own frames.
 *
 * A receiver can arrive either way round: it connects to us ([serveIncoming]) or we
 * connect to it ([connectOutgoing]). After the handshake both look identical.
 */
class SenderHub(
    val deviceName: String,
    private val pin: () -> String,
    private val listener: Listener = object : Listener {},
) {
    interface Listener {
        fun onReceiversChanged(count: Int) {}
        /** A receiver cannot decode until it gets an IDR frame; ask the encoder for one. */
        fun onKeyFrameNeeded() {}
        fun onLog(message: String) {}
    }

    @Volatile
    var streamInfo: StreamInfo? = null
        private set

    private val sessions = CopyOnWriteArrayList<Session>()
    private val ids = AtomicInteger()
    /** Keeps "new stream info", "new frame" and "new receiver" from interleaving. */
    private val fanOut = Any()

    val receiverCount: Int get() = sessions.size

    fun receivers(): List<ReceiverSnapshot> = sessions.map { it.snapshot() }

    /** Handles a receiver that connected to us. Blocks until that connection ends. */
    fun serveIncoming(channel: MessageChannel, transport: String) {
        val session = try {
            channel.setReadTimeout(HANDSHAKE_TIMEOUT_MS)
            val hello = expect(channel, Proto.HELLO)
            if (hello["role"] != Proto.ROLE_RECEIVER) throw PairingException("peer is not a receiver")
            val connectorNonce = hello["nonce"] as? String ?: throw PairingException("missing nonce")
            val myNonce = Auth.newNonce()
            send(channel, Proto.CHALLENGE, mapOf(
                "v" to Proto.VERSION, "role" to Proto.ROLE_SENDER, "name" to deviceName, "nonce" to myNonce,
            ))
            val auth = expect(channel, Proto.AUTH)
            if (!Auth.verify(pin(), connectorNonce, myNonce, auth["proof"] as? String ?: "")) {
                Thread.sleep(FAILED_AUTH_DELAY_MS) // slows down code guessing
                send(channel, Proto.AUTH_RESULT, mapOf("ok" to false, "reason" to "wrong code"))
                throw PairingException("wrong code from ${channel.remote}")
            }
            send(channel, Proto.AUTH_RESULT, mapOf("ok" to true))
            Session(channel, hello["name"] as? String ?: "Receiver", transport)
        } catch (e: Exception) {
            listener.onLog("handshake with ${channel.remote} failed: ${e.message}")
            channel.close()
            return
        }
        session.run()
    }

    /**
     * Pairs with a receiver we connected to, using the code shown on that receiver.
     * Throws [PairingException] or [IOException] if pairing fails; otherwise calls
     * [onPaired] and then blocks until the connection ends.
     */
    fun connectOutgoing(channel: MessageChannel, receiverPin: String, onPaired: (String) -> Unit = {}) {
        val session = try {
            channel.setReadTimeout(HANDSHAKE_TIMEOUT_MS)
            val myNonce = Auth.newNonce()
            send(channel, Proto.HELLO, mapOf(
                "v" to Proto.VERSION, "role" to Proto.ROLE_SENDER, "name" to deviceName, "nonce" to myNonce,
            ))
            val challenge = expect(channel, Proto.CHALLENGE)
            if (challenge["role"] != Proto.ROLE_RECEIVER) throw PairingException("peer is not a receiver")
            val theirNonce = challenge["nonce"] as? String ?: throw PairingException("missing nonce")
            send(channel, Proto.AUTH, mapOf("proof" to Auth.proof(receiverPin, myNonce, theirNonce)))
            val result = expect(channel, Proto.AUTH_RESULT)
            if (result["ok"] != true) throw PairingException(result["reason"] as? String ?: "rejected")
            Session(channel, challenge["name"] as? String ?: "Receiver", "app")
        } catch (e: Exception) {
            channel.close()
            throw e
        }
        onPaired(session.name)
        session.run()
    }

    /** Called when the encoder (re)starts, e.g. after a rotation. Its first frame will be an IDR. */
    fun setStreamInfo(info: StreamInfo) {
        val payload = info.toJson().toByteArray()
        synchronized(fanOut) {
            streamInfo = info
            for (s in sessions) s.restart(payload)
        }
    }

    /** [payload] comes from [videoPayload]. It is shared between receivers and must not be modified. */
    fun publishFrame(payload: ByteArray, key: Boolean) {
        var needKey = false
        synchronized(fanOut) {
            for (s in sessions) if (s.offerVideo(payload, key)) needKey = true
        }
        if (needKey) listener.onKeyFrameNeeded()
    }

    /** Congestion since the previous call, across all receivers. Feed it to [BitrateController]. */
    fun drainCongestion(): Congestion {
        var lag = 0L
        var drops = 0
        for (s in sessions) {
            lag = maxOf(lag, s.drainLagMs())
            drops += s.drops.getAndSet(0)
        }
        return Congestion(lag, drops)
    }

    fun close() {
        for (s in sessions) s.close()
    }

    private fun send(channel: MessageChannel, type: Int, body: Map<String, Any?>) =
        channel.write(type, Json.encode(body).toByteArray())

    private fun expect(channel: MessageChannel, type: Int): Map<String, Any?> {
        val m = channel.read()
        if (m.type != type) throw PairingException("unexpected message ${m.type}")
        return Json.parseObject(String(m.payload, Charsets.UTF_8))
    }

    private class Out(val type: Int, val payload: ByteArray, val video: Boolean, val enqueuedNs: Long)

    private inner class Session(val channel: MessageChannel, val name: String, val transport: String) {
        val id = ids.incrementAndGet()
        val drops = AtomicInteger()
        private val lock = ReentrantLock()
        private val ready = lock.newCondition()
        private val queue = ArrayDeque<Out>()
        private var waitingForKey = true
        private var closed = false
        private var inFlightSinceNs = 0L
        private var maxLagNs = 0L
        @Volatile private var fps = 0.0
        @Volatile private var latencyMs = 0.0
        @Volatile private var decoder = ""

        fun snapshot() = ReceiverSnapshot(id, name, channel.remote, transport, fps, latencyMs, decoder)

        fun run() {
            synchronized(fanOut) {
                streamInfo?.let { restart(it.toJson().toByteArray()) }
                sessions.add(this)
            }
            listener.onLog("receiver connected: $name (${channel.remote}, $transport)")
            listener.onReceiversChanged(sessions.size)
            listener.onKeyFrameNeeded()
            val writer = thread(name = "omm-writer-$id", isDaemon = true) { writeLoop() }
            try {
                channel.setReadTimeout(IDLE_TIMEOUT_MS)
                readLoop()
            } catch (e: Exception) {
                listener.onLog("receiver $name disconnected: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                close()
                writer.interrupt()
                sessions.remove(this)
                listener.onReceiversChanged(sessions.size)
            }
        }

        private fun readLoop() {
            while (true) {
                val m = channel.read()
                when (m.type) {
                    Proto.PING -> if (m.payload.size >= 8) {
                        val pong = ByteArray(16)
                        System.arraycopy(m.payload, 0, pong, 0, 8)
                        putLong(pong, 8, System.nanoTime() / 1000)
                        offer(Out(Proto.PONG, pong, false, System.nanoTime()), front = true)
                    }
                    Proto.STATS -> runCatching {
                        val s = Json.parseObject(String(m.payload, Charsets.UTF_8))
                        fps = (s["fps"] as? Number)?.toDouble() ?: 0.0
                        latencyMs = (s["latencyMs"] as? Number)?.toDouble() ?: 0.0
                        decoder = s["decoder"] as? String ?: ""
                    }
                    Proto.REQUEST_KEY -> listener.onKeyFrameNeeded()
                    Proto.BYE -> return
                }
            }
        }

        private fun writeLoop() {
            try {
                while (true) {
                    val out = lock.withLock {
                        while (queue.isEmpty() && !closed) ready.await()
                        if (closed) return
                        queue.removeFirst().also { if (it.video) inFlightSinceNs = it.enqueuedNs }
                    }
                    channel.write(out.type, out.payload)
                    if (out.video) lock.withLock {
                        maxLagNs = maxOf(maxLagNs, System.nanoTime() - out.enqueuedNs)
                        inFlightSinceNs = 0
                    }
                }
            } catch (e: Exception) {
                close() // unblocks the reader, which does the cleanup
            }
        }

        private fun offer(out: Out, front: Boolean = false) = lock.withLock {
            if (closed) return@withLock
            if (front) queue.addFirst(out) else queue.addLast(out)
            ready.signal()
        }

        /** New stream parameters: anything still queued belongs to the old stream. */
        fun restart(infoPayload: ByteArray) = lock.withLock {
            queue.removeAll { it.video || it.type == Proto.STREAM_INFO }
            waitingForKey = true
            queue.addLast(Out(Proto.STREAM_INFO, infoPayload, false, System.nanoTime()))
            ready.signal()
        }

        /** Returns true if this receiver now needs a fresh keyframe from the encoder. */
        fun offerVideo(payload: ByteArray, key: Boolean): Boolean = lock.withLock {
            if (closed) return false
            val now = System.nanoTime()
            if (waitingForKey && !key) return false
            val oldest = queue.firstOrNull { it.video }
            if (oldest != null && now - oldest.enqueuedNs > MAX_QUEUE_NS) {
                // The network is not keeping up. Stale frames are worthless for mirroring, so
                // throw the backlog away. That breaks the reference chain, hence the keyframe.
                val before = queue.size
                queue.removeAll { it.video }
                drops.addAndGet(before - queue.size)
                if (!key) {
                    drops.incrementAndGet()
                    waitingForKey = true
                    return true
                }
            }
            waitingForKey = false
            queue.addLast(Out(Proto.VIDEO, payload, true, now))
            ready.signal()
            false
        }

        fun drainLagMs(): Long = lock.withLock {
            val now = System.nanoTime()
            var lag = maxLagNs
            if (inFlightSinceNs != 0L) lag = maxOf(lag, now - inFlightSinceNs)
            queue.firstOrNull { it.video }?.let { lag = maxOf(lag, now - it.enqueuedNs) }
            maxLagNs = 0
            lag / 1_000_000
        }

        fun close() {
            lock.withLock {
                closed = true
                queue.clear()
                ready.signalAll()
            }
            channel.close()
        }
    }

    companion object {
        const val HANDSHAKE_TIMEOUT_MS = 5_000
        /** Receivers ping at least every 2 s, so silence this long means the peer is gone. */
        const val IDLE_TIMEOUT_MS = 8_000
        const val FAILED_AUTH_DELAY_MS = 700L
        const val MAX_QUEUE_NS = 200_000_000L
    }
}
