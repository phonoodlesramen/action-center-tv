package com.phonoodlesramen.actioncenter.station

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec

/**
 * This TV's key for the guide station (Phase 8, N3): an EC P-256 key pair whose public half
 * the station signs a certificate for at pairing. On the TVs it's [AndroidKeyStoreKeys], so
 * the private half never leaves the Keystore; tests use a software key (Robolectric has no
 * AndroidKeyStore).
 */
interface StationKeys {
    /** The public half, made with its private half the first time it's asked for. */
    fun publicKey(): PublicKey

    /** The private half, for the TLS handshake; null when there's no key yet. */
    fun privateKey(): PrivateKey?

    /** Forgets the key (leaving the station): the next pairing makes a new one. */
    fun delete()
}

/**
 * [StationKeys] in the Android Keystore (U2): `PURPOSE_SIGN`, with digests NONE and SHA-256.
 * Android's TLS hashes the handshake itself and asks the key to sign the digest, so a key
 * that allows SHA-256 alone fails the handshake with nothing but a socket error.
 */
class AndroidKeyStoreKeys(private val alias: String = ALIAS) : StationKeys {
    @Volatile
    private var cached: PrivateKey? = null

    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    @Synchronized
    override fun publicKey(): PublicKey {
        keyStore().getCertificate(alias)?.let { return it.publicKey }
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER)
        generator.initialize(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256)
                .build(),
        )
        val pair = generator.generateKeyPair()
        cached = pair.private
        return pair.public
    }

    override fun privateKey(): PrivateKey? = cached ?: (keyStore().getKey(alias, null) as? PrivateKey)?.also { cached = it }

    @Synchronized
    override fun delete() {
        cached = null
        keyStore().deleteEntry(alias)
    }

    companion object {
        const val ALIAS = "guide-station"
        private const val PROVIDER = "AndroidKeyStore"
    }
}
