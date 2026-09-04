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
package com.android.geto.domain.usecase

import com.android.geto.domain.framework.LauncherAppsWrapper
import com.android.geto.domain.model.GrantMethod
import com.android.geto.domain.model.LauncherAppsActivityInfo
import com.android.geto.domain.model.SortLauncherAppsActivityInfo
import com.android.geto.domain.model.SortOrderLauncherAppsActivityInfo
import com.android.geto.domain.model.Theme
import com.android.geto.domain.model.UserData
import com.android.geto.domain.repository.UserDataRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class GetLauncherAppsActivityInfosUseCaseTest {
    private val defaultUserData = UserData(
        theme = Theme.FOLLOW_SYSTEM,
        dynamicTheme = true,
        sortLauncherAppsActivityInfo = SortLauncherAppsActivityInfo.Name,
        sortOrderLauncherAppsActivityInfo = SortOrderLauncherAppsActivityInfo.Ascending,
        showSystem = false,
        autoRestartProtection = false,
    )

    @Test
    fun filtersSystemAppsAndUsesComponentAsStableNameTieBreaker() = runTest {
        val launcherApps = TestLauncherAppsWrapper(
            Result.success(
                listOf(
                    activity("pkg.z/.Main", label = "Same"),
                    activity("system/.Main", label = "First", isSystem = true),
                    activity("pkg.a/.Main", label = "Same"),
                ),
            ),
        )

        val result = useCase(launcherApps, defaultUserData)().first().getOrThrow()

        assertEquals(
            listOf("pkg.a/.Main", "pkg.z/.Main"),
            result.launcherAppsActivityInfos.map { it.componentName },
        )
    }

    @Test
    fun sortsUpdateTimeDescendingAndRetainsSystemAppsWhenEnabled() = runTest {
        val launcherApps = TestLauncherAppsWrapper(
            Result.success(
                listOf(
                    activity("old/.Main", label = "Old", lastUpdateTime = 1),
                    activity("new/.Main", label = "New", lastUpdateTime = 3, isSystem = true),
                    activity("middle/.Main", label = "Middle", lastUpdateTime = 2),
                ),
            ),
        )
        val userData = defaultUserData.copy(
            sortLauncherAppsActivityInfo = SortLauncherAppsActivityInfo.UpdateTime,
            sortOrderLauncherAppsActivityInfo = SortOrderLauncherAppsActivityInfo.Descending,
            showSystem = true,
        )

        val result = useCase(launcherApps, userData)().first().getOrThrow()

        assertEquals(
            listOf("new/.Main", "middle/.Main", "old/.Main"),
            result.launcherAppsActivityInfos.map { it.componentName },
        )
    }

    @Test
    fun forwardsLoadFailuresAndDelegatesRetry() = runTest {
        val failure = IllegalStateException("launcher unavailable")
        val launcherApps = TestLauncherAppsWrapper(Result.failure(failure))
        val useCase = useCase(launcherApps, defaultUserData)

        val result = useCase().first()
        useCase.refresh()

        assertSame(failure, result.exceptionOrNull())
        assertEquals(1, launcherApps.refreshCount)
    }

    private fun useCase(
        launcherAppsWrapper: LauncherAppsWrapper,
        userData: UserData,
    ) = GetLauncherAppsActivityInfosUseCase(
        launcherAppsWrapper = launcherAppsWrapper,
        userDataRepository = TestUserDataRepository(userData),
        defaultDispatcher = Dispatchers.Unconfined,
    )

    private fun activity(
        componentName: String,
        label: String,
        lastUpdateTime: Long = 0,
        isSystem: Boolean = false,
    ) = LauncherAppsActivityInfo(
        componentName = componentName,
        packageName = componentName.substringBefore('/'),
        activityLabel = label,
        firstInstallTime = 0,
        lastUpdateTime = lastUpdateTime,
        isSystem = isSystem,
    )
}

private class TestLauncherAppsWrapper(
    initialResult: Result<List<LauncherAppsActivityInfo>>,
) : LauncherAppsWrapper {
    private val results = MutableStateFlow(initialResult)
    var refreshCount = 0
        private set

    override fun getActivityListFlow(): Flow<Result<List<LauncherAppsActivityInfo>>> = results

    override fun refresh() {
        refreshCount++
    }
}

private class TestUserDataRepository(
    initialUserData: UserData,
) : UserDataRepository {
    override val userData = MutableStateFlow(initialUserData)
    override val preferencesWereReset: Flow<Boolean> = flowOf(false)

    override suspend fun updateTheme(theme: Theme) = Unit

    override suspend fun updateDynamicTheme(dynamicTheme: Boolean) = Unit

    override suspend fun updateSortLauncherAppsActivityInfo(
        sortLauncherAppsActivityInfo: SortLauncherAppsActivityInfo,
    ) = Unit

    override suspend fun updateSortOrderLauncherAppsActivityInfo(
        sortOrderLauncherAppsActivityInfo: SortOrderLauncherAppsActivityInfo,
    ) = Unit

    override suspend fun updateShowSystem(showSystem: Boolean) = Unit

    override suspend fun updateAutoRestartProtection(autoRestartProtection: Boolean) = Unit

    override suspend fun updateGrantMethod(grantMethod: GrantMethod) = Unit

    override suspend fun updateProtectionPausedUntil(protectionPausedUntilMillis: Long) = Unit

    override suspend fun resetUserPreferences() = Unit

    override fun acknowledgePreferencesReset() = Unit
}
