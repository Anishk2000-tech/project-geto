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
import com.android.geto.domain.model.ProtectionPauseDuration
import com.android.geto.domain.model.ProtectionPauseState
import com.android.geto.domain.model.ProtectionSessionStatus
import com.android.geto.domain.model.SettingType
import kotlinx.coroutines.flow.Flow

/**
 * Android-facing projection of the domain protection controller.
 *
 * Keeping this boundary inside the service module prevents the Android service from depending on
 * database entities or the controller's persistence details.
 */
interface ProtectionServiceRuntime {
    val state: Flow<ProtectionServiceState>

    /**
     * Every app the user has armed, whether or not it is applied right now.
     *
     * [state] alone could never show these: it is built from sessions, and a session exists only
     * while an app is in the foreground — so whenever the user is looking at Geto, it is empty.
     */
    val armedApps: Flow<List<ArmedAppStatus>>

    suspend fun reconcile()

    /**
     * Repairs one key. Takes no session token because a content observer fires for a key, not for an
     * app, and several apps may claim the same key.
     */
    suspend fun reapply(settingType: SettingType, key: String): Boolean

    /** Returns true when protection was restored or was already inactive. */
    suspend fun restore(sessionToken: String): Boolean

    suspend fun pause(sessionToken: String, duration: ProtectionPauseDuration): Boolean

    suspend fun resume(sessionToken: String): Boolean

    /** Drops cached app labels; they can change when a package is replaced. */
    fun invalidateLabels()
}

sealed interface ProtectionServiceState {
    /** Nothing has been read yet. */
    data object Loading : ProtectionServiceState

    /** No app is protected. */
    data object Inactive : ProtectionServiceState

    data class Running(
        val apps: List<ProtectedAppStatus>,
        /**
         * The union of every watched key across every app, deduplicated — a key five apps share is
         * observed once and repaired once.
         */
        val observedSettings: Set<ObservedProtectionSetting>,
    ) : ProtectionServiceState {
        /**
         * A foreground service is only warranted while something still needs it.
         *
         * An app paused with a deadline keeps it alive, because the service is what runs the timer
         * that resumes on time. An app paused with no deadline has nothing to wait for, so holding a
         * resident process and a permanent notification for it would buy nothing — the user resumes
         * it by hand.
         */
        val needsForeground: Boolean
            get() = apps.any {
                it.mode == ProtectionMode.FOREGROUND &&
                    it.status == ProtectionSessionStatus.ACTIVE &&
                    it.pause != ProtectionPauseState.PausedIndefinitely
            }

        val recoveryApps: List<ProtectedAppStatus>
            get() = apps.filter { it.status == ProtectionSessionStatus.RECOVERY_REQUIRED }

        val pausedApps: List<ProtectedAppStatus>
            get() = apps.filter { it.pause != ProtectionPauseState.Running }

        val oneShotApps: List<ProtectedAppStatus>
            get() = apps.filter { it.mode == ProtectionMode.ONE_SHOT }
    }
}

/** True when at least one app needs the foreground service kept alive. */
fun ProtectionServiceState?.needsForeground(): Boolean = (this as? ProtectionServiceState.Running)?.needsForeground == true

/** An armed app, plus its live status when it happens to be applied. */
data class ArmedAppStatus(
    val componentName: String,
    val label: String,
    val applied: ProtectedAppStatus?,
)

data class ProtectedAppStatus(
    val token: String,
    val componentName: String,
    val label: String,
    val mode: ProtectionMode,
    val status: ProtectionSessionStatus,
    val pause: ProtectionPauseState,
    val settingCount: Int,
    val message: String? = null,
)

/**
 * A key the service watches. Carries no session token on purpose: that is what lets one observer and
 * one pending repair cover every app claiming the key.
 */
data class ObservedProtectionSetting(
    val settingType: SettingType,
    val key: String,
)
