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

import com.android.geto.domain.model.ProtectionMode
import com.android.geto.domain.model.ProtectionPauseState
import com.android.geto.domain.model.ProtectionSessionStatus
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `needsForeground` decides whether a foreground service — a resident process and a permanent
 * notification — is justified. Getting it wrong costs battery for no benefit, so both directions of
 * the pause rule are pinned here.
 */
class ProtectionServiceStateTest {

    @Test
    fun needsForeground_isTrue_whenAnAppIsActivelyProtected() {
        assertTrue(runningWith(app()).needsForeground)
    }

    @Test
    fun needsForeground_isTrue_whenPausedWithADeadline() {
        // The service is what runs the timer that resumes on time, so it has to stay alive.
        val paused = app(pause = ProtectionPauseState.PausedUntil(untilMillis = 1_000))

        assertTrue(runningWith(paused).needsForeground)
    }

    @Test
    fun needsForeground_isFalse_whenEveryAppIsPausedIndefinitely() {
        // Nothing to wait for, so holding the process and notification would buy nothing.
        val paused = app(pause = ProtectionPauseState.PausedIndefinitely)

        assertFalse(runningWith(paused).needsForeground)
    }

    @Test
    fun needsForeground_isTrue_whenOneAppIsIndefinitelyPausedButAnotherIsNot() {
        val state = runningWith(
            app(token = "a", pause = ProtectionPauseState.PausedIndefinitely),
            app(token = "b"),
        )

        assertTrue(state.needsForeground)
    }

    @Test
    fun needsForeground_isFalse_forOneShotAndRecoveryOnly() {
        val state = runningWith(
            app(token = "a", mode = ProtectionMode.ONE_SHOT),
            app(token = "b", status = ProtectionSessionStatus.RECOVERY_REQUIRED),
        )

        assertFalse(state.needsForeground)
    }

    private fun runningWith(vararg apps: ProtectedAppStatus) = ProtectionServiceState.Running(
        apps = apps.toList(),
        observedSettings = emptySet(),
    )

    private fun app(
        token: String = "token",
        mode: ProtectionMode = ProtectionMode.FOREGROUND,
        status: ProtectionSessionStatus = ProtectionSessionStatus.ACTIVE,
        pause: ProtectionPauseState = ProtectionPauseState.Running,
    ) = ProtectedAppStatus(
        token = token,
        componentName = "com.example.$token/.MainActivity",
        label = token,
        mode = mode,
        status = status,
        pause = pause,
        settingCount = 1,
    )
}
