package com.kkwongal.drive.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kkwongal.drive.core.Channel
import com.kkwongal.drive.core.ChannelState
import com.kkwongal.drive.core.ChannelStatus
import kotlinx.coroutines.delay

/**
 * 차량 연결 점검. 차량에서 받는 데이터마다 지금 들어오고 있는지, 마지막 값은 무엇인지 보여 준다.
 * 못 받는 값이 있으면 앱이 어떻게 달라지는지도 함께 알려 준다.
 */
@Composable
fun DiagnosticsScreen(
    diagnostics: Map<Channel, ChannelState>,
    simulation: Boolean,
    onRecheckEnergy: () -> Unit,
    onClose: () -> Unit,
) {
    val c = LocalKk.current
    // "3초 전" 같은 표시를 위해 1초마다 다시 그린다
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    val okCount = diagnostics.values.count { it.status == ChannelStatus.OK }
    val missing = diagnostics.values.count { it.status == ChannelStatus.UNAVAILABLE }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("차량 연결 점검", color = c.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(
                    buildString {
                        append(if (simulation) "시뮬레이션 모드 · " else "실제 차량 · ")
                        append("연결됨 ${okCount}개")
                        if (missing > 0) append(" · 못 받음 ${missing}개")
                    },
                    color = if (missing > 0) c.penalty else c.muted,
                    fontSize = 15.sp,
                )
            }
            KkButton("연료·배터리 다시 읽기", onClick = onRecheckEnergy)
            Box(Modifier.size(10.dp))
            KkButton("닫기", onClick = onClose, kind = ButtonKind.PRIMARY)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(Channel.entries.toList()) { channel ->
                val state = diagnostics[channel] ?: ChannelState(ChannelStatus.WAITING, "확인 중")
                ChannelRow(channel, state, now)
            }
        }
    }
}

@Composable
private fun ChannelRow(channel: Channel, state: ChannelState, now: Long) {
    val c = LocalKk.current
    val (dot, word) = when (state.status) {
        ChannelStatus.OK -> c.good to "연결됨"
        ChannelStatus.WAITING -> c.accentText to "기다리는 중"
        ChannelStatus.UNAVAILABLE -> c.penalty to "못 받음"
    }
    KkCard(
        Modifier.fillMaxWidth(),
        padding = 14.dp,
        borderColor = if (state.status == ChannelStatus.UNAVAILABLE) c.penalty else null,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(dot))
            Column(Modifier.weight(1f)) {
                Text(channel.label, color = c.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(state.detail, color = c.muted, fontSize = 14.sp)
                if (state.status == ChannelStatus.UNAVAILABLE) {
                    Text("→ ${channel.effect}", color = c.penalty, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(word, color = dot, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                state.updatedAtMs?.let {
                    Text(agoText(now - it), color = c.muted, fontSize = 13.sp)
                }
            }
        }
    }
}

private fun agoText(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return when {
        s < 2 -> "방금"
        s < 60 -> "${s}초 전"
        s < 3600 -> "${s / 60}분 전"
        else -> "${s / 3600}시간 전"
    }
}
