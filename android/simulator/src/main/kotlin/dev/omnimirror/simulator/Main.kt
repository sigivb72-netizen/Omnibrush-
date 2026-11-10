package dev.omnimirror.simulator

import dev.omnimirror.core.BitrateController
import dev.omnimirror.core.H264
import dev.omnimirror.core.SenderHub
import dev.omnimirror.core.SenderServer
import dev.omnimirror.core.StreamInfo
import dev.omnimirror.core.videoPayload
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import kotlin.concurrent.thread

/**
 * Desktop stand-in for the Android sender. Runs the real :core server and feeds it a live
 * H.264 test pattern from ffmpeg (libx264, zero-latency, baseline: the same kind of stream
 * a phone's hardware encoder produces). Lets the receivers be exercised without a phone.
 *
 *   ./gradlew :simulator:run --args="--size 1920x1080 --fps 60 --bitrate 8000 --pin 123456"
 */
fun main(args: Array<String>) {
    val opt = args.toList().chunked(2).associate { it[0].removePrefix("--") to it.getOrElse(1) { "" } }
    val (width, height) = (opt["size"] ?: "1920x1080").split("x").map { it.toInt() }
    val fps = (opt["fps"] ?: "60").toInt()
    val kbps = (opt["bitrate"] ?: "8000").toInt()
    val port = (opt["port"] ?: "47800").toInt()
    val pin = opt["pin"] ?: "123456"

    val hub = SenderHub("Simulator", { pin }, object : SenderHub.Listener {
        override fun onReceiversChanged(count: Int) = println("receivers: $count")
        override fun onLog(message: String) = println(message)
    })
    val server = SenderServer(hub, log = ::println)
    server.start(port, port + 1, opt["keystore"]?.let { sslContext(File(it)) })
    println("serving http://localhost:${server.port}/  https port ${server.tlsPort}  code $pin")

    val controller = BitrateController(500_000, kbps * 1000)
    thread(isDaemon = true) {
        while (true) {
            Thread.sleep(1000)
            val c = hub.drainCongestion()
            controller.update(c.maxQueueLagMs, c.droppedFrames)?.let { println("bitrate target -> ${it / 1000} kbps") }
            if (c.droppedFrames > 0 || c.maxQueueLagMs > 50) println("congestion: lag ${c.maxQueueLagMs} ms, dropped ${c.droppedFrames}")
            for (r in hub.receivers()) println("  ${r.name} [${r.transport}/${r.decoder}] ${r.fps} fps, ${r.latencyMs} ms")
        }
    }

    // --rotate N swaps width and height every N seconds, like turning the phone.
    val rotateEvery = (opt["rotate"] ?: "0").toLong()
    var w = width
    var h = height
    while (true) {
        stream(hub, w, h, fps, kbps, if (rotateEvery > 0) System.nanoTime() + rotateEvery * 1_000_000_000 else Long.MAX_VALUE)
        if (rotateEvery <= 0) break
        w = h.also { h = w }
        println("rotating to ${w}x$h")
    }
    println("ffmpeg exited")
}

/** Encodes and publishes a test pattern until ffmpeg exits or [untilNs] passes. */
private fun stream(hub: SenderHub, width: Int, height: Int, fps: Int, kbps: Int, untilNs: Long) {
    val ffmpeg = ProcessBuilder(
        "ffmpeg", "-hide_banner", "-loglevel", "error", "-re",
        "-f", "lavfi", "-i", "testsrc2=size=${width}x$height:rate=$fps",
        "-c:v", "libx264", "-preset", "ultrafast", "-tune", "zerolatency", "-profile:v", "baseline",
        "-pix_fmt", "yuv420p", "-b:v", "${kbps}k", "-maxrate", "${kbps}k", "-bufsize", "${kbps / 2}k",
        "-g", (fps * 2).toString(), "-x264-params", "aud=1:repeat-headers=1",
        "-flush_packets", "1", "-f", "h264", "-",
    ).redirectError(ProcessBuilder.Redirect.INHERIT).start()
    val killer = Thread { ffmpeg.destroy() }
    Runtime.getRuntime().addShutdownHook(killer)

    // Access units are delimited by AUD NALs (aud=1), so splitting the byte stream is trivial.
    val input = ffmpeg.inputStream
    val pending = ByteArrayOutputStream(1 shl 20)
    val chunk = ByteArray(64 * 1024)
    var announced = false
    fun emit(unit: ByteArray) {
        if (unit.isEmpty()) return
        val ptsUs = System.nanoTime() / 1000
        val key = H264.containsIdr(unit)
        if (!announced) {
            val sets = H264.parameterSets(unit) ?: return
            hub.setStreamInfo(StreamInfo(width, height, fps, kbps * 1000, sets.first, sets.second))
            announced = true
        }
        hub.publishFrame(videoPayload(unit, 0, unit.size, ptsUs, key), key)
    }
    while (System.nanoTime() < untilNs) {
        val n = input.read(chunk)
        if (n < 0) break
        val before = pending.size()
        pending.write(chunk, 0, n)
        val buf = pending.toByteArray()
        var unitStart = 0
        var i = maxOf(1, before - 5)
        while (i + 5 < buf.size) {
            if (buf[i].toInt() == 0 && buf[i + 1].toInt() == 0 && buf[i + 2].toInt() == 0 && buf[i + 3].toInt() == 1 &&
                (buf[i + 4].toInt() and 0x1f) == H264.NAL_AUD
            ) {
                emit(buf.copyOfRange(unitStart, i))
                unitStart = i
                i += 5
            } else i++
        }
        if (unitStart > 0) {
            pending.reset()
            pending.write(buf, unitStart, buf.size - unitStart)
        }
    }
    ffmpeg.destroy()
    ffmpeg.waitFor()
    runCatching { Runtime.getRuntime().removeShutdownHook(killer) }
}

private fun sslContext(file: File): SSLContext {
    val password = "omnimirror".toCharArray()
    val store = KeyStore.getInstance("PKCS12").apply { FileInputStream(file).use { load(it, password) } }
    val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, password) }
    return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
}
