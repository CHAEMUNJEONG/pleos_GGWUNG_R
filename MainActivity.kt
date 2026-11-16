package com.kkwongal.drive

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kkwongal.drive.ui.DiagnosticsScreen
import com.kkwongal.drive.ui.DrivingScreen
import com.kkwongal.drive.ui.HomeScreen
import com.kkwongal.drive.ui.KkTheme
import com.kkwongal.drive.ui.LeagueScreen
import com.kkwongal.drive.ui.LocalKk
import com.kkwongal.drive.ui.Logo
import com.kkwongal.drive.ui.MapScreen
import com.kkwongal.drive.ui.ResultScreen
import com.kkwongal.drive.ui.SegmentedPills
import com.kkwongal.drive.ui.SimPanel

class MainActivity : ComponentActivity() {

    private val viewModel: KkwongalViewModel by viewModels()

    // 속도 읽기는 사용자 동의가 필요한 권한이다. 결과와 상관없이 차량 연결을 시작한다.
    private val speedPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.startVehicle() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val granted = checkSelfPermission(CAR_SPEED_PERMISSION) == PackageManager.PERMISSION_GRANTED
        if (granted || savedInstanceState != null) {
            viewModel.startVehicle()
        } else {
            speedPermission.launch(CAR_SPEED_PERMISSION)
        }
        setContent { KkwongalApp(viewModel) }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshLeague()
    }

    private companion object {
        const val CAR_SPEED_PERMISSION = "android.car.permission.CAR_SPEED"
    }
}

/**
 * 차량 화면은 가로가 넓고 멀리서 보기 때문에, 화면 너비에 맞춰 글자와 버튼을 함께 키운다.
 * 휴대폰 크기에서는 그대로 두고, 차량 화면에서는 최대 1.6배까지 키운다.
 */
@Composable
fun KkwongalApp(vm: KkwongalViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val base = LocalDensity.current
    val configuration = LocalConfiguration.current
    val uiScale = minOf(configuration.screenWidthDp / 640f, configuration.screenHeightDp / 420f).coerceIn(1f, 1.6f)
    // Pleos Connect UI 디자인 가이드: 중요 정보는 글자 높이 5mm, 보조 글자는 3mm 이상.
    // 17인치 차량 화면에서 재 보니 보조 글자가 2.5~2.9mm 라서, 차량 화면에서는 글자만 25% 키운다.
    val textBoost = if (uiScale > 1.2f) 1.25f else 1f
    CompositionLocalProvider(LocalDensity provides Density(base.density * uiScale, base.fontScale * textBoost)) {
        KkTheme(ui.themeMode) {
            KkwongalContent(ui, vm)
        }
    }
}

@Composable
private fun KkwongalContent(ui: UiState, vm: KkwongalViewModel) {
    val c = LocalKk.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showDiagnostics by rememberSaveable { mutableStateOf(false) }
    val result = ui.lastResult
    // 가로로 넓은 차량 화면에서는 시뮬레이션 패널을 오른쪽에 둔다
    val wide = LocalConfiguration.current.screenWidthDp >= 900
    // 시뮬레이션 패널은 주행과 관련된 화면에서만 보여 준다 (지도·리그 화면은 넓게 쓴다)
    val showSim = ui.simulation && (ui.tripActive || result != null || tab == 0)
    val showTabs = !ui.tripActive && result == null && !showDiagnostics

    Box(Modifier.fillMaxSize().background(c.bg)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 28.dp, vertical = 20.dp),
        ) {
            // 머리: 로고, 탭, 연결 상태
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Logo()
                if (showTabs) {
                    SegmentedPills(listOf("홈", "여행 지도", "주간 리그"), tab, { tab = it })
                }
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    Text(ui.sourceStatus, color = c.muted, fontSize = 13.sp, maxLines = 1)
                    ui.notice?.let { Text(it, color = c.accentText, fontSize = 13.sp, maxLines = 1) }
                }
            }
            Spacer(Modifier.height(16.dp))

            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    when {
                        // 주행 중에는 조작할 것이 없는 화면만 보여 준다
                        ui.tripActive -> DrivingScreen(ui)
                        result != null -> ResultScreen(
                            result = result,
                            marks = ui.lastTripMarks,
                            events = ui.lastTripEvents,
                            league = ui.league,
                            newStamp = ui.newStamp,
                            eggCount = ui.stamps.size,
                            onSaveStamp = vm::saveStamp,
                            onDone = vm::dismissResult,
                        )
                        showDiagnostics -> DiagnosticsScreen(
                            diagnostics = ui.diagnostics,
                            simulation = ui.simulation,
                            onRecheckEnergy = vm::recheckEnergy,
                            onClose = { showDiagnostics = false },
                        )
                        tab == 0 -> HomeScreen(
                            ui,
                            onSimulation = vm::setSimulation,
                            onThemeMode = vm::setThemeMode,
                            onOpenDiagnostics = { showDiagnostics = true },
                            onVoiceGuide = vm::setVoiceGuide,
                        )
                        tab == 1 -> MapScreen(ui.stamps, onSaveStamp = vm::saveStamp, onNavigate = vm::navigateTo)
                        else -> LeagueScreen(ui.league, ui.earnedPoints)
                    }
                }
                if (showSim && wide) {
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.width(360.dp).verticalScroll(rememberScrollState())) { SimPanel(ui, vm) }
                }
            }

            if (showSim && !wide) {
                Spacer(Modifier.height(12.dp))
                SimPanel(ui, vm)
            }
        }
    }
}
