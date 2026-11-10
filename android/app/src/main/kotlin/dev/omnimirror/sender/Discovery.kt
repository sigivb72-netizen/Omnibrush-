package dev.omnimirror.sender

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.omnimirror.core.Proto

/** A receiver app found on the network. */
data class FoundReceiver(val serviceName: String, val name: String, val host: String, val port: Int)

/**
 * mDNS/DNS-SD (Bonjour) in both directions: advertises this phone as a sender so the iPad
 * app can list it, and browses for iPads that advertise themselves as receivers.
 */
class Discovery(context: Context) {
    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val main = Handler(Looper.getMainLooper())
    private var registration: NsdManager.RegistrationListener? = null
    private var browser: NsdManager.DiscoveryListener? = null
    private val found = LinkedHashMap<String, FoundReceiver>()
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var onChange: ((List<FoundReceiver>) -> Unit)? = null

    fun advertise(deviceName: String, port: Int) {
        stopAdvertising()
        val info = NsdServiceInfo().apply {
            serviceName = deviceName
            serviceType = Proto.SERVICE_TYPE
            setPort(port)
            setAttribute("role", Proto.ROLE_SENDER)
            setAttribute("v", Proto.VERSION.toString())
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) { Log.w(TAG, "mDNS registration failed: $code") }
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) = Unit
        }
        runCatching {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            registration = listener
        }.onFailure { Log.w(TAG, "mDNS registration failed", it) }
    }

    fun stopAdvertising() {
        registration?.let { runCatching { nsd.unregisterService(it) } }
        registration = null
    }

    /** [onChange] is called on the main thread with the current list of receivers. */
    fun browse(onChange: (List<FoundReceiver>) -> Unit) {
        stopBrowsing()
        this.onChange = onChange
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onStartDiscoveryFailed(type: String, code: Int) { Log.w(TAG, "mDNS browse failed: $code") }
            override fun onStopDiscoveryFailed(type: String, code: Int) = Unit
            override fun onServiceFound(info: NsdServiceInfo) { main.post { resolveQueue.addLast(info); resolveNext() } }
            override fun onServiceLost(info: NsdServiceInfo) {
                main.post { if (found.remove(info.serviceName) != null) publish() }
            }
        }
        runCatching {
            nsd.discoverServices(Proto.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            browser = listener
        }.onFailure { Log.w(TAG, "mDNS browse failed", it) }
    }

    fun stopBrowsing() {
        browser?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        browser = null
        onChange = null
        found.clear()
        resolveQueue.clear()
    }

    fun receivers(): List<FoundReceiver> = found.values.toList()

    // NsdManager resolves one service at a time on older Android versions, hence the queue.
    private fun resolveNext() {
        if (resolving) return
        val next = resolveQueue.removeFirstOrNull() ?: return
        resolving = true
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, code: Int) {
                main.post { resolving = false; resolveNext() }
            }

            override fun onServiceResolved(info: NsdServiceInfo) {
                main.post {
                    resolving = false
                    val role = info.attributes["role"]?.let { String(it) }
                    @Suppress("DEPRECATION")
                    val host = info.host?.hostAddress
                    if (role == Proto.ROLE_RECEIVER && host != null && browser != null) {
                        found[info.serviceName] = FoundReceiver(info.serviceName, info.serviceName, host, info.port)
                        publish()
                    }
                    resolveNext()
                }
            }
        }
        @Suppress("DEPRECATION")
        runCatching { nsd.resolveService(next, listener) }.onFailure { resolving = false }
    }

    private fun publish() { onChange?.invoke(receivers()) }

    private companion object { const val TAG = "OmniMirror" }
}
