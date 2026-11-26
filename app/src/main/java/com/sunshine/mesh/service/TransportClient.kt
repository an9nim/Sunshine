package com.sunshine.mesh.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.sunshine.mesh.coordinator.ConnectResult
import com.sunshine.mesh.coordinator.MeshCoordinator
import com.sunshine.mesh.core.ids.PeerId
import com.sunshine.mesh.core.transport.TransportError
import com.sunshine.mesh.core.transport.TransportHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Binds the UI to [TransportService] and exposes the live coordinator/hub.
 * The service owns the mesh; this class only mirrors its state.
 */
class TransportClient(context: Context) : NetworkControl {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val serviceIntent = Intent(appContext, TransportService::class.java)

    private val mutableCoordinator = MutableStateFlow<MeshCoordinator?>(null)
    val coordinator: StateFlow<MeshCoordinator?> = mutableCoordinator.asStateFlow()

    private val mutableHub = MutableStateFlow<TransportHub?>(null)
    override val hub: StateFlow<TransportHub?> = mutableHub.asStateFlow()

    private val mutableRunning = MutableStateFlow(false)
    override val running: StateFlow<Boolean> = mutableRunning.asStateFlow()

    private val mutableStartError = MutableStateFlow<TransportError?>(null)
    override val startError: StateFlow<TransportError?> = mutableStartError.asStateFlow()

    private var bound = false
    private var observeJobs: List<Job> = emptyList()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val transportService = (service as TransportService.LocalBinder).getService()
            mutableRunning.value = true
            observe(transportService)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            clearObserved()
            mutableRunning.value = false
            mutableStartError.value = TransportError.INTERNAL
        }
    }

    override suspend fun connect(peerId: PeerId): ConnectResult {
        val coordinator = mutableCoordinator.value
            ?: return ConnectResult.Failed(TransportError.NOT_CONNECTED)
        return coordinator.connect(peerId)
    }

    /** Idempotent: safe to call from every screen that needs the mesh. */
    override fun start() {
        if (bound) return
        mutableStartError.value = null
        mutableRunning.value = false

        try {
            ContextCompat.startForegroundService(appContext, serviceIntent)
        } catch (error: Exception) {
            Timber.w("Transport service start rejected")
            mutableStartError.value = TransportError.FOREGROUND_SERVICE_DENIED
            return
        }

        val boundNow = try {
            appContext.bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)
        } catch (error: Exception) {
            Timber.w("Transport service bind rejected")
            false
        }
        if (!boundNow) {
            mutableStartError.value = TransportError.INTERNAL
            stopService()
            return
        }
        bound = true
        mutableRunning.value = true
    }

    /** Stops the service and drops the binding; safe to call when already stopped. */
    override fun stop() {
        unbind()
        stopService()
        mutableRunning.value = false
        mutableStartError.value = null
    }

    override fun restart() {
        stop()
        start()
    }

    private fun unbind() {
        clearObserved()
        if (!bound) return
        bound = false
        try {
            appContext.unbindService(connection)
        } catch (error: Exception) {
            Timber.w("Transport service unbind rejected")
        }
    }

    private fun stopService() {
        try {
            appContext.stopService(serviceIntent)
        } catch (error: Exception) {
            Timber.w("Transport service stop rejected")
        }
    }

    private fun clearObserved() {
        observeJobs.forEach(Job::cancel)
        observeJobs = emptyList()
        mutableCoordinator.value = null
        mutableHub.value = null
    }

    private fun observe(service: TransportService) {
        observeJobs.forEach(Job::cancel)
        observeJobs = listOf(
            scope.launch {
                service.coordinator.collect { mutableCoordinator.value = it }
            },
            scope.launch {
                service.hub.collect { mutableHub.value = it }
            },
            scope.launch {
                service.startError.collect { mutableStartError.value = it }
            },
        )
    }
}
