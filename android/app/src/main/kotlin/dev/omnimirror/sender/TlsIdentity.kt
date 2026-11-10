package dev.omnimirror.sender

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.security.auth.x500.X500Principal

/**
 * A self-signed certificate so the phone can serve the browser receiver over HTTPS.
 * Browsers only enable WebCodecs on secure origins. The key is generated inside the
 * Android Keystore and never leaves the phone; the browser shows a warning the first time
 * because no authority vouches for it.
 */
object TlsIdentity {
    private const val ALIAS = "omnimirror_tls"
    private const val DAY_MS = 24L * 3600 * 1000

    fun sslContext(): SSLContext? = try {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getCertificate(ALIAS) as? X509Certificate
        if (existing == null || existing.notAfter.time < System.currentTimeMillis() + 30 * DAY_MS) {
            if (existing != null) store.deleteEntry(ALIAS)
            val now = System.currentTimeMillis()
            val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                .setCertificateSubject(X500Principal("CN=OmniMirror"))
                .setCertificateSerialNumber(BigInteger.valueOf(now))
                .setCertificateNotBefore(Date(now - DAY_MS))
                .setCertificateNotAfter(Date(now + 800 * DAY_MS)) // Apple rejects longer-lived certificates outright
                .build()
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply { initialize(spec) }.generateKeyPair()
        }
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, null) }
        SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) }
    } catch (e: Exception) {
        Log.w("OmniMirror", "HTTPS unavailable", e)
        null
    }
}
