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
package com.android.geto.service

import android.content.ComponentName
import android.content.Context
import com.android.geto.domain.model.ProtectedApp
import com.android.geto.domain.model.ProtectionMode
import com.android.geto.domain.model.ProtectionPauseDuration
import com.android.geto.domain.model.ProtectionResult
import com.android.geto.domain.model.ProtectionSessionStatus
import com.android.geto.domain.model.ProtectionState
import com.android.geto.domain.model.SettingType
import com.android.geto.domain.usecase.ProtectionController
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

internal class DomainProtectionServiceRuntime @Inject constructor(
    private val protectionController: ProtectionController,
    @param:ApplicationContext private val context: Context,
) : ProtectionServiceRuntime {

    /**
     * Resolving a label is a binder call, and with several protected apps it would otherwise run on
     * every database emission. Labels only change when a package is replaced, which is why
     * [invalidateLabels] exists and why the boot/package-replaced receiver calls it.
     */
    private val labelCache = ConcurrentHashMap<String, String>()

    override val state: Flow<ProtectionServiceState> = protectionController.state.map { state ->
        state.asServiceState()
    }

    override val armedApps: Flow<List<ArmedAppStatus>> = combine(
        protectionController.armedComponentNames,
        protectionController.state,
    ) { armed, state ->
        armed.sorted().map { componentName ->
            ArmedAppStatus(
                componentName = componentName,
                label = componentName.displayLabel(),
                applied = state.forComponentName(componentName)?.asStatus(),
            )
        }
    }

    override suspend fun reconcile() {
        protectionController.reconcile()
    }

    override suspend fun reapply(settingType: SettingType, key: String): Boolean = when (protectionController.reapply(type = settingType, key = key)) {
        is ProtectionResult.Success,
        is ProtectionResult.KeyNotProtected,
        ProtectionResult.NoActiveProtection,
        -> true

        else -> false
    }

    override suspend fun restore(sessionToken: String): Boolean = when (protectionController.restore(sessionToken)) {
        is ProtectionResult.Success,
        ProtectionResult.NoActiveProtection,
        // The session is already gone, which is the outcome the caller wanted.
        is ProtectionResult.StaleSession,
        -> true

        else -> false
    }

    override suspend fun pause(
        sessionToken: String,
        duration: ProtectionPauseDuration,
    ): Boolean = protectionController.pause(sessionToken, duration) is ProtectionResult.Success

    override suspend fun resume(sessionToken: String): Boolean = protectionController.resume(sessionToken) is ProtectionResult.Success

    override fun invalidateLabels() {
        labelCache.clear()
    }

    private fun ProtectionState.asServiceState(): ProtectionServiceState {
        if (apps.isEmpty()) return ProtectionServiceState.Inactive

        return ProtectionServiceState.Running(
            apps = apps.map { it.asStatus() },
            observedSettings = apps
                .filter { it.mode == ProtectionMode.FOREGROUND && it.isWatched }
                .flatMapTo(linkedSetOf()) { app ->
                    app.protectedSettings.map { setting ->
                        ObservedProtectionSetting(
                            settingType = setting.settingType,
                            key = setting.key,
                        )
                    }
                },
        )
    }

    private fun ProtectedApp.asStatus(): ProtectedAppStatus = ProtectedAppStatus(
        token = sessionToken,
        componentName = componentName,
        label = componentName.displayLabel(),
        mode = mode,
        status = status,
        pause = pause,
        settingCount = protectedSettings.size,
        message = lastError.takeIf { status == ProtectionSessionStatus.RECOVERY_REQUIRED },
    )

    private fun String.displayLabel(): String = labelCache.getOrPut(this) {
        val component = ComponentName.unflattenFromString(this) ?: return@getOrPut this
        runCatching {
            context.packageManager.getActivityInfo(component, 0).loadLabel(context.packageManager)
                .toString()
        }.getOrDefault(component.packageName)
    }
}
