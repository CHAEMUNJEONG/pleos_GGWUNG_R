package com.kkwongal.drive

import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kkwongal.drive.core.GeoPoint
import com.kkwongal.drive.core.KoreaOutline
import com.kkwongal.drive.core.LeagueStanding
import com.kkwongal.drive.core.Stamp
import com.kkwongal.drive.core.StampTags
import com.kkwongal.drive.core.TripResult
import org.osmdroid.config.Configuration as OsmConfiguration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint as OsmGeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

private val Accent = Color(0xFF7FD1AE)

/**
 * 차량 화면은 가로가 넓고 멀리서 보기 때문에, 화면 너비에 맞춰 글자와 버튼을 함께 키운다.
 * 휴대폰 크기에서는 그대로 두고, 차량 화면에서는 최대 1.6배까지 키운다.
 */
@Composable
fun KkwongalApp(vm: KkwongalViewModel) {
    val base = LocalDensity.current
    val configuration = LocalConfiguration.current
    val uiScale = minOf(configuration.screenWidthDp / 640f, configuration.screenHeightDp / 420f).coerceIn(1f, 1.6f)
    CompositionLocalProvider(LocalDensity provides Density(base.density * uiScale, base.fontScale)) {
        KkwongalContent(vm)
    }
}

@Composable
private fun KkwongalContent(vm: KkwongalViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val result = ui.lastResult
    // 가로로 넓은 차량 화면에서는 시뮬레이션 패널을 오른쪽에 둔다
    val wide = LocalConfiguration.current.screenWidthDp >= 900
    // 시뮬레이션 패널은 주행과 관련된 화면에서만 보여 준다 (지도·리그 화면은 넓게 쓴다)
    val showSim = ui.simulation && (ui.tripActive || result != null || tab == 0)

    MaterialTheme(colorScheme = darkColorScheme(primary = Accent)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .padding(24.dp),
            ) {
                Text("꿩알", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text(ui.sourceStatus, color = Color.Gray, fontSize = 14.sp)
                ui.notice?.let { Text(it, color = Accent, fontSize = 14.sp) }
                Spacer(Modifier.height(16.dp))

                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    when {
                        // 주행 중에는 조작할 것이 없는 화면만 보여 준다
                        ui.tripActive -> DrivingPanel(ui)
                        result != null -> ResultPanel(
                            result = result,
                            league = ui.league,
                            newStamp = ui.newStamp,
                            onSaveStamp = vm::saveStamp,
                            onDone = vm::dismissResult,
                        )
                        tab == 0 -> HomePanel(ui, onSimulation = vm::setSimulation)
                        tab == 1 -> MapPanel(ui.stamps, onSaveStamp = vm::saveStamp)
                        else -> LeaguePanel(ui.league, ui.earnedPoints)
                    }
                }
                if (showSim && wide) {
                    Spacer(Modifier.width(16.dp))
                    Box(modifier = Modifier.width(380.dp)) { SimPanel(ui, vm) }
                }
                } // 본문과 오른쪽 시뮬레이션 패널

                if (!ui.tripActive && result == null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TabButton("홈", selected = tab == 0) { tab = 0 }
                        TabButton("여행 지도", selected = tab == 1) { tab = 1 }
                        TabButton("주간 리그", selected = tab == 2) { tab = 2 }
                    }
                }

                if (showSim && !wide) {
                    Spacer(Modifier.height(12.dp))
                    SimPanel(ui, vm)
                }
            }
        }
    }
}

@Composable
private fun TabButton(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick) { Text(label) }
    }
}

@Composable
private fun DrivingPanel(ui: UiState) {
    // 급조작 직후 2.5초 동안만 감점을 크게 띄운다
    var showEvent by remember { mutableStateOf(false) }
    LaunchedEffect(ui.harshEventSeq) {
        if (ui.lastHarshEvent != null) {
            showEvent = true
            delay(2_500)
            showEvent = false
        }
    }
    val event = ui.lastHarshEvent
    val scoreColor by animateColorAsState(
        targetValue = if (showEvent) Penalty else Accent,
        animationSpec = tween(300),
        label = "scoreColor",
    )

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("주행 기록 중 · 안전 점수", fontSize = 20.sp, color = Color.Gray)
        Text("${ui.liveSafety}", fontSize = 96.sp, fontWeight = FontWeight.Bold, color = scoreColor)
        Spacer(Modifier.height(4.dp))
        Box(modifier = Modifier.height(56.dp), contentAlignment = Alignment.Center) {
            AnimatedVisibility(
                visible = showEvent && event != null,
                enter = fadeIn(tween(150)) + scaleIn(initialScale = 1.4f, animationSpec = tween(200)),
                exit = fadeOut(tween(400)),
            ) {
                if (event != null) {
                    Text(
                        "${event.type.label}!  -${event.penalty}점",
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Bold,
                        color = Penalty,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "급출발 ${ui.liveHarshAccel}회 · 급정거 ${ui.liveHarshBrake}회",
            fontSize = 18.sp,
            color = Color.Gray,
        )
        if (ui.simulation) {
            Spacer(Modifier.height(12.dp))
            Text("${fmt(ui.speedKmh ?: 0.0, 0)} km/h", fontSize = 24.sp)
        }
    }
}

@Composable
private fun ResultPanel(
    result: TripResult,
    league: LeagueStanding?,
    newStamp: Stamp?,
    onSaveStamp: (Int, String, String, List<String>) -> Unit,
    onDone: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("주행 점수", fontSize = 20.sp, color = Color.Gray)
        Text("${result.score}점", fontSize = 72.sp, fontWeight = FontWeight.Bold, color = Accent)
        Text("안전 ${result.safetyScore}점 · 에코 ${result.ecoScore}점", fontSize = 20.sp)
        Text(
            "${fmt(result.distanceKm, 1)} km · ${result.durationMin}분 · " +
                "급가속 ${result.harshAccelCount}회 · 급제동 ${result.harshBrakeCount}회",
            fontSize = 18.sp,
        )
        val rank = league?.myRank
        if (league != null && rank != null) {
            Text(
                "이번 주 리그 ${rank}위 / ${league.entries.size}명 · 예상 리워드 ${league.rewardPoints}P",
                fontSize = 18.sp,
                color = Accent,
            )
        }
        Spacer(Modifier.height(20.dp))

        if (newStamp != null) {
            StampEditor(
                stamp = newStamp,
                title = "새로운 장소! 스탬프를 받았어요",
                onSave = onSaveStamp,
            )
            Spacer(Modifier.height(20.dp))
        }

        Button(onClick = onDone) { Text("확인", fontSize = 18.sp) }
    }
}

@Composable
private fun HomePanel(ui: UiState, onSimulation: (Boolean) -> Unit) {
    val recent = ui.trips.asReversed().take(20)
    val average = if (ui.trips.isEmpty()) null else ui.trips.map { it.score }.average()

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("내 평균 점수", color = Color.Gray)
                    Text(
                        if (average == null) "아직 주행 기록이 없어요" else "${fmt(average, 1)}점",
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text("주행 ${ui.trips.size}회 · 스탬프 ${ui.stamps.size}개 · 포인트 ${ui.earnedPoints}P")
                }
            }
        }
        if (ui.badges.isNotEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("배지", color = Color.Gray)
                        ui.badges.forEach { badge ->
                            Text("${badge.title} · ${badge.description}", fontSize = 18.sp)
                        }
                    }
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("시뮬레이션 모드 (차 없이 테스트)", modifier = Modifier.weight(1f))
                Switch(checked = ui.simulation, onCheckedChange = onSimulation)
            }
        }
        if (recent.isNotEmpty()) {
            item { Text("최근 주행", color = Color.Gray) }
        }
        items(recent) { trip ->
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${trip.score}점", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Accent)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(dateText(trip.endTimeMs))
                    Text(
                        "${fmt(trip.distanceKm, 1)} km · 안전 ${trip.safetyScore} · 에코 ${trip.ecoScore}",
                        color = Color.Gray,
                    )
                }
            }
        }
    }
}

/**
 * 스탬프의 이름, 태그, 한마디를 적는 카드. 주차 중에만 보이는 화면에서 쓴다.
 * 태그는 한 번 눌러 고르므로 차 안에서 글자를 많이 칠 필요가 없다.
 */
@Composable
private fun StampEditor(
    stamp: Stamp,
    title: String,
    onSave: (Int, String, String, List<String>) -> Unit,
    onClose: (() -> Unit)? = null,
) {
    // 스탬프마다 입력 상태를 따로 둔다
    var name by remember(stamp.id) { mutableStateOf(stamp.name) }
    var memo by remember(stamp.id) { mutableStateOf(stamp.memo) }
    var tags by remember(stamp.id) { mutableStateOf(stamp.tags.toSet()) }
    var saved by remember(stamp.id) { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = name,
                onValueChange = { name = it; saved = false },
                label = { Text("장소 이름") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text("어땠나요? (여러 개 선택 가능)", color = Color.Gray)
            StampTags.ALL.chunked(2).forEach { rowTags ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowTags.forEach { tag ->
                        val selected = tag in tags
                        val toggle = {
                            tags = if (selected) tags - tag else tags + tag
                            saved = false
                        }
                        if (selected) {
                            Button(onClick = toggle) { Text(tag) }
                        } else {
                            OutlinedButton(onClick = toggle) { Text(tag) }
                        }
                    }
                }
            }
            OutlinedTextField(
                value = memo,
                onValueChange = { memo = it.take(StampTags.MAX_MEMO_LENGTH); saved = false },
                label = { Text("한마디 (예: 바다가 보이는 주차장이 좋았어요)") },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = {
                    onSave(stamp.id, name, memo, tags.toList())
                    saved = true
                }) { Text("저장") }
                if (onClose != null) {
                    OutlinedButton(onClick = onClose) { Text("닫기") }
                }
                if (saved) Text("저장했어요", color = Accent)
            }
        }
    }
}

/**
 * 여행 지도 화면. 실제 지도(오픈스트리트맵) 위에 스탬프를 표시한다.
 * 인터넷이 안 되면 지도 그림이 안 뜨므로, 버튼으로 앱에 내장된 윤곽 지도로 바꿀 수 있다.
 */
@Composable
private fun MapPanel(stamps: List<Stamp>, onSaveStamp: (Int, String, String, List<String>) -> Unit) {
    var editingId by rememberSaveable { mutableStateOf<Int?>(null) }
    var realMap by rememberSaveable { mutableStateOf(true) }
    val editing = stamps.firstOrNull { it.id == editingId }
    // 차량 화면처럼 가로로 넓으면 지도와 목록을 나란히 놓는다
    val wide = LocalConfiguration.current.screenWidthDp >= 900

    Column(modifier = Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "나만의 여행 지도 · 스탬프 ${stamps.size}개",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = { realMap = !realMap }) {
                Text(if (realMap) "윤곽 지도로 보기" else "실제 지도로 보기")
            }
        }
        Spacer(Modifier.height(8.dp))
        if (editing != null) {
            // 목록에서 고른 스탬프를 고친다. 고치는 동안에는 지도 대신 입력 카드를 보여 준다.
            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                StampEditor(
                    stamp = editing,
                    title = "스탬프 기록 고치기",
                    onSave = onSaveStamp,
                    onClose = { editingId = null },
                )
            }
        } else if (wide) {
            Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(modifier = Modifier.weight(1.2f).fillMaxHeight()) { StampMap(stamps, realMap) }
                StampList(stamps, onPick = { editingId = it }, modifier = Modifier.weight(1f))
            }
        } else {
            Box(modifier = Modifier.fillMaxWidth().height(280.dp)) { StampMap(stamps, realMap) }
            Spacer(Modifier.height(12.dp))
            StampList(stamps, onPick = { editingId = it }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun StampMap(stamps: List<Stamp>, realMap: Boolean) {
    if (realMap) OsmMap(stamps) else OutlineMap(stamps)
}

@Composable
private fun StampList(stamps: List<Stamp>, onPick: (Int) -> Unit, modifier: Modifier) {
    Column(modifier = modifier) {
        if (stamps.isEmpty()) {
            Text("처음 가 보는 곳에 도착하면 스탬프가 찍혀요", color = Color.Gray)
        } else {
            Text("스탬프를 누르면 태그와 한마디를 남길 수 있어요", color = Color.Gray, fontSize = 14.sp)
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(stamps.asReversed()) { stamp ->
                Column(modifier = Modifier.fillMaxWidth().clickable { onPick(stamp.id) }) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(stamp.name, fontSize = 18.sp, modifier = Modifier.weight(1f))
                        Text("${dateText(stamp.timeMs)} · ${stamp.score}점", color = Color.Gray)
                    }
                    if (stamp.tags.isNotEmpty()) {
                        Text(stamp.tags.joinToString(" · "), color = Accent, fontSize = 14.sp)
                    }
                    if (stamp.memo.isNotBlank()) {
                        Text(stamp.memo, fontSize = 15.sp)
                    }
                }
            }
        }
    }
}

/** 실제 지도. 오픈스트리트맵 지도 그림을 인터넷에서 받아 와 보여 주고, 스탬프를 표시로 올린다. */
@Composable
private fun OsmMap(stamps: List<Stamp>) {
    val context = LocalContext.current
    val mapView = remember {
        val config = OsmConfiguration.getInstance()
        config.load(context, context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
        config.userAgentValue = context.packageName
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            // 차량 화면에서는 두 손가락 확대가 어려우므로 확대·축소 버튼을 항상 보여 준다
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.ALWAYS)
            controller.setZoom(7.3)
            controller.setCenter(OsmGeoPoint(36.2, 127.9)) // 대한민국 가운데
        }
    }
    DisposableEffect(mapView) {
        mapView.onResume()
        onDispose {
            mapView.onPause()
            mapView.onDetach()
        }
    }
    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize(),
            update = { map ->
                map.overlays.clear()
                stamps.forEach { stamp ->
                    val marker = Marker(map)
                    marker.position = OsmGeoPoint(stamp.latitude, stamp.longitude)
                    marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    marker.title = stamp.name
                    marker.snippet = (stamp.tags + listOf(stamp.memo)).filter { it.isNotBlank() }.joinToString(" · ")
                    map.overlays.add(marker)
                }
                map.invalidate()
            },
        )
        // 오픈스트리트맵 이용 조건에 따른 출처 표시
        Text(
            "© OpenStreetMap contributors",
            fontSize = 11.sp,
            color = Color.DarkGray,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .background(Color(0xCCFFFFFF))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/** 윤곽 지도. 앱에 내장된 대한민국 윤곽선 위에 스탬프를 찍는다. 인터넷이 없어도 보인다. */
@Composable
private fun OutlineMap(stamps: List<Stamp>) {
    val labelPaint = remember {
        android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            isAntiAlias = true
        }
    }
    Canvas(modifier = Modifier.fillMaxSize()) {
        // 위도 36도 부근에서 경도 1도는 위도 1도보다 짧으므로 가로를 줄여 모양을 맞춘다
        val lonSpan = (KoreaOutline.MAX_LON - KoreaOutline.MIN_LON) * LON_SQUASH
        val latSpan = KoreaOutline.MAX_LAT - KoreaOutline.MIN_LAT
        val scale = minOf(size.width / lonSpan, size.height / latSpan)
        val left = (size.width - lonSpan * scale) / 2.0
        val top = (size.height - latSpan * scale) / 2.0
        fun x(lon: Double): Float =
            (left + (lon.coerceIn(KoreaOutline.MIN_LON, KoreaOutline.MAX_LON) - KoreaOutline.MIN_LON) * LON_SQUASH * scale).toFloat()
        fun y(lat: Double): Float =
            (top + (KoreaOutline.MAX_LAT - lat.coerceIn(KoreaOutline.MIN_LAT, KoreaOutline.MAX_LAT)) * scale).toFloat()

        KoreaOutline.POLYGONS.forEach { polygon ->
            val path = Path()
            for (i in polygon.indices step 2) {
                val px = x(polygon[i].toDouble())
                val py = y(polygon[i + 1].toDouble())
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            path.close()
            drawPath(path, color = LandColor)
            drawPath(path, color = Accent.copy(alpha = 0.6f), style = Stroke(width = 1.dp.toPx()))
        }
        drawCircle(
            color = Accent.copy(alpha = 0.6f),
            radius = 1.5.dp.toPx(),
            center = Offset(x(KoreaOutline.DOKDO[0].toDouble()), y(KoreaOutline.DOKDO[1].toDouble())),
        )

        labelPaint.textSize = 13.sp.toPx()
        stamps.forEach { stamp ->
            val center = Offset(x(stamp.longitude), y(stamp.latitude))
            drawCircle(color = Color.White, radius = 6.dp.toPx(), center = center)
            drawCircle(color = Accent, radius = 4.5.dp.toPx(), center = center)
            drawContext.canvas.nativeCanvas.drawText(
                stamp.name,
                center.x + 9.dp.toPx(),
                center.y + 4.dp.toPx(),
                labelPaint,
            )
        }
    }
}

/** 이번 주 순위표. 다른 운전자는 시연용 가상 데이터다. */
@Composable
private fun LeaguePanel(league: LeagueStanding?, earnedPoints: Int) {
    if (league == null) return
    val rank = league.myRank
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("이번 주 리그", color = Color.Gray)
                    if (rank != null) {
                        Text(
                            "${rank}위 / ${league.entries.size}명",
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Bold,
                            color = Accent,
                        )
                        Text("평균 ${fmt(league.myAverage ?: 0.0, 1)}점 · 주행 ${league.myTripCount}회")
                        Text("이대로 주가 끝나면 ${league.rewardPoints}P")
                    } else {
                        Text("아직 순위에 없어요", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        Text("이번 주에 1km 이상 주행하면 순위에 올라요 (지금 ${fmt(league.myDistanceKm, 1)} km)")
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("지금까지 받은 포인트 ${earnedPoints}P")
                    Text(
                        "순위는 주행 거리가 아니라 평균 점수로 정해요. 빨리 달려도 점수는 오르지 않아요.",
                        color = Color.Gray,
                        fontSize = 14.sp,
                    )
                    Text(
                        "시연용: 다른 운전자는 가상 데이터, 포인트는 앱 안의 가상 포인트입니다.",
                        color = Color.Gray,
                        fontSize = 14.sp,
                    )
                }
            }
        }
        items(league.entries.size) { index ->
            val entry = league.entries[index]
            val color = if (entry.isMe) Accent else Color.Unspecified
            Row(modifier = Modifier.fillMaxWidth()) {
                Text("${index + 1}", modifier = Modifier.width(48.dp), color = color, fontSize = 18.sp)
                Text(
                    entry.name,
                    modifier = Modifier.weight(1f),
                    color = color,
                    fontSize = 18.sp,
                    fontWeight = if (entry.isMe) FontWeight.Bold else FontWeight.Normal,
                )
                Text("${fmt(entry.averageScore, 1)}점", color = color, fontSize = 18.sp)
            }
        }
    }
}

/** 차 없이 테스트하기 위한 조작 패널. 시뮬레이션 모드에서만 보인다. */
@Composable
private fun SimPanel(ui: UiState, vm: KkwongalViewModel) {
    val speed = (ui.speedKmh ?: 0.0).toFloat()
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("시뮬레이션 · ${fmt(speed.toDouble(), 0)} km/h", color = Color.Gray)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.simSetParked(false) }) { Text("출발 (D)") }
                Button(onClick = { vm.simSetParked(true) }) { Text("주차 (P)") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.simSetSpeed(speed + 30.0) }) { Text("급가속 +30") }
                OutlinedButton(onClick = { vm.simSetSpeed(speed - 30.0) }) { Text("급제동 -30") }
            }
            Slider(
                value = speed.coerceIn(0f, 120f),
                onValueChange = { vm.simSetSpeed(it.toDouble()) },
                valueRange = 0f..120f,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SIM_PLACES.forEach { (name, point) ->
                    OutlinedButton(onClick = { vm.simSetLocation(point) }) { Text(name) }
                }
            }
        }
    }
}

/** 윤곽 지도에서 가로(경도)를 줄이는 비율. cos(36도). */
private const val LON_SQUASH = 0.809

private val LandColor = Color(0xFF28372F)

/** 감점 표시 색. */
private val Penalty = Color(0xFFFF6B6B)

// 시뮬레이션용 위치 (대략적인 좌표)
private val SIM_PLACES = listOf(
    "서울" to GeoPoint(37.5665, 126.9780),
    "강릉" to GeoPoint(37.7519, 128.8761),
    "부산" to GeoPoint(35.1587, 129.1604),
    "전주" to GeoPoint(35.8150, 127.1530),
)

private fun fmt(value: Double, digits: Int): String = String.format(Locale.KOREA, "%.${digits}f", value)

private fun dateText(timeMs: Long): String =
    SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA).format(Date(timeMs))
