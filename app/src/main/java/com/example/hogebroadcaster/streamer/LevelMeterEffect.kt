package com.example.hogebroadcaster.streamer

import com.pedro.encoder.input.audio.CustomAudioEffect
import kotlin.math.abs

/**
 * マイク入力レベル観測用の素通しエフェクト。
 *
 * 音声データは一切加工せず返し、16bit PCM のピーク振幅だけを [peak] に記録する。
 * UI層は [StreamController.micLevel] 経由で 0.0〜1.0 に正規化された値を読む。
 */
class LevelMeterEffect : CustomAudioEffect() {

    /** 直近チャンクのピーク振幅 (0〜32768)。別スレッドから読まれる */
    @Volatile
    var peak: Int = 0
        private set

    override fun process(pcmBuffer: ByteArray): ByteArray {
        var max = 0
        var i = 0
        while (i + 1 < pcmBuffer.size) {
            // リトルエンディアン 16bit PCM → 符号付きサンプル
            val sample = (pcmBuffer[i].toInt() and 0xFF) or (pcmBuffer[i + 1].toInt() shl 8)
            val amplitude = abs(sample)
            if (amplitude > max) max = amplitude
            i += 2
        }
        peak = max
        return pcmBuffer // 加工せず素通し
    }
}
