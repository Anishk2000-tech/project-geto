# Repository Guidelines

## Project Structure & Module Organization

Geto is a multi-module Android app written in Kotlin and Jetpack Compose. `app/` contains the
application, activities, navigation, and manifest. Keep UI features in `feature/` (`apps`,
`app-settings`, `home`, `settings`), reusable Compose pieces in `design-system/` and `ui/`, and
background protection code in `service/`.

The clean architecture layers are `domain/` (models, repository interfaces, use cases), `data/`
(DataStore, Room, repository implementations), and `framework/` (Android API adapters). Put Room
entities, DAOs, migrations, and schemas in `data/room/`; protobuf definitions are in
`data/datastore-proto/src/main/proto/`. Assets such as setting templates belong under the owning
module's `src/main/assets/`.

## Build, Test, and Development Commands

Run commands from the repository root. Ensure `ANDROID_HOME` points to an installed Android SDK.

- `./gradlew :app:assembleDebug` builds the debug APK.
- `./gradlew testDebugUnitTest` runs Android module unit tests; use a module task such as
  `./gradlew :domain:use-case:test` for focused work.
- `./gradlew :data:room:connectedDebugAndroidTest` runs Room migration tests on a connected device
  or emulator.
- `./gradlew lintDebug` runs Android lint.
- `./gradlew spotlessApply --init-script gradle/init.gradle.kts` formats Kotlin, Gradle Kotlin,
  and XML; use `spotlessCheck` in review/CI checks.

## Coding Style & Naming Conventions

Use four-space indentation and Kotlin idioms. Spotless/Ktlint is authoritative; run it before
committing. Follow existing package names under `com.android.geto`. Name Compose screens
`*Screen`, ViewModels `*ViewModel`, UI state classes `*UiState`, Hilt modules `*Module`, and use
cases as verb-led `*UseCase`. Preserve the GPL header used by nearby Kotlin and Gradle files.

## Testing Guidelines

Place unit tests in `src/test/kotlin` and device tests in `src/androidTest/kotlin`. Use descriptive
test names that state the behavior, e.g. `migrate9To10_preservesProfilesAndAddsEmptyProtectionTables`.
Add tests for use-case transactions, ViewModel state, and every Room migration; update the schema
JSON when changing Room entities.

## Commit & Pull Request Guidelines

Use concise Conventional Commit-style subjects seen in history: `feat:`, `fix:`, `refactor:`, or
`chore:`. Keep commits focused. PRs should explain behavior and risk, link the issue when present,
list validation commands, and include screenshots or recordings for visible Compose UI changes.
Never commit SDK paths, local properties, signing keys, or device-specific data.
