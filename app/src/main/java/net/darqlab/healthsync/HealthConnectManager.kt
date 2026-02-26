package net.darqlab.healthsync

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit


data class NewDataResult(val hasNew: Boolean, val nextToken: String)

class HealthConnectManager(private val context: Context) {

    private val client = HealthConnectClient.getOrCreate(context)

    companion object {
        fun isAvailable(context: Context): Boolean =
            HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

        val PERMISSIONS = setOf(
            HealthPermission.getReadPermission(StepsRecord::class),
            HealthPermission.getReadPermission(HeartRateRecord::class),
            HealthPermission.getReadPermission(SleepSessionRecord::class),
            HealthPermission.getReadPermission(DistanceRecord::class),
            HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
            HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
            HealthPermission.getReadPermission(ElevationGainedRecord::class),
            HealthPermission.getReadPermission(ExerciseSessionRecord::class),
            HealthPermission.getReadPermission(OxygenSaturationRecord::class),
            HealthPermission.getReadPermission(SpeedRecord::class),
            HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND
        )

        private val ALL_RECORD_TYPES = setOf(
            StepsRecord::class,
            HeartRateRecord::class,
            SleepSessionRecord::class,
            DistanceRecord::class,
            TotalCaloriesBurnedRecord::class,
            ActiveCaloriesBurnedRecord::class,
            ElevationGainedRecord::class,
            ExerciseSessionRecord::class,
            OxygenSaturationRecord::class,
            SpeedRecord::class,
        )
    }

    suspend fun hasPermissions(): Boolean {
        val granted = client.permissionController.getGrantedPermissions()
        return granted.containsAll(PERMISSIONS)
    }

    /** Midnight of today in the device's local timezone. */
    fun todayMidnight(): Instant =
        LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant()

    // ── Changes API ──────────────────────────────────────────────────────────

    /** Returns a fresh changes token covering all tracked record types. */
    suspend fun getChangesToken(): String =
        client.getChangesToken(ChangesTokenRequest(ALL_RECORD_TYPES))

    /**
     * Checks whether any new records were written since [token] was issued.
     * Returns the result and the next token to use on the following check.
     * If the token expired, assumes new data and returns a fresh token.
     */
    suspend fun checkForNewData(token: String): NewDataResult {
        val response = client.getChanges(token)
        return if (response.changesTokenExpired) {
            NewDataResult(hasNew = true, nextToken = getChangesToken())
        } else {
            val hasNew = response.changes.any { it is UpsertionChange }
            NewDataResult(hasNew = hasNew, nextToken = response.nextChangesToken)
        }
    }

    // ── Today's totals ───────────────────────────────────────────────────────

    suspend fun readSteps(from: Instant, to: Instant): Long {
        val request = ReadRecordsRequest(
            recordType = StepsRecord::class,
            timeRangeFilter = TimeRangeFilter.between(from, to)
        )
        return client.readRecords(request).records.sumOf { it.count }
    }

    /** Duration in hours of the most recent sleep session in the last 24 h. */
    suspend fun readSleepHours(from: Instant, to: Instant): Double {
        // Look back 12 h before midnight to catch overnight sessions that started
        // yesterday evening (e.g. 22:31 → 05:43).
        val queryFrom = from.minus(12, ChronoUnit.HOURS)
        val request = ReadRecordsRequest(
            recordType = SleepSessionRecord::class,
            timeRangeFilter = TimeRangeFilter.between(queryFrom, to),
            ascendingOrder = false,
            pageSize = 1
        )
        val session = client.readRecords(request).records.firstOrNull() ?: return 0.0
        return Duration.between(session.startTime, session.endTime).toMinutes() / 60.0
    }

    suspend fun readDistance(from: Instant, to: Instant): Double {
        val request = ReadRecordsRequest(
            recordType = DistanceRecord::class,
            timeRangeFilter = TimeRangeFilter.between(from, to)
        )
        return client.readRecords(request).records.sumOf { it.distance.inMeters }
    }

    suspend fun readCalories(from: Instant, to: Instant): Double {
        val totalRequest = ReadRecordsRequest(
            recordType = TotalCaloriesBurnedRecord::class,
            timeRangeFilter = TimeRangeFilter.between(from, to)
        )
        val activeRequest = ReadRecordsRequest(
            recordType = ActiveCaloriesBurnedRecord::class,
            timeRangeFilter = TimeRangeFilter.between(from, to)
        )
        val total  = client.readRecords(totalRequest).records.sumOf { it.energy.inKilocalories }
        val active = client.readRecords(activeRequest).records.sumOf { it.energy.inKilocalories }
        return if (total > 0.0) total else active
    }

    suspend fun readElevationGained(from: Instant, to: Instant): Double {
        val request = ReadRecordsRequest(
            recordType = ElevationGainedRecord::class,
            timeRangeFilter = TimeRangeFilter.between(from, to)
        )
        return client.readRecords(request).records.sumOf { it.elevation.inMeters }
    }

    suspend fun readExerciseMinutes(from: Instant, to: Instant): Long {
        val request = ReadRecordsRequest(
            recordType = ExerciseSessionRecord::class,
            timeRangeFilter = TimeRangeFilter.between(from, to)
        )
        return client.readRecords(request).records.sumOf {
            Duration.between(it.startTime, it.endTime).toMinutes()
        }
    }

    // ── Latest single reading ────────────────────────────────────────────────

    /** BPM of the single most recent heart rate sample. */
    suspend fun readLatestHeartRate(from: Instant, to: Instant): Long {
        val request = ReadRecordsRequest(
            recordType = HeartRateRecord::class,
            timeRangeFilter = TimeRangeFilter.between(from, to),
            ascendingOrder = false,
            pageSize = 1
        )
        val samples = client.readRecords(request).records.firstOrNull()?.samples ?: return 0L
        return samples.maxByOrNull { it.time }?.beatsPerMinute ?: 0L
    }

    /** SpO2 % of the most recent oxygen saturation reading. */
    suspend fun readLatestOxygenSaturation(from: Instant, to: Instant): Double {
        val request = ReadRecordsRequest(
            recordType = OxygenSaturationRecord::class,
            timeRangeFilter = TimeRangeFilter.between(from, to),
            ascendingOrder = false,
            pageSize = 1
        )
        return client.readRecords(request).records.firstOrNull()?.percentage?.value ?: 0.0
    }

    /** Speed in m/s of the most recent speed sample. */
    suspend fun readLatestSpeed(from: Instant, to: Instant): Double {
        val request = ReadRecordsRequest(
            recordType = SpeedRecord::class,
            timeRangeFilter = TimeRangeFilter.between(from, to),
            ascendingOrder = false,
            pageSize = 1
        )
        val samples = client.readRecords(request).records.firstOrNull()?.samples ?: return 0.0
        return samples.maxByOrNull { it.time }?.speed?.inMetersPerSecond ?: 0.0
    }
}
