package com.psmobile.settings

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import com.psmobile.R
import com.psmobile.ui.theme.PsColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/** Interface language. The app ships Russian and English; the system may pick either. */
enum class Language(val tag: String?, @param:StringRes val label: Int) {
    SYSTEM(null, R.string.settings_language_system),
    RUSSIAN("ru", R.string.settings_language_ru),
    ENGLISH("en", R.string.settings_language_en),
}

/** What surrounds the artwork. Photoshop offers the same choice, for the same reason. */
enum class CanvasBackground(@param:StringRes val label: Int, val color: Color) {
    DARK(R.string.settings_background_dark, PsColors.Ink900),
    BLACK(R.string.settings_background_black, Color(0xFF000000)),
    LIGHT(R.string.settings_background_light, Color(0xFFB8B8BE)),
}

/** How a document is framed when it opens. */
enum class InitialZoom(@param:StringRes val label: Int) {
    /** Fit the canvas, unless its aspect ratio makes that useless — then fill the viewport. */
    AUTO(R.string.settings_zoom_auto),
    FIT(R.string.settings_zoom_fit),
    FILL_WIDTH(R.string.settings_zoom_fill_width),
    ACTUAL(R.string.settings_zoom_actual),
}

/** Sub-samples per axis when shrinking; the cost of a zoomed-out tile scales with its square. */
enum class RenderQuality(@param:StringRes val label: Int, val supersample: Int) {
    FAST(R.string.settings_quality_fast, 1),
    BALANCED(R.string.settings_quality_balanced, 2),
    HIGH(R.string.settings_quality_high, 4),
}

data class AppSettings(
    val language: Language = Language.SYSTEM,
    val canvasBackground: CanvasBackground = CanvasBackground.DARK,
    val showCheckerboard: Boolean = true,
    val initialZoom: InitialZoom = InitialZoom.AUTO,
    val smoothScaling: Boolean = true,
    val renderQuality: RenderQuality = RenderQuality.HIGH,
    val scanOnOpen: Boolean = true,
    val filePreviews: Boolean = true,
    val autoCheckUpdates: Boolean = true,
)

/**
 * App settings, held in [SharedPreferences].
 *
 * Plain preferences rather than a datastore on purpose: the language has to be read
 * synchronously in `attachBaseContext`, before any coroutine could have run.
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(read(prefs))
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    val current: AppSettings get() = _settings.value

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value)
        prefs.edit()
            .putString(KEY_LANGUAGE, next.language.name)
            .putString(KEY_BACKGROUND, next.canvasBackground.name)
            .putBoolean(KEY_CHECKERBOARD, next.showCheckerboard)
            .putString(KEY_ZOOM, next.initialZoom.name)
            .putBoolean(KEY_SMOOTH, next.smoothScaling)
            .putString(KEY_QUALITY, next.renderQuality.name)
            .putBoolean(KEY_SCAN, next.scanOnOpen)
            .putBoolean(KEY_PREVIEWS, next.filePreviews)
            .putBoolean(KEY_AUTO_UPDATES, next.autoCheckUpdates)
            .apply()
        _settings.value = next
    }

    companion object {
        private const val FILE = "ps_mobile_settings"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_BACKGROUND = "canvas_background"
        private const val KEY_CHECKERBOARD = "checkerboard"
        private const val KEY_ZOOM = "initial_zoom"
        private const val KEY_SMOOTH = "smooth_scaling"
        private const val KEY_QUALITY = "render_quality"
        private const val KEY_SCAN = "scan_on_open"
        private const val KEY_PREVIEWS = "file_previews"
        private const val KEY_AUTO_UPDATES = "auto_check_updates"

        private inline fun <reified T : Enum<T>> SharedPreferences.enum(key: String, fallback: T): T {
            val name = getString(key, null) ?: return fallback
            return runCatching { enumValueOf<T>(name) }.getOrDefault(fallback)
        }

        private fun read(prefs: SharedPreferences) = AppSettings(
            language = prefs.enum(KEY_LANGUAGE, Language.SYSTEM),
            canvasBackground = prefs.enum(KEY_BACKGROUND, CanvasBackground.DARK),
            showCheckerboard = prefs.getBoolean(KEY_CHECKERBOARD, true),
            initialZoom = prefs.enum(KEY_ZOOM, InitialZoom.AUTO),
            smoothScaling = prefs.getBoolean(KEY_SMOOTH, true),
            renderQuality = prefs.enum(KEY_QUALITY, RenderQuality.HIGH),
            scanOnOpen = prefs.getBoolean(KEY_SCAN, true),
            filePreviews = prefs.getBoolean(KEY_PREVIEWS, true),
            autoCheckUpdates = prefs.getBoolean(KEY_AUTO_UPDATES, true),
        )

        /** Reads the language before the activity exists, for `attachBaseContext`. */
        fun peekLanguage(context: Context): Language =
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE).enum(KEY_LANGUAGE, Language.SYSTEM)
    }
}

/**
 * Applies the chosen language by overriding the configuration handed to the activity.
 *
 * Doing it this way works identically on every supported release, unlike the per-app locale
 * APIs, which only arrived in Android 13.
 */
fun Context.withLanguage(language: Language): Context {
    val tag = language.tag ?: return this
    val locale = Locale.forLanguageTag(tag)
    Locale.setDefault(locale)
    val configuration = Configuration(resources.configuration)
    configuration.setLocale(locale)
    configuration.setLayoutDirection(locale)
    return createConfigurationContext(configuration)
}
