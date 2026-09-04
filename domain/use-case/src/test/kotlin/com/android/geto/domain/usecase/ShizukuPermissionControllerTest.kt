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
package com.android.geto.domain.usecase

import com.android.geto.domain.framework.SecureSettingsWrapper
import com.android.geto.domain.framework.ShizukuWrapper
import com.android.geto.domain.model.SecureSetting
import com.android.geto.domain.model.SettingReadResult
import com.android.geto.domain.model.SettingType
import com.android.geto.domain.model.SettingWriteResult
import com.android.geto.domain.model.ShizukuGrantResult
import com.android.geto.domain.model.ShizukuState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ShizukuPermissionControllerTest {

    /**
     * The regression that matters most: an earlier implementation reported the permission as
     * granted purely because the code path ran, without the permission ever changing. A wrapper
     * claiming success must not be believed on its own.
     */
    @Test
    fun grant_doesNotReportSuccess_whenPermissionIsStillNotVisible() = runTest {
        val secureSettings = FakeSecureSettingsWrapper(granted = false)
        val shizuku = FakeShizukuWrapper(grantResult = ShizukuGrantResult.Success)

        val result = ShizukuPermissionController(shizuku, secureSettings).grant()

        assertEquals(ShizukuGrantResult.RequiresRestart, result)
    }

    @Test
    fun grant_reportsSuccess_onlyOncePermissionReadsBackAsGranted() = runTest {
        val secureSettings = FakeSecureSettingsWrapper(granted = false)
        val shizuku = FakeShizukuWrapper(
            grantResult = ShizukuGrantResult.Success,
            onGrant = { secureSettings.granted = true },
        )

        val result = ShizukuPermissionController(shizuku, secureSettings).grant()

        assertEquals(ShizukuGrantResult.Success, result)
    }

    @Test
    fun grant_reportsFailure_whenShizukuCannotBeUsed() = runTest {
        val secureSettings = FakeSecureSettingsWrapper(granted = false)
        val shizuku = FakeShizukuWrapper(
            grantResult = ShizukuGrantResult.Failure(ShizukuState.NotRunning),
        )

        val result = ShizukuPermissionController(shizuku, secureSettings).grant()

        assertEquals(ShizukuGrantResult.Failure(ShizukuState.NotRunning), result)
    }

    @Test
    fun grant_reportsError_whenTheAttemptThrewOnTheOtherSide() = runTest {
        val secureSettings = FakeSecureSettingsWrapper(granted = false)
        val shizuku = FakeShizukuWrapper(grantResult = ShizukuGrantResult.Error("dead binder"))

        val result = ShizukuPermissionController(shizuku, secureSettings).grant()

        assertEquals(ShizukuGrantResult.Error("dead binder"), result)
    }

    @Test
    fun grant_doesNotTouchShizuku_whenPermissionIsAlreadyHeld() = runTest {
        val secureSettings = FakeSecureSettingsWrapper(granted = true)
        val shizuku = FakeShizukuWrapper()

        val result = ShizukuPermissionController(shizuku, secureSettings).grant()

        assertEquals(ShizukuGrantResult.Success, result)
        assertEquals(0, shizuku.grantAttempts)
    }

    /** Repeated attempts have to keep producing a real verdict, not a cached first answer. */
    @Test
    fun grant_reportsEachAttemptIndependently() = runTest {
        val secureSettings = FakeSecureSettingsWrapper(granted = false)
        val shizuku = FakeShizukuWrapper(grantResult = ShizukuGrantResult.Success)
        val controller = ShizukuPermissionController(shizuku, secureSettings)

        assertEquals(ShizukuGrantResult.RequiresRestart, controller.grant())

        secureSettings.granted = true

        assertEquals(ShizukuGrantResult.Success, controller.grant())
        assertEquals(1, shizuku.grantAttempts)
    }

    @Test
    fun observation_isForwardedToTheWrapper() {
        val shizuku = FakeShizukuWrapper()
        val controller = ShizukuPermissionController(shizuku, FakeSecureSettingsWrapper())

        controller.start()
        controller.refresh()
        controller.stop()

        assertEquals(1, shizuku.starts)
        assertEquals(1, shizuku.refreshes)
        assertEquals(1, shizuku.stops)
    }

    private class FakeShizukuWrapper(
        private val grantResult: ShizukuGrantResult = ShizukuGrantResult.Success,
        private val onGrant: () -> Unit = {},
    ) : ShizukuWrapper {
        var grantAttempts = 0
        var starts = 0
        var stops = 0
        var refreshes = 0

        private val mutableState = MutableStateFlow(ShizukuState.Unknown)

        override val state = mutableState.asStateFlow()

        override fun start() {
            starts++
        }

        override fun stop() {
            stops++
        }

        override fun refresh() {
            refreshes++
        }

        override suspend fun grantWriteSecureSettings(): ShizukuGrantResult {
            grantAttempts++
            onGrant()
            return grantResult
        }
    }

    /**
     * Deliberately not the fake in `ProtectionControllerTest`: that one fixes the permission at
     * construction, and these tests need it to change underneath the controller.
     */
    private class FakeSecureSettingsWrapper(var granted: Boolean = false) : SecureSettingsWrapper {
        override fun hasWriteSecureSettingsPermission(): Boolean = granted

        override suspend fun read(settingType: SettingType, key: String): SettingReadResult = SettingReadResult.Success(value = null)

        override suspend fun write(
            settingType: SettingType,
            key: String,
            value: String?,
        ): SettingWriteResult = SettingWriteResult.Success(value = value)

        override suspend fun getSecureSettings(settingType: SettingType): List<SecureSetting> = emptyList()
    }
}
