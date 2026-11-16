package com.kkwongal.drive.ui

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.kkwongal.drive.core.EggLevels
import com.kkwongal.drive.core.KoreaOutline
import com.kkwongal.drive.core.Stamp
import org.osmdroid.config.Configuration as OsmConfiguration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint as OsmGeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

/**
 * 나만의 여행 지도. 새로운 곳에 도착할 때마다 알이 하나씩 놓이고,
 * 급조작 없이 도착한 곳은 황금알이 된다. 알이 모이면 꿩이 자란다.
 */
@Composable
fun MapScreen(
    stamps: List<Stamp>,
    onSaveStamp: (Int, String, String, List<String>) -> Unit,
    onNavigate: (Int) -> Unit,
) {
    val c = LocalKk.current
    var selectedId by rememberSaveable { mutableStateOf<Int?>(stamps.lastOrNull()?.id) }
    var editingId by rememberSaveable { mutableStateOf<Int?>(null) }
    var realMap by rememberSaveable { mutableStateOf(false) }
    val editing = stamps.firstOrNull { it.id == editingId }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        MapHeader(stamps)
        if (editing != null) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                EggStampCard(
                    stamp = editing,
                    eggCount = stamps.indexOf(editing) + 1,
                    title = "알 기록 고치기",
                    onSave = onSaveStamp,
                    onClose = { editingId = null },
                )
            }
        } else {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val wide = maxWidth >= 820.dp
            val map: @Composable (Modifier) -> Unit = { m ->
                Box(
                    m.clip(RoundedCornerShape(22.dp))
                        .background(c.sea)
                        .border(1.dp, c.line, RoundedCornerShape(22.dp)),
                ) {
                    if (realMap) OsmMap(stamps) else OutlineMap(stamps, selectedId) { selectedId = it }
                    Row(
                        Modifier.align(Alignment.TopEnd).padding(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SegmentedPills(listOf("알 지도", "실제 지도"), if (realMap) 1 else 0, { realMap = it == 1 })
                    }
                    if (!realMap) Legend(Modifier.align(Alignment.BottomStart).padding(12.dp))
                }
            }
            if (wide) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    map(Modifier.weight(1.25f).fillMaxHeight())
                    StampCards(stamps, selectedId, { selectedId = it }, { editingId = it }, onNavigate, Modifier.weight(1f))
                }
            } else {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    map(Modifier.fillMaxWidth().height(300.dp))
                    StampCards(stamps, selectedId, { selectedId = it }, { editingId = it }, onNavigate, Modifier.weight(1f))
                }
            }
        }
        } // 지도 + 목록
    }
}

@Composable
private fun MapHeader(stamps: List<Stamp>) {
    val c = LocalKk.current
    val eggs = stamps.size
    val next = EggLevels.next(eggs)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f)) {
            Text("나만의 여행 지도", color = c.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(
                "모은 알 ${eggs}개 · 황금알 ${stamps.count { it.perfect }}개 · 지금은 ${EggLevels.current(eggs).name} 단계",
                color = c.muted,
                fontSize = 15.sp,
            )
        }
        KkCard(modifier = Modifier.width(260.dp), padding = 14.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (next == null) "황금 꿩 완성!" else "${next.name}까지",
                    color = c.text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                if (next != null) {
                    Text("${EggLevels.remaining(eggs)}개", color = c.accentText, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
            androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
            ProgressBar(EggLevels.progress(eggs), c.accent, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun Legend(modifier: Modifier) {
    val c = LocalKk.current
    Row(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(c.surface)
            .border(1.dp, c.line, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        EggIcon(12.dp, 14.dp, c.accent)
        Text("새로운 장소", color = c.muted, fontSize = 13.sp)
        androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp))
        EggIcon(12.dp, 14.dp, c.gold, stroke = c.accentText)
        Text("급조작 없이 도착", color = c.muted, fontSize = 13.sp)
    }
}

@Composable
private fun StampCards(
    stamps: List<Stamp>,
    selectedId: Int?,
    onSelect: (Int) -> Unit,
    onEdit: (Int) -> Unit,
    onNavigate: (Int) -> Unit,
    modifier: Modifier,
) {
    val c = LocalKk.current
    if (stamps.isEmpty()) {
        KkCard(modifier) {
            Text("아직 모은 알이 없어요", color = c.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("처음 가 보는 곳에 도착하면 그곳에 알이 놓여요", color = c.muted, fontSize = 15.sp)
        }
        return
    }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(stamps.asReversed(), key = { it.id }) { stamp ->
            val selected = stamp.id == selectedId
            KkCard(
                modifier = Modifier.fillMaxWidth().clickable(onClick = rememberFeedbackClick { onSelect(stamp.id) }),
                padding = 16.dp,
                highlighted = selected,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    EggIcon(22.dp, 26.dp, if (stamp.perfect) c.gold else c.accent, stroke = if (stamp.perfect) c.accentText else null)
                    Column(Modifier.weight(1f)) {
                        Text(stamp.name, color = c.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text("${dayText(stamp.timeMs)} · ${stamp.score}점", color = c.muted, fontSize = 14.sp)
                    }
                    if (selected) {
                        KkButton("기록", onClick = { onEdit(stamp.id) })
                        KkButton("길 안내", onClick = { onNavigate(stamp.id) }, kind = ButtonKind.PRIMARY)
                    }
                }
                if (stamp.tags.isNotEmpty()) {
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        stamp.tags.take(4).forEach { tag ->
                            Text(
                                tag,
                                color = c.accentText,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(c.surface2)
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
                if (stamp.memo.isNotBlank()) {
                    Text("“${stamp.memo}”", color = c.text, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    }
}

/** 윤곽 지도에서 가로(경도)를 줄이는 비율. cos(36도). */
private const val LON_SQUASH = 0.809

/** 알 지도. 앱에 내장된 대한민국 윤곽선 위에 알을 놓는다. 인터넷이 없어도 보인다. */
@Composable
private fun OutlineMap(stamps: List<Stamp>, selectedId: Int?, onSelect: (Int) -> Unit) {
    val c = LocalKk.current
    val labelPaint = remember { android.graphics.Paint().apply { isAntiAlias = true; isFakeBoldText = true } }
    val labelBg = remember { android.graphics.Paint().apply { isAntiAlias = true } }
    // 좌표 변환을 탭 처리와 그리기에서 같이 쓴다
    // 그릴 때마다 바뀌므로 화면을 다시 그리게 하는 상태가 아닌 보관함에 둔다
    val projectRef = remember { arrayOfNulls<(Double, Double) -> Offset>(1) }

    Canvas(
        Modifier.fillMaxSize().padding(24.dp).pointerInput(stamps) {
            detectTapGestures { tap ->
                val p = projectRef[0] ?: return@detectTapGestures
                stamps.minByOrNull { (p(it.longitude, it.latitude) - tap).getDistance() }
                    ?.takeIf { (p(it.longitude, it.latitude) - tap).getDistance() < 40.dp.toPx() }
                    ?.let { onSelect(it.id) }
            }
        },
    ) {
        val lonSpan = (KoreaOutline.MAX_LON - KoreaOutline.MIN_LON) * LON_SQUASH
        val latSpan = KoreaOutline.MAX_LAT - KoreaOutline.MIN_LAT
        val scale = minOf(size.width / lonSpan, size.height / latSpan)
        val left = (size.width - lonSpan * scale) / 2.0
        val top = (size.height - latSpan * scale) / 2.0
        val toScreen = { lon: Double, lat: Double ->
            Offset(
                (left + (lon.coerceIn(KoreaOutline.MIN_LON, KoreaOutline.MAX_LON) - KoreaOutline.MIN_LON) * LON_SQUASH * scale).toFloat(),
                (top + (KoreaOutline.MAX_LAT - lat.coerceIn(KoreaOutline.MIN_LAT, KoreaOutline.MAX_LAT)) * scale).toFloat(),
            )
        }
        projectRef[0] = toScreen

        KoreaOutline.POLYGONS.forEach { polygon ->
            val path = Path()
            for (i in polygon.indices step 2) {
                val pt = toScreen(polygon[i].toDouble(), polygon[i + 1].toDouble())
                if (i == 0) path.moveTo(pt.x, pt.y) else path.lineTo(pt.x, pt.y)
            }
            path.close()
            drawPath(path, c.land)
            drawPath(path, c.line, style = Stroke(1.2.dp.toPx()))
        }
        drawCircle(c.line, 2.dp.toPx(), toScreen(KoreaOutline.DOKDO[0].toDouble(), KoreaOutline.DOKDO[1].toDouble()))

        // 고른 알은 맨 위에 그린다
        val ordered = stamps.sortedBy { it.id == selectedId }
        ordered.forEach { stamp ->
            val at = toScreen(stamp.longitude, stamp.latitude)
            val selected = stamp.id == selectedId
            val w = (if (selected) 26 else 17).dp.toPx()
            val h = w * 28f / 24f
            // 알 바닥이 그 장소를 가리키도록 위로 올려 그린다
            val l = at.x - w / 2f
            val t = at.y - h
            drawOval(Color.Black.copy(alpha = 0.15f), Offset(at.x - w * 0.4f, at.y - 3.dp.toPx()), androidx.compose.ui.geometry.Size(w * 0.8f, 5.dp.toPx()))
            val egg = eggPath(w, h, l, t)
            drawPath(egg, if (stamp.perfect) c.gold else c.accent)
            drawPath(egg, if (stamp.perfect) c.accentText else c.surface, style = Stroke(2.dp.toPx()))
            if (selected) {
                labelPaint.textSize = 15.sp.toPx()
                labelPaint.color = c.text.toArgb()
                labelBg.color = c.surface.toArgb()
                val textW = labelPaint.measureText(stamp.name)
                val padX = 8.dp.toPx()
                val bx = (at.x - textW / 2f - padX).coerceIn(0f, maxOf(0f, size.width - textW - 2 * padX))
                val by = t - 34.dp.toPx()
                drawContext.canvas.nativeCanvas.apply {
                    drawRoundRect(bx, by, bx + textW + 2 * padX, by + 26.dp.toPx(), 8.dp.toPx(), 8.dp.toPx(), labelBg)
                    drawText(stamp.name, bx + padX, by + 18.dp.toPx(), labelPaint)
                }
            }
        }
    }
}

/** 실제 지도. 오픈스트리트맵 지도 그림을 인터넷에서 받아 와 보여 주고, 알을 표시로 올린다. */
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
            controller.setCenter(OsmGeoPoint(36.2, 127.9))
        }
    }
    DisposableEffect(mapView) {
        mapView.onResume()
        onDispose {
            mapView.onPause()
            mapView.onDetach()
        }
    }
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize(),
            update = { map ->
                map.overlays.clear()
                stamps.forEach { stamp ->
                    val marker = Marker(map)
                    marker.position = OsmGeoPoint(stamp.latitude, stamp.longitude)
                    marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    marker.title = if (stamp.perfect) "${stamp.name} (황금알)" else stamp.name
                    marker.snippet = (stamp.tags + listOf(stamp.memo)).filter { it.isNotBlank() }.joinToString(" · ")
                    map.overlays.add(marker)
                }
                map.invalidate()
            },
        )
        // 오픈스트리트맵 이용 조건에 따른 출처 표시
        Text(
            "© OpenStreetMap contributors",
            fontSize = 12.sp,
            color = Color.DarkGray,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .background(Color(0xCCFFFFFF))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
