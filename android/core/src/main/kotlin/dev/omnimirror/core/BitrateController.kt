package dev.omnimirror.core

/**
 * Sender-side rate adaptation. The signal is how long encoded frames wait in the send
 * queue before the network takes them: on a healthy LAN that is a millisecond or two;
 * when Wi-Fi cannot keep up it grows, and frames eventually get dropped.
 *
 * Call [update] about once per second. It backs off quickly, recovers slowly, and
 * returns the new target only when it changed enough to be worth reconfiguring the encoder.
 */
class BitrateController(private val minBitrate: Int, maxBitrate: Int, start: Int = maxBitrate) {
    var maxBitrate: Int = maxBitrate
        set(value) { field = value.coerceAtLeast(minBitrate); target = target.coerceAtMost(field) }

    var target: Int = start.coerceIn(minBitrate, maxBitrate)
        private set

    private var calmTicks = 0
    private var holdTicks = 0

    fun update(maxQueueLagMs: Long, droppedFrames: Int): Int? {
        val before = target
        when {
            droppedFrames > 0 || maxQueueLagMs > 120 -> {
                target = (target * 0.65).toInt()
                calmTicks = 0
                holdTicks = 3
            }
            maxQueueLagMs > 50 -> {
                target = (target * 0.9).toInt()
                calmTicks = 0
                holdTicks = 2
            }
            maxQueueLagMs < 20 -> {
                if (holdTicks > 0) holdTicks-- else calmTicks++
                if (calmTicks >= 3) {
                    target = (target * 1.15).toInt()
                    calmTicks = 2 // keep climbing every tick while it stays calm
                }
            }
            else -> calmTicks = 0
        }
        target = target.coerceIn(minBitrate, maxBitrate)
        return if (target != before) target else null
    }
}
