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
package com.android.geto.data.room

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.android.geto.data.room.migration.Migration10To11
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration10To11Test {
    private val testDatabase = "migration-10-11-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    /**
     * The case that cannot be fixed in a later release: a user upgrading while an app is protected.
     * Their pre-Geto values live only in the old `originalValue` column, so the ledger has to inherit
     * them exactly.
     */
    @Test
    fun migrate10To11_movesSingletonSessionAndSeedsKeyLedgerWithOriginals() {
        helper.createDatabase(testDatabase, 10).use { db ->
            db.execSQL(
                """
                INSERT INTO ProtectionSessionEntity (
                    id, token, componentName, mode, status,
                    createdAtMillis, updatedAtMillis, lastError
                ) VALUES (
                    1, 'token-abc', 'com.example/.MainActivity', 'PERSISTENT', 'ACTIVE',
                    1000, 2000, NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO ProtectionValueEntity (
                    sessionId, settingType, key, protectedValue, originalValue, writeOrder
                ) VALUES
                    (1, 'GLOBAL', 'development_settings_enabled', '0', '1', 0),
                    (1, 'SECURE', 'some_null_original', 'on', NULL, 1)
                """.trimIndent(),
            )
        }

        helper.runMigrationsAndValidate(testDatabase, 11, true, Migration10To11()).use { db ->
            db.query(
                "SELECT * FROM ProtectionSessionEntity WHERE token = 'token-abc'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(
                    "com.example/.MainActivity",
                    cursor.getString(cursor.getColumnIndexOrThrow("componentName")),
                )
                assertEquals(1000, cursor.getLong(cursor.getColumnIndexOrThrow("createdAtMillis")))
                // The removed PERSISTENT constant must be rewritten, or Room cannot read the row and
                // the user's original values become unreachable.
                assertEquals("FOREGROUND", cursor.getString(cursor.getColumnIndexOrThrow("mode")))
                // Existing sessions come across as not paused.
                assertEquals(0, cursor.getLong(cursor.getColumnIndexOrThrow("pausedUntilMillis")))
            }

            db.query(
                "SELECT * FROM ProtectedKeyEntity WHERE key = 'development_settings_enabled'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("1", cursor.getString(cursor.getColumnIndexOrThrow("originalValue")))
                assertEquals("0", cursor.getString(cursor.getColumnIndexOrThrow("enforcedValue")))
                assertEquals("GLOBAL", cursor.getString(cursor.getColumnIndexOrThrow("settingType")))
                assertEquals(1000, cursor.getLong(cursor.getColumnIndexOrThrow("claimedAtMillis")))
            }

            // A null original is a real value meaning "the key was unset", not missing data.
            db.query(
                "SELECT * FROM ProtectedKeyEntity WHERE key = 'some_null_original'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow("originalValue")))
                assertEquals("on", cursor.getString(cursor.getColumnIndexOrThrow("enforcedValue")))
            }

            db.query("SELECT COUNT(*) FROM ProtectedKeyEntity").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(2, cursor.getInt(0))
            }

            // Claims are re-keyed from the old sessionId onto the session token.
            db.query(
                "SELECT * FROM ProtectionValueEntity WHERE key = 'development_settings_enabled'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(
                    "token-abc",
                    cursor.getString(cursor.getColumnIndexOrThrow("sessionToken")),
                )
                assertEquals("0", cursor.getString(cursor.getColumnIndexOrThrow("protectedValue")))
                assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("writeOrder")))
                // originalValue moved to the ledger and must be gone from the claim row.
                assertEquals(-1, cursor.getColumnIndex("originalValue"))
            }
        }
    }

    @Test
    fun migrate10To11_withNoProtection_leavesProfilesIntactAndTablesEmpty() {
        helper.createDatabase(testDatabase, 10).use { db ->
            db.execSQL(
                """
                INSERT INTO AppSettingEntity (
                    id, enabled, settingType, componentName, label, key,
                    valueOnLaunch, valueOnRevert
                ) VALUES (
                    1, 1, 'GLOBAL', 'com.example/.MainActivity', 'Developer options',
                    'development_settings_enabled', '0', '1'
                )
                """.trimIndent(),
            )
        }

        helper.runMigrationsAndValidate(testDatabase, 11, true, Migration10To11()).use { db ->
            db.query("SELECT * FROM AppSettingEntity WHERE id = 1").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(
                    "com.example/.MainActivity",
                    cursor.getString(cursor.getColumnIndexOrThrow("componentName")),
                )
                assertEquals("1", cursor.getString(cursor.getColumnIndexOrThrow("valueOnRevert")))
            }

            db.query("SELECT * FROM ProtectedKeyEntity").use { cursor ->
                assertFalse(cursor.moveToFirst())
            }
            db.query("SELECT * FROM ProtectionSessionEntity").use { cursor ->
                assertFalse(cursor.moveToFirst())
            }
            db.query("SELECT * FROM ProtectionValueEntity").use { cursor ->
                assertFalse(cursor.moveToFirst())
            }
        }
    }
}
