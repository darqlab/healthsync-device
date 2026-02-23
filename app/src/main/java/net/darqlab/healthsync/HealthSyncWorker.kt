package net.darqlab.healthsync

import android.content.Context
import android.util.Log
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

    // change this to your Spring Boot API URL
    private val apiUrl = "https://healthsync.darqlab.net/health/hook"
    private val apiToken = "YOUR_SECRET_TOKEN"

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        SyncLog.add(ctx, "Sync started")

        return try {
            if (!manager.hasPermissions()) {
                Log.w("HealthSync", "Permissions not granted")
                SyncLog.add(ctx, "FAILED: Health Connect permissions not granted", isError = true)
                return Result.failure()
            }

            val steps = manager.readSteps(1)
            val heartRate = manager.readHeartRateAvg(1)
            val sleep = manager.readSleepHours(1)
            val distance = manager.readDistance(1)
            val calories = manager.readCalories(1)

            SyncLog.add(ctx, "Data read - steps: $steps, hr: ${"%.1f".format(heartRate)}, sleep: ${"%.1f".format(sleep)}h, dist: ${"%.0f".format(distance)}m, cal: ${"%.0f".format(calories)}")

            val payload = JSONObject().apply {
                put("steps", steps)
                put("heartRate", heartRate)
                put("sleep", sleep)
                put("distance", distance)
                put("calories", calories)
                put("timestamp", Instant.now().toString())
                put("deviceId", getDeviceId())
            }

            Log.d("HealthSync", "Sending: $payload")
            SyncLog.add(ctx, "Sending to $apiUrl")

            val responseCode = postToApi(payload.toString())
            Log.d("HealthSync", "Sync successful")
            SyncLog.add(ctx, "Sync OK - server responded $responseCode")
            Result.success()

        } catch (e: UnknownHostException) {
            Log.e("HealthSync", "Sync failed: ${e.message}")
            SyncLog.add(ctx, "FAILED: DNS error - cannot resolve host. Check URL or internet connection", isError = true)
            Result.retry()
        } catch (e: ConnectException) {
            Log.e("HealthSync", "Sync failed: ${e.message}")
            SyncLog.add(ctx, "FAILED: Connection refused - server may be down", isError = true)
            Result.retry()
        } catch (e: SocketTimeoutException) {
            Log.e("HealthSync", "Sync failed: ${e.message}")
            SyncLog.add(ctx, "FAILED: Connection timed out", isError = true)
            Result.retry()
        } catch (e: Exception) {
            Log.e("HealthSync", "Sync failed: ${e.message}")
            SyncLog.add(ctx, "FAILED: ${e.message}", isError = true)
            Result.retry()
        }
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

    private fun getDeviceId(): String =
        android.provider.Settings.Secure.getString(
            applicationContext.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        )

    companion object {
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<HealthSyncWorker>(1, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "health_sync",
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun syncNow(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<HealthSyncWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
