package com.psmobile.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller

/**
 * Receives the platform installer's verdict.
 *
 * The one case that needs handling is [PackageInstaller.STATUS_PENDING_USER_ACTION]: the system
 * has prepared the install and wants the user to confirm it, handing back the Intent that shows
 * its own dialog. Everything else is terminal and is reported through [lastStatus] so the
 * settings screen can say what happened.
 */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirmation = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
            confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(confirmation) }
            return
        }

        lastStatus = when (status) {
            PackageInstaller.STATUS_SUCCESS -> InstallStatus.Succeeded
            PackageInstaller.STATUS_FAILURE_ABORTED -> InstallStatus.Cancelled
            else -> InstallStatus.Failed(
                intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty(),
            )
        }
    }

    companion object {
        const val ACTION = "com.psmobile.action.INSTALL_RESULT"

        /** Read by the UI after the system installer returns. */
        @Volatile
        var lastStatus: InstallStatus? = null
    }
}

sealed interface InstallStatus {
    data object Succeeded : InstallStatus
    data object Cancelled : InstallStatus
    data class Failed(val message: String) : InstallStatus
}
