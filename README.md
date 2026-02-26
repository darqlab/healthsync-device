# HealthSync

Android app that reads health data from [Android Health Connect](https://health.google/health-connect-android/) and syncs it to a remote REST API. Designed to run silently in the background and only sync when new data is detected.

## How It Works

1. On launch, the app requests Health Connect read permissions and schedules a background worker.
2. Every 20 minutes the worker checks the Health Connect **Changes API** for new records.
3. If new data exists, it reads today's totals and POSTs them to the API. If not, it skips silently.
4. The changes cursor only advances after a confirmed `200` response — failed requests retry on the next check.
5. After reboot, `BootReceiver` automatically reschedules the worker.

```
Health Connect → HealthConnectManager → HealthSyncWorker → OkHttp POST → REST API
```

## Metrics Synced

| Metric | Method | Time Range |
|---|---|---|
| Steps | Sum | Today (midnight → now) |
| Heart rate | Latest single BPM sample | Today |
| Sleep | Latest session — total hours + stage breakdown | Last session (looks back 12 h before midnight to catch overnight sessions) |
| Distance | Sum | Today |
| Calories | Sum (TotalCaloriesBurned; fallback to ActiveCaloriesBurned) | Today |
| Elevation gained | Sum | Today |
| Exercise minutes | Sum of session durations | Today |
| Oxygen saturation | Latest single SpO2 % | Today |
| Speed | Latest single m/s sample | Today |

## API Payload

```json
{
  "userId":           "...",
  "deviceId":         "<ANDROID_ID>",
  "syncFrom":         "2026-02-26T00:00:00Z",
  "syncTo":           "2026-02-26T09:41:00Z",
  "steps":            6234,
  "heartRate":        78,
  "sleep":            7.2,
  "distance":         4821.0,
  "calories":         312.0,
  "elevationGained":  12.5,
  "exerciseMinutes":  45,
  "oxygenSaturation": 97.0,
  "speed":            1.38,
  "sleepStages": {
    "lightMinutes": 325,
    "deepMinutes":  92,
    "remMinutes":   0,
    "awakeMinutes": 15,
    "stages": [
      { "stage": "light", "from": "2026-02-25T14:31:00Z", "to": "2026-02-25T15:02:00Z", "minutes": 31 },
      { "stage": "awake", "from": "2026-02-25T15:02:00Z", "to": "2026-02-25T15:15:00Z", "minutes": 13 },
      { "stage": "deep",  "from": "2026-02-25T15:22:00Z", "to": "2026-02-25T15:29:00Z", "minutes": 7  }
    ]
  }
}
```

> `sleepStages` is omitted when the session has no stage detail. `sleep` (total hours) is always present and backward compatible.

## Setup

### Requirements

- Android 8.0+ (API 26)
- [Health Connect](https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata) installed
- A fitness app that writes to Health Connect (e.g. Mi Fitness, Google Fit, Samsung Health)

### First Run

1. Install the APK.
2. Open the app — it will prompt for Health Connect permissions. Grant all.
3. Open Health Connect settings and also grant **Background read** for HealthSync.
4. Tap **Allow Background Execution** if the button appears (exempts the app from battery optimization).
5. The app schedules itself and closes. No further interaction needed.

### Manual Sync

Tap **Sync Now** in the app to force an immediate sync regardless of whether new data is detected.

## Tech Stack

| Component | Library |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose + Material 3 |
| Health data | Health Connect (`connect-client:1.1.0-alpha10`) |
| Background work | WorkManager (`work-runtime-ktx:2.11.1`) |
| Networking | OkHttp 4.12.0 |
| Min SDK | 26 (Android 8.0) |
| Target SDK | 36 |

## Project Structure

```
app/src/main/java/net/darqlab/healthsync/
├── MainActivity.kt          # Single activity — permission flow + status UI
├── HealthConnectManager.kt  # All Health Connect reads + Changes API
├── HealthSyncWorker.kt      # Background worker — check, sync, reschedule
├── BootReceiver.kt          # Reschedules worker after reboot
└── SyncLog.kt               # In-app log (SharedPreferences-backed)
```

## Build

```bash
./gradlew assembleDebug
./gradlew installDebug
```
