package com.kkwongal.drive.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kkwongal.drive.KkwongalViewModel
import com.kkwongal.drive.UiState

/** Explicit opt-in development-server flow; the default build keeps the local demo. */
@Composable
fun ServerLeagueScreen(ui: UiState, vm: KkwongalViewModel) {
    val colors = LocalKk.current
    var token by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(ui.leagueStatus, color = colors.text)
        if (!ui.leagueConnected) {
            OutlinedTextField(value = token, onValueChange = { token = it.take(200) },
                label = { Text("개발용 접속 토큰") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), enabled = !ui.leagueBusy,
                modifier = Modifier.fillMaxWidth())
            Button(enabled = token.isNotBlank() && !ui.leagueBusy,
                onClick = { vm.connectLeague(token); token = "" }) { Text("서버 연결") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = vm::refreshLeague, enabled = !ui.leagueBusy) { Text("새로고침") }
            OutlinedButton(onClick = vm::disconnectLeague) { Text("연결 해제") }
        }
        Text("현재 모드의 최근 주행 1건에서 시간·거리·급조작 횟수·점수를 보냅니다. 위치·장소명·메모는 보내지 않습니다.",
            color = colors.muted, fontSize = 13.sp)
        Button(onClick = vm::uploadLatestTrip,
            enabled = ui.leagueConnected && !ui.leagueBusy && !ui.tripActive && ui.trips.any { it.simulated == ui.simulation }) {
            Text("최근 주행 1건 전송")
        }
        Box(Modifier.weight(1f)) {
            if (ui.league != null) LeagueScreen(ui.league, ui.earnedPoints, ui.simulation, server = true)
            else Text("서버에 연결하면 순위가 표시됩니다", color = colors.muted)
        }
    }
}
