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
package com.android.geto.domain.model

enum class ProtectionMode {
    /**
     * Applied only while the app is in the foreground, and restored the moment it leaves.
     *
     * This replaced a mode that applied settings permanently. Leaving something like
     * `development_settings_enabled = 0` in place disables developer options system-wide, taking ADB
     * and Shizuku with it, with no automatic way back — so the scope is now the app's time on screen.
     */
    FOREGROUND,

    /** Applied once when launched through Geto, restored when the app leaves the foreground. */
    ONE_SHOT,
}

enum class ProtectionSessionStatus {
    STARTING,
    ACTIVE,
    RESTORING,
    RECOVERY_REQUIRED,
}

/**
 * One app's claim on a setting key.
 *
 * Deliberately carries no original value: many apps can claim the same key, and only the first claim
 * ever sees the true pre-Geto value. That lives on [ProtectedKey] instead.
 */
data class ProtectedSetting(
    val settingType: SettingType,
    val key: String,
    val protectedValue: String,
    val writeOrder: Int,
)

/**
 * The ledger entry for a setting key that is currently under protection.
 *
 * [originalValue] is captured when the key is claimed for the first time and is never overwritten,
 * so the last app to release the key can always put the device back the way it was. [claimCount] is
 * how many apps currently claim it.
 */
data class ProtectedKey(
    val settingType: SettingType,
    val key: String,
    val originalValue: String?,
    val enforcedValue: String,
    val claimedAtMillis: Long,
    val claimCount: Int = 1,
)

data class ProtectionSession(
    val token: String,
    val componentName: String,
    val mode: ProtectionMode,
    val status: ProtectionSessionStatus,
    val protectedSettings: List<ProtectedSetting>,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val pausedUntilMillis: Long = 0L,
    val lastError: String? = null,
)

/** One protected app, as the rest of the app sees it. */
data class ProtectedApp(
    val sessionToken: String,
    val componentName: String,
    val mode: ProtectionMode,
    val status: ProtectionSessionStatus,
    val protectedSettings: List<ProtectedSetting>,
    val pause: ProtectionPauseState,
    val lastError: String? = null,
) {
    /** Mid-transition, so the UI should not offer actions that would race it. */
    val isBusy: Boolean
        get() = status == ProtectionSessionStatus.STARTING ||
            status == ProtectionSessionStatus.RESTORING

    /** Actually watching for drift right now. */
    val isWatched: Boolean
        get() = status == ProtectionSessionStatus.ACTIVE && pause == ProtectionPauseState.Running
}

/**
 * Every protected app at once.
 *
 * Several apps can be protected simultaneously and they are independent, so this is a list rather
 * than a set of mutually exclusive states.
 */
@JvmInline
value class ProtectionState(val apps: List<ProtectedApp>) {
    val isEmpty: Boolean get() = apps.isEmpty()

    fun forComponentName(componentName: String): ProtectedApp? = apps.firstOrNull { it.componentName == componentName }

    fun forToken(sessionToken: String): ProtectedApp? = apps.firstOrNull { it.sessionToken == sessionToken }

    companion object {
        val Empty = ProtectionState(emptyList())
    }
}

enum class ProtectionStage {
    PREFLIGHT,
    SNAPSHOT,
    PERSIST,
    APPLY,
    RESTORE,
    REAPPLY,
}

enum class ProtectionFailureReason {
    PERMISSION_DENIED,
    INVALID_PROFILE,
    INVALID_KEY,
    READ_FAILED,
    WRITE_REJECTED,
    VERIFICATION_FAILED,
    PERSISTENCE_FAILED,
    INVALID_STATE,
    DUPLICATE_KEY,
    KEY_CONFLICT,
}

data class ProtectionFailure(
    val reason: ProtectionFailureReason,
    val settingType: SettingType? = null,
    val key: String? = null,
    val expectedValue: String? = null,
    val actualValue: String? = null,
    val message: String? = null,
)

sealed interface ProtectionResult {
    /** [app] is null when the operation ended with nothing protected, e.g. after a turn-off. */
    data class Success(
        val app: ProtectedApp?,
    ) : ProtectionResult

    data object EmptyProfile : ProtectionResult

    data object NoEnabledSettings : ProtectionResult

    data object NoActiveProtection : ProtectionResult

    /**
     * Another app already enforces this key with a different value.
     *
     * Sharing a key is normal — several apps wanting the same value is the common case — so this only
     * covers a genuine contradiction, where one setting would have to hold two values at once. It is
     * returned before anything is written, so the device is left untouched.
     */
    data class KeyConflict(
        val settingType: SettingType,
        val key: String,
        val requestedValue: String,
        val enforcedValue: String,
        val holderComponentNames: List<String>,
    ) : ProtectionResult

    data class StaleSession(
        val expectedSessionToken: String,
        val activeSessionToken: String?,
    ) : ProtectionResult

    data class PermissionDenied(
        val failure: ProtectionFailure = ProtectionFailure(
            reason = ProtectionFailureReason.PERMISSION_DENIED,
        ),
    ) : ProtectionResult

    data class InvalidProfile(
        val failures: List<ProtectionFailure>,
    ) : ProtectionResult

    data class KeyNotProtected(
        val settingType: SettingType,
        val key: String,
    ) : ProtectionResult

    data class Failure(
        val stage: ProtectionStage,
        val failures: List<ProtectionFailure>,
        val rollbackSucceeded: Boolean? = null,
    ) : ProtectionResult

    data class RecoveryRequired(
        val app: ProtectedApp,
        val failures: List<ProtectionFailure>,
    ) : ProtectionResult
}

/**
 * @param nowMillis passed in rather than read here so the pause deadline is evaluated against the
 * caller's clock on every read — an expired pause reports as running even if nothing woke up to clear it.
 */
fun ProtectionSession.asProtectedApp(nowMillis: Long): ProtectedApp = ProtectedApp(
    sessionToken = token,
    componentName = componentName,
    mode = mode,
    status = status,
    protectedSettings = protectedSettings,
    pause = pauseStateOf(pausedUntilMillis = pausedUntilMillis, nowMillis = nowMillis),
    lastError = lastError,
)

/** True while this session claims [key] of [settingType]. */
fun ProtectionSession.claims(settingType: SettingType, key: String): Boolean = protectedSettings.any { it.settingType == settingType && it.key == key }
