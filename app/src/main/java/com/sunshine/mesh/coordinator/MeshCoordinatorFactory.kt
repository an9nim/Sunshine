package com.sunshine.mesh.coordinator

import com.sunshine.mesh.core.net.MeshPolicy
import com.sunshine.mesh.core.transport.TransportError
import com.sunshine.mesh.core.transport.TransportException
import com.sunshine.mesh.data.message.MessageStore
import com.sunshine.mesh.data.peer.PeerKeyStore
import com.sunshine.mesh.data.peer.PeerTrustGate
import com.sunshine.mesh.data.profile.IdentityProvider
import com.sunshine.mesh.domain.message.FragmentAssembler
import java.security.SecureRandom
import kotlinx.coroutines.CoroutineScope

/**
 * Builds a coordinator with the local identity already loaded, so the service
 * can create MeshEngine with [MeshCoordinator.localPeerId] and then call
 * [MeshCoordinator.attach] exactly once.
 */
class MeshCoordinatorFactory(
    private val identityProvider: IdentityProvider,
    private val peerKeyStore: PeerKeyStore,
    private val trustGate: PeerTrustGate,
    private val messageStore: MessageStore,
    private val now: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
    private val retryIntervalMs: Long = MeshPolicy.RETRY_INTERVAL_MS,
) {

    suspend fun create(scope: CoroutineScope): MeshCoordinator {
        val identity = identityProvider.load()
            ?: throw TransportException(TransportError.PROFILE_MISSING)
        return MeshCoordinator(
            identity = identity,
            peerKeyStore = peerKeyStore,
            trustGate = trustGate,
            messageStore = messageStore,
            scope = scope,
            assembler = FragmentAssembler(),
            now = now,
            random = random,
            retryIntervalMs = retryIntervalMs,
        )
    }
}
