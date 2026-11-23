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
import com.sunshine.mesh.core.transport.TransportException
import com.sunshine.mesh.core.transport.TransportState
import com.sunshine.mesh.core.transport.ble.BleTransport
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
 * Foreground service that owns the BLE transport. The Activity binds to it and
 * observes [transport]; the service keeps scanning, advertising and GATT links
 * alive while the process is in the background.
 */
class TransportService : Service() {

    private val binder = LocalBinder()
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)

    private val mutableTransport = MutableStateFlow<BleTransport?>(null)
    val transport: StateFlow<BleTransport?> = mutableTransport.asStateFlow()

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
            stopSelf()
            return START_NOT_STICKY
        }

        if (!starting) {
            starting = true
            serviceScope.launch { startTransport() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        mutableTransport.value?.close()
        mutableTransport.value = null
        serviceJob.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private suspend fun startTransport() {
        try {
            val graph = (application as SunshineApp).graph
            val created = graph.bleTransportFactory.create(serviceScope)
            mutableTransport.value = created
            created.start()
            serviceScope.launch {
                created.state.collect { state ->
                    if (state is TransportState.Failed) {
                        Timber.w("Transport service stopping: code=%s", state.error)
                        stopSelf()
                    }
                }
            }
        } catch (error: TransportException) {
            Timber.w("Transport service failed to start: code=%s", error.code)
            stopSelf()
        } catch (error: Exception) {
            Timber.w("Transport service failed to start")
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
