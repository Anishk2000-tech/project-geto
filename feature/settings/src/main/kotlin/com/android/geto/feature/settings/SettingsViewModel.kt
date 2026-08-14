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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.geto.domain.model.GrantMethod
import com.android.geto.domain.model.ProtectionPauseDuration
import com.android.geto.domain.model.ProtectionPauseState
import com.android.geto.domain.model.ProtectionResult
import com.android.geto.domain.model.ShizukuGrantResult
import com.android.geto.domain.model.ShizukuState
import com.android.geto.domain.model.Theme
import com.android.geto.domain.model.UserData
import com.android.geto.domain.repository.UserDataRepository
import com.android.geto.domain.usecase.ProtectionController
import com.android.geto.domain.usecase.ShizukuPermissionController
import com.android.geto.framework.notificationmanager.AndroidNotificationManagerWrapper
import com.android.geto.framework.notificationmanager.AndroidNotificationManagerWrapper.Companion.ONE_SHOT_NOTIFICATION_ID
import com.android.geto.service.ArmedAppStatus
import com.android.geto.service.ForegroundProtectionCoordinator
import com.android.geto.service.ProtectionService
import com.android.geto.service.ProtectionServiceManager
import com.android.geto.service.ProtectionServiceRuntime
import com.android.geto.service.ProtectionServiceState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted.Companion.WhileSubscribed
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModel @Inject constructor(
    private val userDataRepository: UserDataRepository,
    private val protectionController: ProtectionController,
    private val protectionServiceManager: ProtectionServiceManager,
    private val notificationManager: AndroidNotificationManagerWrapper,
    private val shizukuPermissionController: ShizukuPermissionController,
    private val foregroundProtectionCoordinator: ForegroundProtectionCoordinator,
    protectionServiceRuntime: ProtectionServiceRuntime,
) : ViewModel() {
    private val retryCount = MutableStateFlow(0)

    val settingsUiState = retryCount.flatMapLatest {
        userDataRepository.userData
            .map<UserData, SettingsUiState>(SettingsUiState::Success)
            .catch { emit(SettingsUiState.Error(it.message)) }
    }.stateIn(
        scope = viewModelScope,
        started = WhileSubscribed(5_000),
        initialValue = SettingsUiState.Loading,
    )

    val isServiceRunning = ProtectionService.isRunning.stateIn(
        scope = viewModelScope,
        started = WhileSubscribed(5_000),
        initialValue = false,
    )

    /**
     * Armed apps, not sessions. The old source could only ever be empty on this screen, because a
     * session exists solely while its app is in the foreground — and Geto is the foreground app
     * whenever the user is reading this.
     */
    val armedApps = protectionServiceRuntime.armedApps.stateIn(
        scope = viewModelScope,
        started = WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    val protectionState = protectionServiceRuntime.state.stateIn(
        scope = viewModelScope,
        started = WhileSubscribed(5_000),
        initialValue = ProtectionServiceState.Loading,
    )

    val preferencesWereReset = userDataRepository.preferencesWereReset.stateIn(
        scope = viewModelScope,
        started = WhileSubscribed(5_000),
        initialValue = false,
    )

    private val protectionMessages = Channel<ProtectionMessage>(Channel.BUFFERED)

    val protectionResults = protectionMessages.receiveAsFlow()

    val shizukuState = shizukuPermissionController.shizukuState.stateIn(
        scope = viewModelScope,
        started = WhileSubscribed(5_000),
        initialValue = ShizukuState.Unknown,
    )

    /**
     * Without the detector nothing is ever applied, so this has to be visible rather than leaving the
     * user to wonder why an armed profile does nothing.
     */
    private val _isAppSwitchDetectorEnabled =
        MutableStateFlow(foregroundProtectionCoordinator.isDetectorEnabled)

    val isAppSwitchDetectorEnabled = _isAppSwitchDetectorEnabled.asStateFlow()

    private val _hasWriteSecureSettings =
        MutableStateFlow(shizukuPermissionController.hasWriteSecureSettingsPermission())

    val hasWriteSecureSettings = _hasWriteSecureSettings.asStateFlow()

    private val _isGranting = MutableStateFlow(false)

    val isGranting = _isGranting.asStateFlow()

    /**
     * One-shot grant outcomes. A channel rather than a state holder, so granting twice with the
     * same outcome still reaches the user both times instead of being conflated away.
     */
    private val grantResultChannel = Channel<ShizukuGrantResult>(Channel.BUFFERED)

    val grantResults = grantResultChannel.receiveAsFlow()

    fun startObservingShizuku() {
        shizukuPermissionController.start()
    }

    fun stopObservingShizuku() {
        shizukuPermissionController.stop()
    }

    /** Re-reads both permission states, e.g. after returning from the Shizuku app. */
    fun refreshPermissionState() {
        _isAppSwitchDetectorEnabled.value = foregroundProtectionCoordinator.isDetectorEnabled
        shizukuPermissionController.refresh()
        _hasWriteSecureSettings.value = shizukuPermissionController.hasWriteSecureSettingsPermission()
    }

    fun grantWithShizuku() {
        if (_isGranting.value) return

        viewModelScope.launch {
            _isGranting.value = true
            try {
                val result = shizukuPermissionController.grant()
                _hasWriteSecureSettings.value =
                    shizukuPermissionController.hasWriteSecureSettingsPermission()
                grantResultChannel.send(result)
            } finally {
                _isGranting.value = false
            }
        }
    }

    fun updateGrantMethod(grantMethod: GrantMethod) {
        viewModelScope.launch {
            userDataRepository.updateGrantMethod(grantMethod = grantMethod)
        }
    }

    fun updateTheme(theme: Theme) {
        viewModelScope.launch {
            userDataRepository.updateTheme(theme = theme)
        }
    }

    fun updateDynamicTheme(dynamicTheme: Boolean) {
        viewModelScope.launch {
            userDataRepository.updateDynamicTheme(dynamicTheme = dynamicTheme)
        }
    }

    fun updateAutoRestartProtection(autoRestartProtection: Boolean) {
        viewModelScope.launch {
            userDataRepository.updateAutoRestartProtection(autoRestartProtection)
        }
    }

    fun retry() {
        retryCount.value += 1
    }

    fun resetPreferences() {
        viewModelScope.launch {
            userDataRepository.resetUserPreferences()
            retry()
        }
    }

    fun acknowledgePreferencesReset() {
        userDataRepository.acknowledgePreferencesReset()
    }

    fun resumeProtection() {
        protectionServiceManager.onProtectionEnabled()
    }

    fun pauseProtection(sessionToken: String, duration: ProtectionPauseDuration) {
        viewModelScope.launch {
            protectionController.pause(sessionToken = sessionToken, duration = duration)
        }
    }

    fun resumeProtection(sessionToken: String) {
        viewModelScope.launch {
            protectionController.resume(sessionToken)
        }
    }

    /** Disarms an app, restoring its originals if it happens to be applied right now. */
    fun disarmApp(componentName: String) {
        viewModelScope.launch {
            val result = protectionController.disarmProfile(componentName)
            protectionMessages.send(result.asProtectionMessage())
        }
    }

    /**
     * A restore can genuinely fail — a revoked permission, a ROM that rejects the write — and the
     * old code dropped that on the floor, so the button looked broken. The outcome goes to a
     * snackbar instead.
     */

    fun turnOffAndRestoreProtection(sessionToken: String) {
        viewModelScope.launch {
            val result = protectionController.restore(sessionToken)
            if (result is ProtectionResult.Success) {
                notificationManager.cancel(ONE_SHOT_NOTIFICATION_ID)
            }
            protectionMessages.send(result.asProtectionMessage())
        }
    }
}

/** What to tell the user about a protection action. */
enum class ProtectionMessage {
    RESTORED,
    RESTORE_PERMISSION_DENIED,
    RESTORE_FAILED,
    ALREADY_INACTIVE,
}

private fun ProtectionResult.asProtectionMessage(): ProtectionMessage = when (this) {
    is ProtectionResult.Success -> ProtectionMessage.RESTORED
    ProtectionResult.NoActiveProtection -> ProtectionMessage.ALREADY_INACTIVE
    is ProtectionResult.StaleSession -> ProtectionMessage.ALREADY_INACTIVE
    is ProtectionResult.PermissionDenied -> ProtectionMessage.RESTORE_PERMISSION_DENIED
    else -> ProtectionMessage.RESTORE_FAILED
}
