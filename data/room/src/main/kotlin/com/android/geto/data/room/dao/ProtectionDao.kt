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
package com.android.geto.data.room.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.android.geto.data.room.model.ArmedProfileEntity
import com.android.geto.data.room.model.ProtectedKeyEntity
import com.android.geto.data.room.model.ProtectedKeyWithClaims
import com.android.geto.data.room.model.ProtectionSessionEntity
import com.android.geto.data.room.model.ProtectionSessionWithValues
import com.android.geto.data.room.model.ProtectionValueEntity
import com.android.geto.domain.model.ProtectionSessionStatus
import com.android.geto.domain.model.SettingType
import kotlinx.coroutines.flow.Flow

@Dao
interface ProtectionDao {

    @Query("SELECT componentName FROM ArmedProfileEntity")
    fun getArmedComponentNamesFlow(): Flow<List<String>>

    @Query("SELECT componentName FROM ArmedProfileEntity")
    suspend fun getArmedComponentNames(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun armProfile(profile: ArmedProfileEntity)

    @Query("DELETE FROM ArmedProfileEntity WHERE componentName = :componentName")
    suspend fun disarmProfile(componentName: String): Int

    @Transaction
    @Query("SELECT * FROM ProtectionSessionEntity ORDER BY createdAtMillis")
    fun getSessionsFlow(): Flow<List<ProtectionSessionWithValues>>

    @Transaction
    @Query("SELECT * FROM ProtectionSessionEntity ORDER BY createdAtMillis")
    suspend fun getSessions(): List<ProtectionSessionWithValues>

    @Transaction
    @Query("SELECT * FROM ProtectionSessionEntity WHERE token = :token")
    suspend fun getSessionByToken(token: String): ProtectionSessionWithValues?

    @Transaction
    @Query("SELECT * FROM ProtectionSessionEntity WHERE componentName = :componentName")
    suspend fun getSessionByComponentName(componentName: String): ProtectionSessionWithValues?

    @Query(
        """
        SELECT k.*, (
            SELECT COUNT(*) FROM ProtectionValueEntity v
            WHERE v.settingType = k.settingType AND v.key = k.key
        ) AS claimCount
        FROM ProtectedKeyEntity k
        """,
    )
    suspend fun getProtectedKeys(): List<ProtectedKeyWithClaims>

    @Query("SELECT * FROM ProtectedKeyEntity WHERE settingType = :settingType AND key = :key")
    suspend fun getProtectedKey(settingType: SettingType, key: String): ProtectedKeyEntity?

    /** Which apps claim this key, so a conflict can name them. */
    @Query(
        """
        SELECT s.componentName FROM ProtectionSessionEntity s
        JOIN ProtectionValueEntity v ON v.sessionToken = s.token
        WHERE v.settingType = :settingType AND v.key = :key
        """,
    )
    suspend fun getClaimantComponentNames(settingType: SettingType, key: String): List<String>

    /**
     * Keys whose only claimant is [token] — exactly the ones a turn-off must write back. Keys another
     * app still claims are excluded, which is what keeps one app's turn-off from breaking another's.
     */
    @Query(
        """
        SELECT k.* FROM ProtectedKeyEntity k
        WHERE EXISTS (
            SELECT 1 FROM ProtectionValueEntity v
            WHERE v.sessionToken = :token AND v.settingType = k.settingType AND v.key = k.key
        )
        AND (
            SELECT COUNT(*) FROM ProtectionValueEntity v2
            WHERE v2.settingType = k.settingType AND v2.key = k.key
        ) = 1
        """,
    )
    suspend fun getKeysReleasedBy(token: String): List<ProtectedKeyEntity>

    /**
     * Ledger rows nobody claims any more. Only reachable if the process died between writing an
     * original back and deleting its rows, so finding one means the settings are already correct and
     * the row just needs sweeping.
     */
    @Query(
        """
        SELECT * FROM ProtectedKeyEntity k WHERE NOT EXISTS (
            SELECT 1 FROM ProtectionValueEntity v
            WHERE v.settingType = k.settingType AND v.key = k.key
        )
        """,
    )
    suspend fun getOrphanKeys(): List<ProtectedKeyEntity>

    @Query(
        """
        DELETE FROM ProtectedKeyEntity WHERE NOT EXISTS (
            SELECT 1 FROM ProtectionValueEntity v
            WHERE v.settingType = ProtectedKeyEntity.settingType AND v.key = ProtectedKeyEntity.key
        )
        """,
    )
    suspend fun deleteOrphanKeys(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertKeys(keys: List<ProtectedKeyEntity>)

    @Insert
    suspend fun insertSession(session: ProtectionSessionEntity)

    @Insert
    suspend fun insertValues(values: List<ProtectionValueEntity>)

    @Query("DELETE FROM ProtectionSessionEntity WHERE token = :token")
    suspend fun deleteSession(token: String): Int

    @Query(
        """
        UPDATE ProtectionSessionEntity
        SET status = :status, updatedAtMillis = :updatedAtMillis, lastError = :lastError
        WHERE token = :token
        """,
    )
    suspend fun updateStatus(
        token: String,
        status: ProtectionSessionStatus,
        updatedAtMillis: Long,
        lastError: String?,
    ): Int

    @Query(
        """
        UPDATE ProtectionSessionEntity
        SET pausedUntilMillis = :pausedUntilMillis, updatedAtMillis = :updatedAtMillis
        WHERE token = :token
        """,
    )
    suspend fun updatePausedUntil(
        token: String,
        pausedUntilMillis: Long,
        updatedAtMillis: Long,
    ): Int

    /** Ledger rows must land before the claims that reference them. */
    @Transaction
    suspend fun createSession(
        session: ProtectionSessionEntity,
        newKeys: List<ProtectedKeyEntity>,
        values: List<ProtectionValueEntity>,
    ) {
        insertKeys(newKeys)
        insertSession(session)
        insertValues(values)
    }

    /** Drops the session, its claims (by cascade) and any ledger row left with no claims. */
    @Transaction
    suspend fun releaseSession(token: String): Int {
        val deleted = deleteSession(token)
        deleteOrphanKeys()
        return deleted
    }
}
