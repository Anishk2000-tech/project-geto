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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.android.geto.designsystem.component.DialogContainer
import com.android.geto.domain.model.LauncherAppsActivityInfo
import com.android.geto.feature.appsettings.R
import com.android.geto.common.R as commonR

@Composable
internal fun HideAppsDialog(
    modifier: Modifier = Modifier,
    apps: List<LauncherAppsActivityInfo>,
    onAddSelectedApps: (List<String>) -> Unit,
    onDismissRequest: () -> Unit,
) {
    val selectedPackageNames = remember { mutableStateMapOf<String, Boolean>() }

    DialogContainer(
        modifier = modifier,
        onDismissRequest = onDismissRequest,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
        ) {
            Text(
                modifier = Modifier.padding(10.dp),
                text = stringResource(id = R.string.hide_selected_apps),
                style = MaterialTheme.typography.titleLarge,
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 350.dp),
            ) {
                items(items = apps) { app ->
                    val checked = selectedPackageNames[app.packageName] == true

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedPackageNames[app.packageName] = !checked
                            }
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = app.activityLabel,
                                style = MaterialTheme.typography.bodyLarge,
                            )

                            Spacer(modifier = Modifier.heightIn(min = 2.dp))

                            Text(
                                text = app.packageName,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }

                        Checkbox(
                            checked = checked,
                            onCheckedChange = {
                                selectedPackageNames[app.packageName] = it
                            },
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismissRequest) {
                    Text(text = stringResource(id = commonR.string.cancel))
                }

                TextButton(
                    onClick = {
                        onAddSelectedApps(
                            selectedPackageNames.filterValues { it }.keys.toList(),
                        )

                        onDismissRequest()
                    },
                ) {
                    Text(text = stringResource(id = commonR.string.add))
                }
            }
        }
    }
}
