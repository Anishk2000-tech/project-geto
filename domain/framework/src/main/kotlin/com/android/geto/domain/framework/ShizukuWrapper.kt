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

import com.android.geto.domain.model.ShizukuGrantResult
import com.android.geto.domain.model.ShizukuState
import kotlinx.coroutines.flow.Flow

interface ShizukuWrapper {
    /** The current [ShizukuState], kept up to date while at least one caller has [start]ed. */
    val state: Flow<ShizukuState>

    /** Begins observing Shizuku's binder. Reference counted, so overlapping callers are safe. */
    fun start()

    /** Balances a previous [start]. Listeners are only removed once every caller has stopped. */
    fun stop()

    /** Re-evaluates [state] on demand, e.g. after the user returns from the Shizuku app. */
    fun refresh()

    /**
     * Asks Shizuku for permission if needed, binds the privileged user service, and grants
     * `WRITE_SECURE_SETTINGS` to Geto.
     *
     * Suspends until the whole sequence has resolved, so the returned result always describes what
     * actually happened rather than what was merely started. Never returns
     * [ShizukuGrantResult.RequiresRestart] — confirming the grant is visible to this process is the
     * caller's job.
     */
    suspend fun grantWriteSecureSettings(): ShizukuGrantResult
}
