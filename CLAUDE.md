# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Test Commands

```bash
# Build the project
./gradlew assembleDebug

# Run unit tests (JVM)
./gradlew test

# Run a single unit test class
./gradlew testDebugUnitTest --tests "net.darqlab.healthsync.ExampleUnitTest"

# Run instrumented tests (requires device/emulator)
./gradlew connectedAndroidTest

# Clean build
./gradlew clean assembleDebug

# Check for dependency updates / sync
./gradlew dependencies
```

## Architecture

Single-module Android app (`app/`) that reads health data from Android Health Connect and periodically syncs it to a remote REST API.

### Key Components

- **MainActivity** (`net.darqlab.healthsync.MainActivity`) — Single activity with Jetpack Compose UI. Handles Health Connect permission requests and schedules the background sync worker.
- **HealthConnectManager** (`net.darqlab.healthsync.HealthConnectManager`) — Wrapper around the Health Connect client. Reads steps, heart rate, sleep, distance, and calories for a configurable number of past days.
- **HealthSyncWorker** (`net.darqlab.healthsync.HealthSyncWorker`) — `CoroutineWorker` scheduled via WorkManager on a 1-hour periodic interval. Collects health data via `HealthConnectManager`, builds a JSON payload, and POSTs it to `https://healthsync.darqlab.net/health/hook` using OkHttp.

### Data Flow

```
Health Connect API → HealthConnectManager → HealthSyncWorker → OkHttp POST → Remote API
```

There is no local database or repository layer. Data flows directly from the platform Health Connect store to the remote endpoint.

### No DI Framework

Dependencies are instantiated directly — `HealthConnectManager(context)` and `OkHttpClient()` are created inline in the activity and worker.

## Tech Stack

- **Language:** Kotlin, Java 11 target
- **UI:** Jetpack Compose with Material Design 3
- **Min SDK:** 26, **Target/Compile SDK:** 36
- **Build:** Gradle 9.2.1, AGP 9.0.1, Gradle version catalog (`gradle/libs.versions.toml`)
- **Health:** `androidx.health.connect:connect-client:1.1.0-alpha10`
- **Background:** AndroidX WorkManager (`work-runtime-ktx:2.9.0`)
- **Networking:** OkHttp 4.12.0
- **Coroutines:** kotlinx-coroutines-android 1.7.3
- **Testing:** JUnit 4, AndroidX Test, Espresso, Compose UI Test

## Project Layout

All application code lives under `app/src/main/java/net/darqlab/healthsync/`. UI theming is in the `ui/theme/` subdirectory. The app uses a single-activity, no-navigation architecture — the Compose UI is a simple static screen since the app's purpose is background data sync.
