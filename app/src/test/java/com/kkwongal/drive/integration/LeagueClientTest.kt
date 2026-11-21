package com.kkwongal.drive.integration

import com.kkwongal.drive.core.TripResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response
import java.io.IOException
import java.net.InetAddress

class LeagueClientTest {
    private val week = 1_794_841_200_000L
    private val json get() = """{
      "weekStartMs":$week,"simulated":false,
      "entries":[{"name":"나","averageScore":81.2,"isMe":true}],
      "myRank":1,"myAverage":91.0,"myLeagueScore":81.2,"myDistanceKm":2.5,
      "myTripCount":1,"rewardPoints":500,"earnedPoints":0
    }"""

    private fun withServer(body: String = json, status: Int = 200,
        check: suspend (LeagueClient, MockWebServer) -> Unit) {
        val server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        try {
            server.enqueue(MockResponse().setResponseCode(status).setBody(body))
            val client = LeagueClient.create("http://127.0.0.1:${server.port}/", { "test-token" }, allowLocalHttp = true)
            runBlocking { check(client, server) }
        } finally { server.shutdown() }
    }

    @Test fun routeModeAuthorizationAndBothScoreFieldsReachDomain() {
        withServer { client, server ->
            val standing = (client.weekly(week) as LeagueReply.Success).snapshot.standing
            assertEquals(91.0, standing.myAverage!!, 0.0)
            assertEquals(81.2, standing.myLeagueScore!!, 0.0)
            val request = server.takeRequest()
            assertEquals("/v1/league/weekly?weekStartMs=$week&simulated=false", request.path)
            assertEquals("Bearer test-token", request.getHeader("Authorization"))
        }
    }
    @Test fun mismatchedSimulationModeIsRejected() {
        withServer { client, _ ->
            assertEquals(LeagueReply.Failed(ApiFailure.INVALID_RESPONSE), client.weekly(week, true))
        }
    }
    @Test fun unauthorizedIsReportedWithoutDemoFallback() {
        withServer(status = 401) { client, _ ->
            assertEquals(LeagueReply.Failed(ApiFailure.HTTP, 401), client.weekly(week))
        }
    }
    @Test fun invalidJsonAndMissingFieldsAreRejected() {
        for (body in listOf("not json", "{}")) withServer(body) { client, _ ->
            assertEquals(LeagueReply.Failed(ApiFailure.INVALID_RESPONSE), client.weekly(week))
        }
    }
    @Test fun emptyBodyIsRejected() {
        withServer(body = "", status = 204) { client, _ ->
            assertEquals(LeagueReply.Failed(ApiFailure.INVALID_RESPONSE), client.weekly(week))
        }
    }
    @Test fun wrongWeekRankScoreAndMissingLeagueScoreAreRejected() {
        for (body in listOf(json.replace("\"weekStartMs\":$week", "\"weekStartMs\":0"),
            json.replace("\"myRank\":1", "\"myRank\":9"), json.replace("81.2", "101.0"),
            json.replace("\"myLeagueScore\":81.2", "\"myLeagueScore\":null"))) {
            withServer(body) { client, _ ->
                assertEquals(LeagueReply.Failed(ApiFailure.INVALID_RESPONSE), client.weekly(week))
            }
        }
    }
    @Test fun emptyLeagueHasNoArtificialUserOrScore() {
        withServer("""{"weekStartMs":$week,"simulated":false,"entries":[],"myRank":null,
            "myAverage":null,"myLeagueScore":null,"myDistanceKm":0,"myTripCount":0,
            "rewardPoints":0,"earnedPoints":0}""") { client, _ ->
            val standing = (client.weekly(week) as LeagueReply.Success).snapshot.standing
            assertTrue(standing.entries.isEmpty())
            assertNull(standing.myRank)
        }
    }
    @Test fun networkFailureIsReported() = runBlocking {
        val api = object : LeagueApi {
            override suspend fun submit(trip: TripUploadDto): Response<TripReceiptDto> = throw IOException()
            override suspend fun weekly(weekStartMs: Long, simulated: Boolean): Response<WeeklyLeagueDto> = throw IOException()
        }
        assertEquals(LeagueReply.Failed(ApiFailure.NETWORK), LeagueClient(api).weekly(week))
    }
    @Test(expected = CancellationException::class) fun cancellationIsNotSwallowed() = runBlocking {
        val api = object : LeagueApi {
            override suspend fun submit(trip: TripUploadDto): Response<TripReceiptDto> = throw CancellationException()
            override suspend fun weekly(weekStartMs: Long, simulated: Boolean): Response<WeeklyLeagueDto> = throw CancellationException()
        }
        LeagueClient(api).weekly(week)
        Unit
    }
    @Test fun factoryRejectsPublicHttpEmbeddedCredentialsAndAmbiguousPaths() {
        for (url in listOf("http://example.com/", "https://user:pass@example.com/", "https://example.com/api",
            "https://example.com/?x=1", "https://example.com/#fragment")) {
            try { LeagueClient.create(url, allowLocalHttp = true); fail(url) } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun loopbackHttpRequiresExplicitOptIn() {
        for (url in listOf("http://127.0.0.1:8765/", "http://10.0.2.2:8765/")) {
            try { LeagueClient.create(url); fail(url) } catch (_: IllegalArgumentException) { }
            LeagueClient.create(url, allowLocalHttp = true)
        }
    }
    @Test fun stableTripIdIncludesModeAndLatestSteeringCount() {
        val trip = TripResult(10, 20, 2.0, 0, 0, 80.0, 98, harshSteerCount = 1)
        val dto = TripUploadDto.from(trip)
        assertEquals(dto.tripId, TripUploadDto.from(trip.copy()).tripId)
        assertNotEquals(dto.tripId, TripUploadDto.from(trip.copy(simulated = true)).tripId)
        assertNotEquals(dto.tripId, TripUploadDto.from(trip.copy(endTimeMs = 21)).tripId)
        assertEquals(1, dto.harshSteerCount)
        assertEquals("prototype-v2", dto.scoringVersion)
    }
    @Test fun wrongUploadReceiptIsRejected() = runBlocking {
        val api = object : LeagueApi {
            override suspend fun weekly(weekStartMs: Long, simulated: Boolean): Response<WeeklyLeagueDto> = throw IOException()
            override suspend fun submit(trip: TripUploadDto): Response<TripReceiptDto> =
                Response.success(TripReceiptDto("another-trip", true, false, trip.score))
        }
        val trip = TripUploadDto.from(TripResult(10, 20, 2.0, 0, 0, 80.0, 100))
        assertEquals(UploadReply.Failed(ApiFailure.INVALID_RESPONSE), LeagueClient(api).submit(trip))
    }
}
