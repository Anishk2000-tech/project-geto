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
package com.android.geto.domain.framework

/**
 * Wrapper around Shizuku that allows hiding (and restoring) other installed apps by running
 * privileged `pm` commands through the Shizuku service. Requires the Shizuku app to be running and
 * the user to have granted Geto the Shizuku permission.
 */
interface ShizukuWrapper {

    /**
     * Whether the Shizuku binder is alive (the Shizuku service is running).
     */
    fun isAvailable(): Boolean

    /**
     * Whether the user has already granted Geto the Shizuku permission.
     */
    fun isPermissionGranted(): Boolean

    /**
     * Requests the Shizuku permission from the user, suspending until a result is delivered.
     * Returns true if the permission is granted.
     */
    suspend fun requestPermission(): Boolean

    /**
     * Hides ([hidden] == true) or restores ([hidden] == false) the given [packageNames].
     * Returns true only if every package was handled successfully.
     */
    suspend fun setPackagesHidden(packageNames: List<String>, hidden: Boolean): Boolean
}
