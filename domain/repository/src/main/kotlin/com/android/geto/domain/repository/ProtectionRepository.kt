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
package com.android.geto.domain.repository

import com.android.geto.domain.model.ProtectedKey
import com.android.geto.domain.model.ProtectionSession
import com.android.geto.domain.model.ProtectionSessionStatus
import com.android.geto.domain.model.SettingType
import kotlinx.coroutines.flow.Flow

interface ProtectionRepository {
    /**
     * Apps the user has armed. Separate from sessions on purpose: arming is standing intent and
     * persists, a session means "applied right now" and must not survive a restart.
     */
    val armedComponentNamesFlow: Flow<Set<String>>

    suspend fun getArmedComponentNames(): Set<String>

    suspend fun armProfile(componentName: String, armedAtMillis: Long)

    suspend fun disarmProfile(componentName: String): Boolean

    val sessionsFlow: Flow<List<ProtectionSession>>

    suspend fun getSessions(): List<ProtectionSession>

    suspend fun getSessionByToken(token: String): ProtectionSession?

    suspend fun getSessionByComponentName(componentName: String): ProtectionSession?

    /** Every key currently under protection, with how many apps claim each one. */
    suspend fun getProtectedKeys(): List<ProtectedKey>

    suspend fun getProtectedKey(settingType: SettingType, key: String): ProtectedKey?

    /** Which apps claim this key, so a conflict can name them. */
    suspend fun getClaimantComponentNames(settingType: SettingType, key: String): List<String>

    /**
     * Keys whose only claimant is [token] — exactly the ones turning that app off must write back.
     * Keys another app still claims are excluded.
     */
    suspend fun getKeysReleasedBy(token: String): List<ProtectedKey>

    /** Ledger rows no app claims any more, left behind by a process death mid-restore. */
    suspend fun getOrphanKeys(): List<ProtectedKey>

    suspend fun deleteOrphanKeys(): Int

    /** Inserts [newKeys], the session and its claims in one transaction. */
    suspend fun createSession(session: ProtectionSession, newKeys: List<ProtectedKey>)

    suspend fun updateStatus(
        token: String,
        status: ProtectionSessionStatus,
        updatedAtMillis: Long,
        lastError: String? = null,
    ): Boolean

    suspend fun updatePausedUntil(
        token: String,
        pausedUntilMillis: Long,
        updatedAtMillis: Long,
    ): Boolean

    /** Deletes the session, its claims, and any ledger row left with no claims. */
    suspend fun clearSession(token: String): Boolean
}
