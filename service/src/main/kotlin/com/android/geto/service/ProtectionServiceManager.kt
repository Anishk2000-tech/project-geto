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

import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.os.Build
import com.android.geto.common.ApplicationScope
import com.android.geto.domain.repository.UserDataRepository
import com.android.geto.framework.notificationmanager.AndroidNotificationManagerWrapper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProtectionServiceManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val userDataRepository: UserDataRepository,
    private val runtime: ProtectionServiceRuntime,
    private val alertNotifier: ProtectionAlertNotifier,
    private val notificationManager: AndroidNotificationManagerWrapper,
    @param:ApplicationScope private val applicationScope: CoroutineScope,
) {
    @Volatile
    private var initialized = false

    private val restartScheduled = AtomicBoolean(false)
    private val restartAttempts = AtomicInteger(0)
    private val runningGeneration = AtomicInteger(0)

    /** Monotonic, so a stable-run reset can tell "quiet" from "restarting just slowly enough". */
    private val totalRestarts = AtomicInteger(0)

    fun initialize() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            initialized = true
        }

        applicationScope.launch { observeProtectionState() }
    }

    fun reconcile() {
        launchSafely { runtime.reconcile() }
    }

    fun reconcileFromVisibleApp() {
        launchSafely {
            runtime.reconcile()
            val userData = userDataRepository.userData.first()
            if (runtime.state.first().needsForeground() && userData.autoRestartProtection) {
                startService(autoRestart = true)
            }
        }
    }

    fun onProtectionEnabled() {
        resetRestartBackoff()
        launchSafely {
            val userData = userDataRepository.userData.first()
            if (runtime.state.first().needsForeground()) {
                startService(autoRestart = userData.autoRestartProtection)
            }
        }
    }

    suspend fun reconcileAfterSystemEvent() {
        try {
            val initialState = runtime.state.first()
            if (initialState !is ProtectionServiceState.Running) return

            initialState.oneShotApps.forEach { app ->
                notifyRecoveryRequired(
                    label = app.label,
                    detail = context.getString(R.string.one_shot_restore_required, app.label),
                )
            }

            if (!initialState.needsForeground) return

            val userData = userDataRepository.userData.first()
            if (!userData.autoRestartProtection) return

            runtime.reconcile()
            if (runtime.state.first().needsForeground()) {
                startService(autoRestart = true)
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: RuntimeException) {
            notifyManagerFailure(exception)
        }
    }

    /**
     * Runs for the whole process lifetime.
     *
     * The retry is bounded and only reports once at the end. It previously retried on a flat
     * two-second timer forever *and* posted a notification on every iteration, so a failure that
     * could not clear itself cost a binder round-trip 30 times a minute with the screen off.
     */
    private suspend fun observeProtectionState() {
        var previousState: ProtectionServiceState? = null
        var previousAutoRestart: Boolean? = null
        var previousIsServiceRunning: Boolean? = null
        val retryPolicy = RetryPolicy()

        while (currentCoroutineContext().isActive) {
            try {
                combine(
                    runtime.state,
                    userDataRepository.userData,
                    ProtectionService.isRunning,
                ) { state, userData, isServiceRunning ->
                    Triple(state, userData.autoRestartProtection, isServiceRunning)
                }
                    .distinctUntilChanged()
                    .collect { (state, autoRestart, isServiceRunning) ->
                        retryPolicy.reset()
                        try {
                            when {
                                state.needsForeground() -> {
                                    val wasEnabledInThisProcess = previousState != null &&
                                        !previousState.needsForeground()
                                    val autoRestartWasJustEnabled =
                                        previousAutoRestart == false && autoRestart
                                    val stoppedUnexpectedly =
                                        previousIsServiceRunning == true && !isServiceRunning

                                    if (stoppedUnexpectedly) {
                                        runningGeneration.incrementAndGet()
                                        totalRestarts.incrementAndGet()
                                    }

                                    when {
                                        isServiceRunning -> {
                                            updateRunningService(autoRestart)
                                            if (previousIsServiceRunning != true) {
                                                scheduleStableRunBackoffReset()
                                            }
                                        }

                                        wasEnabledInThisProcess || autoRestartWasJustEnabled -> {
                                            resetRestartBackoff()
                                            startService(autoRestart)
                                        }

                                        stoppedUnexpectedly && autoRestart ->
                                            scheduleUnexpectedRestart()
                                    }
                                }

                                // Nothing left worth a foreground service: only one-shot sessions,
                                // apps needing recovery, or nothing at all. The service itself posts
                                // the per-app recovery alerts, so this just stops it.
                                state is ProtectionServiceState.Running ||
                                    state is ProtectionServiceState.Inactive -> {
                                    resetRestartBackoff()
                                    ProtectionService.stopWithoutRestore(context)
                                }

                                else -> Unit
                            }
                        } catch (exception: CancellationException) {
                            throw exception
                        } catch (exception: RuntimeException) {
                            notifyManagerFailure(exception)
                        }

                        if (state != ProtectionServiceState.Loading) {
                            previousState = state
                        }
                        previousAutoRestart = autoRestart
                        previousIsServiceRunning = isServiceRunning
                    }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: RuntimeException) {
                val delayMillis = retryPolicy.nextDelayMillis()
                if (delayMillis == null) {
                    // One alert at the end, not one per attempt.
                    notifyManagerFailure(exception)
                    return
                }
                delay(delayMillis)
            }
        }
    }

    private fun updateRunningService(autoRestart: Boolean) {
        try {
            ProtectionService.updateAutoRestart(context, autoRestart)
        } catch (exception: RuntimeException) {
            notifyManagerFailure(exception)
        }
    }

    private fun startService(autoRestart: Boolean): StartOutcome {
        if (ProtectionService.isRunning.value) {
            updateRunningService(autoRestart)
            return StartOutcome.STARTED_OR_UPDATED
        }

        if (
            !notificationManager.areNotificationsEnabled() ||
            !notificationManager.isNotificationChannelEnabled(
                AndroidNotificationManagerWrapper.PROTECTION_NOTIFICATION_CHANNEL_ID,
            )
        ) {
            // Silently returning here meant protection simply never started, with nothing anywhere
            // explaining why. The user has to be told, since only they can re-enable the channel.
            notifyRecoveryRequired(
                label = null,
                detail = context.getString(R.string.protection_blocked_by_notifications),
            )
            return StartOutcome.BLOCKED_BY_NOTIFICATIONS
        }

        return try {
            ProtectionService.start(context, autoRestart)
            StartOutcome.STARTED_OR_UPDATED
        } catch (exception: RuntimeException) {
            notifyManagerFailure(exception)
            StartOutcome.FAILED
        }
    }

    /**
     * Restarts the service after an unexpected stop, backing off and eventually giving up.
     *
     * Without the give-up an unstartable service — `ForegroundServiceStartNotAllowedException` on
     * Android 12+, say — retried every five minutes forever, each attempt costing a preferences read,
     * a database query, a `startForegroundService` binder call and a notification.
     */
    private fun scheduleUnexpectedRestart() {
        if (!restartScheduled.compareAndSet(false, true)) return

        val attempt = restartAttempts.getAndIncrement()
        if (attempt >= MAX_RESTART_ATTEMPTS) {
            restartScheduled.set(false)
            notifyRestartGivenUp()
            return
        }

        val backoffMillis = (RESTART_BASE_MILLIS shl attempt.coerceAtMost(MAX_BACKOFF_EXPONENT))
            .coerceAtMost(RESTART_MAX_MILLIS)

        applicationScope.launch {
            var retry = false
            try {
                delay(backoffMillis)
                val autoRestart = userDataRepository.userData.first().autoRestartProtection
                val state = runtime.state.first()
                if (autoRestart && state.needsForeground() && !ProtectionService.isRunning.value) {
                    retry = startService(autoRestart = true) == StartOutcome.FAILED
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: RuntimeException) {
                notifyManagerFailure(exception)
                retry = true
            } finally {
                restartScheduled.set(false)
                if (retry) scheduleUnexpectedRestart()
            }
        }
    }

    /**
     * Clears the backoff once the service has genuinely settled.
     *
     * The uptime check alone was not enough: a vendor task killer that stops the service just after
     * the threshold reset the counter every cycle, so backoff never engaged and the app restarted at
     * the two-second floor indefinitely. Requiring a quiet stretch of *no restarts* as well means
     * repeated kills still escalate.
     */
    private fun scheduleStableRunBackoffReset() {
        val generation = runningGeneration.incrementAndGet()
        val restartsAtSchedule = totalRestarts.get()
        applicationScope.launch {
            delay(STABLE_RUN_MILLIS)
            if (
                runningGeneration.get() == generation &&
                ProtectionService.isRunning.value &&
                totalRestarts.get() == restartsAtSchedule
            ) {
                restartAttempts.set(0)
            }
        }
    }

    private fun notifyRestartGivenUp() {
        notifyRecoveryRequired(
            label = null,
            detail = context.getString(R.string.protection_stopped_repeated_failures),
        )
    }

    private fun resetRestartBackoff() {
        runningGeneration.incrementAndGet()
        restartAttempts.set(0)
    }

    private fun launchSafely(block: suspend () -> Unit) {
        applicationScope.launch {
            try {
                block()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: RuntimeException) {
                notifyManagerFailure(exception)
            }
        }
    }

    private fun notifyManagerFailure(exception: RuntimeException) {
        val detail = if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            exception is ForegroundServiceStartNotAllowedException
        ) {
            context.getString(R.string.open_geto_to_resume_protection)
        } else {
            context.getString(R.string.protection_manager_failed)
        }
        notifyRecoveryRequired(label = null, detail = detail)
    }

    private fun notifyRecoveryRequired(label: String?, detail: String?) {
        runCatching { alertNotifier.notifyRecoveryRequired(label, detail) }
    }

    private enum class StartOutcome {
        STARTED_OR_UPDATED,
        BLOCKED_BY_NOTIFICATIONS,
        FAILED,
    }

    private companion object {
        const val RESTART_BASE_MILLIS = 2_000L
        const val RESTART_MAX_MILLIS = 5 * 60 * 1_000L
        const val STABLE_RUN_MILLIS = 60_000L
        const val MAX_BACKOFF_EXPONENT = 8
        const val MAX_RESTART_ATTEMPTS = 10
    }
}
