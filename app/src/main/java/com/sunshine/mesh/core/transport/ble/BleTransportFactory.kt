package com.sunshine.mesh.core.transport.ble

import android.content.Context
import com.sunshine.mesh.core.transport.TransportError
import com.sunshine.mesh.core.transport.TransportException
import com.sunshine.mesh.data.profile.ProfileRepository
import kotlinx.coroutines.CoroutineScope

/**
 * Builds a [BleTransport] from real Android components. The local peer id is
 * loaded from the profile; without a profile the transport cannot start.
 */
class BleTransportFactory(
    context: Context,
    private val profileRepository: ProfileRepository,
) {

    private val appContext = context.applicationContext

    suspend fun create(scope: CoroutineScope): BleTransport {
        val profile = profileRepository.loadProfile()
            ?: throw TransportException(TransportError.PROFILE_MISSING)
        val prerequisites = BlePrerequisites(appContext)
        return BleTransport(
            localPeerId = profile.peerId,
            scanner = AndroidBleScanner(appContext, prerequisites),
            advertiser = AndroidBleAdvertiser(appContext, prerequisites),
            server = AndroidBleGattServer(appContext, prerequisites),
            clientFactory = AndroidBleGattClientFactory(appContext, prerequisites),
            scope = scope,
        )
    }
}
