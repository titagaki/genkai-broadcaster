package com.example.hogebroadcaster.streamer

import android.media.MediaCodecInfo

/**
 * 解像度・FPS に見合った H.264 Level を選ぶ。
 *
 * H.264 Annex A の MaxMBPS (1秒あたりの最大マクロブロック数) に基づき、
 * 必要値を満たす最小の Level を返す。古いPCプレーヤー (PCRPlayer等) との
 * 互換性のため、過剰に高い Level は付けない。
 *
 * 参照テーブル (MaxMBPS):
 * - 3.0: 40,500 / 3.1: 108,000 (720p30) / 3.2: 216,000 (720p60)
 * - 4.0: 245,760 (1080p30) / 4.2: 522,240 (1080p60)
 * ※ 4.1 は MBPS 上限が 4.0 と同じため選定対象外 (上限を超える場合は 4.2)
 */
object H264Level {

    private data class Level(val maxMacroblocksPerSecond: Long, val avcLevel: Int)

    private val LEVELS = listOf(
        Level(40_500, MediaCodecInfo.CodecProfileLevel.AVCLevel3),
        Level(108_000, MediaCodecInfo.CodecProfileLevel.AVCLevel31),
        Level(216_000, MediaCodecInfo.CodecProfileLevel.AVCLevel32),
        Level(245_760, MediaCodecInfo.CodecProfileLevel.AVCLevel4),
        Level(522_240, MediaCodecInfo.CodecProfileLevel.AVCLevel42),
    )

    /**
     * @return `MediaCodecInfo.CodecProfileLevel.AVCLevel*` のいずれか
     */
    fun select(width: Int, height: Int, fps: Int): Int {
        val macroblocksPerSecond =
            ((width + 15) / 16L) * ((height + 15) / 16L) * fps
        return LEVELS.firstOrNull { macroblocksPerSecond <= it.maxMacroblocksPerSecond }
            ?.avcLevel
            ?: MediaCodecInfo.CodecProfileLevel.AVCLevel42
    }
}
