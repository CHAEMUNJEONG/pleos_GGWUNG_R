package com.kkwongal.drive.integration

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.kkwongal.drive.core.League
import com.kkwongal.drive.core.TripResult
import com.kkwongal.drive.core.LeagueConfig
import com.kkwongal.drive.core.ScoreCalculator
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.nio.file.Files
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** Cross-language contract test: actual app client → actual Python/SQLite backend. */
class LocalBackendContractTest {
    private var server: Process? = null
    private var temp: File? = null
    private lateinit var root: File
    private lateinit var python: String
    private lateinit var baseUrl: String

    @Before fun startBackend() {
        val executable = System.getenv("PLEOS_BACKEND_PYTHON")
        assumeTrue("Set PLEOS_BACKEND_PYTHON to run local backend contract tests", !executable.isNullOrBlank())
        python = executable!!
        root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "backend/server.py").isFile }
        temp = Files.createTempDirectory("pleos-contract-").toFile()
        val ready = File(temp, "ready.json")
        server = ProcessBuilder(python, File(root, "backend/server.py").path,
            "--db", File(temp, "league.sqlite3").path, "--port", "0", "--ready-file", ready.path)
            .redirectErrorStream(true).redirectOutput(File(temp, "server.log")).start()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (!ready.isFile && System.nanoTime() < deadline && server!!.isAlive) Thread.sleep(50)
        check(ready.isFile) { "Backend did not start" }
        baseUrl = Gson().fromJson(ready.readText(), JsonObject::class.java).get("url").asString
    }

    @After fun stopBackend() {
        server?.let { process ->
            process.destroy()
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly().waitFor()
        }
        temp?.deleteRecursively()
    }

    private fun user(name: String): LeagueClient {
        val process = ProcessBuilder(python, File(root, "backend/server.py").path,
            "--db", File(temp, "league.sqlite3").path, "--create-user", name)
            .redirectErrorStream(true).start()
        check(process.waitFor(10, TimeUnit.SECONDS)) { "Account provisioning timed out" }
        check(process.exitValue() == 0) { "Account provisioning failed" }
        val token = Gson().fromJson(process.inputStream.bufferedReader().readText(), JsonObject::class.java).get("token").asString
        return LeagueClient.create(baseUrl, token = { token }, allowLocalHttp = true)
    }

    private fun trip(eco: Int = 100, simulated: Boolean = false): TripUploadDto {
        val now = System.currentTimeMillis()
        return TripUploadDto.from(TripResult(now - 300_000, now - 1_000, 2.0, 0, 0, 80.0,
            score = ScoreCalculator.total(100, eco), safetyScore = 100, ecoScore = eco, simulated = simulated))
    }

    private fun week() = League.weekStartMs(System.currentTimeMillis(), ZoneId.of("Asia/Seoul"))

    @Test fun authenticatedUploadRetryAndTwoUserRanking() = runBlocking {
        val first = user("contract-first")
        val second = user("contract-second")
        val trip = trip()
        assertEquals(UploadReply.Success(false), first.submit(trip))
        assertEquals(UploadReply.Success(true), first.submit(trip))
        assertEquals(UploadReply.Failed(ApiFailure.HTTP, 409), first.submit(trip.copy(distanceKm = 3.0)))
        assertEquals(UploadReply.Success(false), second.submit(trip(eco = 0)))
        val firstResult = (first.weekly(week()) as LeagueReply.Success).snapshot
        val secondResult = (second.weekly(week()) as LeagueReply.Success).snapshot
        assertEquals(1, firstResult.standing.myRank)
        assertEquals(1, firstResult.standing.myTripCount)
        assertEquals(2, firstResult.standing.entries.size)
        assertEquals(2, secondResult.standing.myRank)
        assertEquals(40.0, secondResult.standing.myAverage!!, 0.0)
        assertEquals(76.4, secondResult.standing.myLeagueScore!!, 0.0)
    }

    @Test fun unauthorizedClientCannotReadOrUpload() = runBlocking {
        val client = LeagueClient.create(baseUrl, token = { "invalid" }, allowLocalHttp = true)
        assertEquals(LeagueReply.Failed(ApiFailure.HTTP, 401), client.weekly(week()))
        assertEquals(UploadReply.Failed(ApiFailure.HTTP, 401), client.submit(trip()))
    }

    @Test fun serverAndKotlinLeagueAgreeOnWeightedScoreAndModeIsolation() = runBlocking {
        val client = user("contract-parity")
        val now = System.currentTimeMillis()
        val real = listOf(
            TripResult(now - 3_600_000, now - 2000, 1.0, 0, 0, 80.0, 100, ecoScore = 100),
            TripResult(now - 3_500_000, now - 1000, 9.0, 0, 0, 80.0, 40, safetyScore = 100, ecoScore = 0))
        real.forEach { assertEquals(UploadReply.Success(false), client.submit(TripUploadDto.from(it))) }
        assertEquals(UploadReply.Success(false), client.submit(trip(simulated = true)))
        val expected = League.standing(real, now, ZoneId.of("Asia/Seoul"), LeagueConfig(rivalCount = 0))
        val actual = (client.weekly(week()) as LeagueReply.Success).snapshot.standing
        assertEquals(expected.myAverage!!, actual.myAverage!!, 0.0)
        assertEquals(expected.myLeagueScore!!, actual.myLeagueScore!!, 0.0)
        assertEquals(expected.myTripCount, actual.myTripCount)
        assertEquals(1, (client.weekly(week(), true) as LeagueReply.Success).snapshot.standing.myTripCount)
    }

    @Test fun serverAcceptsKotlinScoringAcrossSteeringRoundingAndDistanceBoundaries() = runBlocking {
        val client = user("contract-scores")
        val now = System.currentTimeMillis()
        for ((index, km) in listOf(1.0, 9.5, 10.0, 10.1, 25.0, 40.2).withIndex()) {
            val safety = ScoreCalculator.score(km, 2, 1, harshSteer = 3)
            val eco = if (index % 2 == 0) 17 else 99
            val trip = TripResult(now - 7_200_000, now - 1000 - index, km, 2, 1, 80.0,
                ScoreCalculator.total(safety, eco), safetyScore = safety, ecoScore = eco, harshSteerCount = 3)
            assertEquals(UploadReply.Success(false), client.submit(TripUploadDto.from(trip)))
        }
        assertEquals(6, (client.weekly(week()) as LeagueReply.Success).snapshot.standing.myTripCount)
    }
}
