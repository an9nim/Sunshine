package com.sunshine.mesh.core.transport.wifidirect

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import com.sunshine.mesh.core.transport.TransportError
import com.sunshine.mesh.core.transport.TransportException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/**
 * WifiP2pManager actions are asynchronous; every call is bridged to a
 * [CompletableDeferred] and bounded by a 10-second deadline. close() forces
 * removeGroup so a half-open group never outlives the transport.
 */
class AndroidWifiDirectGroupManager(
    context: Context,
    private val prerequisites: WifiDirectPrerequisites,
) : WifiDirectGroupManager {

    private val appContext = context.applicationContext

    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null

    @SuppressLint("MissingPermission")
    override suspend fun createGroup(): WifiDirectGroupInfo {
        val (manager, channel) = channel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            awaitAction { manager.createGroup(channel, groupConfig(GO_INTENT), it) }
        } else {
            awaitAction { manager.createGroup(channel, it) }
        }
        return awaitGroupInfo(manager, channel)
    }

    @SuppressLint("MissingPermission")
    override suspend fun connect(deviceAddress: String): WifiDirectGroupInfo {
        val (manager, channel) = channel()
        val config = groupConfig(CLIENT_INTENT).apply {
            @Suppress("DEPRECATION")
            this.deviceAddress = deviceAddress
        }
        awaitAction { manager.connect(channel, config, it) }
        return awaitGroupInfo(manager, channel)
    }

    /**
     * The public fields are deprecated but remain the only way to set the
     * owner intent across API 24-36; the newer Builder dropped that setter.
     */
    private fun groupConfig(intent: Int): WifiP2pConfig =
        @Suppress("DEPRECATION")
        WifiP2pConfig().apply { groupOwnerIntent = intent }

    @SuppressLint("MissingPermission")
    override suspend fun removeGroup() {
        val (manager, channel) = channel()
        try {
            awaitAction { manager.removeGroup(channel, it) }
        } catch (error: TransportException) {
            // No group to remove is a success for our purposes.
        }
    }

    override suspend fun currentGroup(): WifiDirectGroupInfo? {
        val (manager, channel) = channel()
        val info = requestInfo(manager, channel)
        if (!info.groupFormed || info.groupOwnerAddress == null) return null
        return WifiDirectGroupInfo(info.isGroupOwner, info.groupOwnerAddress)
    }

    @SuppressLint("MissingPermission")
    override fun close() {
        val currentManager = manager
        val currentChannel = channel
        manager = null
        channel = null
        if (currentManager == null || currentChannel == null) return
        try {
            currentManager.removeGroup(
                currentChannel,
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() = Unit
                    override fun onFailure(reason: Int) = Unit
                },
            )
        } catch (_: SecurityException) {
        } catch (_: IllegalStateException) {
        }
    }

    @SuppressLint("MissingPermission")
    private fun channel(): Pair<WifiP2pManager, WifiP2pManager.Channel> {
        manager?.let { existing -> channel?.let { return existing to it } }
        val newManager = prerequisites.requireManager()
        val newChannel = newManager.initialize(appContext, appContext.mainLooper, null)
        manager = newManager
        channel = newChannel
        return newManager to newChannel
    }

    private suspend fun awaitAction(block: (WifiP2pManager.ActionListener) -> Unit) {
        val deferred = CompletableDeferred<Unit>()
        val listener = object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                deferred.complete(Unit)
            }

            override fun onFailure(reason: Int) {
                deferred.completeExceptionally(
                    TransportException(TransportError.WIFI_GROUP_FAILED),
                )
            }
        }
        try {
            block(listener)
        } catch (error: SecurityException) {
            throw TransportException(TransportError.PERMISSION_DENIED, error)
        }
        try {
            withTimeout(WifiDirectProtocol.GROUP_TIMEOUT_MS) { deferred.await() }
        } catch (error: TimeoutCancellationException) {
            throw TransportException(TransportError.CONNECT_TIMEOUT, error)
        }
    }

    private suspend fun awaitGroupInfo(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel,
    ): WifiDirectGroupInfo =
        try {
            withTimeout(WifiDirectProtocol.GROUP_TIMEOUT_MS) {
                var result: WifiDirectGroupInfo? = null
                while (result == null) {
                    val info = requestInfo(manager, channel)
                    if (info.groupFormed && info.groupOwnerAddress != null) {
                        result = WifiDirectGroupInfo(info.isGroupOwner, info.groupOwnerAddress)
                    } else {
                        delay(WifiDirectProtocol.GROUP_POLL_MS)
                    }
                }
                requireNotNull(result)
            }
        } catch (error: TimeoutCancellationException) {
            throw TransportException(TransportError.CONNECT_TIMEOUT, error)
        }

    private suspend fun requestInfo(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel,
    ): WifiP2pInfo {
        val deferred = CompletableDeferred<WifiP2pInfo>()
        val listener = WifiP2pManager.ConnectionInfoListener { info ->
            deferred.complete(info ?: WifiP2pInfo())
        }
        try {
            manager.requestConnectionInfo(channel, listener)
        } catch (error: SecurityException) {
            throw TransportException(TransportError.PERMISSION_DENIED, error)
        }
        return withTimeout(WifiDirectProtocol.GROUP_TIMEOUT_MS) { deferred.await() }
    }

    private companion object {
        const val GO_INTENT = 15
        const val CLIENT_INTENT = 0
    }
}
