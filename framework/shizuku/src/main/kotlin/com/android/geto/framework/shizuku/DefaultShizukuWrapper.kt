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

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.android.geto.domain.common.dispatcher.Dispatcher
import com.android.geto.domain.common.dispatcher.GetoDispatchers.IO
import com.android.geto.domain.framework.ShizukuWrapper
import com.android.geto.domain.model.ShizukuGrantResult
import com.android.geto.domain.model.ShizukuState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Singleton
internal class DefaultShizukuWrapper @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:Dispatcher(IO) private val ioDispatcher: CoroutineDispatcher,
) : ShizukuWrapper {

    private val _state = MutableStateFlow(ShizukuState.Unknown)

    override val state = _state.asStateFlow()

    private val serviceArgs = Shizuku.UserServiceArgs(
        ComponentName(context.packageName, UserService::class.java.name),
    ).daemon(false).processNameSuffix("user_service")

    /**
     * Serialises grant attempts. Two overlapping binds against the same [serviceArgs] would fight
     * over the same connection.
     */
    private val grantMutex = Mutex()

    private val listenerLock = Any()

    /** Reference count, so one screen stopping cannot tear down another screen's listeners. */
    private var observers = 0

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener(::refresh)

    private val binderDeadListener = Shizuku.OnBinderDeadListener(::refresh)

    override fun start() {
        synchronized(listenerLock) {
            if (observers++ == 0) {
                // Sticky, so an already-received binder reports itself immediately. The plain
                // variant only fires on arrival, which has usually happened before any screen is
                // on-screen to hear it.
                Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
                Shizuku.addBinderDeadListener(binderDeadListener)
            }
        }

        refresh()
    }

    override fun stop() {
        synchronized(listenerLock) {
            if (observers > 0 && --observers == 0) {
                Shizuku.removeBinderReceivedListener(binderReceivedListener)
                Shizuku.removeBinderDeadListener(binderDeadListener)
            }
        }
    }

    override fun refresh() {
        _state.value = currentState()
    }

    override suspend fun grantWriteSecureSettings(): ShizukuGrantResult = withContext(ioDispatcher) {
        grantMutex.withLock {
            refresh()

            when (val current = _state.value) {
                ShizukuState.Unknown,
                ShizukuState.NotInstalled,
                ShizukuState.NotRunning,
                ShizukuState.OutdatedVersion,
                ShizukuState.PermissionDenied,
                -> return@withLock ShizukuGrantResult.Failure(current)

                ShizukuState.PermissionRequired,
                ShizukuState.Ready,
                -> Unit
            }

            try {
                if (_state.value == ShizukuState.PermissionRequired && !requestShizukuPermission()) {
                    refresh()
                    return@withLock ShizukuGrantResult.Failure(_state.value)
                }

                if (Shizuku.getVersion() < MINIMUM_USER_SERVICE_VERSION) {
                    _state.value = ShizukuState.OutdatedVersion
                    return@withLock ShizukuGrantResult.Failure(ShizukuState.OutdatedVersion)
                }

                grantThroughUserService()

                refresh()

                ShizukuGrantResult.Success
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Throwable) {
                refresh()
                ShizukuGrantResult.Error(exception.message ?: exception::class.simpleName)
            }
        }
    }

    /**
     * Binds the privileged user service and only calls through once it is actually connected.
     *
     * The service has to exist before the grant is issued: an earlier version granted first and
     * bound afterwards, against a null service reference, so the call silently did nothing while
     * still reporting success.
     */
    private suspend fun grantThroughUserService() {
        val binding = CompletableDeferred<IUserService>()

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (binder != null && binder.pingBinder()) {
                    binding.complete(IUserService.Stub.asInterface(binder))
                } else {
                    binding.completeExceptionally(
                        IllegalStateException("Shizuku returned a dead user service binder"),
                    )
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                binding.completeExceptionally(
                    IllegalStateException("Shizuku disconnected the user service"),
                )
            }
        }

        try {
            Shizuku.bindUserService(serviceArgs, connection)

            // Without a bound timeout a wedged Shizuku would leave the caller suspended forever.
            val userService = withTimeout(BIND_TIMEOUT_MILLIS) { binding.await() }

            userService.grantRuntimePermission(
                context.packageName,
                Manifest.permission.WRITE_SECURE_SETTINGS,
            )
        } finally {
            runCatching { Shizuku.unbindUserService(serviceArgs, connection, true) }
        }
    }

    private suspend fun requestShizukuPermission(): Boolean = suspendCancellableCoroutine { continuation ->
        val listener = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                if (requestCode != REQUEST_PERMISSION_CODE) return

                Shizuku.removeRequestPermissionResultListener(this)

                if (continuation.isActive) {
                    continuation.resume(grantResult == PackageManager.PERMISSION_GRANTED)
                }
            }
        }

        Shizuku.addRequestPermissionResultListener(listener)

        continuation.invokeOnCancellation {
            runCatching { Shizuku.removeRequestPermissionResultListener(listener) }
        }

        try {
            Shizuku.requestPermission(REQUEST_PERMISSION_CODE)
        } catch (exception: Throwable) {
            Shizuku.removeRequestPermissionResultListener(listener)

            if (continuation.isActive) continuation.resumeWithException(exception)
        }
    }

    private fun currentState(): ShizukuState {
        if (!isShizukuInstalled()) return ShizukuState.NotInstalled

        // Every Shizuku entry point below reaches through a binder that can disappear between two
        // statements, and throws IllegalStateException when it has.
        return try {
            when {
                !Shizuku.pingBinder() -> ShizukuState.NotRunning

                Shizuku.isPreV11() ||
                    Shizuku.getVersion() < MINIMUM_USER_SERVICE_VERSION -> ShizukuState.OutdatedVersion

                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> ShizukuState.Ready

                // Shizuku uses the rationale flag to mean "the user said no and I will not ask again".
                Shizuku.shouldShowRequestPermissionRationale() -> ShizukuState.PermissionDenied

                else -> ShizukuState.PermissionRequired
            }
        } catch (_: Throwable) {
            ShizukuState.NotRunning
        }
    }

    private fun isShizukuInstalled(): Boolean = try {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE_NAME, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private companion object {
        const val SHIZUKU_PACKAGE_NAME = "moe.shizuku.privileged.api"
        const val REQUEST_PERMISSION_CODE = 1
        const val MINIMUM_USER_SERVICE_VERSION = 10
        const val BIND_TIMEOUT_MILLIS = 15_000L
    }
}
