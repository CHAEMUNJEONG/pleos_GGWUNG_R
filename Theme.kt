package com.kkwongal.drive.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.kkwongal.drive.R

/** 화면 테마 설정. AUTO 는 차량(시스템)의 주간/야간 모드를 따른다. */
enum class ThemeMode(val label: String) {
    AUTO("자동"),
    LIGHT("밝게"),
    DARK("어둡게"),
}

/**
 * 꿩알 색 체계. 밝은 테마가 기본(Pleos 차량 화면과 어울림), 어두운 테마는 야간 모드.
 * Pleos Connect UI 디자인 가이드에 따라 글자 색은 모든 배경에서 대비 4.5:1 이상이 되도록 골랐다.
 */
data class KkColors(
    val isDark: Boolean,
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val line: Color,
    val text: Color,
    val muted: Color,
    /** 노른자색: 게이지, 버튼, 알 */
    val accent: Color,
    /** 노른자색 글자 (밝은 배경에서도 읽히는 짙은 색) */
    val accentText: Color,
    val onAccent: Color,
    val penalty: Color,
    val penaltySoft: Color,
    /** 빨간 바탕 위 글자 */
    val onPenalty: Color,
    val good: Color,
    val track: Color,
    val land: Color,
    val sea: Color,
    val gold: Color,
)

val LightColors = KkColors(
    isDark = false,
    bg = Color(0xFFEEF0F3),
    surface = Color(0xFFFFFFFF),
    surface2 = Color(0xFFF5F6F8),
    line = Color(0xFFDCE0E6),
    text = Color(0xFF141922),
    muted = Color(0xFF545C6B),
    accent = Color(0xFFE3A82B),
    accentText = Color(0xFF8A6100),
    onAccent = Color(0xFF1A1405),
    penalty = Color(0xFFB83227),
    penaltySoft = Color(0xFFFBE7E4),
    onPenalty = Color(0xFFFFFFFF),
    good = Color(0xFF1A7550),
    track = Color(0xFFE3E6EB),
    land = Color(0xFFE6E9EE),
    sea = Color(0xFFF7F8FA),
    gold = Color(0xFFFFC93C),
)

val DarkColors = KkColors(
    isDark = true,
    bg = Color(0xFF0D121C),
    surface = Color(0xFF161D2B),
    surface2 = Color(0xFF1E2636),
    line = Color(0xFF2A3446),
    text = Color(0xFFF3F5F9),
    muted = Color(0xFFA3ACBD),
    accent = Color(0xFFF5C451),
    accentText = Color(0xFFF5C451),
    onAccent = Color(0xFF1A1405),
    penalty = Color(0xFFFF8473),
    penaltySoft = Color(0xFF3A2026),
    onPenalty = Color(0xFF1A0A08),
    good = Color(0xFF6FD3A8),
    track = Color(0xFF263043),
    land = Color(0xFF1E2636),
    sea = Color(0xFF121826),
    gold = Color(0xFFFFC93C),
)

val LocalKk = staticCompositionLocalOf { LightColors }

/**
 * Pleos Connect UI 디자인 가이드의 권장 글꼴 Asta Sans (한국어·영어, SIL Open Font License).
 * 굵기를 바꿀 수 있는 가변 글꼴 파일 하나를 굵기별로 나눠 쓴다.
 */
@OptIn(ExperimentalTextApi::class)
val AstaSans = FontFamily(
    listOf(400, 500, 700, 900).map { w ->
        Font(
            R.font.asta_sans,
            weight = FontWeight(w),
            variationSettings = FontVariation.Settings(FontVariation.weight(w)),
        )
    },
)

private fun TextStyle.asta() = copy(fontFamily = AstaSans)

private val KkTypography = Typography().let { t ->
    t.copy(
        displayLarge = t.displayLarge.asta(), displayMedium = t.displayMedium.asta(), displaySmall = t.displaySmall.asta(),
        headlineLarge = t.headlineLarge.asta(), headlineMedium = t.headlineMedium.asta(), headlineSmall = t.headlineSmall.asta(),
        titleLarge = t.titleLarge.asta(), titleMedium = t.titleMedium.asta(), titleSmall = t.titleSmall.asta(),
        bodyLarge = t.bodyLarge.asta(), bodyMedium = t.bodyMedium.asta(), bodySmall = t.bodySmall.asta(),
        labelLarge = t.labelLarge.asta(), labelMedium = t.labelMedium.asta(), labelSmall = t.labelSmall.asta(),
    )
}

@Composable
fun KkTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.AUTO -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val c = if (dark) DarkColors else LightColors
    val scheme = if (dark) {
        darkColorScheme(
            primary = c.accent, onPrimary = c.onAccent,
            background = c.bg, onBackground = c.text,
            surface = c.surface, onSurface = c.text, onSurfaceVariant = c.muted,
            outline = c.line, error = c.penalty,
        )
    } else {
        lightColorScheme(
            primary = c.accent, onPrimary = c.onAccent,
            background = c.bg, onBackground = c.text,
            surface = c.surface, onSurface = c.text, onSurfaceVariant = c.muted,
            outline = c.line, error = c.penalty,
        )
    }
    CompositionLocalProvider(LocalKk provides c) {
        MaterialTheme(colorScheme = scheme, typography = KkTypography, content = content)
    }
}
