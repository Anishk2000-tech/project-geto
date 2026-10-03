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
package com.android.geto.framework.shizuku

import android.content.pm.PackageManager
import com.android.geto.domain.common.dispatcher.Dispatcher
import com.android.geto.domain.common.dispatcher.GetoDispatchers.IO
import com.android.geto.domain.framework.ShizukuWrapper
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import javax.inject.Inject
import kotlin.coroutines.resume

internal class DefaultShizukuWrapper @Inject constructor(
    @param:Dispatcher(IO) private val ioDispatcher: CoroutineDispatcher,
) : ShizukuWrapper {

    override fun isAvailable(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Exception) {
        false
    }

    override fun isPermissionGranted(): Boolean = try {
        isAvailable() &&
            !Shizuku.isPreV11() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Exception) {
        false
    }

    override suspend fun requestPermission(): Boolean = withContext(ioDispatcher) {
        if (!isAvailable() || Shizuku.isPreV11()) return@withContext false
        if (isPermissionGranted()) return@withContext true

        suspendCancellableCoroutine { continuation ->
            val listener = object : Shizuku.OnRequestPermissionResultListener {
                override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                    if (requestCode != SHIZUKU_PERMISSION_REQUEST_CODE) return

                    Shizuku.removeRequestPermissionResultListener(this)

                    if (continuation.isActive) {
                        continuation.resume(grantResult == PackageManager.PERMISSION_GRANTED)
                    }
                }
            }

            Shizuku.addRequestPermissionResultListener(listener)

            continuation.invokeOnCancellation {
                Shizuku.removeRequestPermissionResultListener(listener)
            }

            try {
                Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
            } catch (_: Exception) {
                Shizuku.removeRequestPermissionResultListener(listener)

                if (continuation.isActive) {
                    continuation.resume(false)
                }
            }
        }
    }

    override suspend fun setPackagesHidden(
        packageNames: List<String>,
        hidden: Boolean,
    ): Boolean = withContext(ioDispatcher) {
        if (packageNames.isEmpty()) return@withContext true

        if (!isPermissionGranted() && !requestPermission()) {
            return@withContext false
        }

        packageNames.all { packageName ->
            val command = if (hidden) {
                "pm disable-user --user 0 $packageName"
            } else {
                "pm enable $packageName"
            }

            runShizukuCommand(command = command)
        }
    }

    /**
     * Runs a shell command through the Shizuku service. [Shizuku.newProcess] is a hidden API, so it
     * is invoked reflectively.
     */
    private fun runShizukuCommand(command: String): Boolean = try {
        val newProcess = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        ).apply { isAccessible = true }

        val process = newProcess.invoke(
            null,
            arrayOf("sh", "-c", command),
            null,
            null,
        ) as Process

        process.waitFor() == 0
    } catch (_: Exception) {
        false
    }

    private companion object {
        const val SHIZUKU_PERMISSION_REQUEST_CODE = 8964
    }
}
