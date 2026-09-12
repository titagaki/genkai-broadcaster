package io.github.titagaki.genkaibroadcaster.streamer

/** 送信解像度の1候補。横長を基準に持ち、縦配信では幅と高さを入れ替えて出力する */
data class Resolution(val label: String, val width: Int, val height: Int) {
    fun outputWidth(portrait: Boolean): Int = if (portrait) height else width
    fun outputHeight(portrait: Boolean): Int = if (portrait) width else height
    fun dimensions(portrait: Boolean): String = "${outputWidth(portrait)} x ${outputHeight(portrait)}"
}
