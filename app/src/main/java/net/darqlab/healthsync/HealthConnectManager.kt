package net.darqlab.healthsync

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit


class HealthConnectManager(private val context: Context)  {

    private val client = HealthConnectClient.getOrCreate(context)

    companion object {
        fun isAvailable(context: Context): Boolean =
            HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

        val PERMISSIONS = setOf(
            HealthPermission.getReadPermission(StepsRecord::class),
            HealthPermission.getReadPermission(HeartRateRecord::class),
            HealthPermission.getReadPermission(SleepSessionRecord::class),
            HealthPermission.getReadPermission(DistanceRecord::class),
            HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class)
        )
    }

    suspend fun hasPermissions(): Boolean {
        val granted = client.permissionController.getGrantedPermissions()
        return granted.containsAll(PERMISSIONS)
    }

    suspend fun readSteps(days: Long = 1): Long {
        val request = ReadRecordsRequest(
            recordType = StepsRecord::class,
            timeRangeFilter = timeRange(days)
        )
        return client.readRecords(request).records.sumOf { it.count }
    }

    suspend fun readHeartRateAvg(days: Long = 1): Double {
        val request = ReadRecordsRequest(
            recordType = HeartRateRecord::class,
            timeRangeFilter = timeRange(days)
        )
        val samples = client.readRecords(request).records.flatMap { it.samples }
        return if (samples.isEmpty()) 0.0
        else samples.map { it.beatsPerMinute }.average()
    }

    suspend fun readSleepHours(days: Long = 1): Double {
        val request = ReadRecordsRequest(
            recordType = SleepSessionRecord::class,
            timeRangeFilter = timeRange(days)
        )
        return client.readRecords(request).records.sumOf {
            Duration.between(it.startTime, it.endTime).toMinutes()
        } / 60.0
    }

    suspend fun readDistance(days: Long = 1): Double {
        val request = ReadRecordsRequest(
            recordType = DistanceRecord::class,
            timeRangeFilter = timeRange(days)
        )
        return client.readRecords(request).records.sumOf {
            it.distance.inMeters
        }
    }

    suspend fun readCalories(days: Long = 1): Double {
        val request = ReadRecordsRequest(
            recordType = TotalCaloriesBurnedRecord::class,
            timeRangeFilter = timeRange(days)
        )
        return client.readRecords(request).records.sumOf {
            it.energy.inKilocalories
        }
    }

    private fun timeRange(days: Long) = TimeRangeFilter.between(
        Instant.now().minus(days, ChronoUnit.DAYS),
        Instant.now()
    )


}