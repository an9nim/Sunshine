package com.sunshine.mesh.core.crypto.e2e

import com.sunshine.mesh.core.crypto.IdentityMaterial
import com.sunshine.mesh.core.ids.PeerId
import com.sunshine.mesh.domain.message.FragmentAssembler
import com.sunshine.mesh.domain.model.AssembledMessage
import java.nio.charset.StandardCharsets
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters

internal object E2ETestSupport {

    fun identity(seed: Int): IdentityMaterial {
        val privateKeyBytes = ByteArray(32) { index -> (seed + index).toByte() }
        val privateKey = X25519PrivateKeyParameters(privateKeyBytes, 0)
        val publicKey = privateKey.generatePublicKey().encoded
        return IdentityMaterial.from(
            peerId = PeerId.fromPublicKey(publicKey),
            publicKey = publicKey,
            privateKey = privateKey.encoded,
        )
    }

    fun session(local: IdentityMaterial, remote: IdentityMaterial): E2ESession =
        E2ESession(HkdfSession.derive(local, remote.publicKey))

    /**
     * Opens every packet, feeds it to the real assembler and decodes text only
     * after a complete message has been assembled.
     */
    fun openText(receiver: E2ESession, packets: List<ByteArray>): String {
        val assembler = FragmentAssembler()
        var complete: AssembledMessage? = null
        try {
            for (packet in packets) {
                val fragment = receiver.openFragment(packet)
                val assembled = try {
                    assembler.accept(fragment)
                } finally {
                    fragment.close()
                }
                if (assembled != null) complete = assembled
            }
            val message = requireNotNull(complete) { "message was not assembled" }
            return try {
                String(message.plaintext, StandardCharsets.UTF_8)
            } finally {
                message.close()
            }
        } finally {
            assembler.clear()
        }
    }
}
