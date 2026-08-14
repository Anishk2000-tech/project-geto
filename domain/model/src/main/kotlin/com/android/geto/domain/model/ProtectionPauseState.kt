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
 * Whether protection is currently watching its settings.
 *
 * A pause leaves the profile applied and the session untouched; it only stops Geto from repairing
 * drift. Undoing a profile is still [ProtectionState] territory.
 */
sealed interface ProtectionPauseState {
    /** Protection is doing its job. */
    data object Running : ProtectionPauseState

    /** Paused until [untilMillis] (epoch millis), after which it resumes on its own. */
    data class PausedUntil(val untilMillis: Long) : ProtectionPauseState

    /** Paused with no deadline; only the user can end it. */
    data object PausedIndefinitely : ProtectionPauseState
}

/** True while protection is paused, whichever kind of pause it is. */
val ProtectionPauseState.isPaused: Boolean
    get() = this != ProtectionPauseState.Running

/** Stored value meaning "not paused". */
const val PAUSE_NOT_PAUSED = 0L

/** Stored value meaning "paused with no deadline". */
const val PAUSE_INDEFINITE = Long.MAX_VALUE

/**
 * Turns a stored deadline into a state, comparing it against [nowMillis] every time.
 *
 * That comparison is what makes a pause self-correcting: a deadline that ran out while nothing was
 * awake to clear it still reads as [ProtectionPauseState.Running], so no timer has to fire on time
 * for the state to be right.
 */
fun pauseStateOf(pausedUntilMillis: Long, nowMillis: Long): ProtectionPauseState = when {
    pausedUntilMillis == PAUSE_NOT_PAUSED -> ProtectionPauseState.Running
    pausedUntilMillis == PAUSE_INDEFINITE -> ProtectionPauseState.PausedIndefinitely
    pausedUntilMillis <= nowMillis -> ProtectionPauseState.Running
    else -> ProtectionPauseState.PausedUntil(pausedUntilMillis)
}

/** The stored deadline for pausing by [duration] starting at [nowMillis]. */
fun ProtectionPauseDuration.deadlineFrom(nowMillis: Long): Long = millis?.let { nowMillis + it } ?: PAUSE_INDEFINITE
