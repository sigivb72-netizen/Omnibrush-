package dev.omnimirror.sender

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Surface
import dev.omnimirror.core.H264
import dev.omnimirror.core.Proto
import dev.omnimirror.core.putLong
import java.nio.ByteBuffer

/**
 * Hardware H.264 encoder fed directly by a Surface. The screen compositor renders into
 * [inputSurface] and the encoder reads it on the GPU, so no pixels pass through app memory.
 */
class ScreenEncoder private constructor(
    private val codec: MediaCodec,
    val inputSurface: Surface,
    val width: Int,
    val height: Int,
    val fps: Int,
    private val callback: Callback,
) {
    interface Callback {
        fun onParameterSets(sps: ByteArray, pps: ByteArray)
        /** [payload] is a complete VIDEO message body (header + Annex-B access unit). */
        fun onFrame(payload: ByteArray, key: Boolean)
        fun onEncoderError(e: Exception)
    }

    val codecName: String = codec.name

    @Volatile var framesOut = 0L
        private set
    @Volatile var bytesOut = 0L
        private set

    private var lastKeyRequestMs = 0L
    private var trustPts: Boolean? = null
    private var released = false

    private val codecCallback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            val sps = format.getByteBuffer("csd-0") ?: return
            val pps = format.getByteBuffer("csd-1") ?: return
            val joined = ByteArray(sps.remaining() + pps.remaining())
            val spsLen = sps.remaining()
            sps.duplicate().get(joined, 0, spsLen)
            pps.duplicate().get(joined, spsLen, joined.size - spsLen)
            H264.parameterSets(joined)?.let { callback.onParameterSets(it.first, it.second) }
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            try {
                val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                if (info.size > 0 && !config) {
                    val buffer: ByteBuffer = codec.getOutputBuffer(index) ?: return
                    val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                    val payload = ByteArray(Proto.VIDEO_HEADER_SIZE + info.size)
                    payload[0] = if (key) Proto.VIDEO_FLAG_KEY.toByte() else 0
                    putLong(payload, 1, captureTimeUs(info.presentationTimeUs))
                    buffer.position(info.offset)
                    buffer.get(payload, Proto.VIDEO_HEADER_SIZE, info.size)
                    framesOut++
                    bytesOut += info.size
                    callback.onFrame(payload, key)
                }
                codec.releaseOutputBuffer(index, false)
            } catch (e: IllegalStateException) {
                if (!released) callback.onEncoderError(e)
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            if (!released) callback.onEncoderError(e)
        }
    }

    /**
     * With Surface input the encoder's timestamps are normally the compositor's frame times
     * on the monotonic clock, which is exactly the "capture time" the receiver needs to
     * measure latency. If a device reports something else, fall back to the encode-done time.
     */
    private fun captureTimeUs(ptsUs: Long): Long {
        val nowUs = System.nanoTime() / 1000
        val trusted = trustPts ?: (ptsUs in (nowUs - 1_000_000)..(nowUs + 20_000)).also { trustPts = it }
        return if (trusted && ptsUs <= nowUs) ptsUs else nowUs
    }

    fun start(handler: Handler) {
        codec.setCallback(codecCallback, handler)
        codec.start()
    }

    fun requestKeyFrame() {
        val now = SystemClock.elapsedRealtime()
        if (released || now - lastKeyRequestMs < 400) return
        lastKeyRequestMs = now
        runCatching { codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
    }

    fun setBitrate(bitsPerSecond: Int) {
        if (released) return
        runCatching { codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitsPerSecond) }) }
    }

    fun release() {
        released = true
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { inputSurface.release() }
    }

    companion object {
        private const val TAG = "OmniMirror"
        private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC

        /** Scales [source] so its shorter edge is at most [shortSide], keeping the aspect ratio. */
        fun scaledSize(source: Size, shortSide: Int, alignment: Int = 8): Size {
            val scale = minOf(1.0, shortSide.toDouble() / minOf(source.width, source.height))
            fun fit(v: Int) = maxOf(alignment, (v * scale).toInt() / alignment * alignment)
            return Size(fit(source.width), fit(source.height))
        }

        /**
         * Opens an encoder as close to the requested settings as this phone's hardware allows.
         * Tries the request first, then steps down frame rate and resolution. Settings the
         * codec declares as supported are preferred over ones that merely happen to configure.
         */
        fun open(source: Size, shortSide: Int, fps: Int, bitrate: Int, callback: Callback): ScreenEncoder? {
            val ladder = LinkedHashSet<Pair<Int, Int>>()
            ladder.add(shortSide to fps)
            if (fps > 30) ladder.add(shortSide to 30)
            for (side in intArrayOf(720, 540)) if (side < shortSide) {
                ladder.add(side to fps)
                ladder.add(side to 30)
            }
            for (declaredOnly in booleanArrayOf(true, false)) {
                for ((side, rate) in ladder) {
                    tryOpen(scaledSize(source, side), rate, bitrate, declaredOnly, callback)?.let { return it }
                }
            }
            return null
        }

        private fun tryOpen(size: Size, fps: Int, bitrate: Int, declaredOnly: Boolean, callback: Callback): ScreenEncoder? {
            val codec = try { MediaCodec.createEncoderByType(MIME) } catch (e: Exception) { return null }
            try {
                val caps = codec.codecInfo.getCapabilitiesForType(MIME)
                val video = caps.videoCapabilities
                val declared = runCatching { video.areSizeAndRateSupported(size.width, size.height, fps.toDouble()) }.getOrDefault(false)
                if (declaredOnly && !declared) { codec.release(); return null }

                val format = MediaFormat.createVideoFormat(MIME, size.width, size.height).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                    setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                    // TCP never loses data, so keyframes are only needed when a receiver joins or
                    // after frames were dropped. Those are requested on demand; this is a backstop.
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 10)
                    // The screen only produces frames when something changes. Re-encoding the last
                    // frame a few times per second lets a static image sharpen up and keeps
                    // receivers' buffers flushed, at negligible cost.
                    setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 250_000L)
                    // Displays refresh at up to 90/120 Hz; never encode more than the stream rate.
                    setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, fps.toFloat())
                }
                val tuned = MediaFormat(format).apply {
                    setInteger(MediaFormat.KEY_PRIORITY, 0) // realtime
                    setInteger(MediaFormat.KEY_LATENCY, 0)
                    if (caps.encoderCapabilities.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)) {
                        setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                    }
                }
                try {
                    codec.configure(tuned, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                } catch (e: Exception) {
                    Log.w(TAG, "tuned encoder config rejected, using plain config", e)
                    codec.reset()
                    codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                }
                val surface = codec.createInputSurface()
                Log.i(TAG, "encoder ${codec.name} ${size.width}x${size.height}@$fps declared=$declared")
                return ScreenEncoder(codec, surface, size.width, size.height, fps, callback)
            } catch (e: Exception) {
                Log.w(TAG, "encoder rejected ${size.width}x${size.height}@$fps", e)
                runCatching { codec.release() }
                return null
            }
        }
    }
}
