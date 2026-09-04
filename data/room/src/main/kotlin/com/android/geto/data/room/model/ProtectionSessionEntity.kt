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
import androidx.room.Index
import androidx.room.PrimaryKey
import com.android.geto.domain.model.ProtectionMode
import com.android.geto.domain.model.ProtectionSessionStatus

/**
 * One protected app. Several rows can coexist; the unique index on [componentName] is what keeps an
 * app from being protected twice.
 */
@Entity(indices = [Index(value = ["componentName"], unique = true)])
data class ProtectionSessionEntity(
    @PrimaryKey val token: String,
    val componentName: String,
    val mode: ProtectionMode,
    val status: ProtectionSessionStatus,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val lastError: String?,
    /** 0 = running, [Long.MAX_VALUE] = paused with no deadline, otherwise an epoch-millis deadline. */
    val pausedUntilMillis: Long = 0L,
)
