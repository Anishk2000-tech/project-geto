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
package com.android.geto.domain.framework

import com.android.geto.domain.model.SecureSetting
import com.android.geto.domain.model.SettingReadResult
import com.android.geto.domain.model.SettingType
import com.android.geto.domain.model.SettingWriteResult

interface SecureSettingsWrapper {
    fun hasWriteSecureSettingsPermission(): Boolean

    suspend fun read(
        settingType: SettingType,
        key: String,
    ): SettingReadResult

    /** Writes a nullable value and verifies the effective value by reading it back. */
    suspend fun write(
        settingType: SettingType,
        key: String,
        value: String?,
    ): SettingWriteResult

    @Deprecated("Use write(), which reports verification failures")
    suspend fun canWriteSecureSettings(
        settingType: SettingType,
        key: String,
        value: String,
    ): Boolean = write(settingType, key, value) is SettingWriteResult.Success

    suspend fun getSecureSettings(settingType: SettingType): List<SecureSetting>
}
