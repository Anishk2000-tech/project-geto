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
package com.android.geto.feature.apps

import com.android.geto.domain.model.LauncherAppsActivityInfoData

sealed interface AppsUiState {
    data class Success(
        val launcherAppsActivityInfoData: LauncherAppsActivityInfoData,
        val searchQuery: String,
        val appTags: AppTags = AppTags(),
    ) : AppsUiState

    data class Error(
        val previousData: LauncherAppsActivityInfoData?,
        val searchQuery: String,
        val appTags: AppTags = AppTags(),
    ) : AppsUiState

    data object Loading : AppsUiState
}

/**
 * What to show beside each app in the list: how many Geto settings it has saved, and whether it is
 * protected right now. Keyed by component name, matching the grid's own key.
 */
data class AppTags(
    val settingCountByComponentName: Map<String, Int> = emptyMap(),
    /** Set up to apply when opened, whether or not it is applied at this moment. */
    val armedComponentNames: Set<String> = emptySet(),
    /** Applied right now, i.e. this app is in the foreground. */
    val protectedComponentNames: Set<String> = emptySet(),
)
