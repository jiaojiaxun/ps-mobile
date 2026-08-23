package com.psmobile.update

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.psmobile.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

sealed interface UpdateState {
    /** Nothing has been asked for yet. */
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: AvailableRelease) : UpdateState
    data class Downloading(val release: AvailableRelease, val progress: Float) : UpdateState
    data class Verifying(val release: AvailableRelease) : UpdateState

    /** The download is checked and handed over; the system installer takes it from here. */
    data class Installing(val release: AvailableRelease) : UpdateState

    /** The user has not allowed this app to install packages yet. */
    data class NeedsPermission(val release: AvailableRelease) : UpdateState
    data class Failed(val reason: Reason, val detail: String = "") : UpdateState

    enum class Reason { NETWORK, SIGNATURE, INSTALL }
}

/**
 * Keeps the app up to date from its own GitHub releases.
 *
 * Distribution outside a store means nothing will offer the user a newer build, so the app has
 * to ask. A check is a single HTTPS request for the newest release; installing is a download
 * plus a signature check plus a handover to the platform installer, which is what puts the
 * familiar system confirmation and progress in front of the user.
 */
class UpdateManager(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val installer = UpdateInstaller(context)
    private val preferences = context.applicationContext
        .getSharedPreferences("ps_mobile_updates", Context.MODE_PRIVATE)

    var state by mutableStateOf<UpdateState>(UpdateState.Idle)
        private set

    val currentVersion: String get() = BuildConfig.VERSION_NAME

    private var job: Job? = null
    private var downloaded: File? = null

    /**
     * Looks for a newer release.
     *
     * @param force runs even if one was checked recently; the automatic check on start does not.
     */
    fun check(force: Boolean) {
        if (!force && System.currentTimeMillis() - lastCheck < CHECK_INTERVAL_MILLIS) return
        if (state is UpdateState.Downloading || state is UpdateState.Installing) return

        job?.cancel()
        state = UpdateState.Checking
        job = scope.launch {
            val release = GitHubReleases.fetchLatest()
            lastCheck = System.currentTimeMillis()
            state = when {
                release == null ->
                    if (force) UpdateState.Failed(UpdateState.Reason.NETWORK) else UpdateState.Idle
                release.versionCode > BuildConfig.VERSION_CODE -> UpdateState.Available(release)
                else -> UpdateState.UpToDate
            }
        }
    }

    /** Downloads the release, verifies it is ours, and hands it to the platform installer. */
    fun installUpdate(release: AvailableRelease) {
        job?.cancel()
        job = scope.launch {
            try {
                if (!installer.canInstall()) {
                    state = UpdateState.NeedsPermission(release)
                    return@launch
                }

                state = UpdateState.Downloading(release, 0f)
                val apk = installer.download(release) { progress ->
                    state = UpdateState.Downloading(release, progress)
                }
                downloaded = apk

                state = UpdateState.Verifying(release)
                if (!installer.isSignedLikeUs(apk)) {
                    // Either the release was signed with a different key or the file is not what
                    // it claims to be. Refuse rather than let the installer decide.
                    installer.discardDownloads()
                    state = UpdateState.Failed(UpdateState.Reason.SIGNATURE)
                    return@launch
                }

                state = UpdateState.Installing(release)
                InstallResultReceiver.lastStatus = null
                installer.install(apk)
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                state = UpdateState.Failed(UpdateState.Reason.NETWORK, t.message.orEmpty())
            }
        }
    }

    /** Called when the app comes back from the system installer. */
    fun refreshInstallOutcome() {
        when (val status = InstallResultReceiver.lastStatus) {
            null -> Unit
            is InstallStatus.Succeeded -> {
                InstallResultReceiver.lastStatus = null
                installer.discardDownloads()
                state = UpdateState.UpToDate
            }
            is InstallStatus.Cancelled -> {
                InstallResultReceiver.lastStatus = null
                val release = (state as? UpdateState.Installing)?.release
                state = if (release != null) UpdateState.Available(release) else UpdateState.Idle
            }
            is InstallStatus.Failed -> {
                InstallResultReceiver.lastStatus = null
                state = UpdateState.Failed(UpdateState.Reason.INSTALL, status.message)
            }
        }
    }

    fun grantInstallPermission(context: Context) {
        runCatching { context.startActivity(installer.installPermissionIntent()) }
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
