/*
 *
 *   Copyright 2023 Einstein Blanco
 *
 *   Licensed under the GNU General Public License v3.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       https://www.gnu.org/licenses/gpl-3.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *
 */
package com.android.geto.feature.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.format.DateFormat
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.geto.designsystem.theme.supportsDynamicTheming
import com.android.geto.domain.model.GrantMethod
import com.android.geto.domain.model.ProtectionFailureReason
import com.android.geto.domain.model.ProtectionMode
import com.android.geto.domain.model.ProtectionPauseDuration
import com.android.geto.domain.model.ProtectionPauseState
import com.android.geto.domain.model.ProtectionSessionStatus
import com.android.geto.domain.model.ShizukuGrantResult
import com.android.geto.domain.model.ShizukuState
import com.android.geto.domain.model.Theme
import com.android.geto.domain.model.UserData
import com.android.geto.domain.model.isPaused
import com.android.geto.feature.settings.dialog.GrantMethodDialog
import com.android.geto.feature.settings.dialog.PauseProtectionDialog
import com.android.geto.feature.settings.dialog.ThemeDialog
import com.android.geto.framework.notificationmanager.AndroidNotificationManagerWrapper
import com.android.geto.service.ArmedAppStatus
import com.android.geto.service.ProtectedAppStatus
import com.android.geto.service.ProtectionServiceState
import com.android.geto.ui.local.LocalNotificationManager
import java.util.Date

@Composable
internal fun SettingsRoute(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
    snackbarHostState: SnackbarHostState,
) {
    val settingsUiState by viewModel.settingsUiState.collectAsStateWithLifecycle()
    val isServiceRunning by viewModel.isServiceRunning.collectAsStateWithLifecycle()
    val protectionState by viewModel.protectionState.collectAsStateWithLifecycle()
    val armedApps by viewModel.armedApps.collectAsStateWithLifecycle()
    val preferencesWereReset by viewModel.preferencesWereReset.collectAsStateWithLifecycle()
    val isAppSwitchDetectorEnabled by
        viewModel.isAppSwitchDetectorEnabled.collectAsStateWithLifecycle()
    val shizukuState by viewModel.shizukuState.collectAsStateWithLifecycle()
    val hasWriteSecureSettings by viewModel.hasWriteSecureSettings.collectAsStateWithLifecycle()
    val isGranting by viewModel.isGranting.collectAsStateWithLifecycle()
    val notificationManager = LocalNotificationManager.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    var serviceNotificationsEnabled by remember {
        mutableStateOf(notificationManager.protectionServiceNotificationsEnabled())
    }
    var protectionAlertsEnabled by remember {
        mutableStateOf(notificationManager.protectionAlertsEnabled())
    }

    DisposableEffect(lifecycleOwner, notificationManager) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                serviceNotificationsEnabled =
                    notificationManager.protectionServiceNotificationsEnabled()
                protectionAlertsEnabled = notificationManager.protectionAlertsEnabled()
                // The user may have started Shizuku, or run the ADB command, while we were away.
                viewModel.refreshPermissionState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Balanced by construction, so the reference count in the wrapper cannot drift.
    DisposableEffect(viewModel) {
        viewModel.startObservingShizuku()
        onDispose { viewModel.stopObservingShizuku() }
    }

    LaunchedEffect(viewModel, snackbarHostState) {
        viewModel.grantResults.collect { result ->
            snackbarHostState.showSnackbar(message = context.grantResultMessage(result))
        }
    }

    LaunchedEffect(viewModel, snackbarHostState) {
        viewModel.protectionResults.collect { message ->
            snackbarHostState.showSnackbar(message = context.protectionMessage(message))
        }
    }

    SettingsScreen(
        modifier = modifier,
        settingsUiState = settingsUiState,
        protectionState = protectionState,
        armedApps = armedApps,
        isServiceRunning = isServiceRunning,
        serviceNotificationsEnabled = serviceNotificationsEnabled,
        protectionAlertsEnabled = protectionAlertsEnabled,
        preferencesWereReset = preferencesWereReset,
        isAppSwitchDetectorEnabled = isAppSwitchDetectorEnabled,
        shizukuState = shizukuState,
        hasWriteSecureSettings = hasWriteSecureSettings,
        isGranting = isGranting,
        onDisarmApp = viewModel::disarmApp,
        onPauseProtection = viewModel::pauseProtection,
        onResumeFromPause = viewModel::resumeProtection,
        onUpdateTheme = viewModel::updateTheme,
        onUpdateDynamicTheme = viewModel::updateDynamicTheme,
        onUpdateAutoRestartProtection = viewModel::updateAutoRestartProtection,
        onUpdateGrantMethod = viewModel::updateGrantMethod,
        onGrantWithShizuku = viewModel::grantWithShizuku,
        onRetry = viewModel::retry,
        onResetPreferences = viewModel::resetPreferences,
        onAcknowledgePreferencesReset = viewModel::acknowledgePreferencesReset,
        onResumeProtection = viewModel::resumeProtection,
        onTurnOffProtection = viewModel::turnOffAndRestoreProtection,
    )
}

@VisibleForTesting
@Composable
internal fun SettingsScreen(
    modifier: Modifier = Modifier,
    settingsUiState: SettingsUiState,
    protectionState: ProtectionServiceState,
    armedApps: List<ArmedAppStatus>,
    isServiceRunning: Boolean,
    serviceNotificationsEnabled: Boolean,
    protectionAlertsEnabled: Boolean,
    preferencesWereReset: Boolean,
    isAppSwitchDetectorEnabled: Boolean,
    shizukuState: ShizukuState,
    hasWriteSecureSettings: Boolean,
    isGranting: Boolean,
    onDisarmApp: (String) -> Unit,
    onPauseProtection: (String, ProtectionPauseDuration) -> Unit,
    onResumeFromPause: (String) -> Unit,
    onUpdateTheme: (Theme) -> Unit,
    onUpdateDynamicTheme: (Boolean) -> Unit,
    onUpdateAutoRestartProtection: (Boolean) -> Unit,
    onUpdateGrantMethod: (GrantMethod) -> Unit,
    onGrantWithShizuku: () -> Unit,
    onRetry: () -> Unit,
    onResetPreferences: () -> Unit,
    onAcknowledgePreferencesReset: () -> Unit,
    onResumeProtection: () -> Unit,
    onTurnOffProtection: (String) -> Unit,
) {
    Box(modifier = modifier.fillMaxSize()) {
        when (settingsUiState) {
            SettingsUiState.Loading -> {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }

            is SettingsUiState.Error -> {
                SettingsError(
                    modifier = Modifier.align(Alignment.Center),
                    message = settingsUiState.message,
                    onRetry = onRetry,
                    onResetPreferences = onResetPreferences,
                )
            }

            is SettingsUiState.Success -> {
                Success(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    userData = settingsUiState.userData,
                    protectionState = protectionState,
                    armedApps = armedApps,
                    isServiceRunning = isServiceRunning,
                    serviceNotificationsEnabled = serviceNotificationsEnabled,
                    protectionAlertsEnabled = protectionAlertsEnabled,
                    preferencesWereReset = preferencesWereReset,
                    isAppSwitchDetectorEnabled = isAppSwitchDetectorEnabled,
                    shizukuState = shizukuState,
                    hasWriteSecureSettings = hasWriteSecureSettings,
                    isGranting = isGranting,
                    onDisarmApp = onDisarmApp,
                    onPauseProtection = onPauseProtection,
                    onResumeFromPause = onResumeFromPause,
                    onUpdateDynamicTheme = onUpdateDynamicTheme,
                    onUpdateTheme = onUpdateTheme,
                    onUpdateAutoRestartProtection = onUpdateAutoRestartProtection,
                    onUpdateGrantMethod = onUpdateGrantMethod,
                    onGrantWithShizuku = onGrantWithShizuku,
                    onAcknowledgePreferencesReset = onAcknowledgePreferencesReset,
                    onResumeProtection = onResumeProtection,
                    onTurnOffProtection = onTurnOffProtection,
                )
            }
        }
    }
}

@Composable
private fun SettingsError(
    modifier: Modifier = Modifier,
    message: String?,
    onRetry: () -> Unit,
    onResetPreferences: () -> Unit,
) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.could_not_load_settings),
            style = MaterialTheme.typography.titleMedium,
        )
        message?.takeIf(String::isNotBlank)?.let {
            Text(text = it, style = MaterialTheme.typography.bodySmall)
        }
        Button(onClick = onRetry) {
            Text(stringResource(R.string.retry))
        }
        TextButton(onClick = onResetPreferences) {
            Text(stringResource(R.string.reset_preferences))
        }
    }
}

@Composable
private fun Success(
    modifier: Modifier = Modifier,
    userData: UserData,
    protectionState: ProtectionServiceState,
    armedApps: List<ArmedAppStatus>,
    isServiceRunning: Boolean,
    serviceNotificationsEnabled: Boolean,
    protectionAlertsEnabled: Boolean,
    preferencesWereReset: Boolean,
    isAppSwitchDetectorEnabled: Boolean,
    shizukuState: ShizukuState,
    hasWriteSecureSettings: Boolean,
    isGranting: Boolean,
    onDisarmApp: (String) -> Unit,
    onPauseProtection: (String, ProtectionPauseDuration) -> Unit,
    onResumeFromPause: (String) -> Unit,
    onUpdateDynamicTheme: (Boolean) -> Unit,
    onUpdateTheme: (Theme) -> Unit,
    onUpdateAutoRestartProtection: (Boolean) -> Unit,
    onUpdateGrantMethod: (GrantMethod) -> Unit,
    onGrantWithShizuku: () -> Unit,
    onAcknowledgePreferencesReset: () -> Unit,
    onResumeProtection: () -> Unit,
    onTurnOffProtection: (String) -> Unit,
) {
    val context = LocalContext.current
    var showThemeDialog by rememberSaveable { mutableStateOf(false) }
    var selectedTheme by rememberSaveable(userData.theme) {
        mutableIntStateOf(Theme.entries.indexOf(userData.theme))
    }
    // Non-null while the pause dialog is open; holds which app it will pause.
    var pauseTargetToken by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedPauseDuration by rememberSaveable { mutableIntStateOf(0) }
    val writeSettingsCommand =
        "pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"

    Column(modifier = modifier.fillMaxSize()) {
        if (preferencesWereReset) {
            MessageCard(
                title = stringResource(R.string.preferences_recovered),
                text = stringResource(R.string.preferences_recovered_description),
                action = stringResource(R.string.dismiss),
                onAction = onAcknowledgePreferencesReset,
            )
        }

        // Any app blocked on the permission is worth a prominent card, since one fix unblocks them all.
        if (
            (protectionState as? ProtectionServiceState.Running)?.recoveryApps.orEmpty().any {
                it.message?.contains(ProtectionFailureReason.PERMISSION_DENIED.name) == true
            }
        ) {
            MessageCard(
                title = stringResource(R.string.write_settings_permission_required),
                text = stringResource(R.string.write_settings_permission_required_description),
                action = stringResource(R.string.copy_adb_command),
                onAction = {
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                        ClipData.newPlainText("ADB command", writeSettingsCommand),
                    )
                },
                isWarning = true,
            )
        }

        if (!serviceNotificationsEnabled || !protectionAlertsEnabled) {
            MessageCard(
                title = stringResource(R.string.notifications_disabled),
                text = stringResource(R.string.notifications_disabled_description),
                action = stringResource(R.string.open_settings),
                onAction = {
                    context.startActivity(notificationSettingsIntent(context.packageName))
                },
                isWarning = true,
            )
        }

        PermissionSection(
            grantMethod = userData.grantMethod,
            hasWriteSecureSettings = hasWriteSecureSettings,
            shizukuState = shizukuState,
            isGranting = isGranting,
            writeSettingsCommand = writeSettingsCommand,
            onUpdateGrantMethod = onUpdateGrantMethod,
            onGrantWithShizuku = onGrantWithShizuku,
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        AppSwitchingSection(isEnabled = isAppSwitchDetectorEnabled)

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        ProtectedAppsSection(
            armedApps = armedApps,
            isServiceRunning = isServiceRunning,
            serviceNotificationsEnabled = serviceNotificationsEnabled,
            onDisarmApp = onDisarmApp,
            onPauseRequested = { token ->
                pauseTargetToken = token
                selectedPauseDuration = 0
            },
            onResumeFromPause = onResumeFromPause,
            onTurnOffProtection = onTurnOffProtection,
            onResumeProtection = onResumeProtection,
        )
        ToggleSetting(
            title = stringResource(R.string.restart_protection_automatically),
            subtitle = stringResource(R.string.restart_protection_automatically_description),
            checked = userData.autoRestartProtection,
            onCheckedChange = onUpdateAutoRestartProtection,
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        SectionTitle(stringResource(R.string.appearance))
        DynamicThemeSetting(
            dynamicTheme = userData.dynamicTheme,
            onUpdateDynamicTheme = onUpdateDynamicTheme,
        )
        ListItem(
            modifier = Modifier.clickableWithRole {
                selectedTheme = Theme.entries.indexOf(userData.theme)
                showThemeDialog = true
            },
            headlineContent = { Text(stringResource(R.string.theme)) },
            supportingContent = { Text(userData.theme.getTitle()) },
        )
    }

    pauseTargetToken?.let { token ->
        PauseProtectionDialog(
            onDismissRequest = { pauseTargetToken = null },
            selected = selectedPauseDuration,
            onSelect = { selectedPauseDuration = it },
            onPauseClick = {
                onPauseProtection(token, ProtectionPauseDuration.entries[selectedPauseDuration])
                pauseTargetToken = null
            },
        )
    }

    if (showThemeDialog) {
        ThemeDialog(
            onDismissRequest = {
                selectedTheme = Theme.entries.indexOf(userData.theme)
                showThemeDialog = false
            },
            selected = selectedTheme,
            onSelect = { selectedTheme = it },
            onChangeClick = {
                onUpdateTheme(Theme.entries[selectedTheme])
                showThemeDialog = false
            },
        )
    }
}

/**
 * Whether Geto can see app switches at all.
 *
 * Nothing is applied without this, so an armed profile that silently does nothing is the most
 * confusing failure the app has. It gets its own section rather than a footnote.
 */
@Composable
private fun AppSwitchingSection(isEnabled: Boolean) {
    val context = LocalContext.current

    SectionTitle(stringResource(R.string.app_switching))

    ListItem(
        headlineContent = {
            Text(
                stringResource(
                    if (isEnabled) {
                        R.string.app_switch_detector_enabled
                    } else {
                        R.string.app_switch_detector_disabled
                    },
                ),
            )
        },
        supportingContent = { Text(stringResource(R.string.app_switch_detector_description)) },
    )

    if (!isEnabled) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            Button(
                onClick = {
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                },
            ) {
                Text(stringResource(R.string.turn_on_app_switching))
            }
        }
    }
}

/**
 * Every protected app, one row each.
 *
 * Several apps can be protected at once, so a single "protection status" line could never say which
 * app it meant. Each row names its app, its own state, and carries its own actions.
 */
@Composable
private fun ProtectedAppsSection(
    armedApps: List<ArmedAppStatus>,
    isServiceRunning: Boolean,
    serviceNotificationsEnabled: Boolean,
    onDisarmApp: (String) -> Unit,
    onPauseRequested: (String) -> Unit,
    onResumeFromPause: (String) -> Unit,
    onTurnOffProtection: (String) -> Unit,
    onResumeProtection: () -> Unit,
) {
    SectionTitle(stringResource(R.string.protection))

    if (armedApps.isEmpty()) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.no_apps_protected)) },
            supportingContent = { Text(stringResource(R.string.no_apps_protected_description)) },
        )
        return
    }

    armedApps.forEach { armed ->
        val applied = armed.applied

        if (applied == null) {
            // Armed and waiting. This is the state the screen could never show before, which is why
            // it always claimed nothing was protected.
            ListItem(
                overlineContent = { Text(stringResource(R.string.status_waiting)) },
                headlineContent = { Text(armed.label) },
                supportingContent = { Text(stringResource(R.string.status_waiting_description)) },
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = { onDisarmApp(armed.componentName) }) {
                    Text(stringResource(R.string.turn_off))
                }
            }
        } else {
            ProtectedAppItem(
                app = applied,
                isServiceRunning = isServiceRunning,
                serviceNotificationsEnabled = serviceNotificationsEnabled,
                onPauseRequested = onPauseRequested,
                onResumeFromPause = onResumeFromPause,
                onTurnOffProtection = onTurnOffProtection,
                onResumeProtection = onResumeProtection,
            )
        }
    }
}

@Composable
private fun ProtectedAppItem(
    app: ProtectedAppStatus,
    isServiceRunning: Boolean,
    serviceNotificationsEnabled: Boolean,
    onPauseRequested: (String) -> Unit,
    onResumeFromPause: (String) -> Unit,
    onTurnOffProtection: (String) -> Unit,
    onResumeProtection: () -> Unit,
) {
    // Only a persistent profile depends on the service; a one-shot does not.
    val serviceStopped = app.mode == ProtectionMode.FOREGROUND &&
        app.status == ProtectionSessionStatus.ACTIVE &&
        !isServiceRunning

    ListItem(
        overlineContent = { Text(app.statusText(serviceStopped)) },
        headlineContent = { Text(app.label) },
        supportingContent = {
            Text(
                pluralStringResource(
                    R.plurals.watching_settings,
                    app.settingCount,
                    app.settingCount,
                ),
            )
        },
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        TextButton(onClick = { onTurnOffProtection(app.token) }) {
            Text(
                stringResource(
                    when {
                        app.status == ProtectionSessionStatus.RECOVERY_REQUIRED ->
                            R.string.retry_restore

                        app.mode == ProtectionMode.ONE_SHOT -> R.string.restore_now

                        else -> R.string.turn_off_and_restore
                    },
                ),
            )
        }

        when {
            serviceStopped -> Button(
                enabled = serviceNotificationsEnabled,
                onClick = onResumeProtection,
            ) {
                Text(stringResource(R.string.resume))
            }

            app.pause.isPaused -> Button(onClick = { onResumeFromPause(app.token) }) {
                Text(stringResource(R.string.resume_protection))
            }

            app.mode == ProtectionMode.FOREGROUND &&
                app.status == ProtectionSessionStatus.ACTIVE ->
                TextButton(onClick = { onPauseRequested(app.token) }) {
                    Text(stringResource(R.string.pause_protection))
                }
        }
    }
}

@Composable
private fun ProtectedAppStatus.statusText(serviceStopped: Boolean): String = when {
    status == ProtectionSessionStatus.RECOVERY_REQUIRED ->
        stringResource(R.string.status_needs_attention)

    status == ProtectionSessionStatus.STARTING -> stringResource(R.string.status_starting)

    status == ProtectionSessionStatus.RESTORING -> stringResource(R.string.status_restoring)

    serviceStopped -> stringResource(R.string.status_service_stopped)

    pause is ProtectionPauseState.PausedUntil -> stringResource(
        R.string.status_paused_until,
        DateFormat.getTimeFormat(LocalContext.current)
            .format(Date((pause as ProtectionPauseState.PausedUntil).untilMillis)),
    )

    pause == ProtectionPauseState.PausedIndefinitely -> stringResource(R.string.status_paused)

    mode == ProtectionMode.ONE_SHOT -> stringResource(R.string.status_one_shot)

    else -> stringResource(R.string.status_protected)
}

/**
 * How the user gets `WRITE_SECURE_SETTINGS` onto Geto. The method is a stored preference, so it
 * stays visible once the permission is held; only the instructions for acting on it are hidden.
 */
@Composable
private fun PermissionSection(
    grantMethod: GrantMethod,
    hasWriteSecureSettings: Boolean,
    shizukuState: ShizukuState,
    isGranting: Boolean,
    writeSettingsCommand: String,
    onUpdateGrantMethod: (GrantMethod) -> Unit,
    onGrantWithShizuku: () -> Unit,
) {
    var showGrantMethodDialog by rememberSaveable { mutableStateOf(false) }
    var selectedGrantMethod by rememberSaveable(grantMethod) {
        mutableIntStateOf(GrantMethod.entries.indexOf(grantMethod))
    }

    SectionTitle(stringResource(R.string.permission))

    ListItem(
        headlineContent = { Text(stringResource(R.string.write_secure_settings)) },
        supportingContent = {
            Text(
                stringResource(
                    if (hasWriteSecureSettings) {
                        R.string.write_secure_settings_granted
                    } else {
                        R.string.write_secure_settings_not_granted
                    },
                ),
            )
        },
    )

    ListItem(
        modifier = Modifier.clickableWithRole {
            selectedGrantMethod = GrantMethod.entries.indexOf(grantMethod)
            showGrantMethodDialog = true
        },
        headlineContent = { Text(stringResource(R.string.grant_method)) },
        supportingContent = { Text(grantMethod.getTitle()) },
    )

    // Each method always renders something. Gating the whole block on the permission meant that once
    // it was granted, switching method changed nothing on screen and looked broken.
    when (grantMethod) {
        GrantMethod.ADB -> if (hasWriteSecureSettings) {
            ListItem(
                supportingContent = { Text(stringResource(R.string.nothing_to_grant)) },
                headlineContent = { Text(stringResource(R.string.grant_method_adb)) },
            )
        } else {
            AdbGrant(writeSettingsCommand = writeSettingsCommand)
        }

        GrantMethod.SHIZUKU -> ShizukuGrant(
            shizukuState = shizukuState,
            isGranting = isGranting,
            canGrant = !hasWriteSecureSettings,
            onGrantWithShizuku = onGrantWithShizuku,
        )
    }

    if (showGrantMethodDialog) {
        GrantMethodDialog(
            onDismissRequest = {
                selectedGrantMethod = GrantMethod.entries.indexOf(grantMethod)
                showGrantMethodDialog = false
            },
            selected = selectedGrantMethod,
            onSelect = { selectedGrantMethod = it },
            onChangeClick = {
                onUpdateGrantMethod(GrantMethod.entries[selectedGrantMethod])
                showGrantMethodDialog = false
            },
        )
    }
}

@Composable
private fun AdbGrant(writeSettingsCommand: String) {
    val context = LocalContext.current

    MessageCard(
        title = stringResource(R.string.adb_grant_instructions),
        text = writeSettingsCommand,
        action = stringResource(R.string.copy_adb_command),
        onAction = {
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                ClipData.newPlainText("ADB command", writeSettingsCommand),
            )
        },
    )
}

@Composable
private fun ShizukuGrant(
    shizukuState: ShizukuState,
    isGranting: Boolean,
    canGrant: Boolean,
    onGrantWithShizuku: () -> Unit,
) {
    val context = LocalContext.current
    val shizukuIntent = remember(context, shizukuState) {
        context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE_NAME)
    }

    ListItem(
        headlineContent = { Text(stringResource(R.string.shizuku)) },
        supportingContent = {
            Text(
                if (canGrant) {
                    shizukuState.getDescription()
                } else {
                    stringResource(R.string.nothing_to_grant)
                },
            )
        },
    )

    // Nothing left to grant, so no button — but the status above still shows, which is what makes
    // choosing this method visibly do something.
    if (!canGrant) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        when (shizukuState) {
            // Nothing in-app can fix this one; the supporting text already says what to do.
            ShizukuState.NotInstalled -> Unit

            ShizukuState.NotRunning,
            ShizukuState.OutdatedVersion,
            ShizukuState.PermissionDenied,
            -> Button(
                enabled = shizukuIntent != null,
                onClick = { shizukuIntent?.let(context::startActivity) },
            ) {
                Text(stringResource(R.string.open_shizuku))
            }

            ShizukuState.Unknown,
            ShizukuState.PermissionRequired,
            ShizukuState.Ready,
            -> Button(
                enabled = !isGranting && shizukuState != ShizukuState.Unknown,
                onClick = onGrantWithShizuku,
            ) {
                Text(
                    stringResource(
                        if (isGranting) R.string.granting else R.string.grant_with_shizuku,
                    ),
                )
            }
        }
    }
}

@Composable
private fun MessageCard(
    title: String,
    text: String,
    action: String,
    onAction: () -> Unit,
    isWarning: Boolean = false,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = if (isWarning) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        } else {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        },
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = text, style = MaterialTheme.typography.bodyMedium)
            TextButton(
                modifier = Modifier.align(Alignment.End),
                onClick = onAction,
            ) {
                Text(action)
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 4.dp),
        text = title,
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelLarge,
    )
}

@Composable
private fun ToggleSetting(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        modifier = Modifier.toggleable(
            value = checked,
            role = Role.Switch,
            onValueChange = onCheckedChange,
        ),
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = null,
            )
        },
    )
}

@Composable
private fun DynamicThemeSetting(
    dynamicTheme: Boolean,
    onUpdateDynamicTheme: (Boolean) -> Unit,
) {
    if (supportsDynamicTheming()) {
        ToggleSetting(
            title = stringResource(R.string.dynamic_theme),
            subtitle = stringResource(R.string.available_on_android_12),
            checked = dynamicTheme,
            onCheckedChange = onUpdateDynamicTheme,
        )
    }
}

private fun Context.protectionMessage(message: ProtectionMessage): String = when (message) {
    ProtectionMessage.RESTORED -> getString(R.string.protection_restored)

    ProtectionMessage.ALREADY_INACTIVE -> getString(R.string.protection_already_inactive)

    ProtectionMessage.RESTORE_FAILED -> getString(R.string.protection_restore_failed)

    ProtectionMessage.RESTORE_PERMISSION_DENIED ->
        getString(R.string.protection_restore_permission_denied)
}

private fun Modifier.clickableWithRole(onClick: () -> Unit): Modifier = clickable(
    role = Role.Button,
    onClick = onClick,
)

private fun AndroidNotificationManagerWrapper.protectionServiceNotificationsEnabled(): Boolean = areNotificationsEnabled() &&
    isNotificationChannelEnabled(
        AndroidNotificationManagerWrapper.PROTECTION_NOTIFICATION_CHANNEL_ID,
    )

private fun AndroidNotificationManagerWrapper.protectionAlertsEnabled(): Boolean = areNotificationsEnabled() &&
    isNotificationChannelEnabled(
        AndroidNotificationManagerWrapper.PROTECTION_ALERT_CHANNEL_ID,
    )

private fun notificationSettingsIntent(packageName: String): Intent = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
    }
} else {
    Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", packageName, null),
    )
}

@Composable
internal fun Theme.getTitle() = when (this) {
    Theme.FOLLOW_SYSTEM -> stringResource(R.string.follow_system)
    Theme.LIGHT -> stringResource(R.string.light)
    Theme.DARK -> stringResource(R.string.dark)
}

@Composable
internal fun ProtectionPauseDuration.getTitle() = when (this) {
    ProtectionPauseDuration.TEN_MINUTES -> stringResource(R.string.pause_ten_minutes)
    ProtectionPauseDuration.THIRTY_MINUTES -> stringResource(R.string.pause_thirty_minutes)
    ProtectionPauseDuration.ONE_HOUR -> stringResource(R.string.pause_one_hour)
    ProtectionPauseDuration.INDEFINITE -> stringResource(R.string.pause_indefinite)
}

@Composable
internal fun GrantMethod.getTitle() = when (this) {
    GrantMethod.ADB -> stringResource(R.string.grant_method_adb)
    GrantMethod.SHIZUKU -> stringResource(R.string.grant_method_shizuku)
}

@Composable
internal fun GrantMethod.getDescription() = when (this) {
    GrantMethod.ADB -> stringResource(R.string.grant_method_adb_description)
    GrantMethod.SHIZUKU -> stringResource(R.string.grant_method_shizuku_description)
}

@Composable
private fun ShizukuState.getDescription() = when (this) {
    ShizukuState.Unknown -> stringResource(R.string.shizuku_checking)
    ShizukuState.NotInstalled -> stringResource(R.string.shizuku_not_installed)
    ShizukuState.NotRunning -> stringResource(R.string.shizuku_not_running)
    ShizukuState.OutdatedVersion -> stringResource(R.string.shizuku_outdated)
    ShizukuState.PermissionRequired -> stringResource(R.string.shizuku_permission_required)
    ShizukuState.PermissionDenied -> stringResource(R.string.shizuku_permission_denied)
    ShizukuState.Ready -> stringResource(R.string.shizuku_ready)
}

private fun Context.grantResultMessage(result: ShizukuGrantResult): String = when (result) {
    ShizukuGrantResult.Success -> getString(R.string.grant_succeeded)

    ShizukuGrantResult.RequiresRestart -> getString(R.string.grant_requires_restart)

    // The Shizuku status row right above the button already spells out the state, so repeating it
    // in the snackbar would only be noise.
    is ShizukuGrantResult.Failure -> getString(R.string.grant_failed)

    is ShizukuGrantResult.Error ->
        result.message
            ?.let { getString(R.string.grant_failed_with_reason, it) }
            ?: getString(R.string.grant_failed)
}

private const val SHIZUKU_PACKAGE_NAME = "moe.shizuku.privileged.api"
