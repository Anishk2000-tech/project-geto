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

data class AppSettingTemplateEntry(
    val settingType: SettingType,
    val label: String,
    val key: String,
    val valueOnLaunch: String,
    /** Legacy/manual fallback only. Protection sessions restore captured original values. */
    val valueOnRevert: String,
)

data class AppSettingTemplate(
    val id: String,
    val label: String,
    val description: String,
    val warning: String? = null,
    val entries: List<AppSettingTemplateEntry>,
) {
    fun toAppSettings(componentName: String): List<AppSetting> = entries.map { entry ->
        AppSetting(
            enabled = true,
            settingType = entry.settingType,
            componentName = componentName,
            label = entry.label,
            key = entry.key,
            valueOnLaunch = entry.valueOnLaunch,
            valueOnRevert = entry.valueOnRevert,
        )
    }
}
