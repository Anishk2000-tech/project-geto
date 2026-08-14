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

import coil.ImageLoader
import com.android.geto.domain.framework.LauncherAppsWrapper
import com.android.geto.domain.model.GrantMethod
import com.android.geto.domain.model.LauncherAppsActivityInfo
import com.android.geto.domain.model.SortLauncherAppsActivityInfo
import com.android.geto.domain.model.SortOrderLauncherAppsActivityInfo
import com.android.geto.domain.model.Theme
import com.android.geto.domain.model.UserData
import com.android.geto.domain.repository.UserDataRepository
import com.android.geto.domain.usecase.GetLauncherAppsActivityInfosUseCase
import com.android.geto.domain.usecase.ProtectionController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

@OptIn(ExperimentalCoroutinesApi::class)
class AppsViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun nonEmptySearchIsDebouncedAndClearingIsImmediate() = runTest(testDispatcher) {
        val launcherApps = ViewModelTestLauncherAppsWrapper(
            Result.success(listOf(activity("Alpha"), activity("Beta"))),
        )
        val viewModel = viewModel(launcherApps)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.appsUiState.collect()
        }
        runCurrent()

        viewModel.search("alpha")
        advanceTimeBy(249)
        assertEquals(2, viewModel.successState().launcherAppsActivityInfoData.launcherAppsActivityInfos.size)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(
            listOf("Alpha"),
            viewModel.successState().launcherAppsActivityInfoData.launcherAppsActivityInfos.map {
                it.activityLabel
            },
        )

        viewModel.search("")
        runCurrent()
        assertEquals(2, viewModel.successState().launcherAppsActivityInfoData.launcherAppsActivityInfos.size)
    }

    @Test
    fun refreshFailureKeepsLastSuccessfulListAndRetryDelegates() = runTest(testDispatcher) {
        val launcherApps = ViewModelTestLauncherAppsWrapper(
            Result.success(listOf(activity("Alpha"), activity("Beta"))),
        )
        val viewModel = viewModel(launcherApps)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.appsUiState.collect()
        }
        runCurrent()

        launcherApps.results.value = Result.failure(IllegalStateException("failed"))
        runCurrent()
        viewModel.retry()

        val error = assertIs<AppsUiState.Error>(viewModel.appsUiState.value)
        assertEquals(2, assertNotNull(error.previousData).launcherAppsActivityInfos.size)
        assertEquals(1, launcherApps.refreshCount)
    }

    private fun viewModel(
        launcherAppsWrapper: LauncherAppsWrapper,
    ): AppsViewModel {
        val userDataRepository = ViewModelTestUserDataRepository()
        return AppsViewModel(
            getLauncherAppsActivityInfosUseCase = GetLauncherAppsActivityInfosUseCase(
                launcherAppsWrapper = launcherAppsWrapper,
                userDataRepository = userDataRepository,
                defaultDispatcher = testDispatcher,
            ),
            userDataRepository = userDataRepository,
            appSettingsRepository = EmptyAppSettingsRepository,
            protectionController = ProtectionController(
                appSettingsRepository = EmptyAppSettingsRepository,
                protectionRepository = EmptyProtectionRepository,
                secureSettingsWrapper = UnusedSecureSettingsWrapper,
            ),
            imageLoader = unusedImageLoader(),
        )
    }

    private fun AppsViewModel.successState(): AppsUiState.Success = assertIs<AppsUiState.Success>(appsUiState.value)

    private fun activity(label: String) = LauncherAppsActivityInfo(
        componentName = "package.$label/.Main",
        packageName = "package.$label",
        activityLabel = label,
        firstInstallTime = 0,
        lastUpdateTime = 0,
        isSystem = false,
    )

    private fun unusedImageLoader(): ImageLoader = Proxy.newProxyInstance(
        ImageLoader::class.java.classLoader,
        arrayOf(ImageLoader::class.java),
    ) { _, _, _ -> error("The image loader is not used by AppsViewModel") } as ImageLoader
}

private class ViewModelTestLauncherAppsWrapper(
    initialResult: Result<List<LauncherAppsActivityInfo>>,
) : LauncherAppsWrapper {
    val results = MutableStateFlow(initialResult)
    var refreshCount = 0
        private set

    override fun getActivityListFlow(): Flow<Result<List<LauncherAppsActivityInfo>>> = results

    override fun refresh() {
        refreshCount++
    }
}

private class ViewModelTestUserDataRepository : UserDataRepository {
    override val userData = MutableStateFlow(
        UserData(
            theme = Theme.FOLLOW_SYSTEM,
            dynamicTheme = true,
            sortLauncherAppsActivityInfo = SortLauncherAppsActivityInfo.Name,
            sortOrderLauncherAppsActivityInfo = SortOrderLauncherAppsActivityInfo.Ascending,
            showSystem = false,
            autoRestartProtection = false,
        ),
    )
    override val preferencesWereReset: Flow<Boolean> = flowOf(false)

    override suspend fun updateTheme(theme: Theme) = Unit

    override suspend fun updateDynamicTheme(dynamicTheme: Boolean) = Unit

    override suspend fun updateSortLauncherAppsActivityInfo(
        sortLauncherAppsActivityInfo: SortLauncherAppsActivityInfo,
    ) {
        userData.value = userData.value.copy(
            sortLauncherAppsActivityInfo = sortLauncherAppsActivityInfo,
        )
    }

    override suspend fun updateSortOrderLauncherAppsActivityInfo(
        sortOrderLauncherAppsActivityInfo: SortOrderLauncherAppsActivityInfo,
    ) {
        userData.value = userData.value.copy(
            sortOrderLauncherAppsActivityInfo = sortOrderLauncherAppsActivityInfo,
        )
    }

    override suspend fun updateShowSystem(showSystem: Boolean) {
        userData.value = userData.value.copy(showSystem = showSystem)
    }

    override suspend fun updateAutoRestartProtection(autoRestartProtection: Boolean) = Unit

    override suspend fun updateGrantMethod(grantMethod: GrantMethod) {
        userData.value = userData.value.copy(grantMethod = grantMethod)
    }

    override suspend fun updateProtectionPausedUntil(protectionPausedUntilMillis: Long) {
        userData.value = userData.value.copy(
            protectionPausedUntilMillis = protectionPausedUntilMillis,
        )
    }

    override suspend fun resetUserPreferences() = Unit

    override fun acknowledgePreferencesReset() = Unit
}
