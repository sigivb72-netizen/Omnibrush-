package dev.omnimirror.core

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Pairing: the listening device shows a 6-digit code; the connecting device proves it
 * knows the code with HMAC-SHA256 over both nonces, so the code never crosses the network.
 * This keeps casual LAN neighbours out. It is not a defence against a determined attacker
 * who records the handshake (a 6-digit secret can be brute-forced offline).
 */
object Auth {
    private val random = SecureRandom()

    fun newPin(): String = random.nextInt(1_000_000).toString().padStart(6, '0')

    fun newNonce(): String = ByteArray(16).also { random.nextBytes(it) }.toHex()

    fun proof(pin: String, connectorNonce: String, listenerNonce: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(pin.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal("$connectorNonce:$listenerNonce".toByteArray(Charsets.UTF_8)).toHex()
    }

    fun verify(pin: String, connectorNonce: String, listenerNonce: String, proof: String): Boolean =
        MessageDigest.isEqual(
            proof(pin, connectorNonce, listenerNonce).toByteArray(), proof.lowercase().toByteArray()
        )

    private fun ByteArray.toHex(): String = joinToString("") { HEX[(it.toInt() shr 4) and 15].toString() + HEX[it.toInt() and 15] }

    private const val HEX = "0123456789abcdef"
}
