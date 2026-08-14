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

import com.android.geto.domain.common.dispatcher.Dispatcher
import com.android.geto.domain.common.dispatcher.GetoDispatchers
import com.android.geto.domain.model.AppSettingsResult
import com.android.geto.domain.model.ProtectionResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject

class RevertAppSettingsUseCase @Inject constructor(
    private val protectionController: ProtectionController,
    @param:Dispatcher(GetoDispatchers.Default) private val defaultDispatcher: CoroutineDispatcher,
) {
    suspend operator fun invoke(componentName: String): AppSettingsResult = withContext(defaultDispatcher) {
        when (protectionController.restoreLegacyProfile(componentName)) {
            is ProtectionResult.Success -> AppSettingsResult.Success

            ProtectionResult.NoActiveProtection -> AppSettingsResult.EmptyAppSettings

            is ProtectionResult.PermissionDenied -> AppSettingsResult.NoPermission

            is ProtectionResult.InvalidProfile -> AppSettingsResult.InvalidValues

            ProtectionResult.EmptyProfile -> AppSettingsResult.EmptyAppSettings

            ProtectionResult.NoEnabledSettings -> AppSettingsResult.DisabledAppSettings

            is ProtectionResult.Failure,
            is ProtectionResult.KeyConflict,
            is ProtectionResult.KeyNotProtected,
            is ProtectionResult.StaleSession,
            is ProtectionResult.RecoveryRequired,
            -> AppSettingsResult.Failure
        }
    }
}
