package com.livecaption

/**
 * 线性插值重采样器：单声道 16bit PCM 小端。
 *
 * 为什么需要它：AudioPlaybackCapture 最好按设备原生采样率采集（常见 48000 或 44100Hz），
 * 否则系统会在 AudioFlinger 里做一次低质重采样；而模型要求 16000Hz。
 * 所以采集用原生率、降采样这一步由我们自己控制。
 */
class PcmResampler(private val srcRate: Int, private val dstRate: Int) {

    private val ratio = srcRate.toDouble() / dstRate.toDouble()

    /** 下一个输出点在输入流中的位置（可为负，表示要用到上一块的最后一个样本） */
    private var pos = 0.0
    private var tailSample = 0
    private var hasTail = false

    fun process(input: ShortArray, length: Int): ShortArray {
        if (length <= 0) return EMPTY
        if (srcRate == dstRate) return input.copyOf(length)

        val capacity = (length / ratio).toInt() + 2
        val out = ShortArray(capacity)
        var count = 0
        var i = 0

        while (i < length - 1) {
            // 用 floor 而不是 toInt：pos 处于 (-1, 0) 时 toInt 向零取整，
            // 会把跨块边界的插值基准搞错
            val idx = Math.floor(pos).toInt()
            if (idx > length - 2) break

            val frac = pos - idx
            val a: Int = when {
                idx < 0 -> if (hasTail) tailSample else input[0].toInt()
                else -> input[idx].toInt()
            }
            val b = input[idx + 1].toInt()
            val v = a + ((b - a) * frac).toInt()
            out[count++] = v.coerceIn(-32768, 32767).toShort()

            pos += ratio
            i = idx
        }

        pos -= length.toDouble()
        // pos 可以为负（表示下一个输出点落在上一块末尾），由 tailSample 分支正确插值；
        // 不要钳位到 0——钳位会在每个块边界丢掉一个输出样本，并造成相位跳变
        tailSample = input[length - 1].toInt()
        hasTail = true

        return if (count == out.size) out else out.copyOf(count)
    }

    companion object {
        private val EMPTY = ShortArray(0)
    }
}
