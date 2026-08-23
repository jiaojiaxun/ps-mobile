package com.psmobile

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.psmobile.browser.DocumentPreviews
import com.psmobile.browser.FileBrowserViewModel
import com.psmobile.document.DocumentOpener
import com.psmobile.settings.SettingsStore
import com.psmobile.settings.withLanguage
import com.psmobile.ui.browser.FileBrowserScreen
import com.psmobile.ui.error.FailureScreen
import com.psmobile.ui.home.HomeScreen
import com.psmobile.ui.loading.LoadingScreen
import com.psmobile.ui.newdoc.NewDocumentSheet
import com.psmobile.ui.settings.SettingsScreen
import com.psmobile.ui.theme.PsColors
import com.psmobile.ui.theme.PsMobileTheme
import com.psmobile.ui.viewer.ViewerScreen
import com.psmobile.update.UpdateState

class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels()
    private val browserViewModel: FileBrowserViewModel by viewModels()

    /** The chosen language has to be in place before any resource is resolved. */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase.withLanguage(SettingsStore.peekLanguage(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            PsMobileTheme {
                Box(Modifier.fillMaxSize().background(PsColors.Ink900)) {
                    PsMobileApp(viewModel, browserViewModel, ::recreate)
                }
            }
        }

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /** Documents can arrive from a file manager, a share sheet, or the launcher with nothing. */
    private fun handleIntent(intent: Intent?) {
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_VIEW, Intent.ACTION_EDIT -> intent.data
            Intent.ACTION_SEND -> @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> null
        }
        if (uri != null) viewModel.open(uri, intent?.flags ?: 0)
    }
}

@Composable
private fun PsMobileApp(
    viewModel: AppViewModel,
    browser: FileBrowserViewModel,
    onLanguageChanged: () -> Unit,
) {
    val context = LocalContext.current
    val recents by viewModel.recents.items.collectAsState()
    val settings by viewModel.settings.settings.collectAsState()
    var showNewDocument by remember { mutableStateOf(false) }

    val systemPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.open(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) browser.rememberTree(uri)
    }
    // All-files access is toggled in system settings, so the state is re-read on the way back.
    val accessLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { browser.refresh() }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                browser.refresh()
                // Coming back from the system installer is how we learn the outcome.
                viewModel.updates.refreshInstallOutcome()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    AnimatedContent(
        targetState = viewModel.screen,
        transitionSpec = {
            val forward = targetState !is AppScreen.Home
            if (forward) {
                (fadeIn(tween(220)) + scaleIn(tween(260), initialScale = 0.97f)) togetherWith
                    fadeOut(tween(160))
            } else {
                fadeIn(tween(220)) togetherWith
                    (fadeOut(tween(180)) + scaleOut(tween(220), targetScale = 0.98f))
            }
        },
        label = "screen",
        modifier = Modifier.fillMaxSize(),
    ) { screen ->
        when (screen) {
            is AppScreen.Home -> HomeScreen(
                recents = recents,
                updateVersion = (viewModel.updates.state as? UpdateState.Available)?.release?.versionName,
                onOpenFile = viewModel::openBrowser,
                onNewDocument = { showNewDocument = true },
                onOpenSettings = viewModel::openSettings,
                onOpenUpdate = viewModel::openSettings,
                onOpenRecent = { viewModel.open(Uri.parse(it.uri)) },
                onForgetRecent = { viewModel.forgetRecent(it.identity) },
                onClearRecents = viewModel::clearRecents,
            )

            is AppScreen.Browser -> {
                LaunchedEffect(settings.scanOnOpen) {
                    if (settings.scanOnOpen) browser.autoScanIfNeeded()
                }
                BackHandler(enabled = true) {
                    if (!browser.navigateUp()) viewModel.goHome()
                }
                FileBrowserScreen(
                    state = browser.state,
                    showPreviews = settings.filePreviews,
                    onBack = { if (!browser.navigateUp()) viewModel.goHome() },
                    onOpenTarget = browser::open,
                    onJumpTo = browser::jumpTo,
                    onOpenFile = { uri -> viewModel.open(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) },
                    onQueryChange = browser::setQuery,
                    onScan = browser::scanDevice,
                    onStopScan = browser::clearScan,
                    onGrantAllFiles = {
                        browser.allFilesAccessIntent()?.let { accessLauncher.launch(it) }
                    },
                    onPickFolder = { folderPicker.launch(null) },
                    onSystemPicker = { systemPicker.launch(DocumentOpener.PICKER_MIME_TYPES) },
                    onForgetTree = browser::forgetTree,
                )
            }

            is AppScreen.Settings -> {
                BackHandler(enabled = true) { viewModel.goHome() }
                SettingsScreen(
                    settings = settings,
                    hasAllFilesAccess = browser.state.hasAllFilesAccess,
                    appVersion = BuildConfig.VERSION_NAME,
                    updateState = viewModel.updates.state,
                    onBack = viewModel::goHome,
                    onChange = { next ->
                        val languageChanged = next.language != settings.language
                        viewModel.updateSettings(next)
                        // A language change means every resource has to be resolved again.
                        if (languageChanged) onLanguageChanged()
                    },
                    onGrantAccess = {
                        browser.allFilesAccessIntent()?.let { accessLauncher.launch(it) }
                    },
                    onClearRecents = viewModel::clearRecents,
                    onClearPreviewCache = { DocumentPreviews.clear() },
                    onCheckUpdates = { viewModel.updates.check(force = true) },
                    onInstallUpdate = {
                        val available = viewModel.updates.state
                        val release = (available as? UpdateState.Available)?.release
                            ?: (available as? UpdateState.NeedsPermission)?.release
                        if (release != null) viewModel.updates.installUpdate(release)
                    },
                    onGrantInstallPermission = { viewModel.updates.grantInstallPermission(context) },
                    onDismissUpdate = viewModel.updates::dismiss,
                )
            }

            is AppScreen.Loading -> LoadingScreen(
                title = screen.title,
                progress = viewModel.progress,
                onCancel = viewModel::cancelOpen,
            )

            is AppScreen.Viewer -> ViewerScreen(
                session = screen.session,
                settings = settings,
                onClose = viewModel::closeDocument,
            )

            is AppScreen.Failure -> FailureScreen(
                title = screen.title,
                message = screen.message,
                onRetry = screen.retryUri?.let { uri -> { viewModel.open(uri) } },
                onDismiss = viewModel::closeDocument,
            )
        }
    }

    if (showNewDocument) {
        NewDocumentSheet(
            onDismiss = { showNewDocument = false },
            onCreate = { spec ->
                showNewDocument = false
                viewModel.createBlank(spec)
            },
        )
    }
}
