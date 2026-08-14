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
package com.android.geto.activity.shortcut

import android.app.AlertDialog
import android.app.Notification
import android.app.PendingIntent
import android.app.PendingIntent.FLAG_IMMUTABLE
import android.app.PendingIntent.FLAG_UPDATE_CURRENT
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.android.geto.R
import com.android.geto.broadcastreceiver.RevertSettingsBroadcastReceiver
import com.android.geto.domain.framework.ShortcutManagerCompatWrapper
import com.android.geto.domain.model.ProtectionMode
import com.android.geto.domain.model.ProtectionResult
import com.android.geto.domain.model.ProtectionState
import com.android.geto.framework.launcherapps.AndroidLauncherAppsWrapper
import com.android.geto.framework.launcherapps.LaunchResult
import com.android.geto.framework.notificationmanager.AndroidNotificationManagerWrapper
import com.android.geto.framework.notificationmanager.AndroidNotificationManagerWrapper.Companion.ACTION_REVERT_SETTINGS
import com.android.geto.framework.notificationmanager.AndroidNotificationManagerWrapper.Companion.NOTIFICATION_EXTRA_COMPONENT_NAME
import com.android.geto.framework.notificationmanager.AndroidNotificationManagerWrapper.Companion.NOTIFICATION_EXTRA_NOTIFICATION_ID
import com.android.geto.framework.notificationmanager.AndroidNotificationManagerWrapper.Companion.NOTIFICATION_EXTRA_SESSION_TOKEN
import com.android.geto.framework.notificationmanager.AndroidNotificationManagerWrapper.Companion.ONE_SHOT_NOTIFICATION_ID
import com.android.geto.service.ProtectionServiceManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class ShortcutActivity : ComponentActivity() {
    @Inject
    lateinit var androidNotificationManagerWrapper: AndroidNotificationManagerWrapper

    @Inject
    lateinit var androidLauncherAppsWrapper: AndroidLauncherAppsWrapper

    @Inject
    lateinit var protectionServiceManager: ProtectionServiceManager

    private val viewModel: ShortcutActivityViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val componentName =
            intent.getStringExtra(ShortcutManagerCompatWrapper.SHORTCUT_EXTRA_COMPONENT_NAME)
                ?: run {
                    finish()
                    return
                }

        val oneShotChannelId = AndroidNotificationManagerWrapper.NOTIFICATION_CHANNEL_ID
        val notificationsEnabled = androidNotificationManagerWrapper.areNotificationsEnabled()
        val oneShotChannelEnabled =
            androidNotificationManagerWrapper.isNotificationChannelEnabled(oneShotChannelId)
        if (!notificationsEnabled || !oneShotChannelEnabled) {
            val openChannelSettings = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                notificationsEnabled &&
                !oneShotChannelEnabled
            AlertDialog.Builder(this)
                .setTitle(R.string.shortcut_notifications_required_title)
                .setMessage(R.string.shortcut_notifications_required_message)
                .setPositiveButton(R.string.open_notification_settings) { _, _ ->
                    val notificationSettingsIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        Intent(
                            if (openChannelSettings) {
                                Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS
                            } else {
                                Settings.ACTION_APP_NOTIFICATION_SETTINGS
                            },
                        ).apply {
                            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                            if (openChannelSettings) {
                                putExtra(Settings.EXTRA_CHANNEL_ID, oneShotChannelId)
                            }
                        }
                    } else {
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", packageName, null),
                        )
                    }
                    startActivity(notificationSettingsIntent)
                    finish()
                }
                .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
                .setOnCancelListener { finish() }
                .show()
            return
        }

        val notificationId = ONE_SHOT_NOTIFICATION_ID

        viewModel.applyAppSettings(componentName = componentName)

        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.shortcutActivityUiState.collect { shortcutActivityUiState ->
                    when (shortcutActivityUiState) {
                        ShortcutActivityUiState.Loading -> Unit

                        ShortcutActivityUiState.Error -> {
                            androidNotificationManagerWrapper.notify(
                                id = LAUNCH_FAILURE_NOTIFICATION_ID,
                                notification = getNotification(
                                    notificationId = LAUNCH_FAILURE_NOTIFICATION_ID,
                                    componentName = componentName,
                                    icon = null,
                                    contentTitle = getString(com.android.geto.feature.appsettings.R.string.geto_settings),
                                    contentText = getString(R.string.shortcut_load_failed),
                                    showRestoreAction = false,
                                ),
                            )
                            finish()
                        }

                        is ShortcutActivityUiState.Success -> {
                            when (val result = shortcutActivityUiState.protectionResult) {
                                is ProtectionResult.Success -> {
                                    val activeApp = result.app
                                    val oneShotToken = activeApp
                                        ?.takeIf { it.mode == ProtectionMode.ONE_SHOT }
                                        ?.sessionToken

                                    if (activeApp?.mode == ProtectionMode.FOREGROUND) {
                                        protectionServiceManager.onProtectionEnabled()
                                    }

                                    if (oneShotToken != null) {
                                        androidNotificationManagerWrapper.notify(
                                            id = notificationId,
                                            notification = getNotification(
                                                notificationId = notificationId,
                                                sessionToken = oneShotToken,
                                                componentName = componentName,
                                                icon = shortcutActivityUiState.applicationIcon,
                                                contentTitle = getString(com.android.geto.feature.appsettings.R.string.geto_settings),
                                                contentText = getString(com.android.geto.feature.appsettings.R.string.apply_success),
                                            ),
                                        )
                                    }

                                    if (
                                        androidLauncherAppsWrapper.startMainActivity(
                                            componentName = componentName,
                                        ) !is LaunchResult.Success
                                    ) {
                                        val restoreResult = oneShotToken?.let { token ->
                                            viewModel.restore(token)
                                        }
                                        if (restoreResult is ProtectionResult.Success) {
                                            androidNotificationManagerWrapper.cancel(notificationId)
                                        } else if (
                                            oneShotToken != null &&
                                            restoreResult !is ProtectionResult.StaleSession
                                        ) {
                                            androidNotificationManagerWrapper.notify(
                                                id = notificationId,
                                                notification = getNotification(
                                                    notificationId = notificationId,
                                                    sessionToken = oneShotToken,
                                                    componentName = componentName,
                                                    icon = shortcutActivityUiState.applicationIcon,
                                                    contentTitle = getString(com.android.geto.feature.appsettings.R.string.geto_settings),
                                                    contentText = getString(
                                                        com.android.geto.feature.appsettings.R.string.launch_failed_restore_failed,
                                                    ),
                                                ),
                                            )
                                        }
                                        androidNotificationManagerWrapper.notify(
                                            id = LAUNCH_FAILURE_NOTIFICATION_ID,
                                            notification = getNotification(
                                                notificationId = LAUNCH_FAILURE_NOTIFICATION_ID,
                                                componentName = componentName,
                                                icon = shortcutActivityUiState.applicationIcon,
                                                contentTitle = getString(com.android.geto.feature.appsettings.R.string.geto_settings),
                                                contentText = getString(com.android.geto.feature.appsettings.R.string.launch_failed),
                                                showRestoreAction = false,
                                            ),
                                        )
                                    }

                                    finish()
                                }

                                is ProtectionResult.PermissionDenied -> {
                                    androidNotificationManagerWrapper.notify(
                                        id = notificationId,
                                        notification = getNotification(
                                            notificationId = notificationId,
                                            componentName = componentName,
                                            icon = shortcutActivityUiState.applicationIcon,
                                            contentTitle = getString(com.android.geto.feature.appsettings.R.string.geto_settings),
                                            contentText = getString(com.android.geto.feature.appsettings.R.string.no_permission),
                                            showRestoreAction = false,
                                        ),
                                    )

                                    finish()
                                }

                                ProtectionResult.EmptyProfile -> {
                                    androidNotificationManagerWrapper.notify(
                                        id = notificationId,
                                        notification = getNotification(
                                            notificationId = notificationId,
                                            componentName = componentName,
                                            icon = shortcutActivityUiState.applicationIcon,
                                            contentTitle = getString(com.android.geto.feature.appsettings.R.string.geto_settings),
                                            contentText = getString(com.android.geto.feature.appsettings.R.string.empty_app_settings_list),
                                            showRestoreAction = false,
                                        ),
                                    )

                                    finish()
                                }

                                ProtectionResult.NoEnabledSettings -> {
                                    androidNotificationManagerWrapper.notify(
                                        id = notificationId,
                                        notification = getNotification(
                                            notificationId = notificationId,
                                            componentName = componentName,
                                            icon = shortcutActivityUiState.applicationIcon,
                                            contentTitle = getString(com.android.geto.feature.appsettings.R.string.geto_settings),
                                            contentText = getString(com.android.geto.feature.appsettings.R.string.app_settings_disabled),
                                            showRestoreAction = false,
                                        ),
                                    )

                                    finish()
                                }

                                is ProtectionResult.KeyConflict -> {
                                    androidNotificationManagerWrapper.notify(
                                        id = notificationId,
                                        notification = getNotification(
                                            notificationId = notificationId,
                                            componentName = componentName,
                                            icon = shortcutActivityUiState.applicationIcon,
                                            contentTitle = getString(com.android.geto.feature.appsettings.R.string.geto_settings),
                                            contentText = getString(com.android.geto.feature.appsettings.R.string.another_profile_active),
                                            showRestoreAction = false,
                                        ),
                                    )

                                    finish()
                                }

                                else -> {
                                    androidNotificationManagerWrapper.notify(
                                        id = notificationId,
                                        notification = getNotification(
                                            notificationId = notificationId,
                                            componentName = componentName,
                                            icon = shortcutActivityUiState.applicationIcon,
                                            contentTitle = getString(com.android.geto.feature.appsettings.R.string.geto_settings),
                                            contentText = getString(com.android.geto.feature.appsettings.R.string.apply_failure),
                                            showRestoreAction = false,
                                        ),
                                    )

                                    finish()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun getNotification(
        notificationId: Int,
        sessionToken: String? = null,
        componentName: String,
        icon: ByteArray?,
        contentTitle: String,
        contentText: String,
        showRestoreAction: Boolean = true,
    ): Notification {
        val revertIntent = Intent(this, RevertSettingsBroadcastReceiver::class.java).apply {
            action = ACTION_REVERT_SETTINGS
            data = "geto://restore/${Uri.encode(sessionToken.orEmpty())}".toUri()
            putExtra(NOTIFICATION_EXTRA_COMPONENT_NAME, componentName)
            putExtra(NOTIFICATION_EXTRA_NOTIFICATION_ID, notificationId)
            putExtra(NOTIFICATION_EXTRA_SESSION_TOKEN, sessionToken)
        }

        val revertPendingIntent = PendingIntent.getBroadcast(
            this,
            sessionToken?.hashCode() ?: notificationId,
            revertIntent,
            FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE,
        )
        val contentPendingIntent = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(
                this,
                notificationId,
                it,
                FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE,
            )
        }

        return NotificationCompat.Builder(
            this,
            AndroidNotificationManagerWrapper.NOTIFICATION_CHANNEL_ID,
        ).apply {
            setSmallIcon(com.android.geto.framework.notificationmanager.R.drawable.baseline_settings_24)

            icon?.let {
                setLargeIcon(Icon.createWithData(icon, 0, it.size))
            }

            setContentTitle(contentTitle)
            setContentText(contentText)
            setPriority(NotificationCompat.PRIORITY_DEFAULT)
            setCategory(NotificationCompat.CATEGORY_STATUS)
            setOnlyAlertOnce(true)
            setOngoing(showRestoreAction)
            setAutoCancel(!showRestoreAction)
            contentPendingIntent?.let(::setContentIntent)
            if (showRestoreAction && sessionToken != null) {
                addAction(
                    com.android.geto.framework.notificationmanager.R.drawable.baseline_settings_24,
                    getString(com.android.geto.framework.notificationmanager.R.string.revert),
                    revertPendingIntent,
                )
            }
        }.build()
    }

    private companion object {
        const val LAUNCH_FAILURE_NOTIFICATION_ID = 10_002
    }
}
