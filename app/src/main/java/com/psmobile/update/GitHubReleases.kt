package com.psmobile.update

import com.psmobile.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

/** A release on GitHub that carries an installable APK. */
data class AvailableRelease(
    val versionName: String,
    val versionCode: Int,
    val notes: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val pageUrl: String,
)

@Serializable
private class ReleaseJson(
    @SerialName("tag_name") val tagName: String = "",
    val body: String? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<AssetJson> = emptyList(),
)

@Serializable
private class AssetJson(
    val name: String = "",
    @SerialName("browser_download_url") val downloadUrl: String = "",
    val size: Long = 0,
)

/**
 * Reads the project's own GitHub releases.
 *
 * The app is distributed outside any store, so this is how it learns that a newer build exists.
 * Only the repository baked in at build time is ever contacted, over HTTPS, and nothing here
 * decides to install anything — it reports what is published and leaves the rest to
 * [UpdateInstaller], which checks the signature before the installer is ever invoked.
 */
object GitHubReleases {

    private val json = Json { ignoreUnknownKeys = true }

    private val latestUrl: String
        get() = "https://api.github.com/repos/${BuildConfig.GITHUB_OWNER}/${BuildConfig.GITHUB_REPO}/releases/latest"

    /** The newest published release, or null when there is none or the network refuses. */
    suspend fun fetchLatest(): AvailableRelease? = withContext(Dispatchers.IO) {
        val payload = runCatching { get(latestUrl) }.getOrNull() ?: return@withContext null
        val release = runCatching { json.decodeFromString<ReleaseJson>(payload) }.getOrNull()
            ?: return@withContext null
        if (release.draft || release.prerelease) return@withContext null

        val version = release.tagName.removePrefix("v").takeIf { it.isNotBlank() }
            ?: return@withContext null
        val asset = release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
            ?: return@withContext null
        // Assets are served from a different host, but it must still be GitHub over TLS.
        if (!asset.downloadUrl.startsWith("https://")) return@withContext null

        AvailableRelease(
            versionName = version,
            versionCode = versionCodeOf(version),
            notes = release.body.orEmpty().trim(),
            downloadUrl = asset.downloadUrl,
            sizeBytes = asset.size,
            pageUrl = release.htmlUrl.orEmpty(),
        )
    }

    /**
     * The same mapping the build uses, so a tag and an installed build compare correctly.
     * `1.2.3` becomes `10203`.
     */
    fun versionCodeOf(versionName: String): Int {
        val numbers = versionName.removePrefix("v").substringBefore('-').split('.')
        val major = numbers.getOrNull(0)?.toIntOrNull() ?: 0
        val minor = numbers.getOrNull(1)?.toIntOrNull() ?: 0
        val patch = numbers.getOrNull(2)?.toIntOrNull() ?: 0
        return major * 10_000 + minor * 100 + patch
    }

    private fun get(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "PS-Mobile/${BuildConfig.VERSION_NAME}")
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("GitHub returned ${connection.responseCode}")
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
