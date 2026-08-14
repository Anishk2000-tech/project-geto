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
package com.android.geto.data.repository

import com.android.geto.data.room.dao.ProtectionDao
import com.android.geto.data.room.model.ArmedProfileEntity
import com.android.geto.data.room.model.ProtectedKeyEntity
import com.android.geto.data.room.model.ProtectedKeyWithClaims
import com.android.geto.data.room.model.ProtectionSessionEntity
import com.android.geto.data.room.model.ProtectionSessionWithValues
import com.android.geto.data.room.model.ProtectionValueEntity
import com.android.geto.domain.model.ProtectedKey
import com.android.geto.domain.model.ProtectedSetting
import com.android.geto.domain.model.ProtectionSession
import com.android.geto.domain.model.ProtectionSessionStatus
import com.android.geto.domain.model.SettingType
import com.android.geto.domain.repository.ProtectionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class DefaultProtectionRepository @Inject constructor(
    private val protectionDao: ProtectionDao,
) : ProtectionRepository {

    override val armedComponentNamesFlow: Flow<Set<String>> =
        protectionDao.getArmedComponentNamesFlow().map(List<String>::toSet)

    override suspend fun getArmedComponentNames(): Set<String> = protectionDao.getArmedComponentNames().toSet()

    override suspend fun armProfile(componentName: String, armedAtMillis: Long) {
        protectionDao.armProfile(
            ArmedProfileEntity(componentName = componentName, armedAtMillis = armedAtMillis),
        )
    }

    override suspend fun disarmProfile(componentName: String): Boolean = protectionDao.disarmProfile(componentName) == 1

    override val sessionsFlow: Flow<List<ProtectionSession>> = protectionDao.getSessionsFlow().map { relations ->
        relations.map(ProtectionSessionWithValues::asExternalModel)
    }

    override suspend fun getSessions(): List<ProtectionSession> = protectionDao.getSessions().map(ProtectionSessionWithValues::asExternalModel)

    override suspend fun getSessionByToken(token: String): ProtectionSession? = protectionDao.getSessionByToken(token)?.asExternalModel()

    override suspend fun getSessionByComponentName(componentName: String): ProtectionSession? = protectionDao.getSessionByComponentName(componentName)?.asExternalModel()

    override suspend fun getProtectedKeys(): List<ProtectedKey> = protectionDao.getProtectedKeys().map(ProtectedKeyWithClaims::asExternalModel)

    override suspend fun getProtectedKey(
        settingType: SettingType,
        key: String,
    ): ProtectedKey? = protectionDao.getProtectedKey(settingType = settingType, key = key)?.asExternalModel()

    override suspend fun getClaimantComponentNames(
        settingType: SettingType,
        key: String,
    ): List<String> = protectionDao.getClaimantComponentNames(settingType = settingType, key = key)

    override suspend fun getKeysReleasedBy(token: String): List<ProtectedKey> = protectionDao.getKeysReleasedBy(token).map(ProtectedKeyEntity::asExternalModel)

    override suspend fun getOrphanKeys(): List<ProtectedKey> = protectionDao.getOrphanKeys().map(ProtectedKeyEntity::asExternalModel)

    override suspend fun deleteOrphanKeys(): Int = protectionDao.deleteOrphanKeys()

    override suspend fun createSession(session: ProtectionSession, newKeys: List<ProtectedKey>) {
        val sessionEntity = ProtectionSessionEntity(
            token = session.token,
            componentName = session.componentName,
            mode = session.mode,
            status = session.status,
            createdAtMillis = session.createdAtMillis,
            updatedAtMillis = session.updatedAtMillis,
            lastError = session.lastError,
            pausedUntilMillis = session.pausedUntilMillis,
        )

        val keyEntities = newKeys.map { key ->
            ProtectedKeyEntity(
                settingType = key.settingType,
                key = key.key,
                originalValue = key.originalValue,
                enforcedValue = key.enforcedValue,
                claimedAtMillis = key.claimedAtMillis,
            )
        }

        val valueEntities = session.protectedSettings.map { setting ->
            ProtectionValueEntity(
                sessionToken = session.token,
                settingType = setting.settingType,
                key = setting.key,
                protectedValue = setting.protectedValue,
                writeOrder = setting.writeOrder,
            )
        }

        protectionDao.createSession(
            session = sessionEntity,
            newKeys = keyEntities,
            values = valueEntities,
        )
    }

    override suspend fun updateStatus(
        token: String,
        status: ProtectionSessionStatus,
        updatedAtMillis: Long,
        lastError: String?,
    ): Boolean = protectionDao.updateStatus(
        token = token,
        status = status,
        updatedAtMillis = updatedAtMillis,
        lastError = lastError,
    ) == 1

    override suspend fun updatePausedUntil(
        token: String,
        pausedUntilMillis: Long,
        updatedAtMillis: Long,
    ): Boolean = protectionDao.updatePausedUntil(
        token = token,
        pausedUntilMillis = pausedUntilMillis,
        updatedAtMillis = updatedAtMillis,
    ) == 1

    override suspend fun clearSession(token: String): Boolean = protectionDao.releaseSession(token) == 1
}

private fun ProtectionSessionWithValues.asExternalModel(): ProtectionSession = ProtectionSession(
    token = session.token,
    componentName = session.componentName,
    mode = session.mode,
    status = session.status,
    protectedSettings = values.sortedBy { it.writeOrder }.map { value ->
        ProtectedSetting(
            settingType = value.settingType,
            key = value.key,
            protectedValue = value.protectedValue,
            writeOrder = value.writeOrder,
        )
    },
    createdAtMillis = session.createdAtMillis,
    updatedAtMillis = session.updatedAtMillis,
    pausedUntilMillis = session.pausedUntilMillis,
    lastError = session.lastError,
)

private fun ProtectedKeyEntity.asExternalModel(): ProtectedKey = ProtectedKey(
    settingType = settingType,
    key = key,
    originalValue = originalValue,
    enforcedValue = enforcedValue,
    claimedAtMillis = claimedAtMillis,
)

private fun ProtectedKeyWithClaims.asExternalModel(): ProtectedKey = key.asExternalModel().copy(claimCount = claimCount)
