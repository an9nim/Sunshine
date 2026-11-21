package com.sunshine.mesh

import android.content.Context
import com.sunshine.mesh.core.crypto.KeystoreSecret
import com.sunshine.mesh.data.local.SunshineDatabase
import com.sunshine.mesh.data.peer.PeerRepository
import com.sunshine.mesh.data.peer.RoomPeerTransactions
import com.sunshine.mesh.data.profile.ProfileRepository
import com.sunshine.mesh.data.profile.createProfileStore

/**
 * Manual dependency graph: one application-scoped instance, no service locator.
 * Keystore aliases are versioned because their key material cannot be migrated.
 */
class AppGraph(context: Context) {

    private val appContext = context.applicationContext

    val database: SunshineDatabase by lazy { SunshineDatabase.create(appContext) }

    val profileRepository: ProfileRepository by lazy {
        ProfileRepository(
            store = createProfileStore(appContext),
            secret = KeystoreSecret(
                alias = "sunshine.identity.v1",
                aadLabel = "sunshine/identity/v1",
            ),
        )
    }

    val peerRepository: PeerRepository by lazy {
        PeerRepository(
            peerDao = database.peerDao(),
            transactions = RoomPeerTransactions(database),
        )
    }
}
