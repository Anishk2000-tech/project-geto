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
package com.android.geto.feature.apps

import com.android.geto.domain.framework.SecureSettingsWrapper
import com.android.geto.domain.model.AppSetting
import com.android.geto.domain.model.ProtectedKey
import com.android.geto.domain.model.ProtectionSession
import com.android.geto.domain.model.ProtectionSessionStatus
import com.android.geto.domain.model.SecureSetting
import com.android.geto.domain.model.SettingReadResult
import com.android.geto.domain.model.SettingType
import com.android.geto.domain.model.SettingWriteResult
import com.android.geto.domain.repository.AppSettingsRepository
import com.android.geto.domain.repository.ProtectionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * The apps list only reads these to decide which tags to show, so nothing here needs behaviour —
 * only enough shape to construct the view model.
 */
internal object EmptyAppSettingsRepository : AppSettingsRepository {
    override val appSettingsFlow: Flow<List<AppSetting>> = flowOf(emptyList())
    override suspend fun upsertAppSetting(appSetting: AppSetting) = Unit
    override suspend fun upsertAppSettings(appSettings: List<AppSetting>) = Unit
    override suspend fun deleteAppSetting(appSetting: AppSetting) = Unit
    override fun getAppSettingsFlowByComponentName(componentName: String): Flow<List<AppSetting>> = flowOf(emptyList())
    override suspend fun getAppSettingsByComponentName(componentName: String): List<AppSetting> = emptyList()
}

internal object EmptyProtectionRepository : ProtectionRepository {
    override val armedComponentNamesFlow: Flow<Set<String>> = flowOf(emptySet())
    override suspend fun getArmedComponentNames(): Set<String> = emptySet()
    override suspend fun armProfile(componentName: String, armedAtMillis: Long) = Unit
    override suspend fun disarmProfile(componentName: String): Boolean = false
    override val sessionsFlow: Flow<List<ProtectionSession>> = flowOf(emptyList())
    override suspend fun getSessions(): List<ProtectionSession> = emptyList()
    override suspend fun getSessionByToken(token: String): ProtectionSession? = null
    override suspend fun getSessionByComponentName(componentName: String): ProtectionSession? = null
    override suspend fun getProtectedKeys(): List<ProtectedKey> = emptyList()
    override suspend fun getProtectedKey(settingType: SettingType, key: String): ProtectedKey? = null
    override suspend fun getClaimantComponentNames(settingType: SettingType, key: String): List<String> = emptyList()
    override suspend fun getKeysReleasedBy(token: String): List<ProtectedKey> = emptyList()
    override suspend fun getOrphanKeys(): List<ProtectedKey> = emptyList()
    override suspend fun deleteOrphanKeys(): Int = 0
    override suspend fun createSession(session: ProtectionSession, newKeys: List<ProtectedKey>) = Unit
    override suspend fun updateStatus(
        token: String,
        status: ProtectionSessionStatus,
        updatedAtMillis: Long,
        lastError: String?,
    ): Boolean = false
    override suspend fun updatePausedUntil(
        token: String,
        pausedUntilMillis: Long,
        updatedAtMillis: Long,
    ): Boolean = false
    override suspend fun clearSession(token: String): Boolean = false
}

internal object UnusedSecureSettingsWrapper : SecureSettingsWrapper {
    override fun hasWriteSecureSettingsPermission(): Boolean = false
    override suspend fun read(settingType: SettingType, key: String): SettingReadResult = SettingReadResult.Success(null)
    override suspend fun write(settingType: SettingType, key: String, value: String?): SettingWriteResult = SettingWriteResult.Success(value)
    override suspend fun getSecureSettings(settingType: SettingType): List<SecureSetting> = emptyList()
}
