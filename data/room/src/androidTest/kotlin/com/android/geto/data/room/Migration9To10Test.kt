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
import com.android.geto.data.room.migration.Migration9To10
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration9To10Test {
    private val testDatabase = "migration-9-10-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun migrate9To10_preservesProfilesAndAddsEmptyProtectionTables() {
        helper.createDatabase(testDatabase, 9).use { db ->
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

        helper.runMigrationsAndValidate(
            testDatabase,
            10,
            true,
            Migration9To10(),
        ).use { db ->
            db.query("SELECT * FROM AppSettingEntity WHERE id = 1").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(
                    "com.example/.MainActivity",
                    cursor.getString(cursor.getColumnIndexOrThrow("componentName")),
                )
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
