package com.kkwongal.drive.integration

import com.google.gson.JsonParseException
import com.google.gson.stream.MalformedJsonException
import com.kkwongal.drive.core.LeagueStanding
import com.kkwongal.drive.core.TripResult
import com.kkwongal.drive.rust.RustBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query
import java.io.EOFException
import java.io.IOException

// Proposed team API contract, not an official Pleos/HMG endpoint.
interface LeagueApi {
    @GET("v1/league/weekly")
    suspend fun weekly(
        @Query("weekStartMs") weekStartMs: Long,
        @Query("simulated") simulated: Boolean,
    ): Response<WeeklyLeagueDto>

    @POST("v1/trips")
    suspend fun submit(@Body trip: TripUploadDto): Response<TripReceiptDto>
}

data class TripUploadDto(
    val tripId: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val distanceKm: Double,
    val harshAccelCount: Int,
    val harshBrakeCount: Int,
    val maxSpeedKmh: Double,
    val ecoScore: Int,
    val score: Int,
    val harshSteerCount: Int,
    val simulated: Boolean,
    val scoringVersion: String = "prototype-v2",
) {
    companion object {
        /** Computes stable MD5 UUID v3 in Rust (kkwongal-core::league_client::TripUploadDto::from_trip). */
        fun from(trip: TripResult): TripUploadDto =
            RustBridge.parseTripUploadDto(RustBridge.nativeTripUploadFromTrip(RustBridge.toJson(trip)!!))
    }
}

data class TripReceiptDto(val tripId: String?, val accepted: Boolean?, val duplicate: Boolean?, val score: Int?)

sealed interface UploadReply {
    data class Success(val duplicate: Boolean) : UploadReply
    data class Failed(val reason: ApiFailure, val httpStatus: Int? = null) : UploadReply
}

data class LeagueEntryDto(val name: String?, val averageScore: Double?, val isMe: Boolean?)

data class WeeklyLeagueDto(
    val weekStartMs: Long?,
    val entries: List<LeagueEntryDto?>?,
    val myRank: Int?,
    val myAverage: Double?,
    val myLeagueScore: Double?,
    val myDistanceKm: Double?,
    val myTripCount: Int?,
    val rewardPoints: Int?,
    val earnedPoints: Int?,
    val simulated: Boolean?,
)

data class LeagueSnapshot(val standing: LeagueStanding, val earnedPoints: Int)

enum class ApiFailure { HTTP, NETWORK, INVALID_RESPONSE }

sealed interface LeagueReply {
    data class Success(val snapshot: LeagueSnapshot) : LeagueReply
    data class Failed(val reason: ApiFailure, val httpStatus: Int? = null) : LeagueReply
}

/**
 * Development API client.
 * - Default factory (`LeagueClient.create`) executes HTTP calls and response validation directly in Rust
 *   via `ureq` + `rustls` (`kkwongal-core::league_client::LeagueClient`).
 * - The `LeagueClient(api: LeagueApi)` constructor is retained for unit-test mock injection and still
 *   validates receipts/responses through Rust.
 */
class LeagueClient private constructor(
    private val api: LeagueApi?,
    private val rustBaseUrl: String?,
    private val rustToken: (() -> String?)?,
    private val rustAllowLocalHttp: Boolean,
) {
    constructor(api: LeagueApi) : this(api, null, null, false)

    suspend fun submit(trip: TripUploadDto): UploadReply {
        val baseUrl = rustBaseUrl
        if (baseUrl != null) {
            return withContext(Dispatchers.IO) {
                val json = RustBridge.nativeLeagueClientSubmit(
                    baseUrl,
                    rustToken?.invoke(),
                    rustAllowLocalHttp,
                    RustBridge.toJson(trip)!!,
                )
                RustBridge.parseUploadReply(json)
            }
        }
        val mockApi = requireNotNull(api)
        return try {
            val response = mockApi.submit(trip)
            if (!response.isSuccessful) {
                UploadReply.Failed(ApiFailure.HTTP, response.code())
            } else {
                val receipt = response.body() ?: return UploadReply.Failed(ApiFailure.INVALID_RESPONSE)
                val validated = RustBridge.nativeValidateUploadReceipt(
                    RustBridge.toJson(trip)!!,
                    RustBridge.toJson(receipt)!!,
                )
                RustBridge.parseUploadReply(validated)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: JsonParseException) {
            UploadReply.Failed(ApiFailure.INVALID_RESPONSE)
        } catch (_: MalformedJsonException) {
            UploadReply.Failed(ApiFailure.INVALID_RESPONSE)
        } catch (_: EOFException) {
            UploadReply.Failed(ApiFailure.INVALID_RESPONSE)
        } catch (_: IllegalArgumentException) {
            UploadReply.Failed(ApiFailure.INVALID_RESPONSE)
        } catch (_: IOException) {
            UploadReply.Failed(ApiFailure.NETWORK)
        }
    }

    suspend fun weekly(weekStartMs: Long, simulated: Boolean = false): LeagueReply {
        val baseUrl = rustBaseUrl
        if (baseUrl != null) {
            return withContext(Dispatchers.IO) {
                val json = RustBridge.nativeLeagueClientWeekly(
                    baseUrl,
                    rustToken?.invoke(),
                    rustAllowLocalHttp,
                    weekStartMs,
                    simulated,
                )
                RustBridge.parseLeagueReply(json)
            }
        }
        val mockApi = requireNotNull(api)
        return try {
            val response = mockApi.weekly(weekStartMs, simulated)
            if (!response.isSuccessful) {
                LeagueReply.Failed(ApiFailure.HTTP, response.code())
            } else {
                val body = response.body() ?: return LeagueReply.Failed(ApiFailure.INVALID_RESPONSE)
                val validated = RustBridge.nativeValidateWeeklyResponse(
                    RustBridge.toJson(body)!!,
                    weekStartMs,
                    simulated,
                )
                RustBridge.parseLeagueReply(validated)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: JsonParseException) {
            LeagueReply.Failed(ApiFailure.INVALID_RESPONSE)
        } catch (_: MalformedJsonException) {
            LeagueReply.Failed(ApiFailure.INVALID_RESPONSE)
        } catch (_: EOFException) {
            LeagueReply.Failed(ApiFailure.INVALID_RESPONSE)
        } catch (_: IllegalArgumentException) {
            LeagueReply.Failed(ApiFailure.INVALID_RESPONSE)
        } catch (_: IOException) {
            LeagueReply.Failed(ApiFailure.NETWORK)
        }
    }

    companion object {
        /** Validates URL in Rust and creates a Rust-backed HTTP LeagueClient. */
        fun create(
            baseUrl: String,
            token: () -> String? = { null },
            allowLocalHttp: Boolean = false,
        ): LeagueClient {
            val err = RustBridge.nativeValidateLeagueBaseUrl(baseUrl, allowLocalHttp)
            require(err == null) { err!! }
            return LeagueClient(
                api = null,
                rustBaseUrl = baseUrl,
                rustToken = token,
                rustAllowLocalHttp = allowLocalHttp,
            )
        }
    }
}
