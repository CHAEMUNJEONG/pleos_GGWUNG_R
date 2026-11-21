package com.kkwongal.drive.integration

import com.kkwongal.drive.core.GeoPoint
import com.kkwongal.drive.core.StampBook
import com.kkwongal.drive.vehicle.ArrivedPlace
import com.kkwongal.drive.vehicle.FakeVehicleSource
import org.junit.Assert.*
import org.junit.Test

class StampNameResolverTest {
    private val point = GeoPoint(37.5665, 126.978)
    private val now = 1_000_000L
    private fun resolve(arrival: ArrivedPlace?, address: String? = "현재 주소", addressAt: Long? = now,
        parkedAt: GeoPoint = point, start: Long = now - 1000) =
        StampNameResolver.resolve(arrival, address, addressAt, parkedAt, start, now)

    @Test fun recentNearbyDestinationNamesTheStamp() {
        val name = resolve(ArrivedPlace(" 서울 ", now, point))
        assertEquals("서울", StampBook().tryAdd(point, now, 90, name)!!.name)
    }
    @Test fun noDestinationUsesFreshAddressAndMissingDataUsesDefault() {
        assertEquals("현재 주소", resolve(null))
        val name = resolve(null, address = "  ")
        assertNull(name)
        assertEquals("새로운 장소 1", StampBook().tryAdd(point, now, 90, name)!!.name)
    }
    @Test fun previousTripFutureAndExpiredArrivalsUseAddress() {
        for (time in listOf(now - 1001, now + 1, now - StampNameResolver.MAX_AGE_MS - 1)) {
            assertEquals("현재 주소", resolve(ArrivedPlace("오래된 목적지", time, point)))
        }
    }
    @Test fun farMissingAndInvalidCoordinatesCannotNameTheStamp() {
        for (location in listOf(GeoPoint(35.1796, 129.0756), GeoPoint(Double.NaN, 127.0), GeoPoint(91.0, 127.0), null)) {
            assertEquals("현재 주소", resolve(ArrivedPlace("다른 장소", now, location)))
        }
        assertNull(resolve(null, parkedAt = GeoPoint(Double.NaN, 127.0)))
    }
    @Test fun previousTripFutureAndExpiredAddressesUseDefault() {
        for (time in listOf(now - 1001, now + 1, now - StampNameResolver.MAX_AGE_MS - 1, null)) {
            assertNull(resolve(null, addressAt = time))
        }
    }
    @Test fun blankDestinationUsesAddressAndLongNamesAreBounded() {
        assertEquals("현재 주소", resolve(ArrivedPlace(" ", now, point)))
        assertEquals(100, resolve(ArrivedPlace("가".repeat(110), now, point))!!.length)
    }
    @Test fun exactAgeBoundaryIsAcceptedAndOlderEventRejected() {
        val time = now - StampNameResolver.MAX_AGE_MS
        assertEquals("서울", resolve(ArrivedPlace("서울", time, point), start = time))
        assertEquals("현재 주소", resolve(ArrivedPlace("서울", time - 1, point), start = time - 1))
    }
    @Test fun fakeArrivalPrecedesParkAndClearsAtDeparture() {
        val source = FakeVehicleSource()
        source.setLocation(point, "서울", "서울 주소")
        source.setParked(false)
        source.setParked(true)
        assertEquals(point, source.arrivedPlace.value!!.point)
        assertEquals("서울", source.arrivedPlace.value!!.name)
        source.setParked(false)
        assertNull(source.arrivedPlace.value)
    }
    @Test fun duplicatePlaceStillDoesNotCreateAnotherStamp() {
        val book = StampBook()
        book.tryAdd(point, now, 90, "서울")
        assertNull(book.tryAdd(point, now + 1, 90, "다른 이름"))
    }
}
