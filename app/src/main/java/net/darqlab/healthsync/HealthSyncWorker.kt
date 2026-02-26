package net.darqlab.healthsync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.*
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.UnknownHostException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.time.Instant
import java.util.concurrent.TimeUnit

class HealthSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val manager = HealthConnectManager(context)
    private val httpClient = OkHttpClient()

    private val apiUrl   = "https://healthsync.darqlab.net/device/sync"
    private val apiToken = "c6f76ad93c0d14de9d5edfff6d0f98e4ba2046734c69d04c90ca78eebe356ef3"

    private val prefs by lazy {
        applicationContext.getSharedPreferences("health_sync", Context.MODE_PRIVATE)
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = createForegroundInfo()

    private fun createForegroundInfo(): ForegroundInfo {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Health Sync", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("HealthSync")
            .setContentText("Syncing health data…")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    override suspend fun doWork(): Result {
        val ctx = applicationContext

        if (!manager.hasPermissions()) {
            SyncLog.add(ctx, "FAILED: Health Connect permissions not granted", isError = true)
            reschedule()
            return Result.failure()
        }

        val forceSync = inputData.getBoolean(KEY_FORCE_SYNC, false)

        // ── Establish baseline token on first run ────────────────────────────
        val savedToken = prefs.getString(PREF_CHANGES_TOKEN, null)
        if (savedToken == null) {
            val token = manager.getChangesToken()
            prefs.edit().putString(PREF_CHANGES_TOKEN, token).apply()
            SyncLog.add(ctx, "Monitoring started — will sync when new data arrives")
            reschedule()
            return Result.success()
        }

        // ── Check for new data ───────────────────────────────────────────────
        val check = manager.checkForNewData(savedToken)

        if (!forceSync && !check.hasNew) {
            prefs.edit().putString(PREF_CHANGES_TOKEN, check.nextToken).apply()
            Log.d("HealthSync", "No new data, skipping sync")
            reschedule()
            return Result.success()
        }

        // ── New data detected (or forced) → sync ─────────────────────────────
        setForeground(createForegroundInfo())
        SyncLog.add(ctx, if (forceSync) "Sync started (forced)" else "New data detected, syncing…")

        return try {
            val syncFrom = manager.todayMidnight()
            val syncTo   = Instant.now()

            val steps            = manager.readSteps(syncFrom, syncTo)
            val heartRate        = manager.readLatestHeartRate(syncFrom, syncTo)
            val sleep            = manager.readSleepHours(syncFrom, syncTo)
            val distance         = manager.readDistance(syncFrom, syncTo)
            val calories         = manager.readCalories(syncFrom, syncTo)
            val elevationGained  = manager.readElevationGained(syncFrom, syncTo)
            val exerciseMinutes  = manager.readExerciseMinutes(syncFrom, syncTo)
            val oxygenSaturation = manager.readLatestOxygenSaturation(syncFrom, syncTo)
            val speed            = manager.readLatestSpeed(syncFrom, syncTo)

            SyncLog.add(ctx, "steps: $steps, hr: $heartRate bpm, spo2: ${"%.1f".format(oxygenSaturation)}%, " +
                    "sleep: ${"%.1f".format(sleep)}h, dist: ${"%.0f".format(distance)}m, " +
                    "cal: ${"%.0f".format(calories)}, elev: ${"%.1f".format(elevationGained)}m, " +
                    "exercise: ${exerciseMinutes}min, speed: ${"%.2f".format(speed)}m/s")

            val payload = JSONObject().apply {
                put("userId",           "114142556320445743052")
                put("deviceId",         Settings.Secure.getString(applicationContext.contentResolver, Settings.Secure.ANDROID_ID))
                put("syncFrom",         syncFrom.toString())
                put("syncTo",           syncTo.toString())
                put("steps",            steps)
                put("heartRate",        heartRate)
                put("sleep",            sleep)
                put("distance",         distance)
                put("calories",         calories)
                put("elevationGained",  elevationGained)
                put("exerciseMinutes",  exerciseMinutes)
                put("oxygenSaturation", oxygenSaturation)
                put("speed",            speed)
            }

            Log.d("HealthSync", "Sending: $payload")
            SyncLog.add(ctx, "Sending to $apiUrl")

            val responseCode = postToApi(payload.toString())

            // Advance the changes cursor only after a confirmed successful POST
            prefs.edit().putString(PREF_CHANGES_TOKEN, check.nextToken).apply()

            Log.d("HealthSync", "Sync successful")
            SyncLog.add(ctx, "Sync OK - server responded $responseCode")
            reschedule()
            Result.success()

        } catch (e: UnknownHostException) {
            Log.e("HealthSync", "Sync failed: ${e.message}")
            SyncLog.add(ctx, "FAILED: DNS error - cannot resolve host", isError = true)
            // Don't advance token — next check will see the same new data and retry
            reschedule()
            Result.success()
        } catch (e: ConnectException) {
            Log.e("HealthSync", "Sync failed: ${e.message}")
            SyncLog.add(ctx, "FAILED: Connection refused - server may be down", isError = true)
            reschedule()
            Result.success()
        } catch (e: SocketTimeoutException) {
            Log.e("HealthSync", "Sync failed: ${e.message}")
            SyncLog.add(ctx, "FAILED: Connection timed out", isError = true)
            reschedule()
            Result.success()
        } catch (e: Exception) {
            Log.e("HealthSync", "Sync failed: ${e.message}")
            SyncLog.add(ctx, "FAILED: ${e.message}", isError = true)
            reschedule()
            Result.success()
        }
    }

    /** Schedules the next health-check 5 minutes from now. */
    private fun reschedule() {
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            WORK_CHECK,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<HealthSyncWorker>()
                .setInitialDelay(20, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
        )
    }

    private suspend fun postToApi(json: String): Int = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(apiUrl)
            .post(json.toRequestBody("application/json".toMediaType()))
            .header("X-Api-Token", apiToken)
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful)
                throw Exception("API error: ${response.code} ${response.message}")
            response.code
        }
    }

    companion object {
        private const val NOTIFICATION_ID    = 1001
        private const val CHANNEL_ID         = "health_sync"
        private const val PREF_CHANGES_TOKEN = "changes_token"
        private const val WORK_CHECK         = "health_check"
        private const val KEY_FORCE_SYNC     = "force_sync"

        /** Start the check-and-sync chain. Safe to call multiple times — KEEP policy. */
        fun schedule(context: Context) {
            // Cancel the old hourly periodic worker if still around
            WorkManager.getInstance(context).cancelUniqueWork("health_sync")

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_CHECK,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<HealthSyncWorker>()
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                    )
                    .build()
            )
        }

        /** Force an immediate sync regardless of whether new data is present. */
        fun syncNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "health_sync_now",
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<HealthSyncWorker>()
                    .setInputData(workDataOf(KEY_FORCE_SYNC to true))
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                    )
                    .build()
            )
        }
    }
}
