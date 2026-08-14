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
 * What Shizuku can do for us right now. Each state maps to exactly one thing the user can do about
 * it, so the UI never has to guess.
 */
enum class ShizukuState {
    /** Still working out which of the states below applies. */
    Unknown,

    /** The Shizuku app is not installed at all. */
    NotInstalled,

    /** Shizuku is installed but its service is not running, so there is no binder to talk to. */
    NotRunning,

    /** Shizuku is running but too old to bind a user service. */
    OutdatedVersion,

    /** Shizuku is running; it has not been asked for permission yet, or the ask was dismissed. */
    PermissionRequired,

    /** The user explicitly denied Geto in Shizuku, and Shizuku will not ask again. */
    PermissionDenied,

    /** Shizuku is running and Geto is authorised, so a grant can be attempted. */
    Ready,
}
