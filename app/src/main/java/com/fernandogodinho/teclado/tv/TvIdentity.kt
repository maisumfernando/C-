package com.fernandogodinho.teclado.tv

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.Calendar
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

/**
 * This phone's identity towards TVs: an RSA key pair and self-signed certificate kept in the
 * Android Keystore (the private key never leaves secure hardware). The TV remembers this
 * certificate after pairing.
 */
object TvIdentity {
    private const val ALIAS = "teclado_tv_remote"

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @Synchronized
    fun ensure(): X509Certificate {
        val ks = keyStore()
        (ks.getCertificate(ALIAS) as? X509Certificate)?.let { return it }
        val start = Calendar.getInstance()
        val end = Calendar.getInstance().apply { add(Calendar.YEAR, 20) }
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
            .setKeySize(2048)
            .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
            .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1, KeyProperties.SIGNATURE_PADDING_RSA_PSS)
            .setCertificateSubject(X500Principal("CN=atvremote"))
            .setCertificateSerialNumber(BigInteger.valueOf(System.currentTimeMillis()))
            .setCertificateNotBefore(start.time)
            .setCertificateNotAfter(end.time)
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply { initialize(spec) }.generateKeyPair()
        return keyStore().getCertificate(ALIAS) as X509Certificate
    }

    val publicKey: RSAPublicKey get() = ensure().publicKey as RSAPublicKey

    /** Presents the Keystore certificate when the TV asks for a client certificate. */
    private class ClientKeyManager : X509ExtendedKeyManager() {
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
        override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
        override fun getCertificateChain(alias: String?): Array<X509Certificate> = arrayOf(ensure())
        override fun getPrivateKey(alias: String?): PrivateKey = keyStore().getKey(ALIAS, null) as PrivateKey
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
    }

    /**
     * TVs use self-signed certificates, so there is no CA to check. Instead the TV's certificate
     * is pinned: [expectedSha256] null means "first contact" (pairing, where the code typed by
     * the user authenticates both sides); afterwards only the same certificate is accepted.
     */
    class PinningTrustManager(private val expectedSha256: String?) : X509TrustManager {
        @Volatile var seen: X509Certificate? = null
            private set

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val cert = chain?.firstOrNull() ?: throw CertificateException("no certificate")
            if (expectedSha256 != null && fingerprint(cert) != expectedSha256) throw CertificateException("TV certificate changed")
            seen = cert
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw CertificateException()
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    fun sslContext(trust: PinningTrustManager): SSLContext =
        SSLContext.getInstance("TLS").apply { init(arrayOf(ClientKeyManager()), arrayOf(trust), null) }

    /** TLS without a client certificate (Samsung and LG authenticate with a token instead). */
    fun trustOnlyContext(trust: PinningTrustManager): SSLContext =
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }

    fun fingerprint(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it) }
}
