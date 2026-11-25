package com.sunshine.mesh.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.sunshine.mesh.R
import com.sunshine.mesh.SunshineApp
import com.sunshine.mesh.coordinator.MeshCoordinator
import com.sunshine.mesh.core.mesh.MeshEngine
import com.sunshine.mesh.core.mesh.SeenCache
import com.sunshine.mesh.core.transport.TransportError
import com.sunshine.mesh.core.transport.TransportException
import com.sunshine.mesh.core.transport.TransportHub
import com.sunshine.mesh.core.transport.TransportState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Foreground service that owns both transports, the mesh engine and the
 * coordinator. The Activity binds to it in Stage 6; today it keeps scanning,
 * advertising, GATT/Wi-Fi links and the outbox alive while the process is in the
 * background.
 */
class TransportService : Service() {

    private val binder = LocalBinder()
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)

    private val mutableHub = MutableStateFlow<TransportHub?>(null)
    val hub: StateFlow<TransportHub?> = mutableHub.asStateFlow()

    private val mutableCoordinator = MutableStateFlow<MeshCoordinator?>(null)
    val coordinator: StateFlow<MeshCoordinator?> = mutableCoordinator.asStateFlow()

    private val mutableStartError = MutableStateFlow<TransportError?>(null)
    val startError: StateFlow<TransportError?> = mutableStartError.asStateFlow()

    private var starting = false

    inner class LocalBinder : Binder() {
        fun getService(): TransportService = this@TransportService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForegroundCompat()
        } catch (error: Exception) {
            Timber.w("Foreground service start rejected")
            mutableStartError.value = TransportError.FOREGROUND_SERVICE_DENIED
            stopSelf()
            return START_NOT_STICKY
        }

        if (!starting) {
            starting = true
            serviceScope.launch { startMesh() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        mutableCoordinator.value?.close()
        mutableCoordinator.value = null
        mutableHub.value?.close()
        mutableHub.value = null
        serviceJob.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private suspend fun startMesh() {
        var hub: TransportHub? = null
        var coordinator: MeshCoordinator? = null
        mutableStartError.value = null
        try {
            val graph = (application as SunshineApp).graph
            val ble = graph.bleTransportFactory.create(serviceScope)
            val wifi = graph.wifiDirectTransportFactory.create(serviceScope)
            hub = TransportHub(listOf(ble, wifi), serviceScope)
            coordinator = graph.meshCoordinatorFactory.create(serviceScope)

            val engine = MeshEngine(
                transport = hub,
                localPeerId = coordinator.localPeerId,
                seen = SeenCache(),
                store = graph.meshStore,
                handler = coordinator,
                scope = serviceScope,
            )
            coordinator.attach(engine)

            mutableHub.value = hub
            mutableCoordinator.value = coordinator

            hub.start()
            coordinator.start()

            serviceScope.launch {
                hub.state.collect { state ->
                    if (state is TransportState.Failed) {
                        Timber.w("Transport service stopping: code=%s", state.error)
                        stopSelf()
                    }
                }
            }
        } catch (error: TransportException) {
            Timber.w("Transport service failed to start: code=%s", error.code)
            mutableStartError.value = error.code
            mutableCoordinator.value = null
            mutableHub.value = null
            coordinator?.close()
            hub?.close()
            stopSelf()
        } catch (error: Exception) {
            Timber.w("Transport service failed to start")
            mutableStartError.value = TransportError.INTERNAL
            mutableCoordinator.value = null
            mutableHub.value = null
            coordinator?.close()
            hub?.close()
            stopSelf()
        }
    }

    private fun startForegroundCompat() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_transport)
            .setContentTitle(getString(R.string.transport_notification_title))
            .setContentText(getString(R.string.transport_notification_text))
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.transport_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private companion object {
        const val CHANNEL_ID = "sunshine.transport"
        const val NOTIFICATION_ID = 3001
    }
}
