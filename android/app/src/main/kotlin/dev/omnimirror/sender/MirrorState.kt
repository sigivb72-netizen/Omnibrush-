package dev.omnimirror.sender

import android.os.Handler
import android.os.Looper
import dev.omnimirror.core.ReceiverSnapshot

/** What the UI shows. Written by [MirrorService], observed by [MainActivity] on the main thread. */
data class MirrorSnapshot(
    val running: Boolean = false,
    val address: String? = null,
    val port: Int = 0,
    val tlsPort: Int = 0,
    val receivers: List<ReceiverSnapshot> = emptyList(),
    /** Encoder settings actually in use, e.g. "1080×2336 · 60 fps"; null while no receiver is connected. */
    val encoder: String? = null,
    val encoderNote: String? = null,
    val targetKbps: Int = 0,
    val sentFps: Int = 0,
    val sentKbps: Int = 0,
    /** Progress or failure of a phone-initiated connection to a receiver. */
    val outbound: String? = null,
    val error: String? = null,
)

object MirrorState {
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    var snapshot = MirrorSnapshot()
        private set

    var observer: ((MirrorSnapshot) -> Unit)? = null

    fun update(change: (MirrorSnapshot) -> MirrorSnapshot) {
        main.post {
            snapshot = change(snapshot)
            observer?.invoke(snapshot)
        }
    }
}
