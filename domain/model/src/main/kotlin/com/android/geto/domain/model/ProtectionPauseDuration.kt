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
 * How long protection should stay paused.
 *
 * A null [millis] means the pause has no deadline and only ends when the user resumes it.
 */
enum class ProtectionPauseDuration(val millis: Long?) {
    TEN_MINUTES(10 * 60 * 1_000L),
    THIRTY_MINUTES(30 * 60 * 1_000L),
    ONE_HOUR(60 * 60 * 1_000L),
    INDEFINITE(null),
}
