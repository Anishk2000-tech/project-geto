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
package com.android.geto.framework.securesettings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.database.getLongOrNull
import androidx.core.database.getStringOrNull
import com.android.geto.domain.common.dispatcher.Dispatcher
import com.android.geto.domain.common.dispatcher.GetoDispatchers.IO
import com.android.geto.domain.framework.SecureSettingsWrapper
import com.android.geto.domain.model.ProtectionFailureReason
import com.android.geto.domain.model.SecureSetting
import com.android.geto.domain.model.SettingReadResult
import com.android.geto.domain.model.SettingType
import com.android.geto.domain.model.SettingType.GLOBAL
import com.android.geto.domain.model.SettingType.SECURE
import com.android.geto.domain.model.SettingType.SYSTEM
import com.android.geto.domain.model.SettingWriteResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject

internal class DefaultSecureSettingsWrapper @Inject constructor(
    @param:Dispatcher(IO) private val ioDispatcher: CoroutineDispatcher,
    @param:ApplicationContext private val context: Context,
) : SecureSettingsWrapper {

    private val contentResolver = context.contentResolver

    private val settingsProjection: Array<String> = arrayOf(
        Settings.NameValueTable._ID,
        Settings.NameValueTable.NAME,
        Settings.NameValueTable.VALUE,
    )

    override fun hasWriteSecureSettingsPermission(): Boolean = context.checkSelfPermission(
        Manifest.permission.WRITE_SECURE_SETTINGS,
    ) == PackageManager.PERMISSION_GRANTED

    override suspend fun read(
        settingType: SettingType,
        key: String,
    ): SettingReadResult = withContext(ioDispatcher) {
        if (key.isBlank()) {
            return@withContext SettingReadResult.Failure(
                reason = ProtectionFailureReason.INVALID_KEY,
                message = "Setting key must not be blank",
            )
        }

        try {
            SettingReadResult.Success(readValue(settingType, key))
        } catch (exception: SecurityException) {
            SettingReadResult.Failure(
                reason = ProtectionFailureReason.PERMISSION_DENIED,
                message = exception.message,
            )
        } catch (exception: IllegalArgumentException) {
            SettingReadResult.Failure(
                reason = ProtectionFailureReason.INVALID_KEY,
                message = exception.message,
            )
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: RuntimeException) {
            SettingReadResult.Failure(
                reason = ProtectionFailureReason.READ_FAILED,
                message = exception.message,
            )
        }
    }

    override suspend fun write(
        settingType: SettingType,
        key: String,
        value: String?,
    ): SettingWriteResult = withContext(ioDispatcher) {
        if (!hasWriteSecureSettingsPermission()) {
            return@withContext SettingWriteResult.Failure(
                reason = ProtectionFailureReason.PERMISSION_DENIED,
                expectedValue = value,
                message = "WRITE_SECURE_SETTINGS has not been granted",
            )
        }

        if (key.isBlank()) {
            return@withContext SettingWriteResult.Failure(
                reason = ProtectionFailureReason.INVALID_KEY,
                expectedValue = value,
                message = "Setting key must not be blank",
            )
        }

        try {
            val accepted = when (settingType) {
                SYSTEM -> Settings.System.putString(contentResolver, key, value)
                SECURE -> Settings.Secure.putString(contentResolver, key, value)
                GLOBAL -> Settings.Global.putString(contentResolver, key, value)
            }

            if (!accepted) {
                return@withContext SettingWriteResult.Failure(
                    reason = ProtectionFailureReason.WRITE_REJECTED,
                    expectedValue = value,
                    actualValue = readValue(settingType, key),
                )
            }

            val actualValue = readValue(settingType, key)
            if (actualValue == value) {
                SettingWriteResult.Success(value = actualValue)
            } else {
                SettingWriteResult.Failure(
                    reason = ProtectionFailureReason.VERIFICATION_FAILED,
                    expectedValue = value,
                    actualValue = actualValue,
                )
            }
        } catch (exception: SecurityException) {
            SettingWriteResult.Failure(
                reason = ProtectionFailureReason.PERMISSION_DENIED,
                expectedValue = value,
                message = exception.message,
            )
        } catch (exception: IllegalArgumentException) {
            SettingWriteResult.Failure(
                reason = ProtectionFailureReason.INVALID_KEY,
                expectedValue = value,
                message = exception.message,
            )
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: RuntimeException) {
            SettingWriteResult.Failure(
                reason = ProtectionFailureReason.WRITE_REJECTED,
                expectedValue = value,
                message = exception.message,
            )
        }
    }

    private fun readValue(settingType: SettingType, key: String): String? = when (settingType) {
        SYSTEM -> Settings.System.getString(contentResolver, key)
        SECURE -> Settings.Secure.getString(contentResolver, key)
        GLOBAL -> Settings.Global.getString(contentResolver, key)
    }

    override suspend fun getSecureSettings(settingType: SettingType): List<SecureSetting> = withContext(ioDispatcher) {
        val cursor = when (settingType) {
            SYSTEM -> contentResolver.query(
                Settings.System.CONTENT_URI,
                settingsProjection,
                null,
                null,
                null,
            )

            SECURE -> contentResolver.query(
                Settings.Secure.CONTENT_URI,
                settingsProjection,
                null,
                null,
                null,
            )

            GLOBAL -> contentResolver.query(
                Settings.Global.CONTENT_URI,
                settingsProjection,
                null,
                null,
                null,
            )
        }

        cursor?.use {
            generateSequence { if (cursor.moveToNext()) cursor else null }.map {
                val idIndex =
                    cursor.getColumnIndex(Settings.NameValueTable._ID).takeIf { it != -1 }
                val nameIndex =
                    cursor.getColumnIndex(Settings.NameValueTable.NAME).takeIf { it != -1 }
                val valueIndex =
                    cursor.getColumnIndex(Settings.NameValueTable.VALUE).takeIf { it != -1 }

                val id = idIndex?.let { cursor.getLongOrNull(it) }
                val name = nameIndex?.let { cursor.getStringOrNull(it) }
                val value = valueIndex?.let { cursor.getStringOrNull(it) }

                SecureSetting(
                    settingType = settingType,
                    id = id,
                    name = name,
                    value = value,
                )
            }.sortedBy { it.name }.toList()
        } ?: emptyList()
    }
}
