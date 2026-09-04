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

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.format.DateFormat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.android.geto.domain.model.ProtectionPauseDuration
import com.android.geto.domain.model.ProtectionPauseState
import com.android.geto.domain.model.ProtectionSessionStatus
import com.android.geto.domain.model.SettingType
import com.android.geto.framework.notificationmanager.AndroidNotificationManagerWrapper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import com.android.geto.framework.notificationmanager.R as notificationR

@AndroidEntryPoint
class ProtectionService : Service() {
    @Inject
    lateinit var notificationManager: AndroidNotificationManagerWrapper

    @Inject
    lateinit var runtime: ProtectionServiceRuntime

    @Inject
    lateinit var alertNotifier: ProtectionAlertNotifier

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob)

    /**
     * One observer instance per URI. `unregisterContentObserver` removes every URI a given instance
     * was registered for, so sharing one instance would make it impossible to stop watching a single
     * key when one app of several turns off.
     */
    private val observersByUri = ConcurrentHashMap<Uri, ContentObserver>()
    private val pendingReapplyJobs = ConcurrentHashMap<ObservedProtectionSetting, Job>()

    /**
     * DROP_OLDEST rather than SUSPEND: `tryEmit` from a ContentObserver cannot suspend, so with the
     * default policy a burst past the buffer silently discarded repairs. Dropping is now explicit and
     * recorded, and [droppedChange] turns it into a full reconcile instead of a lost repair.
     */
    private val changedSettings = MutableSharedFlow<ObservedProtectionSetting>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val droppedChange = AtomicBoolean(false)

    @Volatile
    private var runningState: ProtectionServiceState.Running? = null

    /** Session tokens already alerted about, so a recovery alert is posted once, not per emission. */
    private val alertedRecoveryTokens = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var stickyRestart = false

    @Volatile
    private var explicitStopRequested = false

    @Volatile
    private var interruptionAlertPosted = false

    @Volatile
    private var autoRestartEnabled = false

    override fun onCreate() {
        super.onCreate()
        // Clear any flag left set by a stop request that raced a teardown; otherwise it would
        // suppress this instance's legitimate interruption alert.
        PROCESS_STOP_REQUESTED.set(false)
        _isRunning.update { true }

        ServiceCompat.startForeground(
            this,
            ONGOING_NOTIFICATION_ID,
            startingNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            },
        )

        serviceScope.launch { observeProtectionState() }

        serviceScope.launch {
            changedSettings.collect { setting ->
                pendingReapplyJobs.remove(setting)?.cancel()
                pendingReapplyJobs[setting] = launch {
                    delay(SETTING_CHANGE_COALESCE_MILLIS)
                    val currentJob = coroutineContext[Job]
                    if (currentJob != null) {
                        pendingReapplyJobs.remove(setting, currentJob)
                    }
                    if (currentJob?.isActive != true) return@launch
                    if (!observersByUri.containsKey(setting.toUri())) return@launch

                    // No alert here on failure: reapply moves every affected app to
                    // RECOVERY_REQUIRED, and the state handler alerts per app with the right label.
                    // Alerting here could only guess at which app owns the key, and guessed wrong.
                    runtime.reapply(settingType = setting.settingType, key = setting.key)

                    if (droppedChange.compareAndSet(true, false)) {
                        runtime.reconcile()
                    }
                }
            }
        }
    }

    /**
     * Retried because everything downstream is binder or database work that can throw transiently.
     * Without a retry the collector would die and the service would sit there looking healthy while
     * protecting nothing.
     *
     * The retry is bounded, though: a permanent failure such as a corrupt database would otherwise
     * spin this forever at a fixed interval, screen off, with the foreground service making sure the
     * process is never killed to end it. After [RetryPolicy.MAX_ATTEMPTS] it stops and says so.
     */
    private suspend fun observeProtectionState() {
        val retryPolicy = RetryPolicy()

        while (serviceScope.isActive) {
            try {
                runtime.state.collectLatest { state ->
                    retryPolicy.reset()
                    handleProtectionState(state)
                    awaitNextPauseDeadline(state)
                }
                return
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: RuntimeException) {
                val delayMillis = retryPolicy.nextDelayMillis()
                if (delayMillis == null) {
                    alertNotifier.notifyRecoveryRequired(
                        label = null,
                        detail = getString(R.string.protection_stopped_repeated_failures),
                    )
                    interruptionAlertPosted = true
                    stopForegroundAndSelf(intentional = true)
                    return
                }
                delay(delayMillis)
            }
        }
    }

    /**
     * Sleeps until the soonest pause expiry, then resumes those apps.
     *
     * `collectLatest` cancels this on the next emission, so the timer re-arms whenever the pause set
     * changes. The service stays alive through a pause, which is what lets a plain delay work here.
     */
    private suspend fun awaitNextPauseDeadline(state: ProtectionServiceState) {
        val running = state as? ProtectionServiceState.Running ?: return
        val deadlines = running.apps.mapNotNull { app ->
            (app.pause as? ProtectionPauseState.PausedUntil)?.untilMillis?.let { app.token to it }
        }
        val soonest = deadlines.minOfOrNull { it.second } ?: return

        delay((soonest - System.currentTimeMillis()).coerceAtLeast(0))

        deadlines.filter { it.second <= System.currentTimeMillis() }
            .forEach { (token, _) -> runtime.resume(token) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stickyRestart = true
            // A null intent is only delivered when Android recreates a START_STICKY service.
            // Preserve that contract across process death instead of downgrading the restarted
            // instance to START_NOT_STICKY.
            autoRestartEnabled = true
        }
        autoRestartEnabled = intent?.getBooleanExtra(
            EXTRA_AUTO_RESTART,
            autoRestartEnabled,
        ) ?: autoRestartEnabled

        when (intent?.action) {
            ACTION_STOP_AND_RESTORE -> {
                val sessionToken = intent.getStringExtra(EXTRA_SESSION_TOKEN)
                if (sessionToken != null) {
                    serviceScope.launch {
                        if (!runtime.restore(sessionToken)) {
                            alertNotifier.notifyRecoveryRequired(labelFor(sessionToken))
                        }
                    }
                }
            }

            ACTION_PAUSE -> {
                val sessionToken = intent.getStringExtra(EXTRA_SESSION_TOKEN)
                val duration = intent.getStringExtra(EXTRA_PAUSE_DURATION)
                    ?.let { runCatching { ProtectionPauseDuration.valueOf(it) }.getOrNull() }
                    ?: ProtectionPauseDuration.THIRTY_MINUTES
                if (sessionToken != null) {
                    serviceScope.launch { runtime.pause(sessionToken, duration) }
                }
            }

            ACTION_RESUME -> {
                val sessionToken = intent.getStringExtra(EXTRA_SESSION_TOKEN)
                if (sessionToken != null) {
                    serviceScope.launch { runtime.resume(sessionToken) }
                }
            }

            ACTION_RECONCILE, null -> serviceScope.launch { runtime.reconcile() }

            ACTION_UPDATE_AUTO_RESTART -> Unit
        }

        return if (autoRestartEnabled) START_STICKY else START_NOT_STICKY
    }

    override fun onDestroy() {
        val processStopRequested = PROCESS_STOP_REQUESTED.getAndSet(false)
        if (
            runningState?.needsForeground == true &&
            !explicitStopRequested &&
            !processStopRequested &&
            !interruptionAlertPosted
        ) {
            alertNotifier.notifyMayBeInterrupted(soleProtectedLabel())
        }
        unregisterAllObservers()
        cancelPendingReapplyJobs()
        serviceScope.cancel()
        _isRunning.update { false }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTimeout(startId: Int, fgsType: Int) {
        alertNotifier.notifyRecoveryRequired(
            label = soleProtectedLabel(),
            detail = getString(R.string.protection_timed_out),
        )
        interruptionAlertPosted = true
        stopForegroundAndSelf(intentional = false)
    }

    private fun handleProtectionState(state: ProtectionServiceState) {
        when (state) {
            ProtectionServiceState.Loading -> Unit

            ProtectionServiceState.Inactive -> {
                runningState = null
                unregisterAllObservers()
                cancelPendingReapplyJobs()
                stopForegroundAndSelf(intentional = true)
            }

            is ProtectionServiceState.Running -> {
                runningState = state

                // One app needing recovery must not take protection away from the others. Alert only
                // for apps that have newly entered recovery — this branch runs on every state change,
                // so notifying unconditionally re-posted the same alert repeatedly.
                val recoveryTokens = state.recoveryApps.mapTo(mutableSetOf()) { it.token }
                state.recoveryApps
                    .filter { alertedRecoveryTokens.add(it.token) }
                    .forEach { app -> alertNotifier.notifyRecoveryRequired(app.label, app.message) }
                alertedRecoveryTokens.retainAll(recoveryTokens)

                if (!state.needsForeground) {
                    unregisterAllObservers()
                    cancelPendingReapplyJobs()
                    stopForegroundAndSelf(intentional = true)
                    return
                }

                syncSettingsObservers(state.observedSettings)

                notificationManager.notify(
                    ONGOING_NOTIFICATION_ID,
                    createOngoingNotification(state),
                )

                if (stickyRestart) {
                    stickyRestart = false
                    alertNotifier.notifyInterrupted(state.apps.firstOrNull()?.label)
                }
            }
        }
    }

    /** Adds and removes only what changed, so one app turning off leaves the others watching. */
    private fun syncSettingsObservers(settings: Set<ObservedProtectionSetting>) {
        val wanted = settings.associateBy { it.toUri() }

        (observersByUri.keys - wanted.keys).forEach { uri ->
            observersByUri.remove(uri)?.let { observer ->
                runCatching { contentResolver.unregisterContentObserver(observer) }
            }
        }

        (wanted.keys - observersByUri.keys).forEach { uri ->
            val setting = wanted.getValue(uri)
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean, changedUri: Uri?) {
                    if (!changedSettings.tryEmit(setting)) {
                        // Whatever was dropped still has to be repaired; a reconcile covers all keys.
                        droppedChange.set(true)
                    }
                }
            }
            observersByUri[uri] = observer
            runCatching { contentResolver.registerContentObserver(uri, false, observer) }
                .onFailure {
                    // Some OEM ROMs refuse to observe particular settings. That key is now unwatched,
                    // which the user needs to know about rather than discovering it as silent drift.
                    observersByUri.remove(uri)
                    alertNotifier.notifyRecoveryRequired(
                        label = soleProtectedLabel(),
                        detail = getString(R.string.setting_cannot_be_watched, setting.key),
                    )
                }
        }
    }

    private fun unregisterAllObservers() {
        observersByUri.values.forEach { observer ->
            runCatching { contentResolver.unregisterContentObserver(observer) }
        }
        observersByUri.clear()
    }

    private fun cancelPendingReapplyJobs() {
        pendingReapplyJobs.values.forEach(Job::cancel)
        pendingReapplyJobs.clear()
    }

    /**
     * A label only when exactly one app is protected. With several, naming any one of them would be a
     * guess, and the notifier already has a correct app-agnostic message.
     */
    private fun soleProtectedLabel(): String? = runningState?.apps?.singleOrNull()?.label

    private fun labelFor(sessionToken: String): String? = runningState?.apps?.firstOrNull { it.token == sessionToken }?.label

    private fun startingNotification(): Notification = notificationBuilder()
        .setContentTitle(getString(R.string.protection_active))
        .setContentText(getString(R.string.starting_protection))
        .build()

    private fun createOngoingNotification(state: ProtectionServiceState.Running): Notification {
        val watched = state.apps.filter { it.mode == com.android.geto.domain.model.ProtectionMode.FOREGROUND }
        val single = watched.singleOrNull()

        val builder = notificationBuilder()

        return if (single != null) {
            // Keep the familiar single-app wording and per-app actions.
            builder
                .setContentTitle(
                    getString(
                        if (single.pause != ProtectionPauseState.Running) {
                            R.string.protection_paused
                        } else {
                            R.string.protection_active
                        },
                    ),
                )
                .setContentText(single.notificationText())
                .addAppActions(single)
                .build()
        } else {
            val pausedCount = watched.count { it.pause != ProtectionPauseState.Running }
            builder
                .setContentTitle(
                    if (pausedCount > 0) {
                        getString(R.string.protecting_apps_with_paused, watched.size, pausedCount)
                    } else {
                        resources.getQuantityString(
                            R.plurals.protecting_apps,
                            watched.size,
                            watched.size,
                        )
                    },
                )
                .setContentText(watched.joinToString(separator = ", ") { it.label })
                .setStyle(
                    NotificationCompat.InboxStyle().also { style ->
                        watched.forEach { style.addLine(it.notificationText()) }
                    },
                )
                // A notification action cannot ask which app, so with several protected the only
                // sensible affordance is to open the list.
                .addAction(
                    android.R.drawable.ic_menu_manage,
                    getString(R.string.manage_protection),
                    contentPendingIntent(),
                )
                .build()
        }
    }

    private fun NotificationCompat.Builder.addAppActions(
        app: ProtectedAppStatus,
    ): NotificationCompat.Builder = apply {
        addAction(
            android.R.drawable.ic_menu_close_clear_cancel,
            getString(R.string.stop_and_restore),
            servicePendingIntent(
                action = ACTION_STOP_AND_RESTORE,
                sessionToken = app.token,
                requestCode = STOP_REQUEST_CODE,
            ),
        )

        val paused = app.pause != ProtectionPauseState.Running
        addAction(
            if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
            getString(if (paused) R.string.resume_now else R.string.pause_for_thirty_minutes),
            servicePendingIntent(
                action = if (paused) ACTION_RESUME else ACTION_PAUSE,
                sessionToken = app.token,
                requestCode = PAUSE_REQUEST_CODE,
                pauseDuration = ProtectionPauseDuration.THIRTY_MINUTES.takeIf { !paused },
            ),
        )
    }

    private fun ProtectedAppStatus.notificationText(): String = when {
        status == ProtectionSessionStatus.RECOVERY_REQUIRED -> getString(
            R.string.app_needs_attention,
            label,
        )

        pause is ProtectionPauseState.PausedUntil -> getString(
            R.string.paused_resumes_at,
            label,
            DateFormat.getTimeFormat(this@ProtectionService)
                .format(Date((pause as ProtectionPauseState.PausedUntil).untilMillis)),
        )

        pause == ProtectionPauseState.PausedIndefinitely -> getString(
            R.string.paused_until_resumed,
            label,
        )

        else -> getString(R.string.protecting_profile, label)
    }

    private fun notificationBuilder(): NotificationCompat.Builder = NotificationCompat.Builder(
        this,
        AndroidNotificationManagerWrapper.PROTECTION_NOTIFICATION_CHANNEL_ID,
    )
        .setSmallIcon(notificationR.drawable.baseline_settings_24)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(contentPendingIntent())

    private fun contentPendingIntent(): PendingIntent? = packageManager.getLaunchIntentForPackage(packageName)?.let {
        PendingIntent.getActivity(
            this,
            CONTENT_REQUEST_CODE,
            it,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun servicePendingIntent(
        action: String,
        sessionToken: String,
        requestCode: Int,
        pauseDuration: ProtectionPauseDuration? = null,
    ): PendingIntent {
        val intent = Intent(this, ProtectionService::class.java).apply {
            this.action = action
            // Distinct data keeps these PendingIntents from collapsing into one another.
            data = Uri.parse("geto-protection:$action:$sessionToken")
            putExtra(EXTRA_SESSION_TOKEN, sessionToken)
            pauseDuration?.let { putExtra(EXTRA_PAUSE_DURATION, it.name) }
        }

        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun stopForegroundAndSelf(intentional: Boolean) {
        explicitStopRequested = intentional
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun ObservedProtectionSetting.toUri(): Uri = when (settingType) {
        SettingType.SYSTEM -> Settings.System.getUriFor(key)
        SettingType.SECURE -> Settings.Secure.getUriFor(key)
        SettingType.GLOBAL -> Settings.Global.getUriFor(key)
    }

    companion object {
        private const val ONGOING_NOTIFICATION_ID = 20_001
        private const val STOP_REQUEST_CODE = 20_011
        private const val CONTENT_REQUEST_CODE = 20_012
        private const val PAUSE_REQUEST_CODE = 20_013
        private const val SETTING_CHANGE_COALESCE_MILLIS = 500L
        private const val EXTRA_AUTO_RESTART = "com.android.geto.extra.AUTO_RESTART_PROTECTION"
        private const val EXTRA_SESSION_TOKEN = "com.android.geto.extra.PROTECTION_SESSION_TOKEN"
        private const val EXTRA_PAUSE_DURATION = "com.android.geto.extra.PROTECTION_PAUSE_DURATION"

        const val ACTION_RECONCILE = "com.android.geto.action.RECONCILE_PROTECTION"
        const val ACTION_UPDATE_AUTO_RESTART =
            "com.android.geto.action.UPDATE_AUTO_RESTART_PROTECTION"
        const val ACTION_STOP_AND_RESTORE = "com.android.geto.action.STOP_AND_RESTORE_PROTECTION"
        const val ACTION_PAUSE = "com.android.geto.action.PAUSE_PROTECTION"
        const val ACTION_RESUME = "com.android.geto.action.RESUME_PROTECTION"

        private val _isRunning = MutableStateFlow(false)
        val isRunning = _isRunning.asStateFlow()
        private val PROCESS_STOP_REQUESTED = AtomicBoolean(false)

        fun start(context: Context, autoRestart: Boolean) {
            val intent = Intent(context, ProtectionService::class.java).apply {
                action = ACTION_RECONCILE
                putExtra(EXTRA_AUTO_RESTART, autoRestart)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun updateAutoRestart(context: Context, autoRestart: Boolean) {
            val intent = Intent(context, ProtectionService::class.java).apply {
                action = ACTION_UPDATE_AUTO_RESTART
                putExtra(EXTRA_AUTO_RESTART, autoRestart)
            }
            context.startService(intent)
        }

        fun stopWithoutRestore(context: Context) {
            if (isRunning.value) {
                PROCESS_STOP_REQUESTED.set(true)
            }
            context.stopService(Intent(context, ProtectionService::class.java))
        }
    }
}
