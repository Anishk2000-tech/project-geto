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
package com.android.geto.domain.usecase

import com.android.geto.domain.framework.SecureSettingsWrapper
import com.android.geto.domain.model.AppSetting
import com.android.geto.domain.model.PAUSE_INDEFINITE
import com.android.geto.domain.model.ProtectedKey
import com.android.geto.domain.model.ProtectionMode
import com.android.geto.domain.model.ProtectionPauseDuration
import com.android.geto.domain.model.ProtectionResult
import com.android.geto.domain.model.ProtectionSession
import com.android.geto.domain.model.ProtectionSessionStatus
import com.android.geto.domain.model.SecureSetting
import com.android.geto.domain.model.SettingReadResult
import com.android.geto.domain.model.SettingType
import com.android.geto.domain.model.SettingWriteResult
import com.android.geto.domain.repository.AppSettingsRepository
import com.android.geto.domain.repository.ProtectionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * These tests exist to protect one rule: a key's original value is captured on the first claim and
 * written back only when the last claim goes away. Everything about multi-app protection depends on
 * it, and getting it wrong silently destroys the user's real settings.
 */
class ProtectionControllerTest {

    private val devOptions = AppSetting(
        id = 1,
        enabled = true,
        settingType = SettingType.GLOBAL,
        componentName = BANK_A,
        label = "Developer options",
        key = DEV_OPTIONS,
        valueOnLaunch = "0",
        valueOnRevert = "1",
    )

    @Test
    fun enable_foregroundAfterOneShotForSameApp_adoptsTheSessionInsteadOfWedgingIt() = runTest {
        val settings = FakeSecureSettingsWrapper(mapOf(GLOBAL_DEV to "1"))
        val controller = controller(
            appSettings = mapOf(BANK_A to listOf(devOptions)),
            secureSettings = settings,
        )

        // Launching an armed app from Geto applies it, and the coordinator then sees it come to the
        // foreground and asks for the same app again. That second call used to be rejected.
        controller.enable(BANK_A, ProtectionMode.ONE_SHOT)
        val result = controller.enable(BANK_A, ProtectionMode.FOREGROUND)

        assertIs<ProtectionResult.Success>(result)
        assertEquals(ProtectionSessionStatus.ACTIVE, result.app?.status)
        assertEquals("0", settings.values[GLOBAL_DEV])
    }

    @Test
    fun enable_capturesTheOriginalValueAndAppliesTheProfile() = runTest {
        val settings = FakeSecureSettingsWrapper(mapOf(GLOBAL_DEV to "1"))
        val controller = controller(
            appSettings = mapOf(BANK_A to listOf(devOptions)),
            secureSettings = settings,
        )

        val result = controller.enable(BANK_A, ProtectionMode.FOREGROUND)

        assertIs<ProtectionResult.Success>(result)
        assertEquals(ProtectionSessionStatus.ACTIVE, result.app?.status)
        assertEquals("0", settings.values[GLOBAL_DEV])
    }

    @Test
    fun enable_secondAppSameKeySameValue_keepsTheFirstOriginalAndWritesNothingNew() = runTest {
        val settings = FakeSecureSettingsWrapper(mapOf(GLOBAL_DEV to "1"))
        val repository = FakeProtectionRepository()
        val controller = controller(
            appSettings = mapOf(
                BANK_A to listOf(devOptions),
                BANK_B to listOf(devOptions.copy(id = 2, componentName = BANK_B)),
            ),
            secureSettings = settings,
            repository = repository,
        )

        controller.enable(BANK_A, ProtectionMode.FOREGROUND)
        val writesAfterFirst = settings.writes.size

        val result = controller.enable(BANK_B, ProtectionMode.FOREGROUND)

        assertIs<ProtectionResult.Success>(result)
        // The ledger must still hold the value from before Geto ever touched the device.
        assertEquals("1", repository.ledger.getValue(GLOBAL_DEV).originalValue)
        assertEquals(2, repository.getProtectedKeys().single().claimCount)
        // The value was already enforced, so the second enable adds exactly one idempotent write.
        assertEquals(writesAfterFirst + 1, settings.writes.size)
    }

    @Test
    fun restore_whileAnotherAppStillClaimsTheKey_leavesItEnforced() = runTest {
        val settings = FakeSecureSettingsWrapper(mapOf(GLOBAL_DEV to "1"))
        val repository = FakeProtectionRepository()
        val controller = controller(
            appSettings = mapOf(
                BANK_A to listOf(devOptions),
                BANK_B to listOf(devOptions.copy(id = 2, componentName = BANK_B)),
            ),
            secureSettings = settings,
            repository = repository,
        )
        val first = controller.enable(BANK_A, ProtectionMode.FOREGROUND) as ProtectionResult.Success
        controller.enable(BANK_B, ProtectionMode.FOREGROUND)

        val result = controller.restore(first.app!!.sessionToken)

        assertIs<ProtectionResult.Success>(result)
        assertEquals("0", settings.values[GLOBAL_DEV], "the other app still needs this value")
        assertEquals(1, repository.getProtectedKeys().single().claimCount)
    }

    @Test
    fun restore_byTheLastClaimant_writesTheTrueOriginal() = runTest {
        val settings = FakeSecureSettingsWrapper(mapOf(GLOBAL_DEV to "1"))
        val repository = FakeProtectionRepository()
        val controller = controller(
            appSettings = mapOf(
                BANK_A to listOf(devOptions),
                BANK_B to listOf(devOptions.copy(id = 2, componentName = BANK_B)),
            ),
            secureSettings = settings,
            repository = repository,
        )
        val first = controller.enable(BANK_A, ProtectionMode.FOREGROUND) as ProtectionResult.Success
        val second = controller.enable(BANK_B, ProtectionMode.FOREGROUND) as ProtectionResult.Success

        controller.restore(first.app!!.sessionToken)
        controller.restore(second.app!!.sessionToken)

        assertEquals("1", settings.values[GLOBAL_DEV])
        assertTrue(repository.ledger.isEmpty(), "the ledger row should be swept with the last claim")
    }

    @Test
    fun enable_sameKeyDifferentValue_isRejectedWithoutWritingAnything() = runTest {
        val settings = FakeSecureSettingsWrapper(mapOf(GLOBAL_DEV to "1"))
        val controller = controller(
            appSettings = mapOf(
                BANK_A to listOf(devOptions),
                // Same key, contradictory value.
                BANK_B to listOf(devOptions.copy(id = 2, componentName = BANK_B, valueOnLaunch = "1")),
            ),
            secureSettings = settings,
        )
        controller.enable(BANK_A, ProtectionMode.FOREGROUND)
        val writesBefore = settings.writes.size

        val result = controller.enable(BANK_B, ProtectionMode.FOREGROUND)

        val conflict = assertIs<ProtectionResult.KeyConflict>(result)
        assertEquals(DEV_OPTIONS, conflict.key)
        assertEquals("0", conflict.enforcedValue)
        assertEquals("1", conflict.requestedValue)
        assertEquals(listOf(BANK_A), conflict.holderComponentNames)
        assertEquals(writesBefore, settings.writes.size, "a rejected enable must touch nothing")
        assertEquals("0", settings.values[GLOBAL_DEV])
    }

    @Test
    fun enable_whenAWriteFails_rollsBackOnlyItsOwnNewlyClaimedKeys() = runTest {
        val settings = FakeSecureSettingsWrapper(
            initialValues = mapOf(GLOBAL_DEV to "1", SECURE_OTHER to "on"),
            failOnWriteNumber = 3,
        )
        val repository = FakeProtectionRepository()
        val shared = devOptions
        val extra = AppSetting(
            id = 3,
            enabled = true,
            settingType = SettingType.SECURE,
            componentName = BANK_B,
            label = "Other",
            key = OTHER_KEY,
            valueOnLaunch = "off",
            valueOnRevert = "on",
        )
        val controller = controller(
            appSettings = mapOf(
                BANK_A to listOf(shared),
                BANK_B to listOf(shared.copy(id = 2, componentName = BANK_B), extra),
            ),
            secureSettings = settings,
            repository = repository,
        )
        controller.enable(BANK_A, ProtectionMode.FOREGROUND)

        val result = controller.enable(BANK_B, ProtectionMode.FOREGROUND)

        assertIs<ProtectionResult.Failure>(result)
        // The shared key belongs to bank A too, so rolling it back would have broken A.
        assertEquals("0", settings.values[GLOBAL_DEV])
        assertEquals("on", settings.values[SECURE_OTHER], "its own new key is rolled back")
        assertNull(repository.sessionByComponent(BANK_B))
    }

    @Test
    fun reapply_whenEveryClaimantIsPaused_doesNotWrite() = runTest {
        val settings = FakeSecureSettingsWrapper(mapOf(GLOBAL_DEV to "1"))
        val controller = controller(
            appSettings = mapOf(BANK_A to listOf(devOptions)),
            secureSettings = settings,
        )
        val enabled = controller.enable(BANK_A, ProtectionMode.FOREGROUND) as ProtectionResult.Success
        controller.pause(enabled.app!!.sessionToken, ProtectionPauseDuration.ONE_HOUR)
        settings.values[GLOBAL_DEV] = "1"
        val writesBefore = settings.writes.size

        controller.reapply(SettingType.GLOBAL, DEV_OPTIONS)

        assertEquals("1", settings.values[GLOBAL_DEV], "a paused app must not repair drift")
        assertEquals(writesBefore, settings.writes.size)
    }

    @Test
    fun reapply_whenOneClaimantIsStillRunning_repairsTheKey() = runTest {
        val settings = FakeSecureSettingsWrapper(mapOf(GLOBAL_DEV to "1"))
        val controller = controller(
            appSettings = mapOf(
                BANK_A to listOf(devOptions),
                BANK_B to listOf(devOptions.copy(id = 2, componentName = BANK_B)),
            ),
            secureSettings = settings,
        )
        val first = controller.enable(BANK_A, ProtectionMode.FOREGROUND) as ProtectionResult.Success
        controller.enable(BANK_B, ProtectionMode.FOREGROUND)
        controller.pause(first.app!!.sessionToken, ProtectionPauseDuration.ONE_HOUR)
        settings.values[GLOBAL_DEV] = "1"

        controller.reapply(SettingType.GLOBAL, DEV_OPTIONS)

        assertEquals("0", settings.values[GLOBAL_DEV])
    }

    @Test
    fun pause_appliesToOneAppOnly() = runTest {
        val repository = FakeProtectionRepository()
        val controller = controller(
            appSettings = mapOf(
                BANK_A to listOf(devOptions),
                BANK_B to listOf(devOptions.copy(id = 2, componentName = BANK_B)),
            ),
            secureSettings = FakeSecureSettingsWrapper(mapOf(GLOBAL_DEV to "1")),
            repository = repository,
        )
        val first = controller.enable(BANK_A, ProtectionMode.FOREGROUND) as ProtectionResult.Success
        controller.enable(BANK_B, ProtectionMode.FOREGROUND)

        controller.pause(first.app!!.sessionToken, ProtectionPauseDuration.INDEFINITE)

        assertEquals(PAUSE_INDEFINITE, repository.sessionByComponent(BANK_A)?.pausedUntilMillis)
        assertEquals(0L, repository.sessionByComponent(BANK_B)?.pausedUntilMillis)
    }

    /** Resuming must repair drift, or protection would claim to be active while a value is wrong. */
    @Test
    fun resume_repairsWhateverDriftedWhileItWasPaused() = runTest {
        val settings = FakeSecureSettingsWrapper(mapOf(GLOBAL_DEV to "1"))
        val controller = controller(
            appSettings = mapOf(BANK_A to listOf(devOptions)),
            secureSettings = settings,
        )
        val enabled = controller.enable(BANK_A, ProtectionMode.FOREGROUND) as ProtectionResult.Success
        controller.pause(enabled.app!!.sessionToken, ProtectionPauseDuration.ONE_HOUR)
        settings.values[GLOBAL_DEV] = "1"

        controller.resume(enabled.app!!.sessionToken)

        assertEquals("0", settings.values[GLOBAL_DEV])
    }

    @Test
    fun reconcile_sweepsALedgerRowLeftByAnInterruptedRestore() = runTest {
        val settings = FakeSecureSettingsWrapper(mapOf(GLOBAL_DEV to "0"))
        val repository = FakeProtectionRepository()
        // A crash between writing the original back and deleting the rows leaves exactly this.
        repository.ledger[GLOBAL_DEV] = ProtectedKey(
            settingType = SettingType.GLOBAL,
            key = DEV_OPTIONS,
            originalValue = "1",
            enforcedValue = "0",
            claimedAtMillis = 0,
        )
        val controller = controller(
            appSettings = emptyMap(),
            secureSettings = settings,
            repository = repository,
        )

        controller.reconcile()

        assertEquals("1", settings.values[GLOBAL_DEV])
        assertTrue(repository.ledger.isEmpty())
    }

    /**
     * The regression that stranded the user: a profile that stays applied disables developer options
     * system-wide and takes ADB and Shizuku with it. Arming must write nothing on its own.
     */
    @Test
    fun armProfile_writesNothingUntilTheAppIsActuallyOpened() = runTest {
        val settings = FakeSecureSettingsWrapper(mapOf(GLOBAL_DEV to "1"))
        val controller = controller(
            appSettings = mapOf(BANK_A to listOf(devOptions)),
            secureSettings = settings,
        )

        controller.armProfile(BANK_A)

        assertTrue(settings.writes.isEmpty())
        assertEquals("1", settings.values[GLOBAL_DEV], "developer options must stay on")
    }

    @Test
    fun restoreApplied_putsTheOriginalBackWithoutDisarming() = runTest {
        val settings = FakeSecureSettingsWrapper(mapOf(GLOBAL_DEV to "1"))
        val repository = FakeProtectionRepository()
        val controller = controller(
            appSettings = mapOf(BANK_A to listOf(devOptions)),
            secureSettings = settings,
            repository = repository,
        )
        controller.armProfile(BANK_A)
        controller.enable(BANK_A, ProtectionMode.FOREGROUND)
        assertEquals("0", settings.values[GLOBAL_DEV])

        controller.restoreApplied(BANK_A)

        assertEquals("1", settings.values[GLOBAL_DEV], "leaving the app restores the original")
        assertEquals(
            setOf(BANK_A),
            repository.getArmedComponentNames(),
            "still armed for next time",
        )
    }

    /** Nothing may outlive the process that applied it — this is the anti-stranding guarantee. */
    @Test
    fun restoreEverythingApplied_clearsAnythingLeftFromAPreviousRun() = runTest {
        val settings = FakeSecureSettingsWrapper(mapOf(GLOBAL_DEV to "1"))
        val controller = controller(
            appSettings = mapOf(BANK_A to listOf(devOptions)),
            secureSettings = settings,
        )
        controller.enable(BANK_A, ProtectionMode.FOREGROUND)
        assertEquals("0", settings.values[GLOBAL_DEV])

        controller.restoreEverythingApplied()

        assertEquals("1", settings.values[GLOBAL_DEV])
    }

    @Test
    fun disarmProfile_restoresWhenTheProfileIsCurrentlyApplied() = runTest {
        val settings = FakeSecureSettingsWrapper(mapOf(GLOBAL_DEV to "1"))
        val repository = FakeProtectionRepository()
        val controller = controller(
            appSettings = mapOf(BANK_A to listOf(devOptions)),
            secureSettings = settings,
            repository = repository,
        )
        controller.armProfile(BANK_A)
        controller.enable(BANK_A, ProtectionMode.FOREGROUND)

        controller.disarmProfile(BANK_A)

        assertEquals("1", settings.values[GLOBAL_DEV])
        assertTrue(repository.getArmedComponentNames().isEmpty())
    }

    /**
     * `adb_enabled` is only writable while developer options is on. Writing the gate off first made
     * the rest of the profile fail, and the reversed restore then tried to switch the dependants back
     * on while the gate was still off — which is what produced Samsung's "turn on Developer options
     * first" rejection.
     */
    @Test
    fun enable_writesTheGateSettingLast_soDependentKeysAreStillWritable() = runTest {
        val settings = FakeSecureSettingsWrapper(
            mapOf(GLOBAL_DEV to "1", GLOBAL_ADB to "1"),
        )
        val adb = devOptions.copy(id = 9, key = ADB_ENABLED, valueOnLaunch = "0")
        val controller = controller(
            // Template order puts the gate first; the controller must reorder it.
            appSettings = mapOf(BANK_A to listOf(devOptions, adb)),
            secureSettings = settings,
        )

        controller.enable(BANK_A, ProtectionMode.FOREGROUND)

        val written = settings.writes.map { it.second }
        assertEquals(
            listOf(ADB_ENABLED, DEV_OPTIONS),
            written,
            "the gate must be written after the keys that depend on it",
        )
    }

    @Test
    fun restore_writesTheGateSettingFirst_soDependentKeysAreStillWritable() = runTest {
        val settings = FakeSecureSettingsWrapper(
            mapOf(GLOBAL_DEV to "1", GLOBAL_ADB to "1"),
        )
        val adb = devOptions.copy(id = 9, key = ADB_ENABLED, valueOnLaunch = "0")
        val controller = controller(
            appSettings = mapOf(BANK_A to listOf(devOptions, adb)),
            secureSettings = settings,
        )
        val enabled = controller.enable(BANK_A, ProtectionMode.FOREGROUND) as ProtectionResult.Success
        settings.writes.clear()

        controller.restore(enabled.app!!.sessionToken)

        val written = settings.writes.map { it.second }
        assertEquals(
            listOf(DEV_OPTIONS, ADB_ENABLED),
            written,
            "the gate must be restored before the keys that depend on it",
        )
        assertEquals("1", settings.values[GLOBAL_DEV])
        assertEquals("1", settings.values[GLOBAL_ADB])
    }

    @Test
    fun enable_withoutPermission_isRejected() = runTest {
        val controller = controller(
            appSettings = mapOf(BANK_A to listOf(devOptions)),
            secureSettings = FakeSecureSettingsWrapper(
                initialValues = mapOf(GLOBAL_DEV to "1"),
                hasPermission = false,
            ),
        )

        assertIs<ProtectionResult.PermissionDenied>(
            controller.enable(BANK_A, ProtectionMode.FOREGROUND),
        )
    }

    private fun controller(
        appSettings: Map<String, List<AppSetting>>,
        secureSettings: FakeSecureSettingsWrapper,
        repository: FakeProtectionRepository = FakeProtectionRepository(),
    ) = ProtectionController(
        appSettingsRepository = FakeAppSettingsRepository(appSettings),
        protectionRepository = repository,
        secureSettingsWrapper = secureSettings,
    ).apply { now = { CLOCK } }

    private class FakeAppSettingsRepository(
        private val byComponentName: Map<String, List<AppSetting>>,
    ) : AppSettingsRepository {
        override val appSettingsFlow: Flow<List<AppSetting>> =
            flowOf(byComponentName.values.flatten())

        override suspend fun upsertAppSetting(appSetting: AppSetting) = Unit

        override suspend fun upsertAppSettings(appSettings: List<AppSetting>) = Unit

        override suspend fun deleteAppSetting(appSetting: AppSetting) = Unit

        override fun getAppSettingsFlowByComponentName(componentName: String): Flow<List<AppSetting>> = flowOf(byComponentName[componentName].orEmpty())

        override suspend fun getAppSettingsByComponentName(componentName: String): List<AppSetting> = byComponentName[componentName].orEmpty()
    }

    /** In-memory stand-in modelling the ledger and the claim rows the same way Room does. */
    private class FakeProtectionRepository : ProtectionRepository {
        private val sessions = MutableStateFlow<List<ProtectionSession>>(emptyList())
        private val armed = MutableStateFlow<Set<String>>(emptySet())
        val ledger = mutableMapOf<Pair<SettingType, String>, ProtectedKey>()

        override val armedComponentNamesFlow: Flow<Set<String>> = armed

        override suspend fun getArmedComponentNames(): Set<String> = armed.value

        override suspend fun armProfile(componentName: String, armedAtMillis: Long) {
            armed.value = armed.value + componentName
        }

        override suspend fun disarmProfile(componentName: String): Boolean {
            if (componentName !in armed.value) return false
            armed.value = armed.value - componentName
            return true
        }

        override val sessionsFlow: Flow<List<ProtectionSession>> = sessions

        fun sessionByComponent(componentName: String): ProtectionSession? = sessions.value.firstOrNull { it.componentName == componentName }

        private fun claimCount(settingType: SettingType, key: String): Int = sessions.value.count { session ->
            session.protectedSettings.any { it.settingType == settingType && it.key == key }
        }

        override suspend fun getSessions(): List<ProtectionSession> = sessions.value

        override suspend fun getSessionByToken(token: String): ProtectionSession? = sessions.value.firstOrNull { it.token == token }

        override suspend fun getSessionByComponentName(componentName: String): ProtectionSession? = sessionByComponent(componentName)

        override suspend fun getProtectedKeys(): List<ProtectedKey> = ledger.values.map { it.copy(claimCount = claimCount(it.settingType, it.key)) }

        override suspend fun getProtectedKey(
            settingType: SettingType,
            key: String,
        ): ProtectedKey? = ledger[settingType to key]

        override suspend fun getClaimantComponentNames(
            settingType: SettingType,
            key: String,
        ): List<String> = sessions.value
            .filter { session ->
                session.protectedSettings.any { it.settingType == settingType && it.key == key }
            }
            .map { it.componentName }

        override suspend fun getKeysReleasedBy(token: String): List<ProtectedKey> {
            val session = getSessionByToken(token) ?: return emptyList()
            return session.protectedSettings
                .filter { claimCount(it.settingType, it.key) == 1 }
                .mapNotNull { ledger[it.settingType to it.key] }
        }

        override suspend fun getOrphanKeys(): List<ProtectedKey> = ledger.values.filter { claimCount(it.settingType, it.key) == 0 }

        override suspend fun deleteOrphanKeys(): Int {
            val orphans = ledger.filterValues { claimCount(it.settingType, it.key) == 0 }
            orphans.keys.forEach(ledger::remove)
            return orphans.size
        }

        override suspend fun createSession(
            session: ProtectionSession,
            newKeys: List<ProtectedKey>,
        ) {
            newKeys.forEach { ledger.putIfAbsent(it.settingType to it.key, it) }
            sessions.value = sessions.value + session
        }

        override suspend fun updateStatus(
            token: String,
            status: ProtectionSessionStatus,
            updatedAtMillis: Long,
            lastError: String?,
        ): Boolean = update(token) {
            it.copy(status = status, updatedAtMillis = updatedAtMillis, lastError = lastError)
        }

        override suspend fun updatePausedUntil(
            token: String,
            pausedUntilMillis: Long,
            updatedAtMillis: Long,
        ): Boolean = update(token) {
            it.copy(pausedUntilMillis = pausedUntilMillis, updatedAtMillis = updatedAtMillis)
        }

        override suspend fun clearSession(token: String): Boolean {
            if (sessions.value.none { it.token == token }) return false
            sessions.value = sessions.value.filterNot { it.token == token }
            deleteOrphanKeys()
            return true
        }

        private fun update(
            token: String,
            transform: (ProtectionSession) -> ProtectionSession,
        ): Boolean {
            if (sessions.value.none { it.token == token }) return false
            sessions.value = sessions.value.map { if (it.token == token) transform(it) else it }
            return true
        }
    }

    private class FakeSecureSettingsWrapper(
        initialValues: Map<Pair<SettingType, String>, String?> = emptyMap(),
        private val failOnWriteNumber: Int? = null,
        private val hasPermission: Boolean = true,
    ) : SecureSettingsWrapper {
        val values = initialValues.toMutableMap()
        val writes = mutableListOf<Pair<SettingType, String>>()
        private var writeCount = 0

        override fun hasWriteSecureSettingsPermission(): Boolean = hasPermission

        override suspend fun read(
            settingType: SettingType,
            key: String,
        ): SettingReadResult = SettingReadResult.Success(values[settingType to key])

        override suspend fun write(
            settingType: SettingType,
            key: String,
            value: String?,
        ): SettingWriteResult {
            writeCount++
            writes += settingType to key
            if (writeCount == failOnWriteNumber) {
                return SettingWriteResult.Failure(
                    reason = com.android.geto.domain.model.ProtectionFailureReason.WRITE_REJECTED,
                    expectedValue = value,
                    actualValue = values[settingType to key],
                )
            }
            values[settingType to key] = value
            return SettingWriteResult.Success(value)
        }

        override suspend fun getSecureSettings(settingType: SettingType): List<SecureSetting> = emptyList()
    }

    private companion object {
        const val BANK_A = "com.bank.a/.MainActivity"
        const val BANK_B = "com.bank.b/.MainActivity"
        const val DEV_OPTIONS = "development_settings_enabled"
        const val ADB_ENABLED = "adb_enabled"
        const val OTHER_KEY = "other_key"
        const val CLOCK = 1_700_000_000_000L
        val GLOBAL_DEV = SettingType.GLOBAL to DEV_OPTIONS
        val GLOBAL_ADB = SettingType.GLOBAL to ADB_ENABLED
        val SECURE_OTHER = SettingType.SECURE to OTHER_KEY
    }
}
