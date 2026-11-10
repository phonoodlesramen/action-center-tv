package com.phonoodlesramen.actioncenter.station

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.net.Socket
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Principal
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager

/** A software EC P-256 key: Robolectric has no AndroidKeyStore. */
class SoftwareKeys : StationKeys {
    private var pair: KeyPair? = null

    @Synchronized
    override fun publicKey(): PublicKey = (pair ?: TestCertificates.ecKeyPair().also { pair = it }).public

    override fun privateKey(): PrivateKey? = pair?.private

    @Synchronized
    override fun delete() {
        pair = null
    }

    val deleted: Boolean get() = pair == null
}

/**
 * Just enough X.509 to stand in for the station's CA in tests (the catalog has no
 * okhttp-tls): DER by hand, ECDSA P-256 with SHA-256, as the station makes them.
 */
object TestCertificates {
    const val SERVER_AUTH = "1.3.6.1.5.5.7.3.1"
    const val CLIENT_AUTH = "1.3.6.1.5.5.7.3.2"

    fun ecKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    /** A certificate for [subjectKey] (its SubjectPublicKeyInfo DER), signed by [signer]. */
    fun certificate(
        subject: String,
        subjectKey: ByteArray,
        issuer: String,
        signer: PrivateKey,
        serial: BigInteger,
        ca: Boolean = false,
        usage: String? = null,
        notBefore: Instant = Instant.parse("2026-01-01T00:00:00Z"),
        notAfter: Instant = Instant.parse("2036-01-01T00:00:00Z"),
    ): X509Certificate {
        val algorithm = seq(oid("1.2.840.10045.4.3.2"))
        val extensions = buildList {
            if (ca) add(seq(oid("2.5.29.19"), bool(true), octets(seq(bool(true)))))
            if (usage != null) add(seq(oid("2.5.29.37"), octets(seq(oid(usage)))))
        }
        val tbs = seq(
            *listOfNotNull(
                tlv(0xA0, int(BigInteger.valueOf(2))),
                int(serial),
                algorithm,
                name(issuer),
                seq(time(notBefore), time(notAfter)),
                name(subject),
                subjectKey,
                if (extensions.isEmpty()) null else tlv(0xA3, seq(*extensions.toTypedArray())),
            ).toTypedArray(),
        )
        val signature = Signature.getInstance("SHA256withECDSA").apply {
            initSign(signer)
            update(tbs)
        }.sign()
        val der = seq(tbs, algorithm, tlv(0x03, byteArrayOf(0) + signature))
        return CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate
    }

    private fun name(cn: String) = seq(tlv(0x31, seq(oid("2.5.4.3"), tlv(0x0C, cn.toByteArray()))))

    private fun time(at: Instant): ByteArray {
        val utc = at.atZone(ZoneOffset.UTC)
        return if (utc.year < 2050) {
            tlv(0x17, utc.format(DateTimeFormatter.ofPattern("yyMMddHHmmss'Z'")).toByteArray())
        } else {
            tlv(0x18, utc.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss'Z'")).toByteArray())
        }
    }

    private fun seq(vararg parts: ByteArray) = tlv(0x30, parts.fold(ByteArray(0)) { all, part -> all + part })
    private fun int(value: BigInteger) = tlv(0x02, value.toByteArray())
    private fun bool(value: Boolean) = tlv(0x01, byteArrayOf(if (value) 0xFF.toByte() else 0))
    private fun octets(bytes: ByteArray) = tlv(0x04, bytes)

    private fun oid(dotted: String): ByteArray {
        val arcs = dotted.split('.').map { it.toLong() }
        val out = ByteArrayOutputStream()
        out.write((arcs[0] * 40 + arcs[1]).toInt())
        for (arc in arcs.drop(2)) {
            val bytes = mutableListOf((arc and 0x7F).toInt())
            var rest = arc ushr 7
            while (rest > 0) {
                bytes.add(0, ((rest and 0x7F) or 0x80).toInt())
                rest = rest ushr 7
            }
            bytes.forEach(out::write)
        }
        return tlv(0x06, out.toByteArray())
    }

    private fun tlv(tag: Int, content: ByteArray): ByteArray {
        val length = content.size
        val header = when {
            length < 0x80 -> byteArrayOf(tag.toByte(), length.toByte())
            length < 0x100 -> byteArrayOf(tag.toByte(), 0x81.toByte(), length.toByte())
            else -> byteArrayOf(tag.toByte(), 0x82.toByte(), (length shr 8).toByte(), length.toByte())
        }
        return header + content
    }

    /**
     * The station's server side of TLS 1.3: its key and chain ([chain]: server, then CA), and
     * a client certificate taken only when [ca] signed it. Like the station's
     * `HttpsServer`, it asks for a certificate but doesn't need one (`pair` is open).
     */
    fun serverContext(key: PrivateKey, chain: List<X509Certificate>, ca: X509Certificate): SSLContext {
        val keys = object : X509ExtendedKeyManager() {
            override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = if (keyType?.startsWith("EC") != false) "server" else null
            override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?) = chooseServerAlias(keyType, issuers, null)
            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf("server")
            override fun getCertificateChain(alias: String?) = chain.toTypedArray()
            override fun getPrivateKey(alias: String?) = key
            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
            override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? = null
        }
        val trust = object : X509ExtendedTrustManager() {
            private fun check(chain: Array<out X509Certificate>?) {
                val leaf = chain?.firstOrNull() ?: throw CertificateException("no certificate")
                try {
                    leaf.verify(ca.publicKey)
                } catch (e: Exception) {
                    throw CertificateException("not this station's", e)
                }
            }

            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = check(chain)
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = check(chain)
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw CertificateException()
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = throw CertificateException()
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = throw CertificateException()
            override fun getAcceptedIssuers() = arrayOf(ca)
        }
        return SSLContext.getInstance("TLSv1.3").apply { init(arrayOf(keys), arrayOf(trust), null) }
    }
}
