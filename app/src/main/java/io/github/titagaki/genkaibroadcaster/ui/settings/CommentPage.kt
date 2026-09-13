package io.github.titagaki.genkaibroadcaster.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.titagaki.genkaibroadcaster.comment.CommentSource
import io.github.titagaki.genkaibroadcaster.streamer.StreamConfig

/**
 * コメントページ: 映像に載せるコメントの提供アプリを選ぶ。
 * 提供アプリは端末に入っているものを列挙する (`CommentSources.list`)。無ければ案内文だけ出す。
 *
 * @param selectedKey 保存値 (`CommentSource.key`)。null は「なし」。一覧に無い保存値は「なし」扱いで表示する
 */
@Composable
internal fun CommentPage(
    sources: List<CommentSource>,
    selectedKey: String?,
    enabled: Boolean,
    onSelect: (String?) -> Unit
) {
    SettingsSection("コメントの取得元", "配信停止中のみ変更できます") {
        val options = listOf<Pair<String?, String>>(null to "なし") + sources.map { it.key to it.label }
        val effective = selectedKey?.takeIf { key -> sources.any { it.key == key } }
        RadioOptionList(options = options, selected = effective, enabled = enabled, onSelect = onSelect)
        Text(
            if (sources.isEmpty()) {
                "コメント提供アプリが見つかりません。提供アプリをインストールすると、ここに表示されます。"
            } else {
                "配信中だけ提供アプリに接続し、届いたコメントを映像の右下に最新 ${StreamConfig.COMMENT_MAX_LINES} 件、" +
                    "${StreamConfig.COMMENT_DISPLAY_MS / 1000} 秒ずつ表示します。プレビューにも同じものが映ります。"
            },
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
