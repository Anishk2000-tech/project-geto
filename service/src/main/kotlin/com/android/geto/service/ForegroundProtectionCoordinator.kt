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
import com.android.geto.common.ApplicationScope
import com.android.geto.domain.framework.ForegroundAppWrapper
import com.android.geto.domain.model.ProtectionFailure
import com.android.geto.domain.model.ProtectionMode
import com.android.geto.domain.model.ProtectionResult
import com.android.geto.domain.usecase.ProtectionController
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Applies an armed profile while its app is in front and restores the originals the moment it leaves.
 *
 * This is the whole point of the app: writing something like `development_settings_enabled = 0` and
 * leaving it there disables developer options system-wide and takes ADB and Shizuku with it. Scoping
 * the change to the app's time on screen keeps the device usable the rest of the day.
 *
 * Nothing here polls. The foreground flow is fed by push events from the accessibility service, and
 * both timers are single delays armed on demand and cancelled on the normal path.
 */
@Singleton
class ForegroundProtectionCoordinator @Inject constructor(
    private val protectionController: ProtectionController,
    private val foregroundAppWrapper: ForegroundAppWrapper,
    private val protectionServiceManager: ProtectionServiceManager,
    private val alertNotifier: ProtectionAlertNotifier,
    @param:ApplicationContext private val context: Context,
    @param:ApplicationScope private val applicationScope: CoroutineScope,
) {
    /** Whether the user has granted the detector its permission; false means nothing will apply. */
    val isDetectorEnabled: Boolean get() = foregroundAppWrapper.isEnabled()

    /** Whether the detector is actually bound and delivering events right now. */
    val isDetectorRunning: Flow<Boolean> = foregroundAppWrapper.isRunning

    @Volatile
    private var initialized = false

    /** The component currently applied, so a repeat event for the same app does nothing. */
    @Volatile
    private var appliedComponentName: String? = null

    @Volatile
    private var foregroundPackage: String? = null

    private var watchdogJob: Job? = null
    private var pendingRestoreJob: Job? = null

    fun initialize() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            initialized = true
        }

        applicationScope.launch {
            // Nothing should be applied at process start: a session only means "applied right now".
            // Anything found here outlived the process that applied it, including a profile carried
            // over from the old always-on mode.
            runCatching { protectionController.restoreEverythingApplied() }

            observeForeground()
        }
    }

    private suspend fun observeForeground() {
        combine(
            foregroundAppWrapper.foregroundPackage,
            foregroundAppWrapper.isRunning,
            protectionController.armedComponentNames,
        ) { currentPackage, isRunning, armed ->
            Triple(currentPackage, isRunning, armed)
        }
            .distinctUntilChanged()
            .collect { (currentPackage, isRunning, armed) ->
                foregroundPackage = currentPackage

                try {
                    if (!isRunning) {
                        // Without the detector nothing would ever restore these. Fail safe.
                        cancelPendingRestore()
                        restoreApplied()
                        return@collect
                    }

                    val wanted = currentPackage
                        ?.let { pkg -> armed.firstOrNull { it.packageOf() == pkg } }

                    if (wanted == appliedComponentName) {
                        // Still on the protected app — or back on it before the grace period ran out.
                        cancelPendingRestore()
                        return@collect
                    }

                    if (wanted == null) {
                        // Some other window is in front. Restoring immediately made protection
                        // flicker off for any overlay the detector's denylist misses, so give the app
                        // a moment to come back before undoing anything.
                        schedulePendingRestore()
                        return@collect
                    }

                    cancelPendingRestore()
                    restoreApplied()
                    apply(wanted)
                } catch (exception: CancellationException) {
                    throw exception
                } catch (_: RuntimeException) {
                    // Never leave settings applied because of a transient failure.
                    restoreApplied()
                }
            }
    }

    private suspend fun apply(componentName: String) {
        when (val result = protectionController.enable(componentName, ProtectionMode.FOREGROUND)) {
            is ProtectionResult.Success -> {
                appliedComponentName = componentName
                armWatchdog(componentName)
                // Start the service now rather than waiting for the state observer to notice. It
                // exists only to defend the values while they are applied, so its lifetime matches
                // this window.
                protectionServiceManager.onProtectionEnabled()
            }

            // Silence here was the worst part of this path: a rejected key, a missing permission and
            // a conflict all looked identical to "the app just doesn't work".
            else -> reportApplyFailure(componentName, result)
        }
    }

    private fun reportApplyFailure(componentName: String, result: ProtectionResult) {
        val label = ComponentName.unflattenFromString(componentName)?.packageName ?: componentName

        val detail = when (result) {
            is ProtectionResult.PermissionDenied ->
                context.getString(R.string.apply_failed_permission)

            is ProtectionResult.KeyConflict ->
                context.getString(R.string.apply_failed_key, result.key)

            is ProtectionResult.Failure -> result.failures.firstKeyOrNull()
                ?.let { context.getString(R.string.apply_failed_key, it) }
                ?: context.getString(R.string.apply_failed_generic)

            is ProtectionResult.RecoveryRequired -> result.failures.firstKeyOrNull()
                ?.let { context.getString(R.string.apply_failed_key, it) }
                ?: context.getString(R.string.apply_failed_generic)

            else -> context.getString(R.string.apply_failed_generic)
        }

        runCatching { alertNotifier.notifyRecoveryRequired(label = label, detail = detail) }
    }

    private fun List<ProtectionFailure>.firstKeyOrNull(): String? = firstNotNullOfOrNull { it.key }

    private suspend fun restoreApplied() {
        val applied = appliedComponentName ?: return
        appliedComponentName = null
        cancelWatchdog()
        protectionController.restoreApplied(applied)
    }

    /**
     * Restores shortly after the protected app stops being in front.
     *
     * A single delay, not a timer: it exists because a window that is not really an app switch would
     * otherwise undo protection while the user is still in the app.
     */
    private fun schedulePendingRestore() {
        if (appliedComponentName == null || pendingRestoreJob?.isActive == true) return

        pendingRestoreJob = applicationScope.launch {
            delay(RESTORE_GRACE_MILLIS)
            restoreApplied()
        }
    }

    private fun cancelPendingRestore() {
        pendingRestoreJob?.cancel()
        pendingRestoreJob = null
    }

    /**
     * Backstop for a detector that died without saying so — a vendor task killer, say — which would
     * otherwise leave the settings applied indefinitely.
     *
     * It deliberately checks the app is no longer in front. An earlier version did not, so simply
     * using a protected app for longer than the timeout silently turned protection off under the user.
     */
    private fun armWatchdog(componentName: String) {
        cancelWatchdog()
        watchdogJob = applicationScope.launch {
            delay(WATCHDOG_MILLIS)

            val stillInFront = foregroundPackage == componentName.packageOf()
            if (appliedComponentName == componentName && !stillInFront) {
                appliedComponentName = null
                runCatching { protectionController.restoreApplied(componentName) }
            }
        }
    }

    private fun cancelWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
    }

    /** Armed entries are component names; foreground events give a package name. */
    private fun String.packageOf(): String = ComponentName.unflattenFromString(this)?.packageName ?: substringBefore('/')

    private companion object {
        const val WATCHDOG_MILLIS = 15 * 60 * 1_000L
        const val RESTORE_GRACE_MILLIS = 1_500L
    }
}
