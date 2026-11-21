package com.kkwongal.drive.integration

import com.kkwongal.drive.core.LeagueEntry
import com.kkwongal.drive.core.LeagueStanding
import com.kkwongal.drive.core.TripResult
import com.google.gson.JsonParseException
import com.google.gson.stream.MalformedJsonException
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import retrofit2.http.POST
import retrofit2.http.Body
import java.io.IOException
import java.io.EOFException
import java.util.concurrent.TimeUnit
import java.util.UUID

// Proposed team API contract, not an official Pleos/HMG endpoint.
interface LeagueApi {
    @GET("v1/league/weekly")
    suspend fun weekly(@Query("weekStartMs") weekStartMs: Long,
        @Query("simulated") simulated: Boolean): Response<WeeklyLeagueDto>

    @POST("v1/trips")
    suspend fun submit(@Body trip: TripUploadDto): Response<TripReceiptDto>
}

data class TripUploadDto(
    val tripId: String, val startTimeMs: Long, val endTimeMs: Long, val distanceKm: Double,
    val harshAccelCount: Int, val harshBrakeCount: Int, val maxSpeedKmh: Double,
    val ecoScore: Int, val score: Int, val harshSteerCount: Int, val simulated: Boolean,
    val scoringVersion: String = "prototype-v2",
) {
    companion object {
        fun from(trip: TripResult): TripUploadDto {
            // Stable across app restarts/retries; the server scopes this ID to the authenticated user.
            val identity = "${trip.startTimeMs}:${trip.endTimeMs}:${trip.simulated}".toByteArray(Charsets.UTF_8)
            return TripUploadDto(UUID.nameUUIDFromBytes(identity).toString(), trip.startTimeMs, trip.endTimeMs,
                trip.distanceKm, trip.harshAccelCount, trip.harshBrakeCount, trip.maxSpeedKmh,
                trip.ecoScore, trip.score, trip.harshSteerCount, trip.simulated)
        }
    }
}
data class TripReceiptDto(val tripId: String?, val accepted: Boolean?, val duplicate: Boolean?, val score: Int?)
sealed interface UploadReply {
    data class Success(val duplicate: Boolean) : UploadReply
    data class Failed(val reason: ApiFailure, val httpStatus: Int? = null) : UploadReply
}

data class LeagueEntryDto(val name: String?, val averageScore: Double?, val isMe: Boolean?)
data class WeeklyLeagueDto(
    val weekStartMs: Long?, val entries: List<LeagueEntryDto?>?,
    val myRank: Int?, val myAverage: Double?, val myLeagueScore: Double?, val myDistanceKm: Double?,
    val myTripCount: Int?, val rewardPoints: Int?, val earnedPoints: Int?,
    val simulated: Boolean?,
)
data class LeagueSnapshot(val standing: LeagueStanding, val earnedPoints: Int)
enum class ApiFailure { HTTP, NETWORK, INVALID_RESPONSE }
sealed interface LeagueReply {
    data class Success(val snapshot: LeagueSnapshot) : LeagueReply
    data class Failed(val reason: ApiFailure, val httpStatus: Int? = null) : LeagueReply
}

/** Development API. Upload is explicit; there are no reward payment calls. */
class LeagueClient(private val api: LeagueApi) {
    suspend fun submit(trip: TripUploadDto): UploadReply = try {
        val response = api.submit(trip)
        if (!response.isSuccessful) UploadReply.Failed(ApiFailure.HTTP, response.code()) else {
            val receipt = requireNotNull(response.body())
            require(receipt.tripId == trip.tripId && receipt.accepted == true && receipt.score == trip.score)
            UploadReply.Success(requireNotNull(receipt.duplicate))
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: JsonParseException) {
        UploadReply.Failed(ApiFailure.INVALID_RESPONSE)
    } catch (e: MalformedJsonException) {
        UploadReply.Failed(ApiFailure.INVALID_RESPONSE)
    } catch (e: EOFException) {
        UploadReply.Failed(ApiFailure.INVALID_RESPONSE)
    } catch (e: IllegalArgumentException) {
        UploadReply.Failed(ApiFailure.INVALID_RESPONSE)
    } catch (e: IOException) {
        UploadReply.Failed(ApiFailure.NETWORK)
    }

    suspend fun weekly(weekStartMs: Long, simulated: Boolean = false): LeagueReply = try {
        val response = api.weekly(weekStartMs, simulated)
        if (!response.isSuccessful) LeagueReply.Failed(ApiFailure.HTTP, response.code())
        else LeagueReply.Success(requireNotNull(response.body()).checked(weekStartMs, simulated))
    } catch (e: CancellationException) {
        throw e
    } catch (e: JsonParseException) {
        LeagueReply.Failed(ApiFailure.INVALID_RESPONSE)
    } catch (e: MalformedJsonException) {
        LeagueReply.Failed(ApiFailure.INVALID_RESPONSE)
    } catch (e: EOFException) {
        LeagueReply.Failed(ApiFailure.INVALID_RESPONSE)
    } catch (e: IllegalArgumentException) {
        LeagueReply.Failed(ApiFailure.INVALID_RESPONSE)
    } catch (e: IOException) {
        LeagueReply.Failed(ApiFailure.NETWORK)
    }

    companion object {
        /** Tokens are supplied at request time, never hard-coded in BuildConfig or logs. */
        fun create(baseUrl: String, token: () -> String? = { null }, allowLocalHttp: Boolean = false): LeagueClient {
            val url = baseUrl.toHttpUrl()
            val localHttp = allowLocalHttp && url.scheme == "http" && url.host in setOf("localhost", "127.0.0.1", "10.0.2.2")
            require((url.isHttps || localHttp) && url.username.isEmpty() && url.password.isEmpty()) { "HTTPS URL required" }
            require(url.query == null && url.fragment == null && url.encodedPath.endsWith('/')) {
                "API base URL must end with / and contain no query/fragment"
            }
            val http = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
                .callTimeout(15, TimeUnit.SECONDS)
                .followRedirects(false).followSslRedirects(false)
                .addInterceptor { chain ->
                    val request = chain.request().newBuilder().header("Accept", "application/json")
                    token()?.takeIf { it.isNotBlank() }?.let { request.header("Authorization", "Bearer $it") }
                    chain.proceed(request.build())
                }.build()
            return LeagueClient(Retrofit.Builder().baseUrl(url).client(http)
                .addConverterFactory(GsonConverterFactory.create()).build().create(LeagueApi::class.java))
        }
    }
}

private fun WeeklyLeagueDto.checked(requestedWeek: Long, requestedSimulation: Boolean): LeagueSnapshot {
    require(weekStartMs == requestedWeek && simulated == requestedSimulation)
    val rows = requireNotNull(entries).map { raw ->
        val row = requireNotNull(raw)
        val name = requireNotNull(row.name).trim()
        val score = requireNotNull(row.averageScore)
        require(name.isNotEmpty() && name.length <= 100 && score.isFinite() && score in 0.0..100.0)
        LeagueEntry(name, score, requireNotNull(row.isMe))
    }
    require(rows.size <= 1000 && rows.zipWithNext().all { (a, b) -> a.averageScore >= b.averageScore })
    require(rows.count { it.isMe } <= 1)
    require(myRank == rows.indexOfFirst { it.isMe }.takeIf { it >= 0 }?.plus(1))
    val km = requireNotNull(myDistanceKm)
    val count = requireNotNull(myTripCount)
    val reward = requireNotNull(rewardPoints)
    val earned = requireNotNull(earnedPoints)
    require(km.isFinite() && km >= 0 && count >= 0 && reward >= 0 && earned >= 0)
    require(myAverage == null || (myAverage.isFinite() && myAverage in 0.0..100.0))
    require(myLeagueScore == null || (myLeagueScore.isFinite() && myLeagueScore in 0.0..100.0))
    if (myRank == null) {
        require(myAverage == null && myLeagueScore == null && count == 0 && km == 0.0 && reward == 0)
    } else {
        require(myLeagueScore == rows[myRank - 1].averageScore && myAverage != null && count > 0 && km >= count)
    }
    return LeagueSnapshot(LeagueStanding(requestedWeek, rows, myRank, myAverage, myLeagueScore, km, count, reward), earned)
}
