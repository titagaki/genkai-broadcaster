package io.github.titagaki.genkaibroadcaster.comment

import android.os.Parcel
import android.os.Parcelable

/**
 * 提供側アプリから AIDL で受け取るコメント 1 件 (`CommentEntry.aidl` の実体)。
 *
 * Parcelable は手書きにする (kotlin-parcelize プラグインを AGP 内蔵 Kotlin へ足す検証を避けるため)。
 * Parcel の並びは [ICommentSource.VERSION] で固定される契約の一部なので、
 * フィールドを増減するときは末尾追加であっても VERSION を上げる。
 *
 * @param id 提供側内で一意な ID。再 bind 後の重複送信を弾くのに使う
 * @param receivedAtMillis 提供側が受信した時刻 (epoch ms)。並べ替えの参考。表示期限の起点には使わない
 * @param author 投稿者名。無ければ null
 * @param body 本文。改行を含んでもよい (配信アプリは 1 行に潰して表示する)
 */
data class CommentEntry(
    val id: String,
    val receivedAtMillis: Long,
    val author: String?,
    val body: String
) : Parcelable {

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(id)
        dest.writeLong(receivedAtMillis)
        dest.writeString(author)
        dest.writeString(body)
    }

    companion object {
        @JvmField
        val CREATOR = object : Parcelable.Creator<CommentEntry> {
            override fun createFromParcel(source: Parcel): CommentEntry = CommentEntry(
                id = source.readString().orEmpty(),
                receivedAtMillis = source.readLong(),
                author = source.readString(),
                body = source.readString().orEmpty()
            )

            override fun newArray(size: Int): Array<CommentEntry?> = arrayOfNulls(size)
        }
    }
}
