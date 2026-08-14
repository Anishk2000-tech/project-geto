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

/**
 * Single-app protection becomes multi-app protection.
 *
 * Version 10 kept one session on a hardcoded row (`id = 1`) and stored each key's original value on
 * the session's own value rows. Version 11 gives every app its own session keyed by token, and moves
 * the original values into a shared ledger so several apps can claim the same key.
 *
 * The ledger is seeded from the old `originalValue` column **before** anything is dropped: that
 * column is the only record of what the device looked like before Geto touched it, and for a user who
 * upgrades mid-protection it is unrecoverable if lost.
 *
 * The old `PERSISTENT` mode is rewritten to `FOREGROUND`, because the enum no longer has a constant
 * for "applied forever" and Room would fail to read the row. Carrying the row across rather than
 * deleting it is deliberate: the session still holds the claims that point at the ledger, so startup
 * reconciliation can write the user's original values back. Deleting it here would strip the claims
 * and orphan the originals.
 */
internal class Migration10To11 : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `ArmedProfileEntity` (`componentName` TEXT NOT NULL, " +
                "`armedAtMillis` INTEGER NOT NULL, PRIMARY KEY(`componentName`))",
        )

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `ProtectedKeyEntity` (`settingType` TEXT NOT NULL, " +
                "`key` TEXT NOT NULL, `originalValue` TEXT, `enforcedValue` TEXT NOT NULL, " +
                "`claimedAtMillis` INTEGER NOT NULL, PRIMARY KEY(`settingType`, `key`))",
        )

        // Seed first, drop later. INSERT OR IGNORE guards the theoretical case of two rows for one
        // key; the first one wins, which is the same rule the running code applies.
        db.execSQL(
            """
            INSERT OR IGNORE INTO `ProtectedKeyEntity`
                (`settingType`, `key`, `originalValue`, `enforcedValue`, `claimedAtMillis`)
            SELECT v.`settingType`, v.`key`, v.`originalValue`, v.`protectedValue`, s.`createdAtMillis`
            FROM `ProtectionValueEntity` v
            JOIN `ProtectionSessionEntity` s ON v.`sessionId` = s.`id`
            """.trimIndent(),
        )

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `ProtectionSessionEntity_new` (`token` TEXT NOT NULL, " +
                "`componentName` TEXT NOT NULL, `mode` TEXT NOT NULL, `status` TEXT NOT NULL, " +
                "`createdAtMillis` INTEGER NOT NULL, `updatedAtMillis` INTEGER NOT NULL, " +
                "`lastError` TEXT, `pausedUntilMillis` INTEGER NOT NULL, PRIMARY KEY(`token`))",
        )

        db.execSQL(
            """
            INSERT INTO `ProtectionSessionEntity_new`
                (`token`, `componentName`, `mode`, `status`, `createdAtMillis`, `updatedAtMillis`,
                 `lastError`, `pausedUntilMillis`)
            SELECT `token`, `componentName`,
                   CASE `mode` WHEN 'PERSISTENT' THEN 'FOREGROUND' ELSE `mode` END,
                   `status`, `createdAtMillis`, `updatedAtMillis`, `lastError`, 0
            FROM `ProtectionSessionEntity`
            """.trimIndent(),
        )

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `ProtectionValueEntity_new` (`sessionToken` TEXT NOT NULL, " +
                "`settingType` TEXT NOT NULL, `key` TEXT NOT NULL, `protectedValue` TEXT NOT NULL, " +
                "`writeOrder` INTEGER NOT NULL, PRIMARY KEY(`sessionToken`, `settingType`, `key`), " +
                "FOREIGN KEY(`sessionToken`) REFERENCES `ProtectionSessionEntity`(`token`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE , " +
                "FOREIGN KEY(`settingType`, `key`) REFERENCES `ProtectedKeyEntity`(`settingType`, `key`) " +
                "ON UPDATE NO ACTION ON DELETE NO ACTION )",
        )

        db.execSQL(
            """
            INSERT INTO `ProtectionValueEntity_new`
                (`sessionToken`, `settingType`, `key`, `protectedValue`, `writeOrder`)
            SELECT s.`token`, v.`settingType`, v.`key`, v.`protectedValue`, v.`writeOrder`
            FROM `ProtectionValueEntity` v
            JOIN `ProtectionSessionEntity` s ON v.`sessionId` = s.`id`
            """.trimIndent(),
        )

        db.execSQL("DROP TABLE `ProtectionValueEntity`")
        db.execSQL("DROP TABLE `ProtectionSessionEntity`")
        db.execSQL("ALTER TABLE `ProtectionSessionEntity_new` RENAME TO `ProtectionSessionEntity`")
        db.execSQL("ALTER TABLE `ProtectionValueEntity_new` RENAME TO `ProtectionValueEntity`")

        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_ProtectionSessionEntity_componentName` " +
                "ON `ProtectionSessionEntity` (`componentName`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_ProtectionValueEntity_sessionToken` " +
                "ON `ProtectionValueEntity` (`sessionToken`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_ProtectionValueEntity_settingType_key` " +
                "ON `ProtectionValueEntity` (`settingType`, `key`)",
        )
    }
}
