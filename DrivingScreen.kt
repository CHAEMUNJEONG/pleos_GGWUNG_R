package com.kkwongal.drive.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import com.kkwongal.drive.UiState

/**
 * 주행 중 화면. 종합 점수(에코 60% + 안전 40%) 원형 게이지와 숫자 하나만 보여 준다.
 *
 * 운전자의 시선을 빼앗지 않도록 감점 내용(어떤 급조작이었는지, 몇 점인지)은 화면에 띄우지 않고,
 * 원하면 음성으로 알려 준다(홈 설정의 "감점 음성 안내"). 자세한 감점 기록은 주차 후 리포트에서 본다.
 *
 * Pleos Connect UI 디자인 가이드: 주행 중에는 시선을 끄는 움직임을 쓰지 않는다.
 * 그래서 점수가 바뀌어도 게이지와 숫자가 그 자리에서 바로 바뀐다.
 */
@Composable
fun DrivingScreen(ui: UiState) {
    val c = LocalKk.current

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(c.surface)
                    .border(1.dp, c.line, CircleShape)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(c.good))
                Text(
                    if (ui.voiceGuide) "주행 기록 중 · 음성 안내 켜짐" else "주행 기록 중",
                    color = c.text, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                )
            }
            Spacer(Modifier.size(16.dp))
            Text(fmt(ui.speedKmh ?: 0.0, 0), color = c.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(" km/h", color = c.muted, fontSize = 14.sp)
        }

        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val ring = min(maxHeight * 0.92f, min(maxWidth * 0.9f, 420.dp))
            // 게이지 안 글자는 게이지 크기에 맞춘다 (글자 확대 설정과 상관없이 원 안에 들어가도록 toSp 로 환산)
            val density = LocalDensity.current
            val scoreSize = with(density) { (ring * 0.36f).toSp() }
            // 게이지 안에는 점수 숫자만 둔다 (한눈에 읽히도록)
            Box(Modifier.size(ring), contentAlignment = Alignment.Center) {
                // 종합 점수 (에코 60% + 안전 40%): 주차 후 리포트의 종합 점수와 같은 기준
                RingGauge(ui.liveTotal / 100f, c.accent, c.track, 20.dp, Modifier.fillMaxSize())
                Text("${ui.liveTotal}", color = c.text, fontSize = scoreSize, fontWeight = FontWeight.Bold)
            }
        }

        Text(
            "주행 중에는 보기만 하세요 · 감점 기록은 주차하면 리포트에서 보여 드려요",
            color = c.muted,
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
