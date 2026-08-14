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
package com.android.geto.feature.appsettings.dialog

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.android.geto.designsystem.component.DialogContainer
import com.android.geto.designsystem.icon.GetoIcons
import com.android.geto.domain.model.AppSettingTemplate
import com.android.geto.feature.appsettings.R

@Composable
internal fun TemplateDialog(
    modifier: Modifier = Modifier,
    appSettingTemplates: List<AppSettingTemplate>,
    onAddTemplate: (AppSettingTemplate) -> Unit,
    onDismissRequest: () -> Unit,
) {
    var templateAwaitingConfirmationId by rememberSaveable { mutableStateOf<String?>(null) }

    DialogContainer(
        modifier = modifier,
        onDismissRequest = onDismissRequest,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .padding(10.dp),
        ) {
            Text(
                modifier = Modifier.padding(10.dp),
                text = stringResource(id = R.string.templates),
                style = MaterialTheme.typography.titleLarge,
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
            ) {
                items(appSettingTemplates) { appSettingTemplate ->
                    AppSettingTemplateItem(
                        appSettingTemplate = appSettingTemplate,
                        onAddTemplate = { template ->
                            if (template.warning == null) {
                                onAddTemplate(template)
                            } else {
                                templateAwaitingConfirmationId = template.id
                            }
                        },
                    )
                }
            }

            TextButton(
                modifier = Modifier.align(Alignment.End),
                onClick = onDismissRequest,
            ) {
                Text(stringResource(R.string.close))
            }
        }
    }

    templateAwaitingConfirmationId?.let { templateId ->
        val template = appSettingTemplates.firstOrNull { it.id == templateId } ?: return@let
        AlertDialog(
            onDismissRequest = { templateAwaitingConfirmationId = null },
            title = { Text(template.label) },
            text = { Text(template.warning.orEmpty()) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onAddTemplate(template)
                        templateAwaitingConfirmationId = null
                    },
                ) {
                    Text(stringResource(com.android.geto.common.R.string.add))
                }
            },
            dismissButton = {
                TextButton(onClick = { templateAwaitingConfirmationId = null }) {
                    Text(stringResource(com.android.geto.common.R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun AppSettingTemplateItem(
    modifier: Modifier = Modifier,
    appSettingTemplate: AppSettingTemplate,
    onAddTemplate: (AppSettingTemplate) -> Unit,
) {
    Row(
        modifier = modifier.padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = appSettingTemplate.label,
                style = MaterialTheme.typography.bodyLarge,
            )

            Spacer(modifier = Modifier.height(5.dp))

            Text(
                text = appSettingTemplate.description,
                style = MaterialTheme.typography.bodySmall,
            )

            Spacer(modifier = Modifier.height(5.dp))

            Text(
                text = appSettingTemplate.entries.joinToString { it.key },
                style = MaterialTheme.typography.bodySmall,
            )
        }

        IconButton(
            onClick = {
                onAddTemplate(appSettingTemplate)
            },
        ) {
            Icon(
                imageVector = GetoIcons.Add,
                contentDescription = stringResource(
                    R.string.add_named_template,
                    appSettingTemplate.label,
                ),
            )
        }
    }
}
