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

/**
 * The outcome of one attempt to grant `WRITE_SECURE_SETTINGS` through Shizuku.
 *
 * This is returned from a suspending call rather than emitted on a flow, so a repeated attempt with
 * the same outcome can never be conflated away, and [Success] can only be reported after the grant
 * has actually been read back.
 */
sealed interface ShizukuGrantResult {
    /** The permission was granted and Geto can already see it. */
    data object Success : ShizukuGrantResult

    /**
     * The grant call went through, but this process still reads the permission as denied. Some ROMs
     * only surface it to a freshly started process, so the user needs to restart Geto.
     */
    data object RequiresRestart : ShizukuGrantResult

    /** Shizuku was not in a state where a grant could even be attempted. */
    data class Failure(val state: ShizukuState) : ShizukuGrantResult

    /** The grant was attempted and something went wrong along the way. */
    data class Error(val message: String?) : ShizukuGrantResult
}
