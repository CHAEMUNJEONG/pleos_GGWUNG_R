package com.kkwongal.drive.rust

import com.kkwongal.drive.core.EcoBreakdown
import com.kkwongal.drive.core.EnergySnapshot
import com.kkwongal.drive.core.GeoPoint
import com.kkwongal.drive.core.HarshType
import com.kkwongal.drive.core.KoreaOutline
import com.kkwongal.drive.core.LiveEvent
import com.kkwongal.drive.core.OngoingTrip
import com.kkwongal.drive.core.RoadKm
import com.kkwongal.drive.core.Stamp
import com.kkwongal.drive.core.TripRecorder
import com.kkwongal.drive.core.TripResult
import com.kkwongal.drive.data.SavedData
import com.kkwongal.drive.data.Storage
import com.kkwongal.drive.vehicle.DriveState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class RustJniIntegrationTest {

    @Test
    fun rustHealthCheckAndKoreaOutlineLoadFromNativeLibrary() {
        assertEquals("kkwongal-rust-0.1.0", RustBridge.nativeHealthCheck())
        assertEquals(44, KoreaOutline.POLYGONS.size)
        assertEquals(131.861f, KoreaOutline.DOKDO[0], 0.001f)
        assertEquals(37.243f, KoreaOutline.DOKDO[1], 0.001f)
    }

    @Test
    fun rustStorageRoundTripPreservesAllFields() {
        val dir = Files.createTempDirectory("rust-storage-test-").toFile()
        try {
            val storage = Storage(dir)
            val trip = TripResult(
                startTimeMs = 1_000L,
                endTimeMs = 601_000L,
                distanceKm = 12.5,
                harshAccelCount = 1,
                harshBrakeCount = 2,
                maxSpeedKmh = 98.0,
                score = 84,
                safetyScore = 86,
                ecoScore = 83,
                harshSteerCount = 1,
                efficiency = 6.1,
                efficiencyKind = "EV",
                efficiencyImprovement = 10.0,
                ecoDetail = EcoBreakdown(85, 90, 80, 75, 100, 10, 50, 0.0),
                roadKm = RoadKm(2.5, 4.0, 6.0),
                simulated = true,
                events = listOf(LiveEvent(HarshType.BRAKE, 5, 95, 50_000L, 1.2)),
                hidden = true,
            )
            val stamp = Stamp(
                id = 1,
                name = "강릉 안목해변",
                latitude = 37.7719,
                longitude = 128.9473,
                timeMs = 601_000L,
                score = 84,
                memo = "바다",
                tags = listOf("경치가 좋아요"),
                perfect = false,
            )
            val saved = SavedData(listOf(trip), listOf(stamp))
            storage.save(saved)
            assertEquals(saved, storage.load())

            val ongoing = OngoingTrip(
                startWallMs = 1_000L,
                savedAtMs = 31_000L,
                snapshot = TripRecorder().snapshot(),
                startEnergy = EnergySnapshot(evBatteryWh = 72_000.0, odometerKm = 12_000.0),
                events = trip.events,
            )
            storage.saveOngoing(ongoing)
            assertEquals(ongoing, storage.loadOngoing())
            storage.clearOngoing()
            assertNull(storage.loadOngoing())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun rustDemoScriptAndEngineLifecycleRunThroughJni() {
        val script = RustBridge.parseDemoScript(RustBridge.nativeFakeDemoScriptJson())
        assertTrue(script.isNotEmpty())
        val holds = script.filter { it.kind == "hold" }
        assertTrue(holds.all { (it.virtualSeconds ?: 0) > 0 })

        val dir = Files.createTempDirectory("rust-engine-test-").toFile()
        try {
            val now = 1_794_970_800_000L
            val handle = RustBridge.nativeEngineNew(dir.absolutePath, false, true, now)
            try {
                RustBridge.nativeEngineSetSimulation(handle, true, null, now)
                val departStep = RustBridge.parseEngineStep(
                    RustBridge.nativeEngineOnDriveState(
                        handle,
                        DriveState.DRIVING.name,
                        now,
                        true,
                        37.7719,
                        128.9473,
                    ),
                )
                assertTrue(departStep.snapshot.tripActive)
                assertTrue(departStep.needsStartEnergy)

                RustBridge.nativeEngineSetStartEnergy(
                    handle,
                    RustBridge.toJson(EnergySnapshot(evBatteryWh = 72_000.0, odometerKm = 12_000.0)),
                    now,
                )

                var mono = 0L
                for (i in 0..300) {
                    RustBridge.nativeEngineOnSpeed(handle, mono, 60.0, now + mono)
                    mono += 200L
                }

                val parkStep = RustBridge.parseEngineStep(
                    RustBridge.nativeEngineOnDriveState(
                        handle,
                        DriveState.PARKED.name,
                        now + mono,
                        true,
                        37.7719,
                        128.9473,
                    ),
                )
                assertTrue(parkStep.needsFinishEnergy)

                val finishStep = RustBridge.parseEngineStep(
                    RustBridge.nativeEngineCompleteFinish(
                        handle = handle,
                        endEnergyJson = RustBridge.toJson(
                            EnergySnapshot(evBatteryWh = 71_800.0, odometerKm = 12_001.0),
                        ),
                        hasArrival = true,
                        arrivalName = "강릉 안목해변",
                        arrivalTimeMs = now + mono,
                        hasArrivalPoint = true,
                        arrivalLat = 37.7719,
                        arrivalLon = 128.9473,
                        address = "강원 강릉시",
                        hasAddrTime = true,
                        addrTime = now + mono,
                        nowMs = now + mono,
                    ),
                )
                assertFalse(finishStep.snapshot.tripActive)
                assertNotNull(finishStep.speak)
                assertEquals(1, finishStep.snapshot.trips.size)
                assertEquals(1, finishStep.snapshot.stamps.size)
                assertEquals("강릉 안목해변", finishStep.snapshot.stamps.single().name)

                val recorded = finishStep.snapshot.trips.single()
                val opened = RustBridge.parseEngineSnapshot(
                    RustBridge.nativeEngineOpenTrip(handle, recorded.startTimeMs, recorded.endTimeMs),
                )
                assertNotNull(opened.pastReport)
                assertEquals("강릉 안목해변", opened.pastReport!!.placeName)

                val deleted = RustBridge.parseEngineSnapshot(
                    RustBridge.nativeEngineDeleteTrip(handle, recorded.startTimeMs, recorded.endTimeMs, now + mono),
                )
                assertTrue(deleted.trips.single().hidden)
            } finally {
                RustBridge.nativeEngineFree(handle)
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
