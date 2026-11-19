package com.kkwongal.drive

import com.kkwongal.drive.core.Badge
import com.kkwongal.drive.core.Channel
import com.kkwongal.drive.core.ChannelState
import com.kkwongal.drive.core.HarshEvent
import com.kkwongal.drive.core.HarshType
import com.kkwongal.drive.core.LeagueStanding
import com.kkwongal.drive.core.LiveEvent
import com.kkwongal.drive.core.Stamp
import com.kkwongal.drive.core.TripResult
import com.kkwongal.drive.ui.ThemeMode
import com.kkwongal.drive.vehicle.DriveState

/*
 * 화면이 그리는 상태. KkwongalViewModel 이 만들고, 화면(ui 폴더)은 읽기만 한다.
 */

/** 최근 주행에서 다시 여는 지난 리포트. */
data class PastReport(
    val result: TripResult,
    val marks: List<TripMark>,
    val ecoDelta: Int?,
    /** 이 주행으로 찍힌 알의 이름. 없으면 null. */
    val placeName: String?,
)

/** 주행 중 급조작이 일어난 시점. fraction 은 주행 시간 중 위치 (0 = 출발, 1 = 도착). */
data class TripMark(val type: HarshType, val fraction: Float)

data class UiState(
    val simulation: Boolean = false,
    val sourceStatus: String = "",
    val driveState: DriveState = DriveState.UNKNOWN,
    val speedKmh: Double? = null,
    val uxRestricted: Boolean = false,
    val tripActive: Boolean = false,
    /** 주행 중 실시간 안전 점수. */
    val liveSafety: Int = 100,
    /** 주행 중 종합 점수 (에코 60% + 안전 40%). 주행 화면의 원형 게이지에 쓴다. */
    val liveTotal: Int = 100,
    val liveHarshAccel: Int = 0,
    val liveHarshBrake: Int = 0,
    val liveHarshSteer: Int = 0,
    /** 가장 최근 급조작. 화면에 잠깐 감점을 띄우는 데 쓴다. */
    val lastHarshEvent: HarshEvent? = null,
    /** 이번 주행의 감점 기록. 일어난 순서대로. */
    val liveEvents: List<LiveEvent> = emptyList(),
    /** 급조작이 일어날 때마다 1씩 늘어난다. 같은 감점이 연달아 나와도 화면이 다시 띄우도록 쓴다. */
    val harshEventSeq: Int = 0,
    /** 방금 끝난 주행. 다음 주행을 시작하거나 닫으면 null. */
    val lastResult: TripResult? = null,
    /** 방금 끝난 주행에서 새로 찍힌 스탬프. */
    val newStamp: Stamp? = null,
    val notice: String? = null,
    val trips: List<TripResult> = emptyList(),
    val stamps: List<Stamp> = emptyList(),
    val badges: Set<Badge> = emptySet(),
    /** 이번 주 리그 순위표. */
    val league: LeagueStanding? = null,
    /** 지난 주들까지 받은 가상 포인트 합계. */
    val earnedPoints: Int = 0,
    /** 화면 테마: 자동(차량 주야간을 따름) / 밝게 / 어둡게. */
    val themeMode: ThemeMode = ThemeMode.AUTO,
    /** 방금 끝난 주행에서 급조작이 일어난 시점들. 리포트의 시간 막대에 쓴다. */
    val lastTripMarks: List<TripMark> = emptyList(),
    /** 차량 데이터 종류별 연결 상태. 연결 점검 화면에 쓴다. */
    val diagnostics: Map<Channel, ChannelState> = emptyMap(),
    /** 방금 끝난 주행의 감점 기록. 리포트에 보여 준다. */
    val lastTripEvents: List<LiveEvent> = emptyList(),
    /** 주행 중 감점을 음성으로 알려 줄지. 설정에서 끌 수 있다. */
    val voiceGuide: Boolean = true,
    /** 지금 쓰는 음성 엔진 이름 (설정 화면 안내용). */
    val voiceEngine: String = "음성 준비 중",
    /** 방금 끝난 주행의 에코 점수가 최근 평균보다 몇 점 높은지. 비교할 기록이 모자라면 null. */
    val lastEcoDelta: Int? = null,
    /** 최근 주행 목록에서 다시 열어 본 리포트. 닫으면 null. */
    val pastReport: PastReport? = null,
    /** 시연 모드가 진행 중이면 지금 단계 설명. 아니면 null. */
    val demoStep: String? = null,
)
