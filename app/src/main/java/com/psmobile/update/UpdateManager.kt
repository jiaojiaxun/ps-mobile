package com.psmobile.update

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import com.psmobile.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

sealed interface UpdateState {
    /** Nothing has been asked for yet. */
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: AvailableRelease) : UpdateState
    data class Failed(val reason: Reason, val detail: String = "") : UpdateState

    enum class Reason { NETWORK }
}

/**
 * Tells the user when a newer release exists, and sends them to it.
 *
 * An earlier version downloaded the APK and drove [android.content.pm.PackageInstaller] itself.
 * That worked, but Google Play Protect blocks it: a sideloaded app holding
 * `REQUEST_INSTALL_PACKAGES` and installing an APK it just downloaded is indistinguishable, to a
 * heuristic, from a dropper — and every user would have had to dismiss a "Harmful app blocked"
 * warning to update. Handing off to the browser costs one tap and no warning, and the install
 * permission is not needed at all.
 *
 * For unattended updates the README points at Obtainium, which is built for this and carries the
 * install permission itself.
 */
class UpdateManager(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val preferences = context.applicationContext
        .getSharedPreferences("ps_mobile_updates", Context.MODE_PRIVATE)

    var state by mutableStateOf<UpdateState>(UpdateState.Idle)
        private set

    val currentVersion: String get() = BuildConfig.VERSION_NAME

    private var job: Job? = null

    /**
     * Looks for a newer release.
     *
     * @param force runs even if one was checked recently; the automatic check on start does not.
     */
    fun check(force: Boolean) {
        if (!force && System.currentTimeMillis() - lastCheck < CHECK_INTERVAL_MILLIS) return

        job?.cancel()
        state = UpdateState.Checking
        job = scope.launch {
            val release = GitHubReleases.fetchLatest()
            // Only a check that actually reached GitHub counts, otherwise a single failure
            // would silence the automatic check for the whole interval.
            if (release != null) lastCheck = System.currentTimeMillis()
            state = when {
                release == null ->
                    if (force) UpdateState.Failed(UpdateState.Reason.NETWORK) else UpdateState.Idle
                release.versionCode > BuildConfig.VERSION_CODE -> UpdateState.Available(release)
                else -> UpdateState.UpToDate
            }
        }
    }

    /** Opens the release on GitHub so the browser and the system installer take it from there. */
    fun openRelease(context: Context, release: AvailableRelease) {
        val target = release.pageUrl.takeIf { it.startsWith("https://") } ?: release.downloadUrl
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, target.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    fun dismiss() {
        job?.cancel()
        job = null
        state = UpdateState.Idle
    }

    private var lastCheck: Long
        get() = preferences.getLong(KEY_LAST_CHECK, 0L)
        set(value) = preferences.edit().putLong(KEY_LAST_CHECK, value).apply()

    private companion object {
        const val KEY_LAST_CHECK = "last_check"
        const val CHECK_INTERVAL_MILLIS = 6 * 60 * 60 * 1000L
    }
}
