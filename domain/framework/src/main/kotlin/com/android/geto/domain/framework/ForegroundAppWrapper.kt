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
package com.android.geto.domain.framework

import kotlinx.coroutines.flow.Flow

/**
 * Reports which app is in front, so a profile can be applied only while its app is actually open.
 *
 * Applying settings permanently is what makes Geto dangerous: turning developer options off and
 * leaving it off takes ADB and Shizuku with it. Scoping the change to the moments the target app is
 * on screen keeps the device usable the rest of the time.
 *
 * The implementation is push-based — Android delivers a window-change event — so observing this costs
 * nothing while nothing changes.
 */
interface ForegroundAppWrapper {
    /** Package name of the foreground app, or null when it is not known yet. */
    val foregroundPackage: Flow<String?>

    /**
     * Whether the detector is actually running. False means Geto cannot see app switches at all, so
     * nothing will be applied and anything currently applied has to be restored.
     */
    val isRunning: Flow<Boolean>

    /** True when the user has granted the permission the detector needs. */
    fun isEnabled(): Boolean
}
