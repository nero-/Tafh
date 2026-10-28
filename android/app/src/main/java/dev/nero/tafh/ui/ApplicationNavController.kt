package dev.nero.tafh.ui

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.nero.tafh.data.repository.SettingsRepository
import dev.nero.tafh.ui.components.ChuButton
import dev.nero.tafh.ui.components.ChuButtonVariant
import dev.nero.tafh.ui.components.ChuText
import dev.nero.tafh.ui.screens.AddServer.AddServerScreen
import dev.nero.tafh.ui.screens.AddServer.AddServerViewModel
import dev.nero.tafh.ui.screens.Keys.KeysViewModel
import dev.nero.tafh.ui.screens.ServerList.ServerListScreen
import dev.nero.tafh.ui.screens.ServerList.ServerListViewModel
import dev.nero.tafh.ui.screens.Settings.SettingsBackupViewModel
import dev.nero.tafh.ui.screens.Settings.SettingsScreen
import dev.nero.tafh.ui.screens.Terminal.TerminalScreen
import dev.nero.tafh.ui.screens.Terminal.TerminalViewModel
import dev.nero.tafh.ui.security.VerificationResult
import dev.nero.tafh.ui.security.requireUserVerification
import dev.nero.tafh.ui.theme.ChuColors
import dev.nero.tafh.ui.theme.ChuTypography

@Composable
fun ApplicationNavController() {
    val navController = rememberNavController()
    val context = LocalContext.current
    val application = context.applicationContext as Application
    val lifecycleOwner = LocalLifecycleOwner.current
    val settingsRepo = SettingsRepository.getInstance(application)
    val appLockEnabled by settingsRepo.appLockEnabled.collectAsStateWithLifecycle()
    // Not rememberSaveable: an `appUnlocked = true` restored from a saved instance
    // state (process death, task restore) would bypass the lock on relaunch.
    var appUnlocked by remember { mutableStateOf(false) }
    var unlockPromptRequested by remember { mutableStateOf(false) }
    var appLockBlockedUntilToggle by remember { mutableStateOf(false) }

    if (!appLockEnabled) {
        appUnlocked = false
        unlockPromptRequested = false
        appLockBlockedUntilToggle = false
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { source, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                val isConfigChange =
                    (source as? android.app.Activity)?.isChangingConfigurations == true
                if (!isConfigChange) {
                    appUnlocked = false
                    unlockPromptRequested = false
                    appLockBlockedUntilToggle = false
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(appLockEnabled, appUnlocked, unlockPromptRequested, appLockBlockedUntilToggle) {
        if (
            appLockEnabled && !appUnlocked && !unlockPromptRequested && !appLockBlockedUntilToggle
        ) {
            unlockPromptRequested = true
            requireUserVerification(
                context = context,
                title = "Unlock Tafh",
                subtitle = "Authenticate to continue",
            ) { result ->
                appUnlocked = result == VerificationResult.Success
                if (result != VerificationResult.Success) {
                    appLockBlockedUntilToggle = true
                }
                unlockPromptRequested = false
            }
        }
    }

    if (appLockEnabled && !appUnlocked) {
        // Hard gate: while locked this is the only content composed, so no host
        // list, settings screen, or terminal is reachable without authentication.
        AppLockScreen(
            blocked = appLockBlockedUntilToggle,
            onUnlock = {
                // Clearing both flags re-arms the LaunchedEffect above, which
                // re-issues requireUserVerification.
                appLockBlockedUntilToggle = false
                unlockPromptRequested = false
            },
        )
        return
    }

    NavHost(navController = navController, startDestination = "servers") {
        composable("servers") {
            val vm: ServerListViewModel =
                viewModel(factory = ServerListViewModel.factory(application))
            val settingsRepo = SettingsRepository.getInstance(application)
            val requireAuthOnConnect by
                settingsRepo.requireAuthOnConnect.collectAsStateWithLifecycle()
            val localShellEnabled by settingsRepo.localShellEnabled.collectAsStateWithLifecycle()
            val hosts by vm.hosts.collectAsStateWithLifecycle()
            val searchQuery by vm.search.collectAsStateWithLifecycle()
            ServerListScreen(
                hosts = hosts,
                searchQuery = searchQuery,
                onSearchChange = vm::updateSearchQuery,
                onAddServer = { navController.navigate("servers/add") },
                localShellEnabled = localShellEnabled,
                onOpenLocalShell = localShell@{
                        if (!localShellEnabled) return@localShell
                        if (!requireAuthOnConnect) {
                            navController.navigate("terminal/local")
                        } else {
                            requireUserVerification(
                                context = context,
                                title = "Verify to open local shell",
                                subtitle = "Authenticate to open this device shell",
                            ) { result ->
                                if (result == VerificationResult.Success) {
                                    navController.navigate("terminal/local")
                                }
                            }
                        }
                    },
                onEditServer = { id -> navController.navigate("servers/edit/$id") },
                onConnectServer = { id ->
                    val host = hosts.firstOrNull { it.id == id }
                    val hostRequiresAuth = host?.requireAuthOnConnect == true
                    val mustVerify = requireAuthOnConnect || hostRequiresAuth
                    if (!mustVerify) {
                        navController.navigate("terminal/$id")
                    } else {
                        requireUserVerification(
                            context = context,
                            title = "Verify to connect",
                            subtitle = "Authenticate to open this server session",
                        ) { result ->
                            if (result == VerificationResult.Success) {
                                navController.navigate("terminal/$id")
                            }
                        }
                    }
                },
                onDeleteServer = vm::deleteServer,
                onOpenSettings = { navController.navigate("settings?restore=0") },
                onRestoreFromChuchu = { navController.navigate("settings?restore=1") },
            )
        }
        composable(
            route = "settings?restore={restore}",
            arguments = listOf(navArgument("restore") { defaultValue = "0" }),
        ) { entry ->
            val openBackupOnStart = entry.arguments?.getString("restore") == "1"
            val settingsRepo = SettingsRepository.getInstance(application)
            val backupViewModel: SettingsBackupViewModel =
                viewModel(factory = SettingsBackupViewModel.factory(application))
            val keysViewModel: KeysViewModel =
                viewModel(factory = KeysViewModel.factory(application))
            val themeName by settingsRepo.themeName.collectAsStateWithLifecycle()
            val fontName by settingsRepo.fontName.collectAsStateWithLifecycle()
            val appLockEnabled by settingsRepo.appLockEnabled.collectAsStateWithLifecycle()
            val requireAuthOnConnect by
                settingsRepo.requireAuthOnConnect.collectAsStateWithLifecycle()
            val accessoryLayoutIds by settingsRepo.accessoryLayoutIds.collectAsStateWithLifecycle()
            val accessoryBarSingleRow by
                settingsRepo.accessoryBarSingleRow.collectAsStateWithLifecycle()
            val customKeyGroups by
                settingsRepo.terminalCustomKeyGroups.collectAsStateWithLifecycle()
            val showCustomActionsFab by
                settingsRepo.showCustomActionsFab.collectAsStateWithLifecycle()
            val builtinShortcuts by settingsRepo.builtinShortcuts.collectAsStateWithLifecycle()
            val tabMode by settingsRepo.terminalTabMode.collectAsStateWithLifecycle()
            val localShellEnabled by settingsRepo.localShellEnabled.collectAsStateWithLifecycle()
            val keepScreenAwake by settingsRepo.keepScreenAwake.collectAsStateWithLifecycle()
            val hideScreenContents by settingsRepo.hideScreenContents.collectAsStateWithLifecycle()
            val disableAutocorrect by settingsRepo.disableAutocorrect.collectAsStateWithLifecycle()
            val remoteClipboardWriteEnabled by
                settingsRepo.remoteClipboardWriteEnabled.collectAsStateWithLifecycle()
            val themeMode by settingsRepo.themeMode.collectAsStateWithLifecycle()
            val terminalFontSize by settingsRepo.terminalFontSize.collectAsStateWithLifecycle()
            val lightThemeName by settingsRepo.lightThemeName.collectAsStateWithLifecycle()
            SettingsScreen(
                openBackupOnStart = openBackupOnStart,
                currentTheme = themeName,
                currentFont = fontName,
                appLockEnabled = appLockEnabled,
                requireAuthOnConnect = requireAuthOnConnect,
                localShellEnabled = localShellEnabled,
                keepScreenAwake = keepScreenAwake,
                hideScreenContents = hideScreenContents,
                disableAutocorrect = disableAutocorrect,
                currentAccessoryLayoutIds = accessoryLayoutIds,
                accessoryBarSingleRow = accessoryBarSingleRow,
                currentTerminalCustomKeyGroups = customKeyGroups,
                showCustomActionsFab = showCustomActionsFab,
                onShowCustomActionsFabChanged = settingsRepo::setShowCustomActionsFab,
                builtinShortcuts = builtinShortcuts,
                onBuiltinShortcutsChanged = settingsRepo::setBuiltinShortcuts,
                currentTabMode = tabMode,
                onTabModeChanged = settingsRepo::setTerminalTabMode,
                themeMode = themeMode,
                lightThemeName = lightThemeName,
                onThemeSelected = settingsRepo::setTheme,
                onThemeModeChanged = settingsRepo::setThemeMode,
                onLightThemeSelected = settingsRepo::setLightTheme,
                onFontSelected = settingsRepo::setFont,
                onAppLockEnabledChanged = settingsRepo::setAppLockEnabled,
                onRequireAuthOnConnectChanged = settingsRepo::setRequireAuthOnConnect,
                remoteClipboardWriteEnabled = remoteClipboardWriteEnabled,
                onRemoteClipboardWriteEnabledChanged = settingsRepo::setRemoteClipboardWriteEnabled,
                onLocalShellEnabledChanged = settingsRepo::setLocalShellEnabled,
                onKeepScreenAwakeChanged = settingsRepo::setKeepScreenAwake,
                onHideScreenContentsChanged = settingsRepo::setHideScreenContents,
                onDisableAutocorrectChanged = settingsRepo::setDisableAutocorrect,
                onAccessoryLayoutChanged = settingsRepo::setAccessoryLayoutIds,
                onAccessoryBarSingleRowChanged = settingsRepo::setAccessoryBarSingleRow,
                currentTerminalFontSize = terminalFontSize,
                onTerminalFontSizeChanged = settingsRepo::setTerminalFontSize,
                onTerminalCustomActionsChanged = settingsRepo::setTerminalCustomKeyGroups,
                backupViewModel = backupViewModel,
                keysViewModel = keysViewModel,
                // Comparing the raw route is fragile: the settings destination is declared with
                // the optional `restore` argument, so its route string never equals "settings".
                onBack = { navController.popBackStack() },
            )
        }
        composable("servers/add") {
            val vm: AddServerViewModel =
                viewModel(factory = AddServerViewModel.factory(application, null))
            AddServerScreen(vm = vm, onBack = { navController.popBackStack() })
        }
        composable(
            route = "servers/edit/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType }),
        ) { backStackEntry ->
            val id = backStackEntry.arguments?.getLong("id")
            val vm: AddServerViewModel =
                viewModel(factory = AddServerViewModel.factory(application, id))
            AddServerScreen(vm = vm, onBack = { navController.popBackStack() })
        }
        composable("terminal/local") {
            val settingsRepo = SettingsRepository.getInstance(application)
            val localShellEnabled by settingsRepo.localShellEnabled.collectAsStateWithLifecycle()
            if (localShellEnabled) {
                val vm: TerminalViewModel =
                    viewModel(factory = TerminalViewModel.factory(application))
                TerminalScreen(
                    vm = vm,
                    hostId = null,
                    openLocalShell = true,
                    onOpenSettings = { navController.navigate("settings?restore=0") },
                    onBack = { navController.popBackStack() },
                )
            } else {
                LaunchedEffect(Unit) {
                    val popped = navController.popBackStack("servers", inclusive = false)
                    if (!popped) {
                        navController.navigate("servers") { launchSingleTop = true }
                    }
                }
            }
        }
        composable(
            route = "terminal/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType }),
        ) { backStackEntry ->
            val id = backStackEntry.arguments?.getLong("id")
            val vm: TerminalViewModel = viewModel(factory = TerminalViewModel.factory(application))
            TerminalScreen(
                vm = vm,
                hostId = id,
                onOpenSettings = { navController.navigate("settings?restore=0") },
                onBack = { navController.popBackStack() },
            )
        }
    }
}

/**
 * Opaque full-screen cover shown while the app lock is enabled and the app is not unlocked. It is
 * the only content composed in that state, so no host list, tab, or terminal is reachable without
 * authentication.
 */
@Composable
private fun AppLockScreen(blocked: Boolean, onUnlock: () -> Unit) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    Box(
        modifier = Modifier.fillMaxSize().background(colors.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChuText("$ ", style = typography.headline, color = colors.textMuted)
                ChuText("Tafh", style = typography.headline)
            }
            ChuText(
                text =
                    if (blocked) {
                        "Authentication failed. Tap Unlock to try again."
                    } else {
                        "App lock is enabled. Authenticate to continue."
                    },
                style = typography.body,
                color = colors.textSecondary,
            )
            ChuButton(onClick = onUnlock, variant = ChuButtonVariant.Outlined, bracketed = true) {
                ChuText("Unlock", style = typography.label, color = colors.textSecondary)
            }
        }
    }
}
