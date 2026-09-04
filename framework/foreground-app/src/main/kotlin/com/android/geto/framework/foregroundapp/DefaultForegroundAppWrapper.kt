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
package com.android.geto.framework.foregroundapp

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.view.accessibility.AccessibilityManager
import com.android.geto.domain.framework.ForegroundAppWrapper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the current foreground package for the rest of the app.
 *
 * The accessibility service is constructed by the system rather than by Hilt, so it publishes here
 * instead of owning the state itself. That also means the state survives the service being torn down
 * and rebound.
 */
@Singleton
class DefaultForegroundAppWrapper @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : ForegroundAppWrapper {

    private val _foregroundPackage = MutableStateFlow<String?>(null)

    /**
     * A window-state event fires for every dialog and screen inside an app, so the same package
     * arrives dozens of times per session. StateFlow only emits on a changed value, which collapses
     * all of those to nothing and keeps collectors from redoing their work.
     */
    override val foregroundPackage = _foregroundPackage.asStateFlow()

    private val _isRunning = MutableStateFlow(false)

    override val isRunning = _isRunning.asStateFlow()

    /**
     * Matched on [AccessibilityServiceInfo.getId], which is the flattened component name and the
     * documented stable identifier. An earlier version compared through `resolveInfo`, which is not
     * always populated — the service was genuinely bound and this still reported it as off.
     */
    override fun isEnabled(): Boolean {
        val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
        val expected = ComponentName(context, GetoAccessibilityService::class.java)
        val expectedIds = setOf(expected.flattenToString(), expected.flattenToShortString())

        return runCatching {
            manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { it.id in expectedIds }
        }.getOrDefault(false)
    }

    internal fun onForegroundPackage(packageName: String?) {
        _foregroundPackage.value = packageName
    }

    internal fun onRunningChanged(running: Boolean) {
        _isRunning.value = running
        if (!running) {
            // Nothing is being observed any more, so claiming to know what is in front would be a lie
            // and would leave a stale package applied.
            _foregroundPackage.value = null
        }
    }
}
