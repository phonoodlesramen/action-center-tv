package com.phonoodlesramen.actioncenter.station

import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.security.AlgorithmParameters
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x500.X500NameBuilder
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.util.IPAddress

/**
 * The station's certificate authority (N3): ECDSA P-256, made on first start and kept in the
 * volume (mode 600), never made again. It signs the station's server certificate, named for
 * `STATION_HOSTS` and made again when they change, and each TV's client certificate at
 * pairing, knowing only the TV's public key.
 */
class StationCa private constructor(
    val caCertificate: X509Certificate,
    private val caKey: PrivateKey,
    val serverCertificate: X509Certificate,
    private val serverKey: PrivateKey,
    private val random: SecureRandom,
) {
    val caDer: ByteArray get() = caCertificate.encoded
    val caPem: String get() = pem(caCertificate)

    /** A TV's certificate: CN its device id, client authentication only, valid 10 years. */
    fun issueClient(deviceId: String, publicKey: PublicKey, nowMillis: Long): X509Certificate {
        val builder = JcaX509v3CertificateBuilder(
            caCertificate, serial(random), Date(nowMillis - BACKDATE_MILLIS), Date(nowMillis + CLIENT_YEARS * YEAR_MILLIS), name(deviceId), publicKey,
        )
            .addExtension(Extension.basicConstraints, true, BasicConstraints(false))
            .addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature))
            .addExtension(Extension.extendedKeyUsage, false, ExtendedKeyUsage(KeyPurposeId.id_kp_clientAuth))
            .addExtension(Extension.subjectKeyIdentifier, false, extensions().createSubjectKeyIdentifier(publicKey))
            .addExtension(Extension.authorityKeyIdentifier, false, extensions().createAuthorityKeyIdentifier(caCertificate))
        return sign(builder, caKey)
    }

    /**
     * TLS 1.3 with the server certificate (sent with the CA, so a pairing TV sees the CA it
     * works the code out from), trusting only certificates this CA issued.
     */
    fun serverContext(): SSLContext {
        val password = KEYSTORE_PASSWORD.toCharArray()
        val keys = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("station", serverKey, password, arrayOf(serverCertificate, caCertificate))
        }
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, password) }.keyManagers
        val anchors = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setCertificateEntry("ca", caCertificate)
        }
        val trustManagers = TrustManagerFactory.getInstance("PKIX").apply { init(anchors) }.trustManagers
        return SSLContext.getInstance("TLSv1.3").apply { init(keyManagers, trustManagers, random) }
    }

    companion object {
        const val CA_NAME = "Action Center guide station CA"
        const val SERVER_NAME = "Action Center guide station"
        const val CLIENT_YEARS = 10L
        private const val CA_YEARS = 30L
        private const val SERVER_YEARS = 10L
        private const val YEAR_MILLIS = 365L * 24 * 60 * 60_000L

        /** A certificate starts an hour early, for a TV whose clock is a little behind. */
        private const val BACKDATE_MILLIS = 60 * 60_000L

        /** A server certificate this close to its end is made again. */
        private const val RENEW_MILLIS = 30L * 24 * 60 * 60_000L

        /** The in-memory key store's; nothing is written with it. */
        private const val KEYSTORE_PASSWORD = "station"

        private const val CA_CERT = "ca.pem"
        private const val CA_KEY = "ca-key.pem"
        private const val SERVER_CERT = "server.pem"
        private const val SERVER_KEY = "server-key.pem"
        private const val SERVER_HOSTS = "server-hosts"

        private val P256: ECParameterSpec =
            AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }.getParameterSpec(ECParameterSpec::class.java)

        /**
         * The CA in [dir], made the first time; the server certificate, made again when
         * [hosts] change (it always names `localhost` too), it wasn't signed by this CA, or
         * it's within 30 days of its end. A CA that's there but won't read stops the station:
         * making a new one would unpair every TV.
         */
        fun load(dir: Path, hosts: List<String>, nowMillis: Long, log: (String) -> Unit, random: SecureRandom = SecureRandom()): StationCa {
            SecretFiles.ensureDir(dir)
            val caCertFile = dir.resolve(CA_CERT)
            val caKeyFile = dir.resolve(CA_KEY)
            val (caCert, caKey) = if (!Files.exists(caCertFile) && !Files.exists(caKeyFile)) {
                val made = makeCa(nowMillis, random)
                SecretFiles.writeText(caKeyFile, pem("PRIVATE KEY", made.second.encoded))
                SecretFiles.writeText(caCertFile, pem(made.first))
                log("Made the station's certificate authority")
                made
            } else {
                try {
                    readCertificate(SecretFiles.readText(caCertFile) ?: error("ca.pem is missing")) to
                        readPrivateKey(SecretFiles.readText(caKeyFile) ?: error("ca-key.pem is missing"))
                } catch (e: Exception) {
                    throw IllegalStateException(
                        "The station's certificate authority in $dir can't be read (${e.javaClass.simpleName}). Restore $CA_CERT and " +
                            "$CA_KEY from a backup, or delete both to make a new one, after which every TV pairs again.",
                    )
                }
            }

            val names = (hosts + "localhost").distinct()
            val wanted = names.sorted().joinToString(",")
            val saved = try {
                val cert = SecretFiles.readText(dir.resolve(SERVER_CERT))?.let(::readCertificate)
                val key = SecretFiles.readText(dir.resolve(SERVER_KEY))?.let(::readPrivateKey)
                if (cert != null && key != null) cert to key else null
            } catch (e: Exception) {
                null
            }
            val current = saved?.takeIf { (cert, _) ->
                SecretFiles.readText(dir.resolve(SERVER_HOSTS)) == wanted && signedBy(cert, caCert) && cert.notAfter.time - nowMillis > RENEW_MILLIS
            }
            val (serverCert, serverKey) = current ?: makeServer(caCert, caKey, names, nowMillis, random).also { (cert, key) ->
                SecretFiles.writeText(dir.resolve(SERVER_KEY), pem("PRIVATE KEY", key.encoded))
                SecretFiles.writeText(dir.resolve(SERVER_CERT), pem(cert))
                SecretFiles.writeText(dir.resolve(SERVER_HOSTS), wanted)
                log("Made the server certificate for ${names.joinToString(", ")}")
            }
            return StationCa(caCert, caKey, serverCert, serverKey, random)
        }

        /** A TV's public key (SubjectPublicKeyInfo DER), which must be EC P-256. */
        fun ecP256(spki: ByteArray): PublicKey {
            val key = try {
                KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki))
            } catch (e: GeneralSecurityException) {
                throw IllegalArgumentException("not an EC public key")
            }
            val params = (key as? ECPublicKey)?.params ?: throw IllegalArgumentException("not an EC public key")
            require(params.curve == P256.curve && params.generator == P256.generator && params.order == P256.order) { "not a P-256 key" }
            return key
        }

        fun pem(certificate: X509Certificate): String = pem("CERTIFICATE", certificate.encoded)

        fun readCertificate(pem: String): X509Certificate =
            CertificateFactory.getInstance("X.509").generateCertificate(pem.byteInputStream()) as X509Certificate

        /** A fresh EC P-256 key pair: the station's own, and the tests' TVs. */
        fun ecKeyPair(random: SecureRandom = SecureRandom()): KeyPair =
            KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), random) }.generateKeyPair()

        private fun readPrivateKey(pem: String): PrivateKey {
            val body = pem.lineSequence().filterNot { it.startsWith("-----") }.joinToString("") { it.trim() }
            return KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)))
        }

        private fun pem(type: String, der: ByteArray): String =
            "-----BEGIN $type-----\n${Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der)}\n-----END $type-----\n"

        private fun makeCa(nowMillis: Long, random: SecureRandom): Pair<X509Certificate, PrivateKey> {
            val keys = ecKeyPair(random)
            val name = name(CA_NAME)
            val builder = JcaX509v3CertificateBuilder(name, serial(random), Date(nowMillis - BACKDATE_MILLIS), Date(nowMillis + CA_YEARS * YEAR_MILLIS), name, keys.public)
                // A CA that signs end certificates only.
                .addExtension(Extension.basicConstraints, true, BasicConstraints(0))
                .addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign))
                .addExtension(Extension.subjectKeyIdentifier, false, extensions().createSubjectKeyIdentifier(keys.public))
            return sign(builder, keys.private) to keys.private
        }

        private fun makeServer(ca: X509Certificate, caKey: PrivateKey, hosts: List<String>, nowMillis: Long, random: SecureRandom): Pair<X509Certificate, PrivateKey> {
            val keys = ecKeyPair(random)
            val sans = hosts.map { if (IPAddress.isValid(it)) GeneralName(GeneralName.iPAddress, it) else GeneralName(GeneralName.dNSName, it) }
            val builder = JcaX509v3CertificateBuilder(
                ca, serial(random), Date(nowMillis - BACKDATE_MILLIS), Date(nowMillis + SERVER_YEARS * YEAR_MILLIS), name(SERVER_NAME), keys.public,
            )
                .addExtension(Extension.basicConstraints, true, BasicConstraints(false))
                .addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature))
                .addExtension(Extension.extendedKeyUsage, false, ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth))
                .addExtension(Extension.subjectAlternativeName, false, GeneralNames(sans.toTypedArray()))
                .addExtension(Extension.subjectKeyIdentifier, false, extensions().createSubjectKeyIdentifier(keys.public))
                .addExtension(Extension.authorityKeyIdentifier, false, extensions().createAuthorityKeyIdentifier(ca))
            return sign(builder, caKey) to keys.private
        }

        private fun signedBy(cert: X509Certificate, ca: X509Certificate): Boolean = try {
            cert.verify(ca.publicKey)
            true
        } catch (e: GeneralSecurityException) {
            false
        }

        private fun name(commonName: String): X500Name = X500NameBuilder(BCStyle.INSTANCE).addRDN(BCStyle.CN, commonName).build()

        /** A positive 127-bit serial: unique in practice, and what a TV's certificate is known by. */
        private fun serial(random: SecureRandom): BigInteger = BigInteger(126, random).setBit(126)

        private fun extensions() = JcaX509ExtensionUtils()

        private fun sign(builder: X509v3CertificateBuilder, key: PrivateKey): X509Certificate =
            JcaX509CertificateConverter().getCertificate(builder.build(JcaContentSignerBuilder("SHA256withECDSA").build(key)))
    }
}

/** A certificate's serial as the station keeps it: lowercase hex. */
val X509Certificate.serialHex: String get() = serialNumber.toString(16)
