package com.sunshine.mesh.core.transport.wifidirect

import android.content.Context
import com.sunshine.mesh.core.transport.TransportError
import com.sunshine.mesh.core.transport.TransportException
import com.sunshine.mesh.data.profile.ProfileRepository
import kotlinx.coroutines.CoroutineScope

/**
 * Builds a [WifiDirectTransport] from real Android components, mirroring
 * BleTransportFactory. Stage 5 picks which transport(s) the engine runs.
 */
class WifiDirectTransportFactory(
    context: Context,
    private val profileRepository: ProfileRepository,
) {

    private val appContext = context.applicationContext

    suspend fun create(scope: CoroutineScope): WifiDirectTransport {
        val profile = profileRepository.loadProfile()
            ?: throw TransportException(TransportError.PROFILE_MISSING)
        val prerequisites = WifiDirectPrerequisites(appContext)
        return WifiDirectTransport(
            localPeerId = profile.peerId,
            discovery = AndroidWifiDirectDiscovery(appContext, prerequisites),
            groups = AndroidWifiDirectGroupManager(appContext, prerequisites),
            prerequisites = prerequisites,
            scope = scope,
        )
    }
}
