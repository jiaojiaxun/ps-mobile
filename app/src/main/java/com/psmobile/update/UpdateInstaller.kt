package com.psmobile.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Downloads a release APK and hands it to the platform installer.
 *
 * Two things matter here. The download is written to the app's own storage and never made
 * world-readable, and before anything is installed the APK's signing certificate is compared
 * against the certificate of the running app. Android would reject a mismatch anyway, but
 * checking first means a wrong or tampered file is discarded with a clear message instead of a
 * confusing system error — and it means the app never asks the installer to open something it
 * has not vouched for.
 *
 * The install itself goes through [PackageInstaller], so the confirmation and progress the user
 * sees are the system's own, exactly as for any other app update.
 */
class UpdateInstaller(context: Context) {

    private val appContext = context.applicationContext

    private val downloadDir: File
        get() = File(appContext.cacheDir, "updates").apply { mkdirs() }

    /** True once the user has allowed this app to install packages. */
    fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || appContext.packageManager.canRequestPackageInstalls()

    /** Where to send the user to grant that permission. */
    fun installPermissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${appContext.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Fetches the APK, reporting 0..1 progress. Any previous download is replaced.
     */
    suspend fun download(release: AvailableRelease, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            downloadDir.listFiles()?.forEach { it.delete() }
            val target = File(downloadDir, "ps-mobile-${release.versionName}.apk")

            val connection = (URL(release.downloadUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "PS-Mobile")
            }
            try {
                if (connection.responseCode !in 200..299) {
                    error("Download failed with ${connection.responseCode}")
                }
                val total = connection.contentLengthLong.takeIf { it > 0 } ?: release.sizeBytes
                connection.inputStream.use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(1 shl 16)
                        var written = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            written += read
                            if (total > 0) onProgress((written.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }
            target
        }

    /**
     * True when [apk] is signed by the same certificate as the running app.
     *
     * A build signed with a different key cannot update this installation, and an APK that is
     * not ours has no business being handed to the installer at all.
     */
    fun isSignedLikeUs(apk: File): Boolean = runCatching {
        val ours = certificateDigests(installedPackage = appContext.packageName)
        val theirs = certificateDigests(apkPath = apk.absolutePath)
        ours.isNotEmpty() && theirs.isNotEmpty() && ours == theirs
    }.getOrDefault(false)

    @Suppress("DEPRECATION")
    private fun certificateDigests(installedPackage: String? = null, apkPath: String? = null): Set<String> {
        val pm = appContext.packageManager
        val useModernApi = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
        val flags = if (useModernApi) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

        val info = when {
            installedPackage != null -> pm.getPackageInfo(installedPackage, flags)
            apkPath != null -> pm.getPackageArchiveInfo(apkPath, flags)
            else -> null
        } ?: return emptySet()

        val signatures = if (useModernApi) {
            val signing = info.signingInfo ?: return emptySet()
            if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory
        } else {
            info.signatures
        } ?: return emptySet()

        val digest = MessageDigest.getInstance("SHA-256")
        return signatures.mapNotNull { it?.toByteArray() }
            .map { digest.digest(it).joinToString("") { byte -> "%02x".format(byte) } }
            .toSet()
    }

    /**
     * Commits the APK to the platform installer. The system takes over from here: it asks the
     * user to confirm and shows its own progress, the same as any other update.
     */
    suspend fun install(apk: File) = withContext(Dispatchers.IO) {
        val installer = appContext.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(appContext.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_UNSPECIFIED)
            }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite(NAME, 0, apk.length()).use { output ->
                apk.inputStream().use { it.copyTo(output) }
                session.fsync(output)
            }
            val callback = PendingIntent.getBroadcast(
                appContext,
                sessionId,
                Intent(appContext, InstallResultReceiver::class.java)
                    .setAction(InstallResultReceiver.ACTION),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(callback.intentSender)
        }
    }

    fun discardDownloads() {
        runCatching { downloadDir.listFiles()?.forEach { it.delete() } }
    }

    private companion object {
        const val NAME = "ps-mobile-update"
    }
}
