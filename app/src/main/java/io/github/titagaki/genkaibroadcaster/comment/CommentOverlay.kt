package io.github.titagaki.genkaibroadcaster.comment

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import com.pedro.encoder.input.gl.render.filters.`object`.ImageFilterRender

/**
 * [CommentBoard] の内容を出力寸法の透過 Bitmap に描き、RootEncoder の [ImageFilterRender] へ渡す。
 *
 * - 描き直すのは新着・期限切れ・寸法変更・フィルタ差し替えのときだけ (毎フレームではない)
 * - Bitmap は毎回新しく作る。GL スレッドが前の Bitmap を読んでいる途中に書き換えないため
 * - 空のときは 1x1 の透過 Bitmap を渡す。`setImage(null)` は内容未定義のテクスチャになるので使わない
 *   (`TextureLoader.load` が null 要素の texImage2D を飛ばす)
 * - フィルタは GL の stop で解放されるので、[attachNewFilter] でエンジン準備のたびに作り直す
 *
 * 表示は右揃えで最新 [maxLines] 行 (新しいものが下)。白文字 + 黒縁取り。上下は [position] で選ぶ。
 * 左寄せにしないのはプレビュー上の音量メーター (左下) と重ねないため。
 */
class CommentOverlay(
    maxLines: Int,
    displayMillis: Long,
    /** 出力の短辺に対する文字サイズの比 (例 1/24)。縦横で同じ大きさにするため高さではなく短辺を使う */
    private val textSizeRatio: Float
) {
    private val board = CommentBoard(maxLines, displayMillis)
    private val handler = Handler(Looper.getMainLooper())
    private val expiryRunnable = Runnable { redraw() }

    private var width = 0
    private var height = 0
    private var filter: ImageFilterRender? = null
    private var position = CommentPosition.BOTTOM

    /** 配信・プレビューの出力寸法 (縦配信なら縦長の値) を設定する */
    fun setOutputSize(width: Int, height: Int) {
        if (this.width == width && this.height == height) return
        this.width = width
        this.height = height
        redraw()
    }

    /** 表示位置を変える。配信中でも即座に描き直す */
    fun setPosition(position: CommentPosition) {
        if (this.position == position) return
        this.position = position
        redraw()
    }

    /** 新しいフィルタを作って現在の内容を載せる。呼び出し側が `setFilter` で GL に渡す */
    fun attachNewFilter(): ImageFilterRender {
        val f = ImageFilterRender()
        filter = f
        redraw()
        return f
    }

    /** エンジン解放時。以後は描き直さない */
    fun detachFilter() {
        filter = null
        handler.removeCallbacks(expiryRunnable)
    }

    fun add(entries: List<CommentEntry>) {
        board.add(entries, now())
        redraw()
    }

    fun clear() {
        board.clear()
        redraw()
    }

    private fun now(): Long = SystemClock.elapsedRealtime()

    private fun redraw() {
        handler.removeCallbacks(expiryRunnable)
        val f = filter ?: return
        if (width <= 0 || height <= 0) return
        val now = now()
        val visible = board.visible(now)
        f.setImage(if (visible.isEmpty()) emptyBitmap() else render(visible))
        board.nextExpiryAt(now)?.let { at ->
            handler.postDelayed(expiryRunnable, (at - now).coerceAtLeast(1L))
        }
    }

    private fun emptyBitmap(): Bitmap =
        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.TRANSPARENT) }

    private fun render(entries: List<CommentEntry>): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.TRANSPARENT)
        val canvas = Canvas(bitmap)
        val textSize = minOf(width, height) * textSizeRatio
        val margin = textSize * 0.5f
        val lineHeight = textSize * 1.25f
        val maxWidth = width - margin * 2

        val fill = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.textSize = textSize
            typeface = Typeface.DEFAULT_BOLD
            color = Color.WHITE
        }
        val authorFill = TextPaint(fill).apply { color = AUTHOR_COLOR }
        val stroke = TextPaint(fill).apply {
            style = Paint.Style.STROKE
            strokeWidth = textSize * 0.15f
            strokeJoin = Paint.Join.ROUND
            color = Color.BLACK
        }

        // どちらの位置でも新しいものが一番下。下寄せは下端から古い方へ、上寄せは上端から新しい方へ描く
        val metrics = fill.fontMetrics
        val bottomUp = position == CommentPosition.BOTTOM
        var baseline = if (bottomUp) height - margin - metrics.descent else margin - metrics.ascent
        val step = if (bottomUp) -lineHeight else lineHeight
        val ordered = if (bottomUp) entries.asReversed() else entries
        for (entry in ordered) {
            val author = entry.author?.trim().orEmpty()
            // 長い投稿者名で本文が消えないよう、名前は幅の 4 割までに収める
            val authorText = if (author.isEmpty()) "" else {
                TextUtils.ellipsize(author, fill, maxWidth * 0.4f, TextUtils.TruncateAt.END).toString() + ": "
            }
            val authorWidth = fill.measureText(authorText)
            val body = TextUtils.ellipsize(
                oneLine(entry.body), fill, (maxWidth - authorWidth).coerceAtLeast(0f), TextUtils.TruncateAt.END
            ).toString()
            val lineWidth = authorWidth + fill.measureText(body)
            val x = width - margin - lineWidth
            if (authorText.isNotEmpty()) {
                canvas.drawText(authorText, x, baseline, stroke)
                canvas.drawText(authorText, x, baseline, authorFill)
            }
            canvas.drawText(body, x + authorWidth, baseline, stroke)
            canvas.drawText(body, x + authorWidth, baseline, fill)
            baseline += step
        }
        return bitmap
    }

    private fun oneLine(text: String): String =
        text.replace('\r', ' ').replace('\n', ' ').trim()

    private companion object {
        /** 投稿者名の色 (本文より控えめな薄い黄) */
        const val AUTHOR_COLOR = 0xFFF2E394.toInt()
    }
}
