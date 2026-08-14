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
import com.android.geto.domain.model.AddAppSettingResult
import com.android.geto.domain.model.AppSettingTemplate
import com.android.geto.domain.repository.AppSettingsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject

class AddAppSettingTemplateUseCase @Inject constructor(
    private val appSettingsRepository: AppSettingsRepository,
    @param:Dispatcher(GetoDispatchers.Default) private val defaultDispatcher: CoroutineDispatcher,
) {
    suspend operator fun invoke(
        componentName: String,
        template: AppSettingTemplate,
    ): AddAppSettingResult = withContext(defaultDispatcher) {
        val settings = template.toAppSettings(componentName)
        val identities = settings.map { it.settingType to it.key }
        if (settings.isEmpty() || identities.distinct().size != identities.size) {
            return@withContext AddAppSettingResult.Failed
        }

        val existingIdentities = appSettingsRepository
            .getAppSettingsByComponentName(componentName)
            .map { it.settingType to it.key }
            .toSet()
        if (identities.any(existingIdentities::contains)) {
            return@withContext AddAppSettingResult.Failed
        }

        appSettingsRepository.upsertAppSettings(settings)
        AddAppSettingResult.Success
    }
}
