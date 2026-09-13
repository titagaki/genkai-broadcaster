package io.github.titagaki.genkaibroadcaster.comment

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build

/**
 * 端末に入っているコメント提供アプリ (の Service) 1 件。
 *
 * @param component bind 先。設定には [key] で保存する
 * @param label 設定画面に出す名前 (Service の `android:label`、無ければアプリ名)
 */
data class CommentSource(val component: ComponentName, val label: String) {
    val key: String get() = component.flattenToString()
}

/**
 * コメント提供アプリの列挙。契約は `docs/engineering/comment-overlay.md` §3。
 *
 * `AndroidManifest.xml` の `<queries>` に [ACTION] を宣言しているので、Android 11+ でも
 * 同じ action を intent-filter に持つ Service が見える。
 */
object CommentSources {

    /** 提供側の Service が intent-filter に持つ action */
    const val ACTION = "io.github.titagaki.genkaibroadcaster.comment.action.COMMENT_SOURCE"

    fun list(context: Context): List<CommentSource> {
        val pm = context.packageManager
        val intent = Intent(ACTION)
        val resolved: List<ResolveInfo> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentServices(intent, 0)
        }
        return resolved.mapNotNull { info ->
            val service = info.serviceInfo ?: return@mapNotNull null
            CommentSource(
                component = ComponentName(service.packageName, service.name),
                label = info.loadLabel(pm).toString().ifBlank { service.packageName }
            )
        }.sortedBy { it.label }
    }

    /** 保存値 ([CommentSource.key]) から現在も存在する提供側を引く。無ければ null */
    fun find(context: Context, key: String?): CommentSource? {
        if (key.isNullOrBlank()) return null
        val component = ComponentName.unflattenFromString(key) ?: return null
        return list(context).firstOrNull { it.component == component }
    }
}
