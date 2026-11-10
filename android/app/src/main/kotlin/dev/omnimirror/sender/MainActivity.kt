package dev.omnimirror.sender

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import dev.omnimirror.core.Auth
import dev.omnimirror.core.Proto

/** The single screen: start/stop, how to connect, nearby receivers, manual pairing, quality. */
class MainActivity : Activity() {
    private lateinit var settings: Settings
    private lateinit var discovery: Discovery

    private lateinit var status: TextView
    private lateinit var statusDot: View
    private lateinit var detail: TextView
    private lateinit var startStop: Button
    private lateinit var connectCard: View
    private lateinit var connectInfo: TextView
    private lateinit var code: TextView
    private lateinit var searching: TextView
    private lateinit var receiverList: LinearLayout
    private lateinit var encoderNote: TextView

    private var found: List<FoundReceiver> = emptyList()

    /** A receiver the user asked to connect to before capture was running. */
    private var pendingTarget: Triple<String?, Int, String>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        settings = Settings(this)
        discovery = Discovery(this)

        status = findViewById(R.id.status)
        statusDot = findViewById(R.id.statusDot)
        detail = findViewById(R.id.detail)
        startStop = findViewById(R.id.startStop)
        connectCard = findViewById(R.id.connectCard)
        connectInfo = findViewById(R.id.connectInfo)
        code = findViewById(R.id.code)
        searching = findViewById(R.id.searching)
        receiverList = findViewById(R.id.receiverList)
        encoderNote = findViewById(R.id.encoderNote)

        startStop.setOnClickListener {
            if (MirrorState.snapshot.running) stopMirroring() else requestCapture()
        }
        findViewById<Button>(R.id.newCode).setOnClickListener {
            settings.pin = Auth.newPin()
            render(MirrorState.snapshot)
        }
        findViewById<Button>(R.id.manualConnect).setOnClickListener {
            val ip = findViewById<EditText>(R.id.manualIp).text.toString().trim()
            val pin = findViewById<EditText>(R.id.manualCode).text.toString().filter { it.isDigit() }
            if (pin.length != 6) {
                findViewById<EditText>(R.id.manualCode).error = "The code has 6 digits"
            } else {
                val (host, port) = parseAddress(ip)
                connect(host, port, pin)
            }
        }

        bindSpinner(R.id.resolution, Settings.RESOLUTIONS, { "${it}p" }, settings.shortSide) { settings.shortSide = it }
        bindSpinner(R.id.fps, Settings.FRAME_RATES, { "$it fps" }, settings.fps) { settings.fps = it }
        bindSpinner(R.id.bitrate, Settings.BITRATES_KBPS, { "${it / 1000} Mbps" }, settings.maxBitrateKbps) { settings.maxBitrateKbps = it }
        findViewById<Switch>(R.id.adaptive).apply {
            isChecked = settings.adaptive
            setOnCheckedChangeListener { _, checked ->
                settings.adaptive = checked
                MirrorService.instance?.applySettings()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        MirrorState.observer = ::render
        render(MirrorState.snapshot)
        discovery.browse { list ->
            found = list
            renderReceivers()
        }
    }

    override fun onStop() {
        MirrorState.observer = null
        discovery.stopBrowsing()
        super.onStop()
    }

    // ------------------------------------------------------------------ actions

    private fun requestCapture() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
            return
        }
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        // Mirroring works without the notification permission (the system still shows its own
        // capture indicator), so carry on whatever the answer was.
        if (requestCode == REQUEST_NOTIFICATIONS) {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            @Suppress("DEPRECATION")
            startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE)
        }
    }

    @Deprecated("Deprecated in the platform; fine for a dependency-free Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQUEST_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) {
            pendingTarget = null
            return
        }
        startForegroundService(
            Intent(this, MirrorService::class.java)
                .setAction(MirrorService.ACTION_START)
                .putExtra(MirrorService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(MirrorService.EXTRA_DATA, data)
        )
    }

    private fun stopMirroring() {
        pendingTarget = null
        startService(Intent(this, MirrorService::class.java).setAction(MirrorService.ACTION_STOP))
    }

    /** Connects to a receiver, first starting capture (and asking permission) if needed. */
    private fun connect(host: String?, port: Int, pin: String) {
        val service = MirrorService.instance
        if (MirrorState.snapshot.running && service != null) {
            service.connectTo(host, port, pin)
        } else {
            pendingTarget = Triple(host, port, pin)
            requestCapture()
        }
    }

    private fun parseAddress(text: String): Pair<String?, Int> {
        if (text.isEmpty()) return null to Proto.DEFAULT_PORT
        val port = text.substringAfterLast(':', "").toIntOrNull()
        return if (port != null && text.count { it == ':' } == 1) text.substringBeforeLast(':') to port
        else text to Proto.DEFAULT_PORT
    }

    // ------------------------------------------------------------------ rendering

    private fun render(s: MirrorSnapshot) {
        if (s.running) pendingTarget?.let { (host, port, pin) ->
            pendingTarget = null
            MirrorService.instance?.connectTo(host, port, pin)
        }

        startStop.setText(if (s.running) R.string.stop else R.string.start)
        startStop.setBackgroundResource(if (s.running) R.drawable.button_danger else R.drawable.button_primary)
        startStop.setTextColor(getColor(if (s.running) R.color.danger else android.R.color.white))

        val streaming = s.running && s.receivers.isNotEmpty()
        setDot(if (s.error != null) R.color.danger else if (streaming) R.color.ok else if (s.running) R.color.accent else R.color.dim)
        status.text = when {
            s.error != null -> s.error
            streaming -> "Mirroring to " + s.receivers.joinToString { it.name }
            s.running -> "Ready. Waiting for a receiver to connect."
            else -> getString(R.string.idle_hint)
        }

        val lines = ArrayList<String>()
        if (streaming && s.encoder != null) {
            lines.add("${s.encoder} · sending ${s.sentFps} fps, ${formatMbps(s.sentKbps)} (limit ${formatMbps(s.targetKbps)})")
            for (r in s.receivers) {
                val latency = if (r.latencyMs > 0) " · ${r.latencyMs.toInt()} ms latency" else ""
                val decoder = if (r.decoder.isNotEmpty()) " · ${r.decoder}" else ""
                lines.add("${r.name}: ${r.fps.toInt()} fps shown$latency$decoder")
            }
        }
        s.outbound?.let { lines.add(it) }
        detail.text = lines.joinToString("\n")
        detail.visibility = if (lines.isEmpty()) View.GONE else View.VISIBLE

        connectCard.visibility = if (s.running) View.VISIBLE else View.GONE
        if (s.running) {
            connectInfo.text = if (s.address == null) {
                "Connect this phone to Wi-Fi (or turn on its hotspot and join it from the iPad)."
            } else buildString {
                append("Open the OmniMirror app, or in Safari go to\n")
                append("http://${s.address}:${s.port}")
                if (s.tlsPort != 0) append("\n\nLowest latency in Safari:\nhttps://${s.address}:${s.tlsPort}")
                append("\n\nThen enter this code:")
            }
            val pin = settings.pin
            code.text = pin.substring(0, 3) + " " + pin.substring(3)
        }

        encoderNote.text = s.encoderNote
        encoderNote.visibility = if (s.encoderNote != null) View.VISIBLE else View.GONE
    }

    private fun renderReceivers() {
        receiverList.removeAllViews()
        searching.visibility = if (found.isEmpty()) View.VISIBLE else View.GONE
        val pinField = findViewById<EditText>(R.id.manualCode)
        for (receiver in found) {
            val button = Button(this).apply {
                text = "${receiver.name}  ·  ${receiver.host}"
                isAllCaps = false
                stateListAnimator = null
                setBackgroundResource(R.drawable.button_plain)
                setTextColor(getColor(R.color.text))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(8) }
                setOnClickListener {
                    val pin = pinField.text.toString().filter { it.isDigit() }
                    if (pin.length == 6) connect(receiver.host, receiver.port, pin)
                    else {
                        pinField.error = "Enter the code shown on ${receiver.name}, then tap it again"
                        pinField.requestFocus()
                    }
                }
            }
            receiverList.addView(button)
        }
    }

    private fun setDot(colorRes: Int) {
        statusDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(getColor(colorRes))
        }
    }

    private fun bindSpinner(id: Int, values: IntArray, label: (Int) -> String, current: Int, onPick: (Int) -> Unit) {
        val spinner = findViewById<Spinner>(id)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, values.map(label)).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinner.setSelection(values.indexOf(current).coerceAtLeast(0))
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            private var first = true
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, itemId: Long) {
                if (first) { first = false; return } // the initial selection is not a change
                onPick(values[position])
                MirrorService.instance?.applySettings()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun formatMbps(kbps: Int): String = "${kbps / 1000}.${kbps % 1000 / 100} Mbps"

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val REQUEST_CAPTURE = 1
        const val REQUEST_NOTIFICATIONS = 2
    }
}
