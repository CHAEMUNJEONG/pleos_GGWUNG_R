package com.kkwongal.drive.ui

import androidx.compose.foundation.clickable
import com.kkwongal.drive.core.TripResult
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kkwongal.drive.KkwongalViewModel
import com.kkwongal.drive.UiState
import com.kkwongal.drive.core.ChannelStatus
import com.kkwongal.drive.core.EggLevels
import com.kkwongal.drive.core.GeoPoint
import com.kkwongal.drive.core.LeagueStanding

/** 홈: 내 평균 점수, 알 모으기 진행, 이번 주 순위, 배지, 최근 주행, 설정. */
@Composable
fun HomeScreen(
    ui: UiState,
    onSimulation: (Boolean) -> Unit,
    onThemeMode: (ThemeMode) -> Unit,
    onOpenDiagnostics: () -> Unit,
    onVoiceGuide: (Boolean) -> Unit,
    onOpenTrip: (TripResult) -> Unit = {},
) {
    val c = LocalKk.current
    val average = if (ui.trips.isEmpty()) null else ui.trips.map { it.score }.average()
    val eggs = ui.stamps.size
    val next = EggLevels.next(eggs)

    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            BoxWithConstraints {
                val wide = maxWidth >= 760.dp
                val cards = @Composable { m: Modifier, which: Int ->
                    when (which) {
                        0 -> KkCard(m) {
                            Text("내 평균 점수", color = c.muted, fontSize = 15.sp)
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(
                                    if (average == null) "–" else fmt(average, 1),
                                    color = c.text, fontSize = 44.sp, fontWeight = FontWeight.Bold,
                                )
                                Text(" 점", color = c.muted, fontSize = 16.sp, modifier = Modifier.padding(bottom = 8.dp))
                            }
                            Text(
                                if (average == null) "첫 주행을 시작해 보세요" else "주행 ${ui.trips.size}회 · 포인트 ${ui.earnedPoints}P",
                                color = c.muted, fontSize = 14.sp,
                            )
                        }
                        1 -> KkCard(m) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                EggIcon(22.dp, 26.dp, c.accent)
                                Column(Modifier.weight(1f)) {
                                    Text("모은 알", color = c.muted, fontSize = 15.sp)
                                    Text("${eggs}개 · ${EggLevels.current(eggs).name}", color = c.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            ProgressBar(EggLevels.progress(eggs), c.accent, Modifier.fillMaxWidth())
                            Spacer(Modifier.height(6.dp))
                            Text(
                                if (next == null) "황금 꿩까지 다 키웠어요" else "${next.name}까지 ${EggLevels.remaining(eggs)}개",
                                color = c.muted, fontSize = 14.sp,
                            )
                        }
                        else -> KkCard(m) {
                            val rank = ui.league?.myRank
                            // 시뮬레이션 기록은 실제 주행 리그와 섞지 않는다
                        Text(if (ui.simulation) "이번 주 리그 · 시뮬레이션 기록끼리" else "이번 주 리그", color = c.muted, fontSize = 15.sp)
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(if (rank == null) "–" else "$rank", color = c.accentText, fontSize = 44.sp, fontWeight = FontWeight.Bold)
                                Text(
                                    if (rank == null) "" else " 위 / ${ui.league?.entries?.size}명",
                                    color = c.muted, fontSize = 16.sp, modifier = Modifier.padding(bottom = 8.dp),
                                )
                            }
                            Text(
                                if (rank == null) "1km 이상 달리면 순위에 올라요" else "예상 리워드 ${ui.league?.rewardPoints}P",
                                color = c.muted, fontSize = 14.sp,
                            )
                        }
                    }
                }
                if (wide) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        (0..2).forEach { cards(Modifier.weight(1f), it) }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        (0..2).forEach { cards(Modifier.fillMaxWidth(), it) }
                    }
                }
            }
        }
        if (ui.badges.isNotEmpty()) {
            item {
                KkCard(Modifier.fillMaxWidth()) {
                    Text("배지", color = c.muted, fontSize = 15.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ui.badges.forEach { badge ->
                            Column(
                                Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(c.surface2)
                                    .padding(12.dp),
                            ) {
                                Text(badge.title, color = c.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                Text(badge.description, color = c.muted, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }
        item {
            KkCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("화면 밝기", color = c.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Text("자동은 차량의 주간·야간 모드를 따라가요", color = c.muted, fontSize = 14.sp)
                    }
                    SegmentedPills(
                        ThemeMode.entries.map { it.label },
                        ui.themeMode.ordinal,
                        { onThemeMode(ThemeMode.entries[it]) },
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("감점 음성 안내", color = c.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Text(
                            if (ui.voiceGuide) "주행 중 감점되면 \"급정거, 5점 감점\"처럼 알려 줘요 · ${ui.voiceEngine}"
                            else "꺼져 있어요 · 감점 내용은 주차 후 리포트에서 볼 수 있어요",
                            color = c.muted, fontSize = 14.sp,
                        )
                    }
                    KkSwitch(ui.voiceGuide, onVoiceGuide)
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val missing = ui.diagnostics.values.count { it.status == ChannelStatus.UNAVAILABLE }
                    Column(Modifier.weight(1f)) {
                        Text("차량 연결 점검", color = c.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Text(
                            if (missing > 0) "받지 못하는 값이 ${missing}개 있어요" else "속도·기어·핸들·위치·연료가 잘 들어오는지 확인해요",
                            color = if (missing > 0) c.penalty else c.muted,
                            fontSize = 14.sp,
                        )
                    }
                    KkButton("점검하기", onClick = onOpenDiagnostics)
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("시뮬레이션 모드", color = c.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Text("차 없이 주행을 흉내 내서 테스트해요", color = c.muted, fontSize = 14.sp)
                    }
                    KkSwitch(ui.simulation, onSimulation)
                }
            }
        }
        // 지운 기록은 목록에서만 뺀다 (평균 점수·리그에는 그대로 쓴다)
        val recent = ui.trips.filterNot { it.hidden }.asReversed().take(20)
        if (recent.isNotEmpty()) {
            item { Text("최근 주행", color = c.muted, fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp)) }
        }
        items(recent) { trip ->
            // 누르면 그 주행의 리포트를 다시 본다
            KkCard(Modifier.fillMaxWidth().clickable(onClick = rememberFeedbackClick { onOpenTrip(trip) }), padding = 14.dp) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                        RingGauge(trip.score / 100f, c.accent, c.track, 5.dp, Modifier.fillMaxSize())
                        Text("${trip.score}", color = c.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(dateText(trip.endTimeMs), color = c.text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Text(
                            "${fmt(trip.distanceKm, 1)} km · 안전 ${trip.safetyScore} · 에코 ${trip.ecoScore}",
                            color = c.muted, fontSize = 14.sp,
                        )
                    }
                    val harsh = trip.harshAccelCount + trip.harshBrakeCount + trip.harshSteerCount
                    Text(
                        if (harsh == 0) "급조작 없음" else "급조작 ${harsh}회",
                        color = if (harsh == 0) c.good else c.penalty,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text("›", color = c.muted, fontSize = 22.sp)
                }
            }
        }
    }
}

/** 이번 주 순위표. 다른 운전자는 시연용 가상 데이터다. */
@Composable
fun LeagueScreen(league: LeagueStanding?, earnedPoints: Int, simulation: Boolean = false) {
    val c = LocalKk.current
    if (league == null) return
    val rank = league.myRank
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            KkCard(Modifier.fillMaxWidth(), highlighted = rank != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (simulation) "이번 주 리그 · 시뮬레이션 기록끼리" else "이번 주 리그", color = c.muted, fontSize = 15.sp)
                        if (rank != null) {
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text("$rank", color = c.accentText, fontSize = 44.sp, fontWeight = FontWeight.Bold)
                                Text(" 위 / ${league.entries.size}명", color = c.muted, fontSize = 16.sp, modifier = Modifier.padding(bottom = 8.dp))
                            }
                            Text(
                                "리그 점수 ${fmt(league.myLeagueScore ?: 0.0, 1)} · 주행 ${league.myTripCount}회 " +
                                    "${fmt(league.myDistanceKm, 1)}km · 이대로 끝나면 ${league.rewardPoints}P",
                                color = c.text, fontSize = 15.sp,
                            )
                            if (league.myDistanceKm < 40.0) {
                                Text(
                                    "이번 주 평균 ${fmt(league.myAverage ?: 0.0, 1)}점 · 기록이 짧아 80점 쪽으로 보정 중이에요. 많이 달릴수록 보정이 줄어요",
                                    color = c.muted, fontSize = 13.sp,
                                )
                            }
                        } else {
                            Text("아직 순위에 없어요", color = c.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                            Text(
                                "1km 넘게 달린 주행으로 이번 주 1km 이상 채우면 순위에 올라요 (지금 ${fmt(league.myDistanceKm, 1)} km)",
                                color = c.muted, fontSize = 15.sp,
                            )
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("받은 포인트", color = c.muted, fontSize = 14.sp)
                        Text("${earnedPoints}P", color = c.text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "순위는 주행 거리만큼 비중을 둔 평균 점수로 정해요 · 상위 10% 500P, 30% 200P, 참가 50P",
                    color = c.muted, fontSize = 13.sp,
                )
                Text("시연용: 다른 운전자는 가상 데이터, 포인트는 앱 안의 가상 포인트예요", color = c.muted, fontSize = 13.sp)
            }
        }
        items(league.entries.size) { index ->
            val entry = league.entries[index]
            val top = index < 3
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (entry.isMe) c.accent else c.surface)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val fg = if (entry.isMe) c.onAccent else c.text
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(if (top && !entry.isMe) c.surface2 else androidx.compose.ui.graphics.Color.Transparent),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("${index + 1}", color = fg, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    if (entry.isMe) "나" else entry.name,
                    color = fg,
                    fontSize = 17.sp,
                    fontWeight = if (entry.isMe) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.weight(1f),
                )
                Text("${fmt(entry.averageScore, 1)}점", color = fg, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/** 시뮬레이션용 목적지. name 은 내비가 알려 주는 목적지 이름 역할을 한다. 좌표는 대략적인 값. */
private data class SimPlace(val label: String, val name: String, val address: String, val point: GeoPoint)

private val SIM_PLACES = listOf(
    SimPlace("서울", "서울시청", "서울특별시 중구 세종대로 110", GeoPoint(37.5665, 126.9780)),
    SimPlace("강릉", "강릉 안목해변", "강원특별자치도 강릉시 창해로14번길", GeoPoint(37.7719, 128.9473)),
    SimPlace("부산", "해운대해수욕장", "부산광역시 해운대구 우동", GeoPoint(35.1587, 129.1604)),
    SimPlace("전주", "전주한옥마을", "전북특별자치도 전주시 완산구 기린대로 99", GeoPoint(35.8150, 127.1530)),
)

/** 차 없이 테스트하기 위한 조작 패널. 시뮬레이션 모드에서만 보인다. */
@Composable
fun SimPanel(ui: UiState, vm: KkwongalViewModel) {
    val c = LocalKk.current
    val speed = (ui.speedKmh ?: 0.0).toFloat()
    KkCard(Modifier.fillMaxWidth(), padding = 14.dp, background = c.surface2) {
        Text("시뮬레이션 · ${fmt(speed.toDouble(), 0)} km/h", color = c.muted, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        // 발표용: 정해진 시나리오로 출발부터 주차까지 자동으로 달린다
        val demoStep = ui.demoStep
        if (demoStep != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("시연 · $demoStep", color = c.accentText, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                KkButton("시연 멈춤", { vm.simStopDemo() })
            }
        } else {
            KkButton(
                "시연 시작 (자동 주행 약 1분 30초)",
                { vm.simRunDemo() },
                Modifier.fillMaxWidth(),
                ButtonKind.PRIMARY,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KkButton("출발 D", { vm.simSetParked(false) }, Modifier.weight(1f), ButtonKind.PRIMARY)
                KkButton("주차 P", { vm.simSetParked(true) }, Modifier.weight(1f), ButtonKind.PRIMARY)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KkButton("+30", { vm.simSetSpeed(speed + 30.0) }, Modifier.weight(1f))
                KkButton("−30", { vm.simSetSpeed(speed - 30.0) }, Modifier.weight(1f))
                KkButton("급조향", { vm.simJerkSteering() }, Modifier.weight(1f), ButtonKind.DANGER)
            }
            Slider(
                value = speed.coerceIn(0f, 120f),
                onValueChange = { vm.simSetSpeed(it.toDouble()) },
                valueRange = 0f..120f,
                colors = SliderDefaults.colors(
                    thumbColor = c.accent,
                    activeTrackColor = c.accent,
                    inactiveTrackColor = c.track,
                ),
            )
            Text("목적지", color = c.muted, fontSize = 13.sp)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SIM_PLACES.forEach { place ->
                    KkButton(place.label, { vm.simSetLocation(place.point, place.name, place.address) }, Modifier.weight(1f))
                }
            }
            Text(
                "목적지를 고르고 출발 → 속도 올리기 → 주차하면 그곳에 알이 놓여요",
                color = c.muted, fontSize = 12.sp, textAlign = TextAlign.Start,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** 꿩알 색의 켜기/끄기 스위치. 누르면 진동 피드백. */
@Composable
private fun KkSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = LocalKk.current
    val toggle = rememberFeedbackClick { onChange(!checked) }
    Switch(
        checked = checked,
        onCheckedChange = { toggle() },
        colors = SwitchDefaults.colors(
            checkedTrackColor = c.accent,
            checkedThumbColor = c.onAccent,
            uncheckedTrackColor = c.track,
            uncheckedThumbColor = c.muted,
            uncheckedBorderColor = c.line,
        ),
    )
}
