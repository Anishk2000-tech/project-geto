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
package com.android.geto.data.room.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal class Migration9To10 : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS ProtectionSessionEntity (
                id INTEGER NOT NULL,
                token TEXT NOT NULL,
                componentName TEXT NOT NULL,
                mode TEXT NOT NULL,
                status TEXT NOT NULL,
                createdAtMillis INTEGER NOT NULL,
                updatedAtMillis INTEGER NOT NULL,
                lastError TEXT,
                PRIMARY KEY(id)
            )
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS ProtectionValueEntity (
                sessionId INTEGER NOT NULL,
                settingType TEXT NOT NULL,
                key TEXT NOT NULL,
                protectedValue TEXT NOT NULL,
                originalValue TEXT,
                writeOrder INTEGER NOT NULL,
                PRIMARY KEY(sessionId, settingType, key),
                FOREIGN KEY(sessionId) REFERENCES ProtectionSessionEntity(id)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )

        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_ProtectionValueEntity_sessionId " +
                "ON ProtectionValueEntity (sessionId)",
        )
    }
}
