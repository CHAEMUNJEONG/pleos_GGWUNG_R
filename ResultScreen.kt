package com.kkwongal.drive.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kkwongal.drive.TripMark
import com.kkwongal.drive.core.EnergyKind
import com.kkwongal.drive.core.LiveEvent
import com.kkwongal.drive.core.LeagueStanding
import com.kkwongal.drive.core.Stamp
import com.kkwongal.drive.core.StampTags
import com.kkwongal.drive.core.TripResult

/** 주차하면 보이는 주행 리포트: 점수, 새 알(스탬프), 주행 기록, 급조작 시간 막대. */
@Composable
fun ResultScreen(
    result: TripResult,
    marks: List<TripMark>,
    events: List<LiveEvent>,
    league: LeagueStanding?,
    newStamp: Stamp?,
    eggCount: Int,
    onSaveStamp: (Int, String, String, List<String>) -> Unit,
    onDone: () -> Unit,
) {
    val c = LocalKk.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 820.dp
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 머리: 제목과 리그 순위
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("주행 리포트", color = c.text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "${dateText(result.startTimeMs)} 출발 · ${newStamp?.name ?: "도착"}",
                        color = c.muted,
                        fontSize = 15.sp,
                    )
                }
                val rank = league?.myRank
                if (league != null && rank != null) {
                    Row(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(c.surface)
                            .border(1.dp, c.line, CircleShape)
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("이번 주 ", color = c.muted, fontSize = 15.sp)
                        Text("${rank}위", color = c.accentText, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text(" / ${league.entries.size}명 · 예상 ${league.rewardPoints}P", color = c.muted, fontSize = 15.sp)
                    }
                }
            }

            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    ScoreCard(result, Modifier.weight(1f))
                    if (newStamp != null) {
                        EggStampCard(newStamp, eggCount, "새로운 알을 찾았어요", onSaveStamp, Modifier.weight(1.15f))
                    }
                }
            } else {
                ScoreCard(result, Modifier.fillMaxWidth())
                if (newStamp != null) {
                    EggStampCard(newStamp, eggCount, "새로운 알을 찾았어요", onSaveStamp, Modifier.fillMaxWidth())
                }
            }

            TripStatsCard(result, marks)
            PenaltyHistoryCard(events)

            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                KkButton(
                    if (newStamp != null) "닫기" else "확인",
                    onClick = onDone,
                    kind = if (newStamp != null) ButtonKind.SECONDARY else ButtonKind.PRIMARY,
                    modifier = Modifier.width(160.dp),
                )
            }
        }
    }
}

private fun scoreMessage(score: Int): String = when {
    score >= 90 -> "부드러운 주행이었어요"
    score >= 75 -> "안정적으로 잘 다녀왔어요"
    score >= 60 -> "조금만 더 부드럽게 가 볼까요"
    else -> "다음엔 여유 있게 출발해 봐요"
}

@Composable
private fun ScoreCard(result: TripResult, modifier: Modifier) {
    val c = LocalKk.current
    KkCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Box(Modifier.size(150.dp), contentAlignment = Alignment.Center) {
                RingGauge(result.score / 100f, c.accent, c.track, 12.dp, Modifier.fillMaxSize())
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("종합", color = c.muted, fontSize = 14.sp)
                    Text("${result.score}", color = c.text, fontSize = 48.sp, fontWeight = FontWeight.Bold)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(scoreMessage(result.score), color = c.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                ScoreBar("에코", result.ecoScore, c.good)
                ScoreBar("안전", result.safetyScore, c.accent)
                Text("종합 = 에코 60% + 안전 40%", color = c.muted, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun ScoreBar(label: String, score: Int, color: androidx.compose.ui.graphics.Color) {
    val c = LocalKk.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row {
            Text(label, color = c.muted, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text("${score}점", color = c.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        ProgressBar(score / 100f, color, Modifier.fillMaxWidth())
    }
}

/**
 * 새 알(스탬프) 카드. 이름, 태그, 한마디를 남긴다. 주차 중에만 보이는 화면에서 쓴다.
 * 태그는 한 번 눌러 고르므로 차 안에서 글자를 많이 칠 필요가 없다.
 */
@Composable
fun EggStampCard(
    stamp: Stamp,
    eggCount: Int,
    title: String,
    onSave: (Int, String, String, List<String>) -> Unit,
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null,
) {
    val c = LocalKk.current
    var name by remember(stamp.id) { mutableStateOf(stamp.name) }
    var memo by remember(stamp.id) { mutableStateOf(stamp.memo) }
    var tags by remember(stamp.id) { mutableStateOf(stamp.tags.toSet()) }
    var saved by remember(stamp.id) { mutableStateOf(false) }

    KkCard(modifier = modifier, highlighted = true) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                EggIcon(30.dp, 35.dp, if (stamp.perfect) c.gold else c.accent, stroke = if (stamp.perfect) c.accentText else null)
                Column(Modifier.weight(1f)) {
                    Text(
                        if (stamp.perfect) "$title · 황금알" else title,
                        color = c.accentText,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text("${eggCount}번째 알 · ${dayText(stamp.timeMs)}", color = c.muted, fontSize = 14.sp)
                }
            }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it; saved = false },
                label = { Text("장소 이름") },
                singleLine = true,
                colors = kkTextFieldColors(),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold),
                modifier = Modifier.fillMaxWidth(),
            )
            Text("어땠나요? (여러 개 선택 가능)", color = c.muted, fontSize = 14.sp)
            StampTags.ALL.chunked(3).forEach { rowTags ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowTags.forEach { tag ->
                        val selected = tag in tags
                        TagChip(tag, selected, onClick = {
                            tags = if (selected) tags - tag else tags + tag
                            saved = false
                        }, modifier = Modifier.weight(1f))
                    }
                }
            }
            OutlinedTextField(
                value = memo,
                onValueChange = { memo = it.take(StampTags.MAX_MEMO_LENGTH); saved = false },
                label = { Text("한마디") },
                placeholder = { Text("예: 바다가 보이는 주차장이 좋았어요") },
                colors = kkTextFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                KkButton(
                    if (saved) "저장했어요" else "알 저장하기",
                    onClick = {
                        onSave(stamp.id, name, memo, tags.toList())
                        saved = true
                    },
                    kind = ButtonKind.PRIMARY,
                    modifier = Modifier.weight(1f),
                )
                if (onClose != null) KkButton("닫기", onClick = onClose)
            }
        }
    }
}

@Composable
private fun TripStatsCard(result: TripResult, marks: List<TripMark>) {
    val c = LocalKk.current
    val harshTotal = result.harshAccelCount + result.harshBrakeCount + result.harshSteerCount
    KkCard(modifier = Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            StatTile("거리", fmt(result.distanceKm, 1), "km", Modifier.weight(1f))
            StatTile("시간", durationText(result.durationMin.toInt()), "", Modifier.weight(1f))
            val kind = runCatching { EnergyKind.valueOf(result.efficiencyKind ?: "") }.getOrNull()
            val eff = result.efficiency
            if (kind != null && eff != null) {
                val imp = result.efficiencyImprovement
                val (note, noteColor) = when {
                    imp == null -> "기록이 쌓이면 비교해 드려요" to c.muted
                    imp >= 1 -> "평소보다 +${fmt(imp, 0)}%" to c.good
                    imp <= -1 -> "평소보다 ${fmt(imp, 0)}%" to c.penalty
                    else -> "평소와 비슷해요" to c.muted
                }
                StatTile("실제 ${kind.label}", fmt(eff, 1), kind.unit, Modifier.weight(1f), note, noteColor)
            } else {
                StatTile("최고 속도", fmt(result.maxSpeedKmh, 0), "km/h", Modifier.weight(1f))
            }
            StatTile(
                "급조작", "$harshTotal", "회", Modifier.weight(1f),
                note = "출발 ${result.harshAccelCount} · 정거 ${result.harshBrakeCount} · 조향 ${result.harshSteerCount}",
                valueColor = if (harshTotal > 0) c.penalty else c.good,
            )
        }
        androidx.compose.foundation.layout.Spacer(Modifier.height(16.dp))
        Text(
            if (marks.isEmpty()) "주행 흐름 · 급조작 없이 도착했어요" else "주행 흐름 · 빨간 점이 급조작한 순간이에요",
            color = c.muted,
            fontSize = 14.sp,
        )
        androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
        TripTimeline(marks)
        Row(Modifier.fillMaxWidth()) {
            Text("출발", color = c.muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text("도착", color = c.muted, fontSize = 13.sp)
        }
    }
}

/** 출발부터 도착까지 가로 막대에 급조작 순간을 점으로 찍는다. */
@Composable
private fun TripTimeline(marks: List<TripMark>) {
    val c = LocalKk.current
    Canvas(Modifier.fillMaxWidth().height(28.dp)) {
        val mid = size.height / 2f
        val r = 7.dp.toPx()
        drawLine(c.good, Offset(r, mid), Offset(size.width - r, mid), 8.dp.toPx(), StrokeCap.Round)
        marks.forEach { m ->
            val x = r + (size.width - 2 * r) * m.fraction
            drawCircle(c.surface, r + 2.dp.toPx(), Offset(x, mid))
            drawCircle(c.penalty, r, Offset(x, mid))
        }
    }
}

private val clockFormat = java.text.SimpleDateFormat("HH:mm", java.util.Locale.KOREA)

/**
 * 이번 주행의 감점 기록. 주행 중에는 화면에 띄우지 않았던 내용을 주차 후 여기서 자세히 보여 준다.
 * 일어난 순서대로: 언제, 몇 km 지점에서, 무엇 때문에, 몇 점이 깎였는지.
 */
@Composable
private fun PenaltyHistoryCard(events: List<LiveEvent>) {
    val c = LocalKk.current
    KkCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("감점 기록", color = c.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (events.isNotEmpty()) {
                Text("총 ", color = c.muted, fontSize = 15.sp)
                Text("−${events.sumOf { it.penalty }}점", color = c.penalty, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        }
        androidx.compose.foundation.layout.Spacer(Modifier.height(10.dp))
        if (events.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                EggIcon(22.dp, 26.dp, c.gold, stroke = c.accentText)
                Text("급조작 없이 도착했어요", color = c.good, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        } else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            events.forEachIndexed { index, event ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                        .background(c.surface2)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("${index + 1}", color = c.muted, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Column(Modifier.weight(1f)) {
                        Text(event.type.label, color = c.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "${clockFormat.format(java.util.Date(event.wallTimeMs))} · ${fmt(event.km, 1)} km 지점",
                            color = c.muted, fontSize = 14.sp,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("−${event.penalty}", color = c.penalty, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text("안전 ${event.scoreAfter}점", color = c.muted, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}
