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
import com.android.geto.domain.model.SettingType

/**
 * The ledger of setting keys currently under protection — one row per key, no matter how many apps
 * claim it.
 *
 * [originalValue] is written when the key is first claimed and never touched again, which is the
 * whole reason this table exists: if the original lived on the per-app claim rows, the second app to
 * claim a key would snapshot the already-protected value and the real one would be lost.
 *
 * [enforcedValue] is the value every claimant agrees on. A claim asking for a different value is a
 * conflict and is rejected. Should that ever need to become a stack, this column is its top entry.
 */
@Entity(primaryKeys = ["settingType", "key"])
data class ProtectedKeyEntity(
    val settingType: SettingType,
    val key: String,
    val originalValue: String?,
    val enforcedValue: String,
    val claimedAtMillis: Long,
)
