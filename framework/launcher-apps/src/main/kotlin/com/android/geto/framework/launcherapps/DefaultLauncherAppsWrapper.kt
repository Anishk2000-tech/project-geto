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
package com.android.geto.framework.launcherapps

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.os.Handler
import android.os.Looper
import android.os.Process.myUserHandle
import android.os.UserHandle
import com.android.geto.domain.common.dispatcher.Dispatcher
import com.android.geto.domain.common.dispatcher.GetoDispatchers.Default
import com.android.geto.domain.framework.LauncherAppsWrapper
import com.android.geto.domain.framework.PackageManagerWrapper
import com.android.geto.domain.model.LauncherAppsActivityInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Maintains one process-wide launcher metadata snapshot. Package callbacks are reduced by a
 * single coroutine so a slow initial query can never race a newer package update.
 */
@Singleton
internal class DefaultLauncherAppsWrapper @Inject constructor(
    @Dispatcher(Default) defaultDispatcher: CoroutineDispatcher,
    @ApplicationContext context: Context,
    private val packageManagerWrapper: PackageManagerWrapper,
) : LauncherAppsWrapper,
    AndroidLauncherAppsWrapper {
    private val launcherApps =
        context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
    private val currentUser = myUserHandle()
    private val scope = CoroutineScope(SupervisorJob() + defaultDispatcher)
    private val events = Channel<PackageEvent>(capacity = Channel.UNLIMITED)
    private val cachedActivities = linkedMapOf<String, LauncherAppsActivityInfo>()
    private val activityList = MutableSharedFlow<Result<List<LauncherAppsActivityInfo>>>(replay = 1)

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageAdded(packageName: String?, user: UserHandle?) {
            enqueuePackageUpdate(packageName, user)
        }

        override fun onPackageRemoved(packageName: String?, user: UserHandle?) {
            enqueuePackageRemoval(packageName, user)
        }

        override fun onPackageChanged(packageName: String?, user: UserHandle?) {
            enqueuePackageUpdate(packageName, user)
        }

        override fun onPackagesAvailable(
            packageNames: Array<out String>?,
            user: UserHandle?,
            replacing: Boolean,
        ) {
            if (user != currentUser) return
            packageNames.orEmpty().forEach { events.trySend(PackageEvent.Update(it)) }
        }

        override fun onPackagesUnavailable(
            packageNames: Array<out String>?,
            user: UserHandle?,
            replacing: Boolean,
        ) {
            if (user != currentUser || replacing) return
            packageNames.orEmpty().forEach { events.trySend(PackageEvent.Remove(it)) }
        }
    }

    init {
        // Register first so changes occurring during the initial query are queued and replayed.
        launcherApps.registerCallback(callback, Handler(Looper.getMainLooper()))
        scope.launch { processEvents() }
        refresh()
    }

    override fun getActivityListFlow(): Flow<Result<List<LauncherAppsActivityInfo>>> = activityList.asSharedFlow()

    override fun refresh() {
        events.trySend(PackageEvent.RefreshAll)
    }

    private fun enqueuePackageUpdate(packageName: String?, user: UserHandle?) {
        if (user == currentUser && packageName != null) {
            events.trySend(PackageEvent.Update(packageName))
        }
    }

    private fun enqueuePackageRemoval(packageName: String?, user: UserHandle?) {
        if (user == currentUser && packageName != null) {
            events.trySend(PackageEvent.Remove(packageName))
        }
    }

    private suspend fun processEvents() {
        for (firstEvent in events) {
            val batch = buildList {
                add(firstEvent)
                while (true) {
                    add(events.tryReceive().getOrNull() ?: break)
                }
            }

            runCatching {
                if (batch.any { it == PackageEvent.RefreshAll }) {
                    loadAllActivities()
                } else {
                    applyIncrementalEvents(batch)
                }
            }.onSuccess { updatedActivities ->
                cachedActivities.clear()
                cachedActivities.putAll(updatedActivities)
                activityList.emit(Result.success(snapshot()))
            }.onFailure { throwable ->
                activityList.emit(Result.failure(throwable))
            }
        }
    }

    private suspend fun loadAllActivities(): Map<String, LauncherAppsActivityInfo> {
        val launcherActivityInfos = launcherApps.getActivityList(null, currentUser)
        val packageNames = launcherActivityInfos.mapTo(mutableSetOf()) { it.applicationInfo.packageName }
        val lastUpdateTimes = packageManagerWrapper.getLastUpdateTimes(packageNames)

        return launcherActivityInfos.associate { launcherActivityInfo ->
            val packageName = launcherActivityInfo.applicationInfo.packageName
            val activityInfo = launcherActivityInfo.toLauncherAppsActivityInfo(
                lastUpdateTime = lastUpdateTimes[packageName] ?: 0L,
            )
            activityInfo.componentName to activityInfo
        }
    }

    private suspend fun applyIncrementalEvents(
        batch: List<PackageEvent>,
    ): Map<String, LauncherAppsActivityInfo> {
        val finalPackageActions = linkedMapOf<String, PackageEvent>()
        batch.forEach { event ->
            when (event) {
                is PackageEvent.Remove -> finalPackageActions[event.packageName] = event
                is PackageEvent.Update -> finalPackageActions[event.packageName] = event
                PackageEvent.RefreshAll -> Unit
            }
        }

        val updatedActivities = LinkedHashMap(cachedActivities)
        finalPackageActions.forEach { (packageName, event) ->
            updatedActivities.entries.removeAll { it.value.packageName == packageName }

            if (event is PackageEvent.Update) {
                val lastUpdateTime = packageManagerWrapper.getLastUpdateTime(packageName)
                launcherApps.getActivityList(packageName, currentUser)
                    .map { it.toLauncherAppsActivityInfo(lastUpdateTime) }
                    .forEach { updatedActivities[it.componentName] = it }
            }
        }
        return updatedActivities
    }

    private fun snapshot(): List<LauncherAppsActivityInfo> = cachedActivities.values
        .sortedWith(
            compareBy<LauncherAppsActivityInfo, String>(String.CASE_INSENSITIVE_ORDER) {
                it.activityLabel
            }
                .thenBy { it.componentName },
        )

    private fun LauncherActivityInfo.toLauncherAppsActivityInfo(
        lastUpdateTime: Long,
    ): LauncherAppsActivityInfo = LauncherAppsActivityInfo(
        componentName = componentName.flattenToString(),
        packageName = applicationInfo.packageName,
        activityLabel = label.toString().ifBlank { applicationInfo.packageName },
        firstInstallTime = firstInstallTime,
        lastUpdateTime = lastUpdateTime,
        isSystem = packageManagerWrapper.isSystem(flags = applicationInfo.flags),
    )

    override fun startMainActivity(componentName: String): LaunchResult {
        val parsedComponentName = ComponentName.unflattenFromString(componentName)
            ?: return LaunchResult.InvalidComponent

        val isAvailable = try {
            launcherApps.getActivityList(parsedComponentName.packageName, currentUser)
                .any { it.componentName == parsedComponentName }
        } catch (_: SecurityException) {
            return LaunchResult.SecurityFailure
        } catch (_: IllegalStateException) {
            return LaunchResult.NotFoundOrUnavailable
        }
        if (!isAvailable) return LaunchResult.NotFoundOrUnavailable

        return try {
            launcherApps.startMainActivity(
                parsedComponentName,
                currentUser,
                null,
                null,
            )
            LaunchResult.Success
        } catch (_: SecurityException) {
            LaunchResult.SecurityFailure
        } catch (_: ActivityNotFoundException) {
            LaunchResult.NotFoundOrUnavailable
        } catch (_: IllegalStateException) {
            LaunchResult.NotFoundOrUnavailable
        }
    }

    private sealed interface PackageEvent {
        data object RefreshAll : PackageEvent

        data class Update(val packageName: String) : PackageEvent

        data class Remove(val packageName: String) : PackageEvent
    }
}
