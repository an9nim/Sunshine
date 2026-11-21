package com.sunshine.mesh.core.crypto

import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import java.security.SecureRandom

class X25519KeyPair internal constructor(
    val privateKey: ByteArray,
    val publicKey: ByteArray,
) {
    fun destroy() {
        privateKey.fill(0)
    }
}

object IdentityKeys {
    fun generate(random: SecureRandom = SecureRandom()): X25519KeyPair {
        val privateKey = X25519PrivateKeyParameters(random)
        return X25519KeyPair(
            privateKey = privateKey.encoded,
            publicKey = privateKey.generatePublicKey().encoded,
        )
    }
}
