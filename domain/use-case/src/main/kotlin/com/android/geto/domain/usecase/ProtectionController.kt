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
import com.android.geto.domain.model.AppSetting
import com.android.geto.domain.model.PAUSE_NOT_PAUSED
import com.android.geto.domain.model.ProtectedApp
import com.android.geto.domain.model.ProtectedKey
import com.android.geto.domain.model.ProtectedSetting
import com.android.geto.domain.model.ProtectionFailure
import com.android.geto.domain.model.ProtectionFailureReason
import com.android.geto.domain.model.ProtectionMode
import com.android.geto.domain.model.ProtectionPauseDuration
import com.android.geto.domain.model.ProtectionPauseState
import com.android.geto.domain.model.ProtectionResult
import com.android.geto.domain.model.ProtectionSession
import com.android.geto.domain.model.ProtectionSessionStatus
import com.android.geto.domain.model.ProtectionStage
import com.android.geto.domain.model.ProtectionState
import com.android.geto.domain.model.SettingReadResult
import com.android.geto.domain.model.SettingType
import com.android.geto.domain.model.SettingWriteResult
import com.android.geto.domain.model.asProtectedApp
import com.android.geto.domain.model.claims
import com.android.geto.domain.model.deadlineFrom
import com.android.geto.domain.model.pauseStateOf
import com.android.geto.domain.repository.AppSettingsRepository
import com.android.geto.domain.repository.ProtectionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns which apps are protected and what the device's settings must therefore look like.
 *
 * Several apps can be protected at once and they routinely want the *same* key — hiding developer
 * options from a handful of banking apps is the motivating case. Keys are therefore reference
 * counted through a ledger: the first app to claim a key records the real pre-Geto value, later apps
 * simply join the claim, and the original is written back only when the last claim goes away.
 */
@Singleton
class ProtectionController @Inject constructor(
    private val appSettingsRepository: AppSettingsRepository,
    private val protectionRepository: ProtectionRepository,
    private val secureSettingsWrapper: SecureSettingsWrapper,
) {
    /**
     * One global lock, deliberately not sharded per app.
     *
     * The ledger and the device's settings are shared by every app. Two concurrent enables claiming
     * the same key under per-app locks would both find no ledger row, both snapshot an "original",
     * and one of those originals would be lost for good. These operations are user-initiated and
     * drift repair is already coalesced, so the contention is not worth the risk.
     */
    private val operationMutex = Mutex()

    /** Overridable in tests; the repo has no clock abstraction. */
    internal var now: () -> Long = System::currentTimeMillis

    /** Apps the user wants acted on when they come to the foreground. */
    val armedComponentNames: Flow<Set<String>> = protectionRepository.armedComponentNamesFlow

    val state: Flow<ProtectionState> = protectionRepository.sessionsFlow
        .map { sessions -> ProtectionState(sessions.map { it.asProtectedApp(now()) }) }
        .distinctUntilChanged()

    suspend fun enable(
        componentName: String,
        mode: ProtectionMode,
    ): ProtectionResult = operationMutex.withLock {
        enableLocked(componentName, mode)
    }

    suspend fun armProfile(componentName: String) {
        protectionRepository.armProfile(componentName = componentName, armedAtMillis = now())
    }

    /** Disarms and, if the app happens to be in front right now, puts the originals back. */
    suspend fun disarmProfile(componentName: String): ProtectionResult {
        protectionRepository.disarmProfile(componentName)

        val session = operationMutex.withLock {
            protectionRepository.getSessionByComponentName(componentName)
        } ?: return ProtectionResult.Success(app = null)

        return restore(session.token)
    }

    /**
     * Puts every applied session back.
     *
     * Called at process start and whenever foreground detection stops. A session means "settings are
     * applied right now", so one surviving a restart — or outliving the detector — means the device is
     * sitting in a state the user never asked to keep. This is the safety net that stops a profile
     * silently disabling developer options forever.
     */
    suspend fun restoreEverythingApplied(): ProtectionResult = restoreAll()

    /**
     * Puts one app's originals back without changing whether it is armed.
     *
     * This is the normal path when the app leaves the foreground: the user still wants the profile
     * next time, they have just stopped using the app.
     */
    suspend fun restoreApplied(componentName: String): ProtectionResult {
        val session = operationMutex.withLock {
            protectionRepository.getSessionByComponentName(componentName)
        } ?: return ProtectionResult.NoActiveProtection

        return restore(session.token)
    }

    /** Pauses drift repair for one app without touching its applied values. */
    suspend fun pause(
        sessionToken: String,
        duration: ProtectionPauseDuration,
    ): ProtectionResult = operationMutex.withLock {
        val session = protectionRepository.getSessionByToken(sessionToken)
            ?: return@withLock ProtectionResult.NoActiveProtection

        val updatedAtMillis = now()
        val paused = protectionRepository.updatePausedUntil(
            token = sessionToken,
            pausedUntilMillis = duration.deadlineFrom(updatedAtMillis),
            updatedAtMillis = updatedAtMillis,
        )

        if (!paused) {
            return@withLock ProtectionResult.Failure(
                stage = ProtectionStage.PERSIST,
                failures = listOf(
                    ProtectionFailure(
                        reason = ProtectionFailureReason.PERSISTENCE_FAILED,
                        message = "Could not pause protection",
                    ),
                ),
            )
        }

        ProtectionResult.Success(
            session.copy(pausedUntilMillis = duration.deadlineFrom(updatedAtMillis))
                .asProtectedApp(updatedAtMillis),
        )
    }

    /**
     * Ends a pause and repairs whatever drifted while nobody was watching, so "protected" is true
     * again the moment it is reported.
     */
    suspend fun resume(sessionToken: String): ProtectionResult = operationMutex.withLock {
        val session = protectionRepository.getSessionByToken(sessionToken)
            ?: return@withLock ProtectionResult.NoActiveProtection

        val resumed = protectionRepository.updatePausedUntil(
            token = sessionToken,
            pausedUntilMillis = PAUSE_NOT_PAUSED,
            updatedAtMillis = now(),
        )

        if (!resumed) {
            return@withLock ProtectionResult.Failure(
                stage = ProtectionStage.PERSIST,
                failures = listOf(
                    ProtectionFailure(
                        reason = ProtectionFailureReason.PERSISTENCE_FAILED,
                        message = "Could not resume protection",
                    ),
                ),
            )
        }

        reconcileAppLocked(session.copy(pausedUntilMillis = PAUSE_NOT_PAUSED))
    }

    private suspend fun enableLocked(
        componentName: String,
        mode: ProtectionMode,
    ): ProtectionResult {
        if (componentName.isBlank()) {
            return ProtectionResult.InvalidProfile(
                failures = listOf(
                    ProtectionFailure(
                        reason = ProtectionFailureReason.INVALID_PROFILE,
                        message = "Component name must not be blank",
                    ),
                ),
            )
        }

        val existingSession = try {
            protectionRepository.getSessionByComponentName(componentName)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: RuntimeException) {
            return ProtectionResult.Failure(
                stage = ProtectionStage.PERSIST,
                failures = listOf(exception.asPersistenceFailure()),
            )
        }

        if (existingSession != null) {
            // Another app holding a session is no longer anyone's business — only this app's own
            // session matters here.
            if (existingSession.satisfies(componentName, mode)) {
                return reconcileAppLocked(existingSession)
            }

            return ProtectionResult.RecoveryRequired(
                app = existingSession.asProtectedApp(now()),
                failures = listOf(
                    ProtectionFailure(
                        reason = ProtectionFailureReason.INVALID_STATE,
                        message = "This app already has a protection session that is ${existingSession.status}",
                    ),
                ),
            )
        }

        val profile = try {
            appSettingsRepository.getAppSettingsByComponentName(componentName)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: RuntimeException) {
            return ProtectionResult.Failure(
                stage = ProtectionStage.PERSIST,
                failures = listOf(exception.asPersistenceFailure()),
            )
        }
        if (profile.isEmpty()) return ProtectionResult.EmptyProfile

        val enabledSettings = profile.filter(AppSetting::enabled)
        if (enabledSettings.isEmpty()) return ProtectionResult.NoEnabledSettings

        validate(enabledSettings).takeIf(List<ProtectionFailure>::isNotEmpty)?.let { failures ->
            return ProtectionResult.InvalidProfile(failures)
        }

        if (!secureSettingsWrapper.hasWriteSecureSettingsPermission()) {
            return ProtectionResult.PermissionDenied()
        }

        val startedAtMillis = now()
        val newKeys = mutableListOf<ProtectedKey>()
        val claims = mutableListOf<ProtectedSetting>()
        val orderedSettings = enabledSettings.inDependencyOrder()

        // Acquire pass. Runs to completion before anything is written, so a rejected enable leaves
        // the device exactly as it found it and needs no rollback.
        orderedSettings.forEachIndexed { index, appSetting ->
            val ledger = try {
                protectionRepository.getProtectedKey(appSetting.settingType, appSetting.key)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: RuntimeException) {
                return ProtectionResult.Failure(
                    stage = ProtectionStage.PERSIST,
                    failures = listOf(exception.asPersistenceFailure()),
                )
            }

            when {
                ledger == null -> {
                    // First claim: this read is the only chance anyone gets to see the true value.
                    when (
                        val result = secureSettingsWrapper.read(
                            appSetting.settingType,
                            appSetting.key,
                        )
                    ) {
                        is SettingReadResult.Success -> newKeys += ProtectedKey(
                            settingType = appSetting.settingType,
                            key = appSetting.key,
                            originalValue = result.value,
                            enforcedValue = appSetting.valueOnLaunch,
                            claimedAtMillis = startedAtMillis,
                        )

                        is SettingReadResult.Failure -> {
                            val failure = result.asFailure(appSetting.settingType, appSetting.key)
                            return if (failure.reason == ProtectionFailureReason.PERMISSION_DENIED) {
                                ProtectionResult.PermissionDenied(failure)
                            } else {
                                ProtectionResult.Failure(
                                    stage = ProtectionStage.SNAPSHOT,
                                    failures = listOf(failure),
                                )
                            }
                        }
                    }
                }

                // Someone already enforces this key with the same value: just join the claim.
                ledger.enforcedValue == appSetting.valueOnLaunch -> Unit

                else -> return ProtectionResult.KeyConflict(
                    settingType = appSetting.settingType,
                    key = appSetting.key,
                    requestedValue = appSetting.valueOnLaunch,
                    enforcedValue = ledger.enforcedValue,
                    holderComponentNames = protectionRepository.getClaimantComponentNames(
                        settingType = appSetting.settingType,
                        key = appSetting.key,
                    ),
                )
            }

            claims += ProtectedSetting(
                settingType = appSetting.settingType,
                key = appSetting.key,
                protectedValue = appSetting.valueOnLaunch,
                writeOrder = index,
            )
        }

        val session = ProtectionSession(
            token = UUID.randomUUID().toString(),
            componentName = componentName,
            mode = mode,
            status = ProtectionSessionStatus.STARTING,
            protectedSettings = claims,
            createdAtMillis = startedAtMillis,
            updatedAtMillis = startedAtMillis,
        )

        try {
            protectionRepository.createSession(session = session, newKeys = newKeys)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: RuntimeException) {
            return ProtectionResult.Failure(
                stage = ProtectionStage.PERSIST,
                failures = listOf(exception.asPersistenceFailure()),
            )
        }

        // Only keys this session newly claimed may be rolled back. Reverting a shared key would
        // break the app that was already relying on it.
        val newKeysByIdentity = newKeys.associateBy { it.settingType to it.key }
        val attemptedNewKeys = mutableListOf<ProtectedKey>()

        for (claim in claims) {
            newKeysByIdentity[claim.settingType to claim.key]?.let { attemptedNewKeys += it }

            val result = secureSettingsWrapper.write(
                settingType = claim.settingType,
                key = claim.key,
                value = claim.protectedValue,
            )
            if (result is SettingWriteResult.Failure) {
                return handleApplyFailure(
                    session = session,
                    attemptedNewKeys = attemptedNewKeys,
                    failure = result.asFailure(claim.settingType, claim.key),
                )
            }
        }

        verifyExpectedValues(claims.map { it.expected(it.protectedValue) })
            .firstOrNull()
            ?.let { verificationFailure ->
                return handleApplyFailure(
                    session = session,
                    attemptedNewKeys = attemptedNewKeys,
                    failure = verificationFailure,
                )
            }

        val activatedAtMillis = now()
        val activated = try {
            protectionRepository.updateStatus(
                token = session.token,
                status = ProtectionSessionStatus.ACTIVE,
                updatedAtMillis = activatedAtMillis,
            )
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: RuntimeException) {
            false
        }

        if (!activated) {
            return handleApplyFailure(
                session = session,
                attemptedNewKeys = attemptedNewKeys,
                failure = ProtectionFailure(
                    reason = ProtectionFailureReason.PERSISTENCE_FAILED,
                    message = "Could not mark the protection session active",
                ),
            )
        }

        return ProtectionResult.Success(
            session.copy(
                status = ProtectionSessionStatus.ACTIVE,
                updatedAtMillis = activatedAtMillis,
            ).asProtectedApp(activatedAtMillis),
        )
    }

    /** Turns one app's protection off, restoring only the keys nobody else still claims. */
    suspend fun restore(sessionToken: String): ProtectionResult = operationMutex.withLock {
        val session = try {
            protectionRepository.getSessionByToken(sessionToken)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: RuntimeException) {
            return@withLock ProtectionResult.Failure(
                stage = ProtectionStage.PERSIST,
                failures = listOf(exception.asPersistenceFailure()),
            )
        } ?: return@withLock ProtectionResult.NoActiveProtection

        restoreLocked(session)
    }

    /** Turns every protected app off, reporting the first failure encountered. */
    suspend fun restoreAll(): ProtectionResult = operationMutex.withLock {
        val sessions = try {
            protectionRepository.getSessions()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: RuntimeException) {
            return@withLock ProtectionResult.Failure(
                stage = ProtectionStage.PERSIST,
                failures = listOf(exception.asPersistenceFailure()),
            )
        }
        if (sessions.isEmpty()) return@withLock ProtectionResult.NoActiveProtection

        sessions.forEach { session ->
            val result = restoreLocked(session)
            if (result !is ProtectionResult.Success) return@withLock result
        }

        ProtectionResult.Success(app = null)
    }

    /**
     * Restores a pre-v10 notification that predates persisted sessions. Refused when a modern session
     * or ledger entry covers any of the profile's keys, so an old notification can never overwrite a
     * key another app is actively protecting.
     */
    suspend fun restoreLegacyProfile(componentName: String): ProtectionResult = operationMutex.withLock {
        val profile = try {
            appSettingsRepository.getAppSettingsByComponentName(componentName)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: RuntimeException) {
            return@withLock ProtectionResult.Failure(
                stage = ProtectionStage.PERSIST,
                failures = listOf(exception.asPersistenceFailure()),
            )
        }
        if (profile.isEmpty()) return@withLock ProtectionResult.EmptyProfile
        val enabledSettings = profile.filter(AppSetting::enabled)
        if (enabledSettings.isEmpty()) return@withLock ProtectionResult.NoEnabledSettings
        validate(enabledSettings).takeIf(List<ProtectionFailure>::isNotEmpty)?.let { failures ->
            return@withLock ProtectionResult.InvalidProfile(failures)
        }

        protectionRepository.getSessionByComponentName(componentName)?.let { session ->
            return@withLock ProtectionResult.StaleSession(
                expectedSessionToken = "",
                activeSessionToken = session.token,
            )
        }

        enabledSettings.forEach { setting ->
            val ledger = protectionRepository.getProtectedKey(setting.settingType, setting.key)
            if (ledger != null) {
                return@withLock ProtectionResult.KeyConflict(
                    settingType = setting.settingType,
                    key = setting.key,
                    requestedValue = setting.valueOnRevert,
                    enforcedValue = ledger.enforcedValue,
                    holderComponentNames = protectionRepository.getClaimantComponentNames(
                        settingType = setting.settingType,
                        key = setting.key,
                    ),
                )
            }
        }

        if (!secureSettingsWrapper.hasWriteSecureSettingsPermission()) {
            return@withLock ProtectionResult.PermissionDenied()
        }

        val failures = enabledSettings.asReversed().mapNotNull { setting ->
            when (
                val result = secureSettingsWrapper.write(
                    settingType = setting.settingType,
                    key = setting.key,
                    value = setting.valueOnRevert,
                )
            ) {
                is SettingWriteResult.Success -> null
                is SettingWriteResult.Failure -> result.asFailure(setting.settingType, setting.key)
            }
        } + verifyExpectedValues(
            enabledSettings.map { ExpectedValue(it.settingType, it.key, it.valueOnRevert) },
        )

        if (failures.isEmpty()) {
            ProtectionResult.Success(app = null)
        } else {
            ProtectionResult.Failure(
                stage = ProtectionStage.RESTORE,
                failures = failures,
                rollbackSucceeded = null,
            )
        }
    }

    /**
     * Repairs everything after a process restart: sweeps ledger rows left by an interrupted restore,
     * flags sessions caught mid-transition, and re-enforces every key that has drifted.
     */
    suspend fun reconcile(): ProtectionResult = operationMutex.withLock {
        reconcileAllLocked()
    }

    /** Reconciles one app, for the launch path that must not report ready while a value is stale. */
    suspend fun reconcile(componentName: String): ProtectionResult = operationMutex.withLock {
        val session = protectionRepository.getSessionByComponentName(componentName)
            ?: return@withLock ProtectionResult.NoActiveProtection

        reconcileAppLocked(session)
    }

    private suspend fun reconcileAllLocked(): ProtectionResult {
        val sessions = try {
            protectionRepository.getSessions()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: RuntimeException) {
            return ProtectionResult.Failure(
                stage = ProtectionStage.PERSIST,
                failures = listOf(exception.asPersistenceFailure()),
            )
        }

        sweepOrphanKeys()

        if (sessions.isEmpty()) return ProtectionResult.NoActiveProtection

        var firstFailure: ProtectionResult? = null

        // A session stuck mid-transition means the process died during an apply or a restore.
        sessions.filter { it.status == ProtectionSessionStatus.STARTING || it.status == ProtectionSessionStatus.RESTORING }
            .forEach { session ->
                val result = markRecoveryRequired(
                    session = session,
                    stage = ProtectionStage.REAPPLY,
                    failures = listOf(
                        ProtectionFailure(
                            reason = ProtectionFailureReason.INVALID_STATE,
                            message = "Protection was interrupted while ${session.status}",
                        ),
                    ),
                )
                if (firstFailure == null) firstFailure = result
            }

        if (!secureSettingsWrapper.hasWriteSecureSettingsPermission()) {
            return ProtectionResult.PermissionDenied()
        }

        // Drift repair walks the ledger rather than the sessions, so a key five apps share is read
        // and written once instead of five times.
        val keys = try {
            protectionRepository.getProtectedKeys()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: RuntimeException) {
            return ProtectionResult.Failure(
                stage = ProtectionStage.PERSIST,
                failures = listOf(exception.asPersistenceFailure()),
            )
        }

        val nowMillis = now()
        keys.forEach { key ->
            val watchers = sessions.filter { session ->
                session.claims(key.settingType, key.key) &&
                    session.status == ProtectionSessionStatus.ACTIVE &&
                    pauseStateOf(session.pausedUntilMillis, nowMillis) == ProtectionPauseState.Running
            }
            if (watchers.isEmpty()) return@forEach

            val failure = enforceKey(key)
            if (failure != null) {
                watchers.forEach { session ->
                    val result = markRecoveryRequired(
                        session = session,
                        stage = ProtectionStage.REAPPLY,
                        failures = listOf(failure),
                    )
                    if (firstFailure == null) firstFailure = result
                }
            }
        }

        return firstFailure ?: ProtectionResult.Success(app = null)
    }

    private suspend fun reconcileAppLocked(session: ProtectionSession): ProtectionResult {
        if (session.status == ProtectionSessionStatus.RECOVERY_REQUIRED) {
            return ProtectionResult.RecoveryRequired(session.asProtectedApp(now()), emptyList())
        }

        if (session.status != ProtectionSessionStatus.ACTIVE) {
            return markRecoveryRequired(
                session = session,
                stage = ProtectionStage.REAPPLY,
                failures = listOf(
                    ProtectionFailure(
                        reason = ProtectionFailureReason.INVALID_STATE,
                        message = "Protection was interrupted while ${session.status}",
                    ),
                ),
            )
        }

        if (!secureSettingsWrapper.hasWriteSecureSettingsPermission()) {
            return markRecoveryRequired(
                session = session,
                stage = ProtectionStage.PREFLIGHT,
                failures = listOf(
                    ProtectionFailure(reason = ProtectionFailureReason.PERMISSION_DENIED),
                ),
            )
        }

        val nowMillis = now()
        if (pauseStateOf(session.pausedUntilMillis, nowMillis) != ProtectionPauseState.Running) {
            // Paused apps are left alone on purpose; that is what a pause is.
            return ProtectionResult.Success(session.asProtectedApp(nowMillis))
        }

        session.protectedSettings.forEach { setting ->
            val key = protectionRepository.getProtectedKey(setting.settingType, setting.key)
                ?: return markRecoveryRequired(
                    session = session,
                    stage = ProtectionStage.REAPPLY,
                    failures = listOf(
                        ProtectionFailure(
                            reason = ProtectionFailureReason.INVALID_STATE,
                            settingType = setting.settingType,
                            key = setting.key,
                            message = "Protected key is missing from the ledger",
                        ),
                    ),
                )

            enforceKey(key)?.let { failure ->
                return markRecoveryRequired(
                    session = session,
                    stage = ProtectionStage.REAPPLY,
                    failures = listOf(failure),
                )
            }
        }

        return ProtectionResult.Success(session.asProtectedApp(nowMillis))
    }

    /**
     * Repairs one key after a content observer saw it change.
     *
     * Observers fire for a key, not for a session, so this looks the key up in the ledger rather than
     * being told which app to blame.
     */
    suspend fun reapply(type: SettingType, key: String): ProtectionResult = operationMutex.withLock {
        val ledgerKey = try {
            protectionRepository.getProtectedKey(type, key)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: RuntimeException) {
            return@withLock ProtectionResult.Failure(
                stage = ProtectionStage.PERSIST,
                failures = listOf(exception.asPersistenceFailure()),
            )
        } ?: return@withLock ProtectionResult.KeyNotProtected(type, key)

        val nowMillis = now()
        val watchers = protectionRepository.getSessions().filter { session ->
            session.claims(type, key) &&
                session.status == ProtectionSessionStatus.ACTIVE &&
                pauseStateOf(session.pausedUntilMillis, nowMillis) == ProtectionPauseState.Running
        }
        // Every claimant is paused or mid-transition, so this change is not ours to undo.
        if (watchers.isEmpty()) return@withLock ProtectionResult.Success(app = null)

        if (!secureSettingsWrapper.hasWriteSecureSettingsPermission()) {
            return@withLock ProtectionResult.PermissionDenied()
        }

        val failure = enforceKey(ledgerKey)
            ?: return@withLock ProtectionResult.Success(watchers.first().asProtectedApp(nowMillis))

        var result: ProtectionResult = ProtectionResult.Failure(
            stage = ProtectionStage.REAPPLY,
            failures = listOf(failure),
        )
        watchers.forEach { session ->
            result = markRecoveryRequired(
                session = session,
                stage = ProtectionStage.REAPPLY,
                failures = listOf(failure),
            )
        }
        result
    }

    /** Writes [key]'s enforced value if it has drifted. Returns the failure, or null on success. */
    private suspend fun enforceKey(key: ProtectedKey): ProtectionFailure? {
        when (val readResult = secureSettingsWrapper.read(key.settingType, key.key)) {
            is SettingReadResult.Success -> if (readResult.value == key.enforcedValue) return null
            is SettingReadResult.Failure -> return readResult.asFailure(key.settingType, key.key)
        }

        return when (
            val writeResult = secureSettingsWrapper.write(
                settingType = key.settingType,
                key = key.key,
                value = key.enforcedValue,
            )
        ) {
            is SettingWriteResult.Success -> null
            is SettingWriteResult.Failure -> writeResult.asFailure(key.settingType, key.key)
        }
    }

    /** Writes back and clears ledger rows nobody claims, left behind by a death mid-restore. */
    private suspend fun sweepOrphanKeys() {
        val orphans = try {
            protectionRepository.getOrphanKeys()
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: RuntimeException) {
            return
        }
        if (orphans.isEmpty()) return
        if (!secureSettingsWrapper.hasWriteSecureSettingsPermission()) return

        val restored = orphans.filter { key ->
            secureSettingsWrapper.write(
                settingType = key.settingType,
                key = key.key,
                value = key.originalValue,
            ) is SettingWriteResult.Success
        }

        if (restored.size == orphans.size) {
            runCatching { protectionRepository.deleteOrphanKeys() }
        }
    }

    private suspend fun restoreLocked(session: ProtectionSession): ProtectionResult {
        if (!secureSettingsWrapper.hasWriteSecureSettingsPermission()) {
            return markRecoveryRequired(
                session = session,
                stage = ProtectionStage.RESTORE,
                failures = listOf(
                    ProtectionFailure(reason = ProtectionFailureReason.PERMISSION_DENIED),
                ),
            )
        }

        val markedRestoring = try {
            protectionRepository.updateStatus(
                token = session.token,
                status = ProtectionSessionStatus.RESTORING,
                updatedAtMillis = now(),
            )
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: RuntimeException) {
            false
        }
        if (!markedRestoring) {
            return ProtectionResult.Failure(
                stage = ProtectionStage.PERSIST,
                failures = listOf(
                    ProtectionFailure(
                        reason = ProtectionFailureReason.PERSISTENCE_FAILED,
                        message = "Could not mark the protection session as restoring",
                    ),
                ),
            )
        }

        // Only the keys this session is the last claimant of. Anything another app still wants is
        // left enforced.
        val releasedKeys = try {
            protectionRepository.getKeysReleasedBy(session.token)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: RuntimeException) {
            return markRecoveryRequired(
                session = session,
                stage = ProtectionStage.PERSIST,
                failures = listOf(exception.asPersistenceFailure()),
            )
        }

        val failures = restoreKeys(releasedKeys.inSessionWriteOrder(session))
        if (failures.isNotEmpty()) {
            return markRecoveryRequired(session, ProtectionStage.RESTORE, failures)
        }

        // Originals are written before the rows go away. A death in between leaves the device already
        // correct plus a sweepable orphan; the other order would destroy the original first.
        val cleared = try {
            protectionRepository.clearSession(session.token)
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: RuntimeException) {
            false
        }
        if (!cleared) {
            return markRecoveryRequired(
                session = session,
                stage = ProtectionStage.PERSIST,
                failures = listOf(
                    ProtectionFailure(
                        reason = ProtectionFailureReason.PERSISTENCE_FAILED,
                        message = "Settings were restored but the session could not be cleared",
                    ),
                ),
            )
        }

        return ProtectionResult.Success(app = null)
    }

    private suspend fun handleApplyFailure(
        session: ProtectionSession,
        attemptedNewKeys: List<ProtectedKey>,
        failure: ProtectionFailure,
    ): ProtectionResult {
        val rollbackFailures = restoreKeys(attemptedNewKeys)
        if (rollbackFailures.isNotEmpty()) {
            return markRecoveryRequired(
                session = session,
                stage = ProtectionStage.APPLY,
                failures = listOf(failure) + rollbackFailures,
            )
        }

        val cleared = try {
            protectionRepository.clearSession(session.token)
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: RuntimeException) {
            false
        }
        if (!cleared) {
            return markRecoveryRequired(
                session = session,
                stage = ProtectionStage.PERSIST,
                failures = listOf(
                    failure,
                    ProtectionFailure(
                        reason = ProtectionFailureReason.PERSISTENCE_FAILED,
                        message = "Rollback succeeded but the session could not be cleared",
                    ),
                ),
            )
        }

        return ProtectionResult.Failure(
            stage = ProtectionStage.APPLY,
            failures = listOf(failure),
            rollbackSucceeded = true,
        )
    }

    private suspend fun restoreKeys(keys: List<ProtectedKey>): List<ProtectionFailure> {
        val writeFailures = keys.asReversed().mapNotNull { key ->
            when (
                val result = secureSettingsWrapper.write(
                    settingType = key.settingType,
                    key = key.key,
                    value = key.originalValue,
                )
            ) {
                is SettingWriteResult.Success -> null
                is SettingWriteResult.Failure -> result.asFailure(key.settingType, key.key)
            }
        }

        return writeFailures +
            verifyExpectedValues(keys.map { ExpectedValue(it.settingType, it.key, it.originalValue) })
    }

    private suspend fun verifyExpectedValues(
        expected: List<ExpectedValue>,
    ): List<ProtectionFailure> = expected.mapNotNull { entry ->
        when (val result = secureSettingsWrapper.read(entry.settingType, entry.key)) {
            is SettingReadResult.Success -> if (result.value == entry.value) {
                null
            } else {
                ProtectionFailure(
                    reason = ProtectionFailureReason.VERIFICATION_FAILED,
                    settingType = entry.settingType,
                    key = entry.key,
                    expectedValue = entry.value,
                    actualValue = result.value,
                    message = "The final settings bundle did not match the expected value",
                )
            }

            is SettingReadResult.Failure -> result.asFailure(entry.settingType, entry.key)
        }
    }

    private suspend fun markRecoveryRequired(
        session: ProtectionSession,
        stage: ProtectionStage,
        failures: List<ProtectionFailure>,
    ): ProtectionResult {
        val reason = failures.joinToString(separator = "; ") { failure ->
            listOfNotNull(failure.key, failure.reason.name, failure.message).joinToString(": ")
        }
        val updatedAtMillis = now()
        val marked = try {
            protectionRepository.updateStatus(
                token = session.token,
                status = ProtectionSessionStatus.RECOVERY_REQUIRED,
                updatedAtMillis = updatedAtMillis,
                lastError = reason,
            )
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: RuntimeException) {
            false
        }

        if (!marked) {
            return ProtectionResult.Failure(
                stage = ProtectionStage.PERSIST,
                failures = failures + ProtectionFailure(
                    reason = ProtectionFailureReason.PERSISTENCE_FAILED,
                    message = "Could not persist the recovery-required state after $stage",
                ),
                rollbackSucceeded = false,
            )
        }

        return ProtectionResult.RecoveryRequired(
            app = session.copy(
                status = ProtectionSessionStatus.RECOVERY_REQUIRED,
                updatedAtMillis = updatedAtMillis,
                lastError = reason,
            ).asProtectedApp(updatedAtMillis),
            failures = failures,
        )
    }

    private fun validate(settings: List<AppSetting>): List<ProtectionFailure> {
        val failures = settings.filter { it.key.isBlank() }.map { setting ->
            ProtectionFailure(
                reason = ProtectionFailureReason.INVALID_KEY,
                settingType = setting.settingType,
                key = setting.key,
                message = "Setting key must not be blank",
            )
        }.toMutableList()

        settings.groupBy { it.settingType to it.key }
            .filterValues { duplicates -> duplicates.size > 1 }
            .forEach { (identity, duplicates) ->
                failures += ProtectionFailure(
                    reason = ProtectionFailureReason.DUPLICATE_KEY,
                    settingType = identity.first,
                    key = identity.second,
                    message = if (duplicates.map { it.valueOnLaunch }.distinct().size > 1) {
                        "Duplicate key has conflicting protected values"
                    } else {
                        "Duplicate key must be removed from the profile"
                    },
                )
            }

        return failures
    }

    private fun SettingReadResult.Failure.asFailure(
        settingType: SettingType,
        key: String,
    ): ProtectionFailure = ProtectionFailure(
        reason = reason,
        settingType = settingType,
        key = key,
        message = message,
    )

    private fun SettingWriteResult.Failure.asFailure(
        settingType: SettingType,
        key: String,
    ): ProtectionFailure = ProtectionFailure(
        reason = reason,
        settingType = settingType,
        key = key,
        expectedValue = expectedValue,
        actualValue = actualValue,
        message = message,
    )

    private fun RuntimeException.asPersistenceFailure(): ProtectionFailure = ProtectionFailure(
        reason = ProtectionFailureReason.PERSISTENCE_FAILED,
        message = message,
    )

    private fun ProtectedSetting.expected(value: String?): ExpectedValue = ExpectedValue(settingType, key, value)

    /** Restores unwind in the reverse of the order the values were applied in. */
    private fun List<ProtectedKey>.inSessionWriteOrder(session: ProtectionSession): List<ProtectedKey> {
        val orderByIdentity = session.protectedSettings.associate {
            (it.settingType to it.key) to it.writeOrder
        }
        return sortedBy { orderByIdentity[it.settingType to it.key] ?: Int.MAX_VALUE }
    }

    /**
     * Puts gate settings last.
     *
     * `adb_enabled` and `adb_wifi_enabled` are only writable while developer options is on, so turning
     * the gate off first made the rest of the profile fail. Restores walk this list in reverse, which
     * means the gate comes back on first and the dependent keys are writable again — that reversal is
     * what produced Samsung's "turn on Developer options first" rejection.
     *
     * Ordering here rather than in the template so profiles that already exist are fixed too.
     */
    private fun List<AppSetting>.inDependencyOrder(): List<AppSetting> = sortedBy { setting ->
        if (setting.key in GATE_KEYS) 1 else 0
    }

    private fun ProtectionSession.satisfies(
        requestedComponentName: String,
        requestedMode: ProtectionMode,
    ): Boolean {
        if (status != ProtectionSessionStatus.ACTIVE || componentName != requestedComponentName) {
            return false
        }
        // Foreground and one-shot are interchangeable in both directions. The keys are already
        // claimed and the values already written, so whoever got here first owns the session and the
        // other path adopts it. Accepting only foreground -> one-shot meant launching an armed app
        // from Geto created a one-shot session that the coordinator then refused to adopt, leaving
        // that app stuck in RECOVERY_REQUIRED until its row was cleared.
        return mode == requestedMode ||
            (mode in INTERCHANGEABLE_MODES && requestedMode in INTERCHANGEABLE_MODES)
    }

    private companion object {
        /**
         * Settings that other settings depend on. Writing a dependent while its gate is off is
         * rejected by the platform.
         */
        val GATE_KEYS = setOf("development_settings_enabled")

        /** Modes that describe the same applied state and may therefore adopt each other's session. */
        val INTERCHANGEABLE_MODES = setOf(ProtectionMode.FOREGROUND, ProtectionMode.ONE_SHOT)
    }

    private data class ExpectedValue(
        val settingType: SettingType,
        val key: String,
        val value: String?,
    )
}

/** Convenience for callers that hold a [ProtectedApp] rather than a raw token. */
suspend fun ProtectionController.restore(app: ProtectedApp): ProtectionResult = restore(app.sessionToken)
