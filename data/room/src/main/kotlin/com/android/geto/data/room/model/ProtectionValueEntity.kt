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
package com.android.geto.data.room.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import com.android.geto.domain.model.SettingType

/**
 * One app's claim on one key. Counting these rows per key gives the reference count, which is what
 * decides whether releasing an app may write the original value back.
 *
 * The foreign key to [ProtectedKeyEntity] is NO ACTION rather than CASCADE on purpose: deleting a
 * ledger row must never silently drop live claims. Ledger rows are removed only once no claim is
 * left, by [com.android.geto.data.room.dao.ProtectionDao.deleteOrphanKeys].
 */
@Entity(
    primaryKeys = ["sessionToken", "settingType", "key"],
    foreignKeys = [
        ForeignKey(
            entity = ProtectionSessionEntity::class,
            parentColumns = ["token"],
            childColumns = ["sessionToken"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ProtectedKeyEntity::class,
            parentColumns = ["settingType", "key"],
            childColumns = ["settingType", "key"],
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [Index("sessionToken"), Index(value = ["settingType", "key"])],
)
data class ProtectionValueEntity(
    val sessionToken: String,
    val settingType: SettingType,
    val key: String,
    val protectedValue: String,
    val writeOrder: Int,
)
