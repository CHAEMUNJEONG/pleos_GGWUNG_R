package com.kkwongal.drive.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalView
import android.view.HapticFeedbackConstants
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 누를 때 촉각 피드백(진동)을 주는 클릭.
 * Pleos Connect UI 디자인 가이드: 터치하면 햅틱 또는 음성 피드백을 준다. 누르는 동안은 눌림 효과가 보인다.
 */
@Composable
fun rememberFeedbackClick(onClick: () -> Unit): () -> Unit {
    val view = LocalView.current
    return {
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        onClick()
    }
}

/** 둥근 카드. highlighted 면 노른자색 테두리. */
@Composable
fun KkCard(
    modifier: Modifier = Modifier,
    padding: Dp = 20.dp,
    highlighted: Boolean = false,
    borderColor: Color? = null,
    background: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LocalKk.current
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(background ?: c.surface)
            .border(if (highlighted) 2.dp else 1.dp, borderColor ?: if (highlighted) c.accent else c.line, shape)
            .padding(padding),
        content = content,
    )
}

/** 꿩알의 알 모양. 24 x 28 비율로 그린다. */
fun eggPath(width: Float, height: Float, left: Float = 0f, top: Float = 0f): Path {
    val sx = width / 24f
    val sy = height / 28f
    fun x(v: Float) = left + v * sx
    fun y(v: Float) = top + v * sy
    return Path().apply {
        moveTo(x(12f), y(1f))
        cubicTo(x(6.8f), y(1f), x(2.5f), y(9.2f), x(2.5f), y(16.2f))
        cubicTo(x(2.5f), y(22.2f), x(6.8f), y(27f), x(12f), y(27f))
        cubicTo(x(17.2f), y(27f), x(21.5f), y(22.2f), x(21.5f), y(16.2f))
        cubicTo(x(21.5f), y(9.2f), x(17.2f), y(1f), x(12f), y(1f))
        close()
    }
}

@Composable
fun EggIcon(width: Dp, height: Dp, fill: Color, stroke: Color? = null, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(width, height)) {
        val path = eggPath(size.width, size.height)
        drawPath(path, fill)
        if (stroke != null) drawPath(path, stroke, style = Stroke(width = 1.5.dp.toPx()))
    }
}

@Composable
fun Logo() {
    val c = LocalKk.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        EggIcon(20.dp, 24.dp, c.accent)
        Text("꿩알", fontSize = 22.sp, fontWeight = FontWeight.Black, color = c.text)
    }
}

/** 원형 게이지. progress 0~1, 12시 방향에서 시계 방향으로 찬다. */
@Composable
fun RingGauge(progress: Float, color: Color, track: Color, strokeWidth: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val stroke = strokeWidth.toPx()
        val d = size.minDimension - stroke
        val topLeft = Offset((size.width - d) / 2f, (size.height - d) / 2f)
        drawArc(track, 0f, 360f, false, topLeft, Size(d, d), style = Stroke(stroke))
        drawArc(
            color, -90f, 360f * progress.coerceIn(0f, 1f), false, topLeft, Size(d, d),
            style = Stroke(stroke, cap = StrokeCap.Round),
        )
    }
}

/** 가로 진행 막대. */
@Composable
fun ProgressBar(fraction: Float, color: Color, modifier: Modifier = Modifier, height: Dp = 10.dp) {
    val c = LocalKk.current
    Box(
        modifier = modifier
            .height(height)
            .clip(CircleShape)
            .background(c.track),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .clip(CircleShape)
                .background(color),
        )
    }
}

enum class ButtonKind { PRIMARY, SECONDARY, DANGER }

/** 꿩알 버튼. 차 안에서 누르기 쉽도록 최소 높이 48dp. */
@Composable
fun KkButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.SECONDARY,
) {
    val c = LocalKk.current
    val shape = RoundedCornerShape(14.dp)
    val (bg, fg, border) = when (kind) {
        ButtonKind.PRIMARY -> Triple(c.accent, c.onAccent, c.accent)
        ButtonKind.SECONDARY -> Triple(c.surface2, c.text, c.line)
        ButtonKind.DANGER -> Triple(c.penaltySoft, c.penalty, c.penalty)
    }
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(bg)
            .border(1.dp, border, shape)
            .clickable(role = Role.Button, onClick = rememberFeedbackClick(onClick))
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = fg, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

/** 켜고 끄는 태그 버튼. */
@Composable
fun TagChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalKk.current
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .heightIn(min = 44.dp)
            .clip(shape)
            .background(if (selected) c.accent else c.surface2)
            .border(1.dp, if (selected) c.accent else c.line, shape)
            .clickable(role = Role.Checkbox, onClick = rememberFeedbackClick(onClick))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (selected) c.onAccent else c.text,
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        )
    }
}

/** 여러 개 중 하나를 고르는 알약 모양 버튼 묶음 (탭, 테마 설정). */
@Composable
fun SegmentedPills(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = LocalKk.current
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(c.surface)
            .border(1.dp, c.line, CircleShape)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { index, label ->
            val on = index == selected
            val select = rememberFeedbackClick { onSelect(index) }
            Box(
                modifier = Modifier
                    .heightIn(min = 44.dp)
                    .clip(CircleShape)
                    .background(if (on) c.text else Color.Transparent)
                    .clickable(role = Role.Tab, onClick = select)
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    color = if (on) c.bg else c.muted,
                    fontSize = 16.sp,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                )
            }
        }
    }
}

/** 작은 숫자 칸: 이름, 큰 값, 단위, (선택) 한 줄 설명. */
@Composable
fun StatTile(
    label: String,
    value: String,
    unit: String,
    modifier: Modifier = Modifier,
    note: String? = null,
    noteColor: Color? = null,
    valueColor: Color? = null,
) {
    val c = LocalKk.current
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = c.muted, fontSize = 14.sp)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, color = valueColor ?: c.text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            if (unit.isNotEmpty()) {
                Text(" $unit", color = c.muted, fontSize = 14.sp, modifier = Modifier.padding(bottom = 4.dp))
            }
        }
        if (note != null) Text(note, color = noteColor ?: c.muted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun kkTextFieldColors(): TextFieldColors {
    val c = LocalKk.current
    return OutlinedTextFieldDefaults.colors(
        focusedTextColor = c.text,
        unfocusedTextColor = c.text,
        focusedContainerColor = c.surface2,
        unfocusedContainerColor = c.surface2,
        focusedBorderColor = c.accent,
        unfocusedBorderColor = c.line,
        cursorColor = c.accentText,
        focusedLabelColor = c.accentText,
        unfocusedLabelColor = c.muted,
        focusedPlaceholderColor = c.muted,
        unfocusedPlaceholderColor = c.muted,
    )
}

fun fmt(value: Double, digits: Int): String = String.format(Locale.KOREA, "%.${digits}f", value)

fun dateText(timeMs: Long): String = SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA).format(Date(timeMs))

fun dayText(timeMs: Long): String = SimpleDateFormat("M월 d일", Locale.KOREA).format(Date(timeMs))

/** 주행 시간을 "1시간 5분" / "23분" 으로. */
fun durationText(minutes: Int): String =
    if (minutes >= 60) "${minutes / 60}시간 ${minutes % 60}분" else "${minutes}분"
