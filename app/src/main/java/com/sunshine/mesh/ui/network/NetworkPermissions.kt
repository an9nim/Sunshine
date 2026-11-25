package com.sunshine.mesh.ui.network

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.sunshine.mesh.core.permissions.MeshPermissions

/** Runtime permission gate for the network screen, abstracted for tests. */
interface NetworkPermissions {
    val required: List<String>

    /** Currently missing permissions; empty when the transport may start. */
    fun missing(): List<String>
}

class AndroidNetworkPermissions(context: Context) : NetworkPermissions {

    private val appContext = context.applicationContext

    override val required: List<String> =
        MeshPermissions.discoveryPermissions(Build.VERSION.SDK_INT)

    override fun missing(): List<String> = required.filterNot { permission ->
        ContextCompat.checkSelfPermission(appContext, permission) ==
            PackageManager.PERMISSION_GRANTED
    }
}
