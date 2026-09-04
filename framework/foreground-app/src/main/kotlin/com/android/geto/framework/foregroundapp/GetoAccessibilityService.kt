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

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Reports which app is in front.
 *
 * An accessibility service is used rather than polling `UsageStatsManager`: Android pushes the event,
 * so nothing runs between app switches, and the reaction is immediate instead of up to a second late.
 * The subscription is narrowed to window-state changes in `accessibility_service_config.xml`, and this
 * class deliberately never touches [AccessibilityEvent.getSource] or the window content — it needs
 * only a package name.
 */
@AndroidEntryPoint
class GetoAccessibilityService : AccessibilityService() {

    @Inject
    lateinit var foregroundApp: DefaultForegroundAppWrapper

    /**
     * Packages whose windows are not an app coming to the foreground.
     *
     * Resolved once on connect rather than per event: this is the hot path, and enumerating input
     * methods is a binder call.
     */
    private var ignoredPackages: Set<String> = emptySet()

    override fun onServiceConnected() {
        super.onServiceConnected()
        ignoredPackages = resolveIgnoredPackages()
        foregroundApp.onRunningChanged(running = true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val packageName = event.packageName?.toString()?.takeIf(String::isNotBlank) ?: return

        // TYPE_WINDOW_STATE_CHANGED also fires for the keyboard, dialogs, the notification shade and
        // vendor overlays. Treating those as "a new app is in front" made protection turn itself off
        // the moment you tapped a text field, so a protected app could be sampled unprotected.
        if (packageName in ignoredPackages) return

        // Keep this cheap: the only work on the hot path is publishing a string, which the wrapper
        // deduplicates. Anything heavier would run for every dialog and screen inside every app.
        foregroundApp.onForegroundPackage(packageName)
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        // Being unbound means app switches stop being visible, so whatever is applied has to come
        // back. Publishing this is what lets the controller restore rather than strand the user.
        foregroundApp.onRunningChanged(running = false)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        foregroundApp.onRunningChanged(running = false)
        super.onDestroy()
    }

    private fun resolveIgnoredPackages(): Set<String> {
        val inputMethods = runCatching {
            getSystemService(InputMethodManager::class.java)
                ?.enabledInputMethodList
                .orEmpty()
                .map { it.packageName }
        }.getOrDefault(emptyList())

        return buildSet {
            add(packageName)
            add(SYSTEM_UI_PACKAGE)
            addAll(inputMethods)
        }
    }

    private companion object {
        const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    }
}
