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
package com.android.geto.activity.main

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import com.android.geto.R
import com.android.geto.designsystem.theme.GetoTheme
import com.android.geto.domain.model.Theme
import com.android.geto.framework.launcherapps.AndroidLauncherAppsWrapper
import com.android.geto.framework.notificationmanager.AndroidNotificationManagerWrapper
import com.android.geto.navigation.GetoNavHost
import com.android.geto.service.ProtectionServiceManager
import com.android.geto.ui.local.LocalLauncherApps
import com.android.geto.ui.local.LocalNotificationManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var androidLauncherAppsWrapper: AndroidLauncherAppsWrapper

    @Inject
    lateinit var androidNotificationManagerWrapper: AndroidNotificationManagerWrapper

    @Inject
    lateinit var protectionServiceManager: ProtectionServiceManager

    private val viewModel: MainActivityViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()

        enableEdgeToEdge()

        super.onCreate(savedInstanceState)

        splashScreen.setKeepOnScreenCondition {
            viewModel.uiState.value is MainActivityUiState.Loading
        }

        setContent {
            CompositionLocalProvider(
                LocalLauncherApps provides androidLauncherAppsWrapper,
                LocalNotificationManager provides androidNotificationManagerWrapper,
            ) {
                val navController = rememberNavController()

                val mainActivityUiState by viewModel.uiState.collectAsStateWithLifecycle()

                when (val uiState = mainActivityUiState) {
                    MainActivityUiState.Loading -> {
                        LoadingContent()
                    }

                    is MainActivityUiState.Error -> {
                        GetoTheme(
                            theme = Theme.FOLLOW_SYSTEM,
                            dynamicTheme = false,
                        ) {
                            Surface(modifier = Modifier.fillMaxSize()) {
                                Column(
                                    modifier = Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.Center,
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Text(text = stringResource(R.string.preferences_load_failed))
                                    Button(onClick = viewModel::retry) {
                                        Text(text = stringResource(R.string.retry))
                                    }
                                }
                            }
                        }
                    }

                    is MainActivityUiState.Success -> {
                        GetoTheme(
                            theme = uiState.userData.theme,
                            dynamicTheme = uiState.userData.dynamicTheme,
                        ) {
                            Surface {
                                GetoNavHost(navController = navController)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        protectionServiceManager.reconcileFromVisibleApp()
    }
}

@androidx.compose.runtime.Composable
private fun LoadingContent() {
    GetoTheme(
        theme = Theme.FOLLOW_SYSTEM,
        dynamicTheme = false,
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
            }
        }
    }
}
