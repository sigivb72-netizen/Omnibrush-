package dev.omnimirror.sender

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.util.Size
import android.view.Display
import dev.omnimirror.core.BitrateController
import dev.omnimirror.core.Outbound
import dev.omnimirror.core.PairingException
import dev.omnimirror.core.Proto
import dev.omnimirror.core.SenderHub
import dev.omnimirror.core.SenderServer
import dev.omnimirror.core.StreamInfo
import kotlin.concurrent.thread

/**
 * Owns a mirroring session from the moment the user grants screen capture until they stop it.
 *
 *   screen -> VirtualDisplay -> encoder input Surface -> hardware H.264 -> SenderHub -> receivers
 *
 * The encoder only runs while at least one receiver is connected, so an armed but unwatched
 * session costs almost nothing. Runs as a foreground service with a persistent notification,
 * as Android requires for screen capture.
 */
class MirrorService : Service(), ScreenEncoder.Callback, SenderHub.Listener {
    private lateinit var settings: Settings
    private lateinit var hub: SenderHub
    private lateinit var server: SenderServer
    private lateinit var discovery: Discovery
    private lateinit var controller: BitrateController

    /** Encoder callbacks and every change to the capture pipeline run on this one thread. */
    private val worker = HandlerThread("omm-pipeline").apply { start() }
    private val pipeline = Handler(worker.looper)

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: ScreenEncoder? = null
    private var sourceSize: Size? = null
    /** Set once the system starts reporting the captured content's size (Android 14+). */
    private var contentSizeReported = false
    private var wifiLock: WifiManager.WifiLock? = null
    private var lastFrames = 0L
    private var lastBytes = 0L
    private var stopped = false

    @Volatile private var outboundGeneration = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = Settings(this)
        discovery = Discovery(this)
        hub = SenderHub(settings.deviceName, { settings.pin }, this)
        server = SenderServer(hub) { Log.i(TAG, it) }
        controller = BitrateController(Settings.MIN_BITRATE, settings.maxBitrateKbps * 1000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> if (projection == null) start(intent)
            ACTION_STOP -> shutdown()
            else -> if (projection == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun start(intent: Intent) {
        // The foreground notification must be up before the projection token is redeemed.
        startForeground(NOTIFICATION_ID, buildNotification(0), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        @Suppress("DEPRECATION")
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else intent.getParcelableExtra(EXTRA_DATA)
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val granted = data?.let { runCatching { manager.getMediaProjection(resultCode, it) }.getOrNull() }
        if (granted == null) {
            fail("Screen capture permission was not granted.")
            return
        }
        projection = granted
        granted.registerCallback(projectionCallback, pipeline)

        try {
            server.start(Proto.DEFAULT_PORT, Proto.DEFAULT_TLS_PORT, TlsIdentity.sslContext())
        } catch (e: Exception) {
            fail("Could not open port ${Proto.DEFAULT_PORT}: ${e.message}")
            return
        }
        discovery.advertise(settings.deviceName, server.port)
        (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).registerDisplayListener(displayListener, pipeline)

        pipeline.post {
            val size = displaySize()
            sourceSize = size
            val initial = ScreenEncoder.scaledSize(size, settings.shortSide)
            // Android 14 allows exactly one virtual display per capture grant. It is created
            // without a surface and later pointed at whichever encoder is current.
            virtualDisplay = try {
                granted.createVirtualDisplay(
                    "OmniMirror", initial.width, initial.height, resources.displayMetrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, null, null, pipeline,
                )
            } catch (e: Exception) {
                fail("Could not start screen capture: ${e.message}")
                return@post
            }
            if (hub.receiverCount > 0) startEncoder()
            pipeline.postDelayed(ticker, 1000)
        }
        publishNetwork()
        MirrorState.update { it.copy(running = true, error = null, outbound = null) }
    }

    // ------------------------------------------------------------------ capture pipeline

    @Suppress("DEPRECATION")
    private fun displaySize(): Size {
        val display = (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)
        val metrics = DisplayMetrics()
        display.getRealMetrics(metrics)
        return Size(metrics.widthPixels, metrics.heightPixels)
    }

    private fun startEncoder() {
        val display = virtualDisplay ?: return
        val source = sourceSize ?: return
        if (encoder != null) return
        controller.maxBitrate = settings.maxBitrateKbps * 1000
        val bitrate = if (settings.adaptive) controller.target else controller.maxBitrate
        val enc = ScreenEncoder.open(source, settings.shortSide, settings.fps, bitrate, this)
        if (enc == null) {
            MirrorState.update { it.copy(error = "This phone's video encoder rejected every configuration.") }
            return
        }
        try {
            display.resize(enc.width, enc.height, resources.displayMetrics.densityDpi)
            display.surface = enc.inputSurface
            enc.start(pipeline)
        } catch (e: Exception) {
            enc.release()
            MirrorState.update { it.copy(error = "Could not start the encoder: ${e.message}") }
            return
        }
        encoder = enc
        lastFrames = 0
        lastBytes = 0
        acquireWifiLock()

        val requested = ScreenEncoder.scaledSize(source, settings.shortSide)
        val reduced = enc.fps != settings.fps || enc.width != requested.width || enc.height != requested.height
        val note = if (reduced) {
            "This phone's encoder (${enc.codecName}) does not support ${requested.width}×${requested.height} at " +
                "${settings.fps} fps. Using ${enc.width}×${enc.height} at ${enc.fps} fps."
        } else null
        MirrorState.update { it.copy(encoder = "${enc.width}×${enc.height} · ${enc.fps} fps", encoderNote = note, error = null) }
    }

    private fun stopEncoder() {
        val enc = encoder ?: return
        encoder = null
        runCatching { virtualDisplay?.surface = null }
        enc.release()
        releaseWifiLock()
        MirrorState.update { it.copy(encoder = null, sentFps = 0, sentKbps = 0) }
    }

    private fun restartEncoder() {
        stopEncoder()
        if (hub.receiverCount > 0) startEncoder()
    }

    /** Applies changed quality settings to a running session. */
    fun applySettings() = pipeline.post {
        controller.maxBitrate = settings.maxBitrateKbps * 1000
        restartEncoder()
    }

    private fun onSourceSize(size: Size) {
        if (size == sourceSize || size.width <= 0 || size.height <= 0) return
        sourceSize = size
        restartEncoder() // rotation: a new encoder with swapped dimensions; receivers follow the new stream info
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY && !contentSizeReported) onSourceSize(displaySize())
        }
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // The user ended capture from the system UI (status bar chip, lock screen, ...).
            Handler(Looper.getMainLooper()).post { shutdown() }
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            contentSizeReported = true
            onSourceSize(Size(width, height))
        }
    }

    private val stopEncoderWhenIdle = Runnable { if (hub.receiverCount == 0) stopEncoder() }

    /** Once a second: adapt the bitrate to the network and refresh the numbers in the UI. */
    private val ticker = object : Runnable {
        override fun run() {
            if (stopped) return
            val enc = encoder
            val congestion = hub.drainCongestion()
            if (enc != null && settings.adaptive) {
                controller.update(congestion.maxQueueLagMs, congestion.droppedFrames)?.let { enc.setBitrate(it) }
            }
            val frames = enc?.framesOut ?: 0
            val bytes = enc?.bytesOut ?: 0
            val fps = (frames - lastFrames).coerceAtLeast(0).toInt()
            val kbps = ((bytes - lastBytes).coerceAtLeast(0) * 8 / 1000).toInt()
            lastFrames = frames
            lastBytes = bytes
            val receivers = hub.receivers()
            val target = if (settings.adaptive) controller.target else controller.maxBitrate
            MirrorState.update { it.copy(receivers = receivers, sentFps = fps, sentKbps = kbps, targetKbps = target / 1000) }
            publishNetwork()
            pipeline.postDelayed(this, 1000)
        }
    }

    private fun publishNetwork() {
        val address = NetUtil.localAddress()?.address?.hostAddress
        if (address != MirrorState.snapshot.address || server.port != MirrorState.snapshot.port) {
            MirrorState.update { it.copy(address = address, port = server.port, tlsPort = server.tlsPort) }
        }
    }

    // ------------------------------------------------------------------ ScreenEncoder.Callback (pipeline thread)

    override fun onParameterSets(sps: ByteArray, pps: ByteArray) {
        val enc = encoder ?: return
        hub.setStreamInfo(StreamInfo(enc.width, enc.height, enc.fps, controller.target, sps, pps))
    }

    override fun onFrame(payload: ByteArray, key: Boolean) = hub.publishFrame(payload, key)

    override fun onEncoderError(e: Exception) {
        Log.w(TAG, "encoder error", e)
        pipeline.post { restartEncoder() }
    }

    // ------------------------------------------------------------------ SenderHub.Listener (any thread)

    override fun onReceiversChanged(count: Int) {
        pipeline.post {
            pipeline.removeCallbacks(stopEncoderWhenIdle)
            // A short grace period so a reconnecting receiver does not restart the encoder.
            if (count > 0) startEncoder() else pipeline.postDelayed(stopEncoderWhenIdle, 3000)
            val receivers = hub.receivers()
            MirrorState.update { it.copy(receivers = receivers) }
        }
        if (!stopped) notificationManager().notify(NOTIFICATION_ID, buildNotification(count))
    }

    override fun onKeyFrameNeeded() {
        pipeline.post { encoder?.requestKeyFrame() }
    }

    override fun onLog(message: String) {
        Log.i(TAG, message)
    }

    // ------------------------------------------------------------------ phone-initiated connections

    /**
     * Connects to a receiver app that is listening, pairing with the code it shows.
     * With [host] null, the receiver is found by its code: every discovered receiver and then
     * every device on the subnet with the port open is tried until one accepts the code.
     * Once paired, the link is re-established automatically if it drops.
     */
    fun connectTo(host: String?, port: Int, code: String) {
        val generation = ++outboundGeneration
        thread(name = "omm-outbound", isDaemon = true) {
            var target = host
            var everPaired = false
            while (generation == outboundGeneration && !stopped) {
                val fixed = target
                val candidates = if (fixed != null) listOf(fixed) else {
                    setOutbound("Looking for a receiver with that code…")
                    val known = discovery.receivers().map { it.host }
                    val local = NetUtil.localAddress()
                    val scanned = if (local != null) Outbound.probe(Outbound.subnetHosts(local.address, local.prefixLength), port) else emptyList()
                    (known + scanned).distinct()
                }
                var wrongCode = false
                var paired = false
                for (candidate in candidates) {
                    if (generation != outboundGeneration) return@thread
                    try {
                        if (fixed != null && !everPaired) setOutbound("Connecting to $candidate…")
                        hub.connectOutgoing(Outbound.open(candidate, port), code) { name ->
                            paired = true
                            everPaired = true
                            target = candidate
                            setOutbound("Connected to $name")
                        }
                    } catch (e: PairingException) {
                        wrongCode = true
                    } catch (e: Exception) {
                        Log.i(TAG, "outbound to $candidate: ${e.message}")
                    }
                    if (paired) break
                }
                if (generation != outboundGeneration || stopped) return@thread
                if (!everPaired) {
                    setOutbound(
                        when {
                            wrongCode -> "The receiver rejected that code."
                            host != null -> "Could not reach $host."
                            else -> "No receiver with that code was found on this network."
                        }
                    )
                    return@thread
                }
                setOutbound("Connection lost. Reconnecting…")
                Thread.sleep(1500)
            }
        }
    }

    private fun setOutbound(text: String?) = MirrorState.update { it.copy(outbound = text) }

    // ------------------------------------------------------------------ lifecycle

    private fun fail(message: String) {
        Log.w(TAG, message)
        shutdown()
        MirrorState.update { it.copy(error = message) }
    }

    private fun shutdown() {
        if (stopped) return
        stopped = true
        outboundGeneration++
        runCatching { (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).unregisterDisplayListener(displayListener) }
        discovery.stopAdvertising()
        server.stop()
        hub.close()
        pipeline.post {
            stopEncoder()
            runCatching { virtualDisplay?.release() }
            virtualDisplay = null
            runCatching { projection?.unregisterCallback(projectionCallback) }
            runCatching { projection?.stop() }
            projection = null
            worker.quitSafely()
        }
        MirrorState.update { MirrorSnapshot() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        shutdown()
        releaseWifiLock()
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** Keeps the Wi-Fi radio out of power-save while frames are flowing; power-save adds tens of ms of jitter. */
    private fun acquireWifiLock() {
        if (wifiLock != null) return
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = runCatching {
            wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "omnimirror:stream").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()
    }

    private fun releaseWifiLock() {
        wifiLock?.let { runCatching { it.release() } }
        wifiLock = null
    }

    private fun notificationManager() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun buildNotification(receivers: Int): Notification {
        notificationManager().createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, MirrorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = when (receivers) {
            0 -> "Ready. Waiting for a receiver to connect."
            1 -> "Your screen is being mirrored to 1 device."
            else -> "Your screen is being mirrored to $receivers devices."
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mirror)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, getString(R.string.stop), stop).build())
            .build()
    }

    companion object {
        private const val TAG = "OmniMirror"
        private const val CHANNEL_ID = "mirroring"
        private const val NOTIFICATION_ID = 1
        const val ACTION_START = "dev.omnimirror.sender.START"
        const val ACTION_STOP = "dev.omnimirror.sender.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"

        @Volatile
        var instance: MirrorService? = null
            private set
    }
}
