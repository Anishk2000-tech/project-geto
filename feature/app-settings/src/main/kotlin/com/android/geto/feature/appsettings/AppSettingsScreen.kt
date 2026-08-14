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
package com.android.geto.feature.appsettings

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.PendingIntent.FLAG_IMMUTABLE
import android.app.PendingIntent.FLAG_UPDATE_CURRENT
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.BottomAppBarDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.geto.broadcastreceiver.RevertSettingsBroadcastReceiver
import com.android.geto.designsystem.icon.GetoIcons
import com.android.geto.domain.model.AddAppSettingResult
import com.android.geto.domain.model.AppSetting
import com.android.geto.domain.model.AppSettingTemplate
import com.android.geto.domain.model.GetPinShortcutResult
import com.android.geto.domain.model.ProtectedApp
import com.android.geto.domain.model.ProtectionMode
import com.android.geto.domain.model.ProtectionResult
import com.android.geto.domain.model.ProtectionSessionStatus
import com.android.geto.domain.model.ProtectionState
import com.android.geto.domain.model.RequestPinShortcutResult
import com.android.geto.domain.model.SecureSetting
import com.android.geto.domain.model.SettingType
import com.android.geto.domain.model.UpdatePinShortcutResult
import com.android.geto.domain.model.isPaused
import com.android.geto.feature.appsettings.dialog.AppSettingDialog
import com.android.geto.feature.appsettings.dialog.RequestPinShortcutDialog
import com.android.geto.feature.appsettings.dialog.TemplateDialog
import com.android.geto.feature.appsettings.dialog.UpdatePinShortcutDialog
import com.android.geto.feature.appsettings.dialog.WriteSecureSettingsDialog
import com.android.geto.feature.appsettings.navigation.AppSettingsRouteData
import com.android.geto.framework.launcherapps.LaunchResult
import com.android.geto.framework.notificationmanager.AndroidNotificationManagerWrapper
import com.android.geto.framework.notificationmanager.AndroidNotificationManagerWrapper.Companion.ACTION_REVERT_SETTINGS
import com.android.geto.framework.notificationmanager.AndroidNotificationManagerWrapper.Companion.NOTIFICATION_EXTRA_COMPONENT_NAME
import com.android.geto.framework.notificationmanager.AndroidNotificationManagerWrapper.Companion.NOTIFICATION_EXTRA_NOTIFICATION_ID
import com.android.geto.framework.notificationmanager.AndroidNotificationManagerWrapper.Companion.NOTIFICATION_EXTRA_SESSION_TOKEN
import com.android.geto.framework.notificationmanager.AndroidNotificationManagerWrapper.Companion.ONE_SHOT_NOTIFICATION_ID
import com.android.geto.ui.local.LocalLauncherApps
import com.android.geto.ui.local.LocalNotificationManager
import kotlinx.coroutines.launch
import com.android.geto.common.R as commonR

@Composable
internal fun AppSettingsRoute(
    modifier: Modifier = Modifier,
    viewModel: AppSettingsViewModel = hiltViewModel(),
    appSettingsRouteData: AppSettingsRouteData,
    onNavigationIconClick: () -> Unit,
) {
    val context = LocalContext.current
    val notificationManager = LocalNotificationManager.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var pendingNotificationAction by
        rememberSaveable { mutableStateOf<NotificationPermissionAction?>(null) }
    var pendingNotificationRequiredChannelId by rememberSaveable { mutableStateOf<String?>(null) }
    var notificationSettingsChannelId by rememberSaveable { mutableStateOf<String?>(null) }
    var showNotificationPermissionDialog by rememberSaveable { mutableStateOf(false) }

    fun clearPendingNotificationAction() {
        pendingNotificationAction = null
        pendingNotificationRequiredChannelId = null
    }

    fun executeNotificationAction(action: NotificationPermissionAction) {
        when (action) {
            NotificationPermissionAction.LAUNCH_ONCE -> viewModel.launchOnce()

            NotificationPermissionAction.ENABLE_PERSISTENT ->
                viewModel.setForegroundProtection(enabled = true)

            NotificationPermissionAction.RESUME_PERSISTENT -> viewModel.resumeProtection()
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val pendingAction = pendingNotificationAction
        val requiredChannelId = pendingNotificationRequiredChannelId
        val missingChannelId = requiredChannelId?.takeUnless {
            notificationManager.isNotificationChannelEnabled(it)
        }
        if (granted && notificationManager.areNotificationsEnabled() && missingChannelId == null) {
            clearPendingNotificationAction()
            pendingAction?.let(::executeNotificationAction)
        } else {
            notificationSettingsChannelId = missingChannelId
            showNotificationPermissionDialog = true
        }
    }

    fun runWithNotificationPermission(
        requiredChannelId: String,
        action: NotificationPermissionAction,
    ) {
        val notificationsEnabled = notificationManager.areNotificationsEnabled()
        val runtimePermissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        val missingChannelId = requiredChannelId.takeUnless {
            notificationManager.isNotificationChannelEnabled(it)
        }

        when {
            notificationsEnabled && runtimePermissionGranted && missingChannelId == null ->
                executeNotificationAction(action)

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !runtimePermissionGranted -> {
                pendingNotificationAction = action
                pendingNotificationRequiredChannelId = requiredChannelId
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }

            else -> {
                pendingNotificationAction = action
                pendingNotificationRequiredChannelId = requiredChannelId
                notificationSettingsChannelId = missingChannelId
                showNotificationPermissionDialog = true
            }
        }
    }

    DisposableEffect(
        lifecycleOwner,
        pendingNotificationAction,
        pendingNotificationRequiredChannelId,
    ) {
        val observer = LifecycleEventObserver { _, event ->
            val action = pendingNotificationAction
            val requiredChannelId = pendingNotificationRequiredChannelId
            if (
                event == Lifecycle.Event.ON_RESUME &&
                action != null &&
                requiredChannelId != null &&
                notificationManager.areNotificationsEnabled() &&
                notificationManager.isNotificationChannelEnabled(requiredChannelId)
            ) {
                clearPendingNotificationAction()
                notificationSettingsChannelId = null
                showNotificationPermissionDialog = false
                executeNotificationAction(action)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val appSettingsUiState by viewModel.appSettingsUiState.collectAsStateWithLifecycle()

    val secureSettings by viewModel.secureSettings.collectAsStateWithLifecycle()

    val protectionActionResult by viewModel.protectionActionResult.collectAsStateWithLifecycle()
    val isArmed by viewModel.isArmed.collectAsStateWithLifecycle()
    val protectionState by viewModel.protectionState.collectAsStateWithLifecycle()
    val isProtectionServiceRunning by
        viewModel.isProtectionServiceRunning.collectAsStateWithLifecycle()

    val addAppSettingResult by viewModel.addAppSettingsResult.collectAsStateWithLifecycle()

    val activityIcon by viewModel.activityIcon.collectAsStateWithLifecycle()

    val requestPinShortcutResult by viewModel.requestPinShortcutResult.collectAsStateWithLifecycle()

    val appSettingTemplates by viewModel.appSettingTemplates.collectAsStateWithLifecycle()

    val getPinShortcutResult by viewModel.getPinShortcutResult.collectAsStateWithLifecycle()

    val updatePinShortcutResult by viewModel.updatePinShortcutResult.collectAsStateWithLifecycle()

    AppSettingsScreen(
        modifier = modifier,
        appSettingsRouteData = appSettingsRouteData,
        appSettingsUiState = appSettingsUiState,
        activityIcon = activityIcon,
        secureSettings = secureSettings,
        addAppSettingResult = addAppSettingResult,
        protectionActionResult = protectionActionResult,
        protectionState = protectionState,
        isArmed = isArmed,
        isProtectionServiceRunning = isProtectionServiceRunning,
        requestPinShortcutResult = requestPinShortcutResult,
        appSettingTemplates = appSettingTemplates,
        updatePinShortcutResult = updatePinShortcutResult,
        onLaunchOnce = {
            runWithNotificationPermission(
                requiredChannelId = AndroidNotificationManagerWrapper.NOTIFICATION_CHANNEL_ID,
                action = NotificationPermissionAction.LAUNCH_ONCE,
            )
        },
        onSetPersistentProtection = { enabled, _ ->
            // Arming writes nothing on its own, so there is no notification to ask about here; the
            // service and its notification only appear once the app is actually opened.
            viewModel.setForegroundProtection(enabled = enabled)
        },
        onRestoreAfterLaunchFailure = viewModel::restoreProtection,
        onResumeProtection = {
            runWithNotificationPermission(
                requiredChannelId =
                AndroidNotificationManagerWrapper.PROTECTION_NOTIFICATION_CHANNEL_ID,
                action = NotificationPermissionAction.RESUME_PERSISTENT,
            )
        },
        onCheckAppSetting = viewModel::checkAppSetting,
        onDeleteAppSetting = viewModel::deleteAppSetting,
        onAddAppSetting = viewModel::addAppSetting,
        onAddAppSettingTemplate = viewModel::addAppSettingTemplate,
        onRequestPinShortcut = viewModel::requestPinShortcut,
        onGetSecureSettingsByName = viewModel::getSecureSettingsByName,
        onResetProtectionActionResult = viewModel::resetProtectionActionResult,
        onResetRequestPinShortcutResult = viewModel::resetRequestPinShortcutResult,
        onResetAddAppSettingResult = viewModel::resetAddAppSettingResult,
        onNavigationIconClick = onNavigationIconClick,
        getPinShortcutResult = getPinShortcutResult,
        onGetPinShortcut = viewModel::getPinShorcut,
        onResetGetPinShortcutResult = viewModel::resetGetPinShortcutResult,
        onUpdatePinShortcut = viewModel::updatePinShorcut,
        onResetUpdatePinShortcutResult = viewModel::resetUpdatePinShortcutResult,
    )

    if (showNotificationPermissionDialog) {
        AlertDialog(
            onDismissRequest = {
                clearPendingNotificationAction()
                notificationSettingsChannelId = null
                showNotificationPermissionDialog = false
            },
            title = { Text(stringResource(R.string.permission)) },
            text = { Text(stringResource(R.string.notification_permission_required)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showNotificationPermissionDialog = false
                        val channelId = notificationSettingsChannelId
                        notificationSettingsChannelId = null
                        context.startActivity(notificationSettingsIntent(context, channelId))
                    },
                ) {
                    Text(stringResource(R.string.open_notification_settings))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        clearPendingNotificationAction()
                        notificationSettingsChannelId = null
                        showNotificationPermissionDialog = false
                    },
                ) {
                    Text(stringResource(commonR.string.cancel))
                }
            },
        )
    }
}

private enum class NotificationPermissionAction {
    LAUNCH_ONCE,
    ENABLE_PERSISTENT,
    RESUME_PERSISTENT,
}

private fun notificationSettingsIntent(context: Context, channelId: String?): Intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
    Intent(
        if (channelId != null) {
            Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS
        } else {
            Settings.ACTION_APP_NOTIFICATION_SETTINGS
        },
    ).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        channelId?.let { putExtra(Settings.EXTRA_CHANNEL_ID, it) }
    }
} else {
    Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    )
}

@VisibleForTesting
@Composable
internal fun AppSettingsScreen(
    modifier: Modifier = Modifier,
    appSettingsRouteData: AppSettingsRouteData,
    appSettingsUiState: AppSettingsUiState,
    activityIcon: ByteArray?,
    secureSettings: List<SecureSetting>,
    addAppSettingResult: AddAppSettingResult?,
    protectionActionResult: ProtectionActionResult?,
    protectionState: ProtectionState,
    isArmed: Boolean,
    isProtectionServiceRunning: Boolean,
    requestPinShortcutResult: RequestPinShortcutResult?,
    appSettingTemplates: List<AppSettingTemplate>,
    getPinShortcutResult: GetPinShortcutResult?,
    updatePinShortcutResult: UpdatePinShortcutResult?,
    onLaunchOnce: () -> Unit,
    onSetPersistentProtection: (enabled: Boolean, sessionToken: String?) -> Unit,
    onRestoreAfterLaunchFailure: (expectedSessionToken: String) -> Unit,
    onResumeProtection: () -> Unit,
    onCheckAppSetting: (appSetting: AppSetting) -> Unit,
    onDeleteAppSetting: (appSetting: AppSetting) -> Unit,
    onAddAppSetting: (AppSetting) -> Unit,
    onAddAppSettingTemplate: (AppSettingTemplate) -> Unit,
    onRequestPinShortcut: (
        icon: ByteArray?,
        shortLabel: String,
        longLabel: String,
    ) -> Unit,
    onGetSecureSettingsByName: (settingType: SettingType, text: String) -> Unit,
    onResetProtectionActionResult: () -> Unit,
    onResetRequestPinShortcutResult: () -> Unit,
    onResetAddAppSettingResult: () -> Unit,
    onNavigationIconClick: () -> Unit,
    onGetPinShortcut: () -> Unit,
    onResetGetPinShortcutResult: () -> Unit,
    onUpdatePinShortcut: (
        icon: ByteArray?,
        shortLabel: String,
        longLabel: String,
    ) -> Unit,
    onResetUpdatePinShortcutResult: () -> Unit,
) {
    var showAppSettingDialog by rememberSaveable { mutableStateOf(false) }

    var showTemplateDialog by rememberSaveable { mutableStateOf(false) }

    var showWriteSecureSettingsDialog by rememberSaveable { mutableStateOf(false) }
    var keyConflict by remember { mutableStateOf<ProtectionResult.KeyConflict?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val deletedMessage = stringResource(R.string.setting_deleted)
    val undoLabel = stringResource(R.string.undo)

    AppSettingsLaunchedEffects(
        appSettingsRouteData = appSettingsRouteData,
        snackbarHostState = snackbarHostState,
        activityIcon = activityIcon,
        addAppSettingResult = addAppSettingResult,
        protectionActionResult = protectionActionResult,
        requestPinShortcutResult = requestPinShortcutResult,
        getPinShortcutResult = getPinShortcutResult,
        updatePinShortcutResult = updatePinShortcutResult,
        onResetProtectionActionResult = onResetProtectionActionResult,
        onResetRequestPinShortcutResult = onResetRequestPinShortcutResult,
        onResetAddAppSettingResult = onResetAddAppSettingResult,
        onShowWriteSecureSettingsDialog = {
            showWriteSecureSettingsDialog = true
        },
        onResetGetPinShortcutResult = onResetGetPinShortcutResult,
        onResetUpdatePinShortcutResult = onResetUpdatePinShortcutResult,
        onKeyConflict = { keyConflict = it },
        onRestoreAfterLaunchFailure = onRestoreAfterLaunchFailure,
    )

    // Scoped to this component: another app being protected is not this screen's business.
    val thisApp = protectionState.forComponentName(appSettingsRouteData.componentName)
    // "Applied right now" — true only while this app is actually in the foreground.
    val isCurrentlyApplied = thisApp != null
    val protectionBusy = thisApp?.isBusy == true

    Scaffold(
        topBar = {
            AppSettingsTopAppBar(
                title = appSettingsRouteData.activityLabel,
                onNavigationIconClick = onNavigationIconClick,
            )
        },
        bottomBar = {
            AppSettingsBottomAppBar(
                restoreEnabled = isCurrentlyApplied && !protectionBusy,
                // Editing is only unsafe while the values are actually applied.
                editingEnabled = !isCurrentlyApplied && !protectionBusy,
                launchEnabled = !protectionBusy,
                onRefreshIconClick = {
                    thisApp?.sessionToken?.let { token ->
                        onSetPersistentProtection(false, token)
                    }
                },
                onSettingsIconClick = {
                    showAppSettingDialog = true
                },
                onShortcutIconClick = onGetPinShortcut,
                onSettingsSuggestIconClick = {
                    showTemplateDialog = true
                },
                onFloatingActionButtonClick = onLaunchOnce,
            )
        },
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState)
        },
    ) { innerPadding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            ProtectionStatusItem(
                app = thisApp,
                // The switch reflects whether the profile is *armed*, which persists. Reading it from
                // the session made it snap straight back off: a session exists only while the app is
                // in the foreground, and arming deliberately creates none.
                checked = isArmed,
                isServiceRunning = isProtectionServiceRunning,
                enabled = !protectionBusy,
                onCheckedChange = { checked -> onSetPersistentProtection(checked, null) },
            )

            if (
                isArmed &&
                thisApp?.status == ProtectionSessionStatus.ACTIVE &&
                !isProtectionServiceRunning
            ) {
                TextButton(
                    modifier = Modifier.align(Alignment.End),
                    onClick = onResumeProtection,
                ) {
                    Text(stringResource(R.string.resume_background_protection))
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                when (appSettingsUiState) {
                    AppSettingsUiState.Loading -> {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    }

                    is AppSettingsUiState.Success -> {
                        if (appSettingsUiState.appSettings.isNotEmpty()) {
                            Success(
                                appSettingsUiState = appSettingsUiState,
                                editingEnabled = !isCurrentlyApplied,
                                onCheckAppSetting = onCheckAppSetting,
                                onDeleteAppSettingsItem = onDeleteAppSetting,
                                onUndoDelete = { appSetting ->
                                    coroutineScope.launch {
                                        if (
                                            snackbarHostState.showSnackbar(
                                                message = deletedMessage,
                                                actionLabel = undoLabel,
                                                withDismissAction = true,
                                            ) == SnackbarResult.ActionPerformed
                                        ) {
                                            onCheckAppSetting(appSetting)
                                        }
                                    }
                                },
                            )
                        } else {
                            Empty(
                                title = stringResource(R.string.no_settings_found),
                                subtitle = stringResource(R.string.add_your_first_settings),
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAppSettingDialog) {
        AppSettingDialog(
            componentName = appSettingsRouteData.componentName,
            secureSettings = secureSettings,
            onAddAppSetting = onAddAppSetting,
            onDismissRequest = {
                showAppSettingDialog = false
            },
            onGetSecureSettingsByName = onGetSecureSettingsByName,
        )
    }

    if (showTemplateDialog) {
        TemplateDialog(
            appSettingTemplates = appSettingTemplates,
            onAddTemplate = onAddAppSettingTemplate,
            onDismissRequest = {
                showTemplateDialog = false
            },
        )
    }

    if (showWriteSecureSettingsDialog) {
        WriteSecureSettingsDialog(
            onDismissRequest = {
                showWriteSecureSettingsDialog = false
            },
        )
    }

    // Sharing a key with another app is normal; only a contradictory value lands here, and it is
    // reported before anything was written, so nothing needs undoing.
    keyConflict?.let { conflict ->
        val holder = conflict.holderComponentNames.firstOrNull()
        val holderLabel = holder
            ?.let { ComponentName.unflattenFromString(it)?.packageName ?: it }
            ?: stringResource(R.string.another_app)
        AlertDialog(
            onDismissRequest = { keyConflict = null },
            title = { Text(stringResource(R.string.key_conflict_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.key_conflict_message,
                        holderLabel,
                        conflict.key,
                        conflict.enforcedValue,
                        conflict.requestedValue,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = { keyConflict = null }) {
                    Text(stringResource(R.string.got_it))
                }
            },
        )
    }

    when (getPinShortcutResult) {
        GetPinShortcutResult.RequestPinShortcut -> {
            RequestPinShortcutDialog(
                icon = activityIcon,
                onDismissRequest = onResetGetPinShortcutResult,
                onRequestPinShortcut = onRequestPinShortcut,
            )
        }

        is GetPinShortcutResult.UpdatePinShortcut -> {
            UpdatePinShortcutDialog(
                icon = activityIcon,
                getoShortcutInfoCompat = getPinShortcutResult.getoShortcutInfoCompat,
                onDismissRequest = onResetGetPinShortcutResult,
                onUpdatePinShortcut = onUpdatePinShortcut,
            )
        }

        GetPinShortcutResult.UnsupportedLauncher, null -> Unit
    }
}

@Composable
private fun AppSettingsLaunchedEffects(
    appSettingsRouteData: AppSettingsRouteData,
    snackbarHostState: SnackbarHostState,
    activityIcon: ByteArray?,
    addAppSettingResult: AddAppSettingResult?,
    protectionActionResult: ProtectionActionResult?,
    requestPinShortcutResult: RequestPinShortcutResult?,
    getPinShortcutResult: GetPinShortcutResult?,
    updatePinShortcutResult: UpdatePinShortcutResult?,
    onResetProtectionActionResult: () -> Unit,
    onResetRequestPinShortcutResult: () -> Unit,
    onResetAddAppSettingResult: () -> Unit,
    onShowWriteSecureSettingsDialog: () -> Unit,
    onResetGetPinShortcutResult: () -> Unit,
    onResetUpdatePinShortcutResult: () -> Unit,
    onKeyConflict: (ProtectionResult.KeyConflict) -> Unit,
    onRestoreAfterLaunchFailure: (expectedSessionToken: String) -> Unit,
) {
    val context = LocalContext.current

    val androidLauncherAppsWrapper = LocalLauncherApps.current

    val androidNotificationManagerWrapper = LocalNotificationManager.current

    val appSettingsDisabled = stringResource(id = R.string.app_settings_disabled)

    val emptyAppSettingsList = stringResource(id = R.string.empty_app_settings_list)

    val getoSettings = stringResource(id = R.string.geto_settings)

    val applySuccess = stringResource(id = R.string.apply_success)

    val applyFailure = stringResource(id = R.string.apply_failure)

    val revertFailure = stringResource(id = R.string.revert_failure)

    val revertSuccess = stringResource(id = R.string.revert_success)

    val shortcutUpdateImmutableShortcuts =
        stringResource(id = R.string.shortcut_update_immutable_shortcuts)

    val shortcutUpdateFailed = stringResource(id = R.string.shortcut_update_failed)

    val shortcutUpdateSuccess = stringResource(id = R.string.shortcut_update_success)

    val supportedLauncher = stringResource(id = R.string.supported_launcher)

    val unsupportedLauncher = stringResource(id = R.string.unsupported_launcher)

    val invalidValues = stringResource(R.string.settings_has_invalid_values)

    val appSettingAddSuccess = stringResource(R.string.app_setting_added_successfully)

    val appSettingAddFailed = stringResource(R.string.app_setting_already_exists)
    val protectionActive = stringResource(R.string.protection_active)
    val protectionInactive = stringResource(R.string.protection_inactive)
    val launchFailed = stringResource(R.string.launch_failed)
    val rollbackFailed = stringResource(R.string.settings_rollback_failed)

    LaunchedEffect(protectionActionResult) {
        val actionResult = protectionActionResult ?: return@LaunchedEffect

        // Consume the result before anything below suspends. Every branch here shows a snackbar,
        // which suspends for its whole duration — clearing afterwards left the result live, so a
        // rotation in that window re-ran this effect and launched the target app a second time.
        onResetProtectionActionResult()

        when (val result = actionResult.result) {
            is ProtectionResult.Success -> when (actionResult.action) {
                ProtectionAction.LAUNCH_ONCE -> {
                    val activeApp = result.app
                    if (activeApp?.mode == ProtectionMode.ONE_SHOT) {
                        androidNotificationManagerWrapper.notify(
                            id = ONE_SHOT_NOTIFICATION_ID,
                            notification = getNotification(
                                context = context,
                                notificationId = ONE_SHOT_NOTIFICATION_ID,
                                sessionToken = activeApp.sessionToken,
                                componentName = appSettingsRouteData.componentName,
                                icon = activityIcon,
                                contentTitle = getoSettings,
                                contentText = applySuccess,
                            ),
                        )
                    }

                    if (
                        androidLauncherAppsWrapper.startMainActivity(
                            componentName = appSettingsRouteData.componentName,
                        ) !is LaunchResult.Success
                    ) {
                        if (activeApp?.mode == ProtectionMode.ONE_SHOT) {
                            onRestoreAfterLaunchFailure(activeApp.sessionToken)
                        }
                        snackbarHostState.showSnackbar(message = launchFailed)
                    }
                }

                ProtectionAction.ENABLE_PERSISTENT -> {
                    androidNotificationManagerWrapper.cancel(ONE_SHOT_NOTIFICATION_ID)
                    snackbarHostState.showSnackbar(message = protectionActive)
                }

                ProtectionAction.RESTORE -> {
                    androidNotificationManagerWrapper.cancel(ONE_SHOT_NOTIFICATION_ID)
                    snackbarHostState.showSnackbar(message = revertSuccess)
                }
            }

            ProtectionResult.EmptyProfile -> {
                snackbarHostState.showSnackbar(message = emptyAppSettingsList)
            }

            ProtectionResult.NoEnabledSettings -> {
                snackbarHostState.showSnackbar(message = appSettingsDisabled)
            }

            is ProtectionResult.PermissionDenied -> onShowWriteSecureSettingsDialog()

            is ProtectionResult.InvalidProfile -> {
                snackbarHostState.showSnackbar(message = invalidValues)
            }

            is ProtectionResult.KeyConflict -> onKeyConflict(result)

            is ProtectionResult.RecoveryRequired -> {
                if (
                    result.failures.any { failure ->
                        failure.reason == com.android.geto.domain.model.ProtectionFailureReason.PERMISSION_DENIED
                    }
                ) {
                    onShowWriteSecureSettingsDialog()
                }
                if (
                    actionResult.action == ProtectionAction.RESTORE &&
                    result.app.mode == ProtectionMode.ONE_SHOT
                ) {
                    androidNotificationManagerWrapper.notify(
                        id = ONE_SHOT_NOTIFICATION_ID,
                        notification = getNotification(
                            context = context,
                            notificationId = ONE_SHOT_NOTIFICATION_ID,
                            sessionToken = result.app.sessionToken,
                            componentName = result.app.componentName,
                            icon = activityIcon,
                            contentTitle = getoSettings,
                            contentText = rollbackFailed,
                        ),
                    )
                }
                snackbarHostState.showSnackbar(message = rollbackFailed)
            }

            ProtectionResult.NoActiveProtection -> {
                if (actionResult.action == ProtectionAction.RESTORE) {
                    snackbarHostState.showSnackbar(message = protectionInactive)
                }
            }

            is ProtectionResult.Failure,
            is ProtectionResult.KeyNotProtected,
            -> {
                snackbarHostState.showSnackbar(
                    message = if (actionResult.action == ProtectionAction.RESTORE) {
                        revertFailure
                    } else {
                        applyFailure
                    },
                )
            }

            else -> {
                snackbarHostState.showSnackbar(
                    message = if (actionResult.action == ProtectionAction.RESTORE) {
                        revertFailure
                    } else {
                        applyFailure
                    },
                )
            }
        }
    }

    LaunchedEffect(key1 = requestPinShortcutResult) {
        when (requestPinShortcutResult) {
            RequestPinShortcutResult.SupportedLauncher -> {
                snackbarHostState.showSnackbar(message = supportedLauncher)

                onResetRequestPinShortcutResult()
            }

            RequestPinShortcutResult.UnsupportedLauncher -> {
                snackbarHostState.showSnackbar(message = unsupportedLauncher)

                onResetRequestPinShortcutResult()
            }

            null -> Unit
        }
    }

    LaunchedEffect(key1 = addAppSettingResult) {
        when (addAppSettingResult) {
            AddAppSettingResult.Success -> {
                snackbarHostState.showSnackbar(message = appSettingAddSuccess)

                onResetAddAppSettingResult()
            }

            AddAppSettingResult.Failed -> {
                snackbarHostState.showSnackbar(message = appSettingAddFailed)

                onResetAddAppSettingResult()
            }

            null -> Unit
        }
    }

    LaunchedEffect(key1 = getPinShortcutResult) {
        if (getPinShortcutResult == GetPinShortcutResult.UnsupportedLauncher) {
            snackbarHostState.showSnackbar(message = unsupportedLauncher)

            onResetGetPinShortcutResult()
        }
    }

    LaunchedEffect(key1 = updatePinShortcutResult) {
        when (updatePinShortcutResult) {
            UpdatePinShortcutResult.UnsupportedLauncher -> {
                snackbarHostState.showSnackbar(message = unsupportedLauncher)

                onResetUpdatePinShortcutResult()
            }

            UpdatePinShortcutResult.UpdateSuccess -> {
                snackbarHostState.showSnackbar(message = shortcutUpdateSuccess)

                onResetUpdatePinShortcutResult()
            }

            UpdatePinShortcutResult.UpdateFailure -> {
                snackbarHostState.showSnackbar(message = shortcutUpdateFailed)

                onResetUpdatePinShortcutResult()
            }

            UpdatePinShortcutResult.UpdateImmutableShortcuts -> {
                snackbarHostState.showSnackbar(message = shortcutUpdateImmutableShortcuts)

                onResetUpdatePinShortcutResult()
            }

            null -> Unit
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppSettingsTopAppBar(
    modifier: Modifier = Modifier,
    title: String,
    onNavigationIconClick: () -> Unit,
) {
    TopAppBar(
        modifier = modifier,
        title = {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        navigationIcon = {
            IconButton(onClick = onNavigationIconClick) {
                Icon(
                    imageVector = GetoIcons.Back,
                    contentDescription = stringResource(R.string.back),
                )
            }
        },
    )
}

@Composable
private fun AppSettingsBottomAppBar(
    restoreEnabled: Boolean,
    editingEnabled: Boolean,
    launchEnabled: Boolean,
    onRefreshIconClick: () -> Unit,
    onSettingsIconClick: () -> Unit,
    onShortcutIconClick: () -> Unit,
    onSettingsSuggestIconClick: () -> Unit,
    onFloatingActionButtonClick: () -> Unit,
) {
    BottomAppBar(
        actions = {
            AppSettingsBottomAppBarActions(
                restoreEnabled = restoreEnabled,
                editingEnabled = editingEnabled,
                onRefreshIconClick = onRefreshIconClick,
                onSettingsIconClick = onSettingsIconClick,
                onShortcutIconClick = onShortcutIconClick,
                onSettingsSuggestIconClick = onSettingsSuggestIconClick,
            )
        },
        floatingActionButton = {
            AppSettingsFloatingActionButton(
                enabled = launchEnabled,
                onClick = onFloatingActionButtonClick,
            )
        },
    )
}

@Composable
private fun AppSettingsFloatingActionButton(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    FilledIconButton(
        modifier = Modifier.size(56.dp),
        enabled = enabled,
        onClick = onClick,
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = BottomAppBarDefaults.bottomAppBarFabColor,
        ),
    ) {
        Icon(
            imageVector = GetoIcons.ArrowForward,
            contentDescription = stringResource(R.string.launch_once),
        )
    }
}

@Composable
private fun AppSettingsBottomAppBarActions(
    restoreEnabled: Boolean,
    editingEnabled: Boolean,
    onRefreshIconClick: () -> Unit,
    onSettingsIconClick: () -> Unit,
    onShortcutIconClick: () -> Unit,
    onSettingsSuggestIconClick: () -> Unit,
) {
    IconButton(
        enabled = restoreEnabled,
        onClick = onRefreshIconClick,
    ) {
        Icon(
            imageVector = GetoIcons.Refresh,
            contentDescription = stringResource(R.string.restore_settings),
        )
    }

    IconButton(
        enabled = editingEnabled,
        onClick = onSettingsIconClick,
    ) {
        Icon(
            GetoIcons.Settings,
            contentDescription = stringResource(R.string.add_setting),
        )
    }

    IconButton(
        onClick = onShortcutIconClick,
    ) {
        Icon(
            GetoIcons.Shortcut,
            contentDescription = stringResource(R.string.manage_shortcut),
        )
    }

    IconButton(
        enabled = editingEnabled,
        onClick = onSettingsSuggestIconClick,
    ) {
        Icon(
            imageVector = GetoIcons.SettingsSuggest,
            contentDescription = stringResource(R.string.add_template),
        )
    }
}

@Composable
private fun Empty(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String,
) {
    Column(
        modifier = modifier
            .fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            modifier = Modifier.size(100.dp),
            imageVector = GetoIcons.Android,
            contentDescription = null,
        )

        Spacer(modifier = Modifier.height(10.dp))

        Text(text = title, style = MaterialTheme.typography.titleLarge)

        Spacer(modifier = Modifier.height(10.dp))

        Text(text = subtitle, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun Success(
    modifier: Modifier = Modifier,
    appSettingsUiState: AppSettingsUiState.Success,
    editingEnabled: Boolean,
    onCheckAppSetting: (AppSetting) -> Unit,
    onDeleteAppSettingsItem: (AppSetting) -> Unit,
    onUndoDelete: (AppSetting) -> Unit,
) {
    LazyColumn(modifier = modifier) {
        items(items = appSettingsUiState.appSettings, key = { it.id }) { appSettings ->
            AppSettingItem(
                appSetting = appSettings,
                enabled = editingEnabled,
                onCheckedChange = { check ->
                    onCheckAppSetting(
                        appSettings.copy(enabled = check),
                    )
                },
                onDeleteClick = {
                    onDeleteAppSettingsItem(appSettings)
                    onUndoDelete(appSettings)
                },
            )
        }
    }
}

@Composable
private fun LazyItemScope.AppSettingItem(
    modifier: Modifier = Modifier,
    appSetting: AppSetting,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onDeleteClick: () -> Unit,
) {
    ListItem(
        modifier = modifier
            .animateItem()
            .toggleable(
                value = appSetting.enabled,
                enabled = enabled,
                role = Role.Checkbox,
                onValueChange = onCheckedChange,
            ),
        headlineContent = {
            Text(
                text = appSetting.label,
            )
        },
        overlineContent = {
            Text(
                text = appSetting.key,
            )
        },
        supportingContent = {
            Text(
                text = appSetting.settingType.getSettingTypeTitle(),
            )
        },
        leadingContent = {
            Checkbox(
                checked = appSetting.enabled,
                onCheckedChange = null,
            )
        },
        trailingContent = {
            IconButton(
                enabled = enabled,
                onClick = onDeleteClick,
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource(
                        R.string.delete_named_setting,
                        appSetting.label,
                    ),
                )
            }
        },
    )
}

@Composable
private fun ProtectionStatusItem(
    app: ProtectedApp?,
    checked: Boolean,
    isServiceRunning: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    // Every branch describes THIS app only. Previously a different app's Starting/Restoring/
    // RecoveryRequired state leaked in here and made an unrelated screen look broken.
    val status = when {
        // Armed but not applied is the normal resting state: waiting for the app to be opened.
        app == null && checked -> stringResource(R.string.protection_waiting)

        app == null -> stringResource(R.string.protection_inactive)

        app.status == ProtectionSessionStatus.STARTING ->
            stringResource(R.string.protection_starting)

        app.status == ProtectionSessionStatus.RESTORING ->
            stringResource(R.string.protection_restoring)

        app.status == ProtectionSessionStatus.RECOVERY_REQUIRED ->
            stringResource(R.string.protection_recovery_required)

        app.pause.isPaused -> stringResource(R.string.protection_paused_status)

        app.mode == ProtectionMode.ONE_SHOT -> stringResource(R.string.one_shot_active)

        else -> stringResource(R.string.protection_active)
    }
    val supportingText = when {
        app == null -> stringResource(R.string.keep_profile_active_summary)

        app.mode == ProtectionMode.FOREGROUND &&
            app.status == ProtectionSessionStatus.ACTIVE &&
            !isServiceRunning -> stringResource(R.string.background_protection_stopped)

        else -> stringResource(R.string.turn_off_to_edit)
    }

    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            ),
        headlineContent = { Text(stringResource(R.string.keep_profile_active)) },
        overlineContent = { Text(status) },
        supportingContent = { Text(supportingText) },
        trailingContent = {
            Switch(
                checked = checked,
                enabled = enabled,
                onCheckedChange = null,
            )
        },
    )
}

private fun getNotification(
    context: Context,
    notificationId: Int,
    sessionToken: String,
    componentName: String,
    icon: ByteArray?,
    contentTitle: String,
    contentText: String,
): Notification {
    val revertIntent = Intent(context, RevertSettingsBroadcastReceiver::class.java).apply {
        action = ACTION_REVERT_SETTINGS
        data = "geto://restore/${Uri.encode(sessionToken)}".toUri()
        putExtra(NOTIFICATION_EXTRA_COMPONENT_NAME, componentName)
        putExtra(NOTIFICATION_EXTRA_NOTIFICATION_ID, notificationId)
        putExtra(NOTIFICATION_EXTRA_SESSION_TOKEN, sessionToken)
    }

    val revertPendingIntent = PendingIntent.getBroadcast(
        context,
        sessionToken.hashCode(),
        revertIntent,
        FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE,
    )
    val contentPendingIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
        PendingIntent.getActivity(
            context,
            notificationId,
            it,
            FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE,
        )
    }

    return NotificationCompat.Builder(
        context,
        AndroidNotificationManagerWrapper.NOTIFICATION_CHANNEL_ID,
    ).apply {
        setSmallIcon(com.android.geto.framework.notificationmanager.R.drawable.baseline_settings_24)

        icon?.let {
            setLargeIcon(
                BitmapFactory.decodeByteArray(
                    icon,
                    0,
                    icon.size,
                ),
            )
        }

        setContentTitle(contentTitle)
        setContentText(contentText)
        setPriority(NotificationCompat.PRIORITY_DEFAULT)
        setCategory(NotificationCompat.CATEGORY_STATUS)
        setOnlyAlertOnce(true)
        setOngoing(true)
        contentPendingIntent?.let(::setContentIntent)
        addAction(
            com.android.geto.framework.notificationmanager.R.drawable.baseline_settings_24,
            context.getString(com.android.geto.framework.notificationmanager.R.string.revert),
            revertPendingIntent,
        )
    }.build()
}

@Composable
internal fun SettingType.getSettingTypeTitle() = when (this) {
    SettingType.SYSTEM -> stringResource(commonR.string.system)
    SettingType.SECURE -> stringResource(commonR.string.secure)
    SettingType.GLOBAL -> stringResource(commonR.string.global)
}
