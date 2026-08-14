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

/**
 * Bounded retry for the long-lived collectors and the service restart chain.
 *
 * These all used to retry on a flat 2-second timer with no cap. `SQLiteException` is a
 * `RuntimeException`, so a corrupt database or a permanently blocked service start meant 30 wakeups a
 * minute, with the screen off, for as long as the process lived — and the foreground service
 * guarantees it lives. Growing the delay and eventually stopping bounds that cost, and giving up
 * loudly is what keeps it safe: the caller tells the user rather than failing silently.
 *
 * Not thread-safe; each caller owns its own instance on a single coroutine.
 */
internal class RetryPolicy(
    private val baseMillis: Long = BASE_MILLIS,
    private val maxMillis: Long = MAX_MILLIS,
    private val maxAttempts: Int = MAX_ATTEMPTS,
) {
    private var attempts = 0

    val hasGivenUp: Boolean get() = attempts >= maxAttempts

    /** Call after any clean run, so a later unrelated failure starts from the short delay again. */
    fun reset() {
        attempts = 0
    }

    /**
     * How long to wait before the next attempt, or null once this policy has given up and the caller
     * should stop and report.
     */
    fun nextDelayMillis(): Long? {
        if (hasGivenUp) return null

        val exponent = attempts.coerceAtMost(MAX_EXPONENT)
        attempts++
        return (baseMillis shl exponent).coerceAtMost(maxMillis)
    }

    companion object {
        const val BASE_MILLIS = 2_000L
        const val MAX_MILLIS = 5 * 60 * 1_000L
        const val MAX_ATTEMPTS = 10
        private const val MAX_EXPONENT = 8
    }
}
