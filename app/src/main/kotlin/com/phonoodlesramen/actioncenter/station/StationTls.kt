package com.phonoodlesramen.actioncenter.station

import android.annotation.SuppressLint
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager
import okhttp3.ConnectionPool
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.TlsVersion

/**
 * The guide station's TLS on the TV (Phase 8, N3, U2): TLS 1.3 only, and
 * - **paired:** this TV's Keystore key with the certificate the station issued it
 *   ([StationKeyManager]), and trust in nothing but the station's own CA
 *   ([StationTrustManager]);
 * - **pairing:** before the CA is known, the station's certificate is taken only to read
 *   its chain ([ReadOnlyTrustManager]), and the code the TV shows is worked out from it
 *   ([com.phonoodlesramen.actioncenter.guide.station.StationApi.pairingCode]). The code is
 *   what defeats a machine in the middle, so nothing is trusted until you've compared it.
 *
 * The station's address is typed as an IP, which its certificate can't name, so there's no
 * hostname check: the pinned CA is the check.
 */
internal object StationTls {
    /** TLS 1.3 only, as the station serves it. */
    private val TLS_13 = ConnectionSpec.Builder(ConnectionSpec.RESTRICTED_TLS).tlsVersions(TlsVersion.TLS_1_3).build()

    private const val CONNECT_SECONDS = 5L
    private const val READ_SECONDS = 15L

    /**
     * For `pair`: no client certificate, and any station certificate, its chain read by
     * [PairingClient.chain]. OkHttp's own record of the chain is checked against the trust
     * manager's issuers, of which this one has none, so the trust manager keeps it. No
     * connection is pooled, so each call's chain is its own handshake's (or, resumed, the
     * same station's).
     */
    fun pairingClient(base: OkHttpClient): PairingClient {
        val reader = ChainReader()
        val http = client(base, keyManager = null, trust = reader).newBuilder().connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS)).build()
        return PairingClient(http, reader)
    }

    /** For everything else: this TV's key and certificate, and only the station's CA trusted. */
    fun pairedClient(base: OkHttpClient, keys: StationKeys, chain: List<X509Certificate>, ca: X509Certificate): OkHttpClient =
        client(base, StationKeyManager(keys, chain), StationTrustManager(ca))

    /** The same client, held open long enough for a `changes` call. */
    fun waiting(client: OkHttpClient, waitMillis: Long): OkHttpClient =
        client.newBuilder().readTimeout(waitMillis + READ_SECONDS * 1000, TimeUnit.MILLISECONDS).build()

    private fun client(base: OkHttpClient, keyManager: KeyManager?, trust: X509ExtendedTrustManager): OkHttpClient {
        val context = SSLContext.getInstance("TLS").apply { init(keyManager?.let { arrayOf(it) }, arrayOf(trust), SecureRandom()) }
        return base.newBuilder()
            .sslSocketFactory(context.socketFactory, trust)
            .hostnameVerifier { _, _ -> true }
            .connectionSpecs(listOf(TLS_13))
            .connectTimeout(CONNECT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(READ_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    /** Every certificate in [pem] (one or more `BEGIN CERTIFICATE` blocks), in order. */
    fun certificates(pem: String): List<X509Certificate> =
        CertificateFactory.getInstance("X.509").generateCertificates(pem.byteInputStream()).map { it as X509Certificate }

    /** One certificate as PEM. */
    fun pem(certificate: X509Certificate): String = buildString {
        append("-----BEGIN CERTIFICATE-----\n")
        Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(certificate.encoded).let(::append)
        append("\n-----END CERTIFICATE-----\n")
    }

    /** The station's CA in a chain it presented: its last certificate (the root, or the server's own when it's self-signed). */
    fun caOf(chain: List<X509Certificate>): X509Certificate? = chain.lastOrNull()
}

/**
 * Trusts the station's server certificate only when the station's CA signed it, or when it
 * is the CA itself. A certificate made for a TV (its extended key usage says client only)
 * isn't a server's, so a paired TV can't stand in for the station.
 */
@SuppressLint("CustomX509TrustManager")
internal class StationTrustManager(private val ca: X509Certificate) : X509ExtendedTrustManager() {
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = check(chain)

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = check(chain)

    private fun check(chain: Array<out X509Certificate>?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("No certificate from the station")
        try {
            ca.checkValidity()
            leaf.checkValidity()
            if (!leaf.encoded.contentEquals(ca.encoded)) {
                if (leaf.issuerX500Principal != ca.subjectX500Principal) throw CertificateException("Not issued by the station's CA")
                leaf.verify(ca.publicKey)
                val usage = leaf.extendedKeyUsage
                if (usage != null && SERVER_AUTH !in usage) throw CertificateException("Not a server certificate")
            }
        } catch (e: CertificateException) {
            throw e
        } catch (e: Exception) {
            throw CertificateException("Not the station this TV paired with", e)
        }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw CertificateException("Client side only")

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) =
        throw CertificateException("Client side only")

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) =
        throw CertificateException("Client side only")

    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf(ca)

    private companion object {
        const val SERVER_AUTH = "1.3.6.1.5.5.7.3.1"
    }
}

/** The pairing client, and what reads the chain the station showed. */
internal class PairingClient(val http: OkHttpClient, private val reader: ChainReader) {
    /** The chain the station showed in the last handshake. */
    val chain: List<X509Certificate> get() = reader.chain
}

/**
 * Pairing only: any certificate is taken, so its chain can be read and the pairing code
 * worked out from its CA. Nothing sent over it is secret (a name, a model and a public key),
 * and what comes back is trusted only once the codes match and the CA is the same one.
 */
@SuppressLint("TrustAllX509TrustManager", "CustomX509TrustManager")
internal class ChainReader : X509ExtendedTrustManager() {
    @Volatile
    var chain: List<X509Certificate> = emptyList()
        private set

    private fun read(chain: Array<out X509Certificate>?) {
        this.chain = chain?.toList().orEmpty()
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = read(chain)

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = read(chain)

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = read(chain)

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw CertificateException("Client side only")

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) =
        throw CertificateException("Client side only")

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) =
        throw CertificateException("Client side only")

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/**
 * This TV's client certificate: its Keystore key ([keys]) with the [chain] the station issued
 * for it, offered whenever the station asks for an EC key.
 */
internal class StationKeyManager(private val keys: StationKeys, private val chain: List<X509Certificate>) : X509ExtendedKeyManager() {
    private fun alias(keyTypes: Array<out String>?): String? =
        ALIAS.takeIf { keyTypes == null || keyTypes.any { it.startsWith("EC") } }

    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? = alias(keyType)

    override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?): String? = alias(keyType)

    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? =
        alias(keyType?.let { arrayOf(it) })?.let { arrayOf(it) }

    override fun getCertificateChain(alias: String?): Array<X509Certificate>? = if (alias == ALIAS) chain.toTypedArray() else null

    override fun getPrivateKey(alias: String?): PrivateKey? = if (alias == ALIAS) keys.privateKey() else null

    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null

    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null

    override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?): String? = null

    private companion object {
        const val ALIAS = "guide-station"
    }
}
