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

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The point of this policy is that a permanent failure stops costing battery. Both halves are pinned:
 * the delay has to grow, and the retrying has to end.
 */
class RetryPolicyTest {

    @Test
    fun delayGrowsExponentiallyAndIsCapped() {
        val policy = RetryPolicy(baseMillis = 2_000L, maxMillis = 30_000L, maxAttempts = 10)

        assertEquals(
            listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L),
            List(6) { policy.nextDelayMillis() },
        )
    }

    @Test
    fun retryingEndsAfterTheAttemptCap() {
        val policy = RetryPolicy(maxAttempts = 3)

        repeat(3) { attempt ->
            assertFalse(policy.hasGivenUp, "should still be retrying at attempt $attempt")
            assertTrue(policy.nextDelayMillis() != null)
        }

        assertTrue(policy.hasGivenUp)
        assertNull(policy.nextDelayMillis(), "a given-up policy must not ask for another delay")
    }

    @Test
    fun resetStartsOverSoALaterUnrelatedFailureIsNotPenalised() {
        val policy = RetryPolicy(baseMillis = 2_000L, maxAttempts = 3)
        policy.nextDelayMillis()
        policy.nextDelayMillis()

        policy.reset()

        assertFalse(policy.hasGivenUp)
        assertEquals(2_000L, policy.nextDelayMillis())
    }

    @Test
    fun resetRevivesAGivenUpPolicy() {
        val policy = RetryPolicy(maxAttempts = 1)
        policy.nextDelayMillis()
        assertTrue(policy.hasGivenUp)

        policy.reset()

        assertFalse(policy.hasGivenUp)
    }
}
