package com.sunshine.mesh

import android.content.Context
import com.sunshine.mesh.coordinator.MeshCoordinatorFactory
import com.sunshine.mesh.core.crypto.KeystoreSecret
import com.sunshine.mesh.core.mesh.MeshStore
import com.sunshine.mesh.core.transport.ble.BleTransportFactory
import com.sunshine.mesh.core.transport.wifidirect.WifiDirectTransportFactory
import com.sunshine.mesh.data.local.SunshineDatabase
import com.sunshine.mesh.data.mesh.RoomMeshStore
import com.sunshine.mesh.data.message.MessageStore
import com.sunshine.mesh.data.message.RoomMessageStore
import com.sunshine.mesh.data.peer.PeerRepository
import com.sunshine.mesh.data.peer.PeerTrustGate
import com.sunshine.mesh.data.peer.RoomPeerTransactions
import com.sunshine.mesh.data.profile.IdentityProvider
import com.sunshine.mesh.data.profile.IdentityRepository
import com.sunshine.mesh.data.profile.ProfileRepository
import com.sunshine.mesh.data.profile.ProfileStore
import com.sunshine.mesh.data.profile.createProfileStore

/**
 * Manual dependency graph: one application-scoped instance, no service locator.
 * Keystore aliases are versioned because their key material cannot be migrated.
 */
class AppGraph(context: Context) {

    private val appContext = context.applicationContext

    val database: SunshineDatabase by lazy { SunshineDatabase.create(appContext) }

    private val profileStore: ProfileStore by lazy { createProfileStore(appContext) }

    private val identitySecret: KeystoreSecret by lazy {
        KeystoreSecret(
            alias = "sunshine.identity.v1",
            aadLabel = "sunshine/identity/v1",
        )
    }

    val profileRepository: ProfileRepository by lazy {
        ProfileRepository(
            store = profileStore,
            secret = identitySecret,
        )
    }

    val identityProvider: IdentityProvider by lazy {
        IdentityRepository(
            loadStoredProfile = profileStore::readStoredProfile,
            openPrivateKey = identitySecret::open,
        )
    }

    val peerRepository: PeerRepository by lazy {
        PeerRepository(
            peerDao = database.peerDao(),
            transactions = RoomPeerTransactions(database),
        )
    }

    val messageStore: MessageStore by lazy {
        RoomMessageStore(database.messageDao())
    }

    val meshStore: MeshStore by lazy {
        RoomMeshStore(database.pendingPacketDao())
    }

    val peerTrustGate: PeerTrustGate by lazy {
        PeerTrustGate(peerRepository)
    }

    val meshCoordinatorFactory: MeshCoordinatorFactory by lazy {
        MeshCoordinatorFactory(
            identityProvider = identityProvider,
            peerKeyStore = peerRepository,
            trustGate = peerTrustGate,
            messageStore = messageStore,
        )
    }

    val bleTransportFactory: BleTransportFactory by lazy {
        BleTransportFactory(appContext, profileRepository)
    }

    val wifiDirectTransportFactory: WifiDirectTransportFactory by lazy {
        WifiDirectTransportFactory(appContext, profileRepository)
    }
}
