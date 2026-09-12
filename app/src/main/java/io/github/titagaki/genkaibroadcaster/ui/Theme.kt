package io.github.titagaki.genkaibroadcaster.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** アプリ全体のテーマ。配信画面の黒地に合わせ、ダーク固定で緑をアクセントにする */
@Composable
fun GenkaiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF9CDBB5),
            onPrimary = Color(0xFF102B1D),
            primaryContainer = Color(0xFF254936),
            onPrimaryContainer = Color(0xFFC8F3DA),
            background = Color(0xFF101413),
            surface = Color(0xFF181E1B),
            surfaceVariant = Color(0xFF29322D),
            onSurface = Color(0xFFF0F3EF),
            onSurfaceVariant = Color(0xFFB9C4BC),
            outline = Color(0xFF536158)
        ),
        content = content
    )
}

/**
 * カメラプレビューの上に重ねる部品の色。映像の明暗に関わらず読めるよう、
 * MaterialTheme のトークンではなく半透明の固定色を使う。
 */
object OverlayColors {
    /** 情報表示・ラベルの下地 */
    val scrim = Color(0x99101413)
    /** プレビュー不可などの案内パネルの下地 (scrim より濃い) */
    val panel = Color(0xDD101413)
    /** 操作帯 (スタジアム形) の下地と縁 */
    val band = Color(0xF0181E1B)
    val bandBorder = Color(0x59FFFFFF)
    /** 丸ボタンの下地。active は ON 状態 (ミュート中など) の強調 */
    val button = Color(0xAA101413)
    val buttonDisabled = Color(0x66101413)
    val buttonActive = Color(0xFFD1433E)
    val contentDisabled = Color(0x88FFFFFF)
    /** 配信開始 / 停止ボタン */
    val start = Color(0xFF2E7D4F)
    val stop = Color(0xFFB43832)
    /** カメラ・ズーム選択のピル型ボタン */
    val selector = Color(0xCC2B2927)
    val selectorDisabled = Color(0x662B2927)
    /** 補足テキスト (経過時間・マイク名など) */
    val secondaryText = Color(0xFFD4DDD7)
}
