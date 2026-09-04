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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.ImageLoader
import com.android.geto.domain.model.LauncherAppsActivityInfoData
import com.android.geto.domain.model.SortLauncherAppsActivityInfo
import com.android.geto.domain.model.SortOrderLauncherAppsActivityInfo
import com.android.geto.domain.repository.AppSettingsRepository
import com.android.geto.domain.repository.UserDataRepository
import com.android.geto.domain.usecase.GetLauncherAppsActivityInfosUseCase
import com.android.geto.domain.usecase.ProtectionController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class AppsViewModel @Inject constructor(
    private val getLauncherAppsActivityInfosUseCase: GetLauncherAppsActivityInfosUseCase,
    private val userDataRepository: UserDataRepository,
    private val appSettingsRepository: AppSettingsRepository,
    private val protectionController: ProtectionController,
    val imageLoader: ImageLoader,
) : ViewModel() {
    private val searchText = MutableStateFlow("")
    private var lastSuccessfulData: LauncherAppsActivityInfoData? = null

    private val debouncedSearchText = searchText
        .flatMapLatest { text ->
            if (text.isBlank()) {
                flowOf("")
            } else {
                flow {
                    delay(SEARCH_DEBOUNCE_MILLIS)
                    emit(text.trim())
                }
            }
        }

    /** Settings counts and live protection, folded into one object so the list stays a plain map. */
    private val appTags = combine(
        appSettingsRepository.appSettingsFlow,
        protectionController.armedComponentNames,
        protectionController.state,
    ) { appSettings, armed, protectionState ->
        AppTags(
            settingCountByComponentName = appSettings.groupingBy { it.componentName }.eachCount(),
            armedComponentNames = armed,
            protectedComponentNames = protectionState.apps.mapTo(mutableSetOf()) {
                it.componentName
            },
        )
    }.distinctUntilChanged()

    val appsUiState = combine(
        getLauncherAppsActivityInfosUseCase(),
        debouncedSearchText,
        appTags,
    ) { result, query, tags ->
        result.fold(
            onSuccess = { data ->
                lastSuccessfulData = data
                AppsUiState.Success(
                    launcherAppsActivityInfoData = data.filteredBy(query),
                    searchQuery = query,
                    appTags = tags,
                )
            },
            onFailure = {
                AppsUiState.Error(
                    previousData = lastSuccessfulData?.filteredBy(query),
                    searchQuery = query,
                    appTags = tags,
                )
            },
        )
    }.catch {
        emit(
            AppsUiState.Error(
                previousData = lastSuccessfulData?.filteredBy(searchText.value),
                searchQuery = searchText.value,
            ),
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        AppsUiState.Loading,
    )

    fun search(text: String) {
        searchText.value = text
    }

    fun retry() {
        getLauncherAppsActivityInfosUseCase.refresh()
    }

    fun updateSortLauncherAppsActivityInfo(sortLauncherAppsActivityInfo: SortLauncherAppsActivityInfo) {
        viewModelScope.launch {
            userDataRepository.updateSortLauncherAppsActivityInfo(sortLauncherAppsActivityInfo = sortLauncherAppsActivityInfo)
        }
    }

    fun updateSortOrderLauncherAppsActivityInfo(sortOrderLauncherAppsActivityInfo: SortOrderLauncherAppsActivityInfo) {
        viewModelScope.launch {
            userDataRepository.updateSortOrderLauncherAppsActivityInfo(
                sortOrderLauncherAppsActivityInfo = sortOrderLauncherAppsActivityInfo,
            )
        }
    }

    fun updateShowSystem(showSystem: Boolean) {
        viewModelScope.launch {
            userDataRepository.updateShowSystem(showSystem = showSystem)
        }
    }

    private fun LauncherAppsActivityInfoData.filteredBy(query: String): LauncherAppsActivityInfoData {
        if (query.isBlank()) return this

        return copy(
            launcherAppsActivityInfos = launcherAppsActivityInfos.filter { activityInfo ->
                activityInfo.activityLabel.contains(query, ignoreCase = true) ||
                    activityInfo.packageName.contains(query, ignoreCase = true)
            },
        )
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MILLIS = 250L
    }
}
