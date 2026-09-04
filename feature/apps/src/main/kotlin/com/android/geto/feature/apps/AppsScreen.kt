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

import androidx.activity.compose.ReportDrawnWhen
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSearchBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.ImageLoader
import coil.compose.AsyncImage
import com.android.geto.designsystem.icon.GetoIcons
import com.android.geto.domain.model.LauncherAppIcon
import com.android.geto.domain.model.LauncherAppsActivityInfo
import com.android.geto.domain.model.LauncherAppsActivityInfoData
import com.android.geto.domain.model.SortLauncherAppsActivityInfo
import com.android.geto.domain.model.SortOrderLauncherAppsActivityInfo
import com.android.geto.feature.apps.dialog.SortLauncherAppsActivityInfoDialog
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@Composable
internal fun AppsRoute(
    modifier: Modifier = Modifier,
    viewModel: AppsViewModel = hiltViewModel(),
    onClickApp: (
        componentName: String,
        activityLabel: String,
    ) -> Unit,
) {
    val appListUiState by viewModel.appsUiState.collectAsStateWithLifecycle()

    ReportDrawnWhen { appListUiState !is AppsUiState.Loading }

    AppsScreen(
        modifier = modifier,
        appsUiState = appListUiState,
        imageLoader = viewModel.imageLoader,
        onClickApp = onClickApp,
        onSearch = viewModel::search,
        onRetry = viewModel::retry,
        onUpdateSortLauncherAppsActivityInfo = viewModel::updateSortLauncherAppsActivityInfo,
        onUpdateSortOrderLauncherAppsActivityInfo = viewModel::updateSortOrderLauncherAppsActivityInfo,
        onUpdateShowSystem = viewModel::updateShowSystem,
    )
}

@OptIn(ExperimentalComposeUiApi::class)
@VisibleForTesting
@Composable
internal fun AppsScreen(
    modifier: Modifier = Modifier,
    appsUiState: AppsUiState,
    imageLoader: ImageLoader,
    onClickApp: (
        componentName: String,
        activityLabel: String,
    ) -> Unit,
    onSearch: (String) -> Unit,
    onRetry: () -> Unit,
    onUpdateSortLauncherAppsActivityInfo: (SortLauncherAppsActivityInfo) -> Unit,
    onUpdateSortOrderLauncherAppsActivityInfo: (SortOrderLauncherAppsActivityInfo) -> Unit,
    onUpdateShowSystem: (Boolean) -> Unit,
) {
    Box(modifier = modifier.fillMaxSize()) {
        when (appsUiState) {
            AppsUiState.Loading -> {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }

            is AppsUiState.Success -> {
                AppsContent(
                    launcherAppsActivityInfoData = appsUiState.launcherAppsActivityInfoData,
                    searchQuery = appsUiState.searchQuery,
                    appTags = appsUiState.appTags,
                    imageLoader = imageLoader,
                    onClickApp = onClickApp,
                    onSearch = onSearch,
                    onRetry = onRetry,
                    showLoadError = false,
                    onUpdateSortLauncherAppsActivityInfo = onUpdateSortLauncherAppsActivityInfo,
                    onUpdateSortOrderLauncherAppsActivityInfo = onUpdateSortOrderLauncherAppsActivityInfo,
                    onUpdateShowSystem = onUpdateShowSystem,
                )
            }

            is AppsUiState.Error -> {
                val previousData = appsUiState.previousData
                if (previousData == null) {
                    InitialLoadError(onRetry = onRetry)
                } else {
                    AppsContent(
                        launcherAppsActivityInfoData = previousData,
                        searchQuery = appsUiState.searchQuery,
                        appTags = appsUiState.appTags,
                        imageLoader = imageLoader,
                        onClickApp = onClickApp,
                        onSearch = onSearch,
                        onRetry = onRetry,
                        showLoadError = true,
                        onUpdateSortLauncherAppsActivityInfo = onUpdateSortLauncherAppsActivityInfo,
                        onUpdateSortOrderLauncherAppsActivityInfo = onUpdateSortOrderLauncherAppsActivityInfo,
                        onUpdateShowSystem = onUpdateShowSystem,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppsContent(
    launcherAppsActivityInfoData: LauncherAppsActivityInfoData,
    searchQuery: String,
    appTags: AppTags,
    imageLoader: ImageLoader,
    onClickApp: (
        componentName: String,
        activityLabel: String,
    ) -> Unit,
    onSearch: (String) -> Unit,
    onRetry: () -> Unit,
    showLoadError: Boolean,
    onUpdateSortLauncherAppsActivityInfo: (SortLauncherAppsActivityInfo) -> Unit,
    onUpdateSortOrderLauncherAppsActivityInfo: (SortOrderLauncherAppsActivityInfo) -> Unit,
    onUpdateShowSystem: (Boolean) -> Unit,
) {
    val searchBarState = rememberSearchBarState()
    val textFieldState = rememberTextFieldState(initialText = searchQuery)
    val scope = rememberCoroutineScope()
    var showSortLauncherAppsActivityInfoDialog by rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }

    LaunchedEffect(textFieldState) {
        snapshotFlow { textFieldState.text }
            .map { it.toString() }
            .distinctUntilChanged()
            .collect(onSearch)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        SearchBar(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            state = searchBarState,
            inputField = {
                SearchBarDefaults.InputField(
                    textFieldState = textFieldState,
                    searchBarState = searchBarState,
                    leadingIcon = {
                        Icon(
                            imageVector = GetoIcons.Search,
                            contentDescription = null,
                        )
                    },
                    trailingIcon = {
                        IconButton(onClick = { showSortLauncherAppsActivityInfoDialog = true }) {
                            Icon(
                                imageVector = GetoIcons.Sort,
                                contentDescription = stringResource(R.string.sort_and_filter),
                            )
                        }
                    },
                    onSearch = {
                        scope.launch { searchBarState.animateToCollapsed() }
                    },
                    placeholder = { Text(text = stringResource(R.string.search)) },
                )
            },
        )

        if (showLoadError) {
            ListItem(
                modifier = Modifier.semantics {
                    liveRegion = LiveRegionMode.Polite
                },
                headlineContent = { Text(text = stringResource(R.string.apps_refresh_failed)) },
                supportingContent = { Text(text = stringResource(R.string.showing_previous_apps)) },
                trailingContent = {
                    TextButton(onClick = onRetry) {
                        Text(text = stringResource(R.string.retry))
                    }
                },
            )
        }

        val activities = launcherAppsActivityInfoData.launcherAppsActivityInfos
        if (activities.isEmpty()) {
            EmptyState(
                modifier = Modifier.weight(1f),
                hasSearchQuery = searchQuery.isNotBlank(),
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(300.dp),
                modifier = Modifier.weight(1f),
            ) {
                items(
                    items = activities,
                    key = { it.componentName },
                    contentType = { "launcher-app" },
                ) { launcherAppsActivityInfo ->
                    AppItem(
                        launcherAppsActivityInfo = launcherAppsActivityInfo,
                        settingCount = appTags.settingCountByComponentName[
                            launcherAppsActivityInfo.componentName,
                        ] ?: 0,
                        isArmed = launcherAppsActivityInfo.componentName in
                            appTags.armedComponentNames,
                        isProtected = launcherAppsActivityInfo.componentName in
                            appTags.protectedComponentNames,
                        imageLoader = imageLoader,
                        onClickApp = onClickApp,
                    )
                }
            }
        }
    }

    if (showSortLauncherAppsActivityInfoDialog) {
        SortLauncherAppsActivityInfoDialog(
            sortLauncherAppsActivityInfo = launcherAppsActivityInfoData.userData.sortLauncherAppsActivityInfo,
            sortOrderLauncherAppsActivityInfo = launcherAppsActivityInfoData.userData.sortOrderLauncherAppsActivityInfo,
            showSystem = launcherAppsActivityInfoData.userData.showSystem,
            onDismissRequest = { showSortLauncherAppsActivityInfoDialog = false },
            onUpdateSortLauncherAppsActivityInfo = onUpdateSortLauncherAppsActivityInfo,
            onUpdateSortOrderLauncherAppsActivityInfo = onUpdateSortOrderLauncherAppsActivityInfo,
            onUpdateShowSystem = onUpdateShowSystem,
        )
    }
}

@Composable
private fun InitialLoadError(onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            modifier = Modifier.padding(top = 48.dp),
            text = stringResource(R.string.apps_load_failed),
            style = MaterialTheme.typography.titleMedium,
        )
        TextButton(onClick = onRetry) {
            Text(text = stringResource(R.string.retry))
        }
    }
}

@Composable
private fun EmptyState(
    modifier: Modifier = Modifier,
    hasSearchQuery: Boolean,
) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            modifier = Modifier.padding(24.dp),
            text = stringResource(if (hasSearchQuery) R.string.no_search_results else R.string.no_apps),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun AppItem(
    modifier: Modifier = Modifier,
    launcherAppsActivityInfo: LauncherAppsActivityInfo,
    settingCount: Int,
    isArmed: Boolean,
    isProtected: Boolean,
    imageLoader: ImageLoader,
    onClickApp: (
        componentName: String,
        activityLabel: String,
    ) -> Unit,
) {
    val fallbackIcon = rememberVectorPainter(GetoIcons.Android)
    ListItem(
        modifier = modifier.clickable {
            onClickApp(
                launcherAppsActivityInfo.componentName,
                launcherAppsActivityInfo.activityLabel,
            )
        },
        headlineContent = {
            Text(
                text = launcherAppsActivityInfo.activityLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Text(
                text = launcherAppsActivityInfo.packageName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = {
            AsyncImage(
                modifier = Modifier.size(50.dp),
                model = LauncherAppIcon(
                    componentName = launcherAppsActivityInfo.componentName,
                    lastUpdateTime = launcherAppsActivityInfo.lastUpdateTime,
                ),
                imageLoader = imageLoader,
                placeholder = fallbackIcon,
                error = fallbackIcon,
                fallback = fallbackIcon,
                contentDescription = null,
            )
        },
        trailingContent = {
            AppTagsBadge(
                settingCount = settingCount,
                isArmed = isArmed,
                isProtected = isProtected,
            )
        },
    )
}

/**
 * Two distinct signals: a quiet tag for apps that merely have settings saved, and a stronger one for
 * the apps Geto is actively protecting right now.
 */
@Composable
private fun AppTagsBadge(settingCount: Int, isArmed: Boolean, isProtected: Boolean) {
    if (settingCount == 0 && !isArmed && !isProtected) return

    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (settingCount > 0) {
            Tag(
                text = settingCount.toString(),
                contentDescription = pluralStringResource(
                    R.plurals.settings_configured,
                    settingCount,
                    settingCount,
                ),
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Applied right now outranks merely armed, so only the strongest state is shown.
        if (isProtected) {
            Tag(
                text = stringResource(R.string.tag_active),
                contentDescription = stringResource(R.string.tag_active_description),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        } else if (isArmed) {
            Tag(
                text = stringResource(R.string.tag_armed),
                contentDescription = stringResource(R.string.tag_armed_description),
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun Tag(
    text: String,
    contentDescription: String,
    containerColor: Color,
    contentColor: Color,
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = containerColor,
        contentColor = contentColor,
    ) {
        Text(
            modifier = Modifier
                .semantics { this.contentDescription = contentDescription }
                .padding(horizontal = 8.dp, vertical = 2.dp),
            text = text,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}
