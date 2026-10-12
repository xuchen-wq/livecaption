package com.livecaption

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioPlaybackCaptureConfiguration
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast

/**
 * 前台服务：抓取系统播放音频 -> 降到 16k 单声道 -> 推给百炼 -> 把译文画到悬浮字幕。
 *
 * 平台限制（务必知悉）：
 * - AudioPlaybackCapture 只能拿到 usage 为 MEDIA / GAME / UNKNOWN 的音频；
 *   目标 App 若声明 android:allowAudioPlaybackCapture="false"（Netflix、Disney+、
 *   Spotify 及多数正版流媒体），拿到的是静音 —— 这是系统级限制，无法绕过。
 * - Android 14+ 必须"先 enterForeground，再 getMediaProjection"，顺序反了会崩。
 */
class AudioCaptureService : Service() {

    private var projection: MediaProjection? = null
    private var record: AudioRecord? = null
    private var captureThread: Thread? = null

    @Volatile private var running = false
    @Volatile private var paused = false

    private var client: QwenTranslateClient? = null
    private var overlay: OverlayCaption? = null

    /** 采集起始墙钟，用于通知栏"发送/实际"诊断 */
    private var captureStartAt = 0L

    private val main = Handler(Looper.getMainLooper())

    /** 当前句的增量缓冲 */
    private val liveSource = StringBuilder()
    private val liveTarget = StringBuilder()

    /** 上一句的完整结果 */
    private var doneSource = ""
    private var doneTarget = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        // 服务已在运行时的暂停/继续切换（由悬浮按钮触发）
        if (intent?.action == ACTION_TOGGLE_PAUSE && client != null) {
            togglePause()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification("正在准备…"), foregroundType())

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val resultData: Intent? = intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        if (resultData == null) {
            toast("缺少屏幕捕获授权，请重新开始")
            stopSelf()
            return START_NOT_STICKY
        }

        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = manager.getMediaProjection(resultCode, resultData)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, main)

        if (projection == null) {
            toast("获取屏幕捕获会话失败")
            stopSelf()
            return START_NOT_STICKY
        }

        val prefs = Prefs(this)
        overlay = OverlayCaption(this).also { it.show(prefs.bottomMargin) }

        client = QwenTranslateClient(
            apiKey = prefs.apiKey,
            workspaceId = prefs.workspaceId,
            model = prefs.model,
            targetLang = prefs.targetLang,
            sourceLang = prefs.sourceLang,
            phrases = prefs.phrases(),
            listener = clientListener
        ).also { it.connect() }

        startCapture()
        return START_NOT_STICKY
    }

    private val clientListener = object : QwenTranslateClient.Listener {

        override fun onState(text: String) {
            main.post { updateNotification(text) }
        }

        override fun onSourceDelta(delta: String) {
            if (delta.isEmpty()) return
            main.post {
                liveSource.append(delta)
                render()
            }
        }

        override fun onSourceCompleted(text: String) {
            if (text.isBlank()) return
            main.post {
                doneSource = text
                liveSource.setLength(0)
                render()
            }
        }

        override fun onTargetDelta(delta: String) {
            if (delta.isEmpty()) return
            main.post {
                liveTarget.append(delta)
                render()
            }
        }

        override fun onTargetCompleted(text: String) {
            main.post {
                // 译文优先用最终结果；若服务端未回最终字段则退化为增量拼接结果
                doneTarget = text.ifBlank { liveTarget.toString() }
                liveTarget.setLength(0)
                render()
            }
        }

        override fun onFatal(message: String) {
            Log.w(TAG, message)
            main.post {
                toast(message)
                stopSelf()
            }
        }
    }

    /**
     * 暂停 = 停止向服务端推音频（不计费），但保留悬浮窗和前台服务；
     * 继续 = 重开一条会话继续推。会话是按时长/上下文计费的，
     * 所以暂停期间不能让连接空挂着 —— 直接正常收尾最干净。
     */
    private fun togglePause() {
        paused = !paused
        val active = client
        if (paused) {
            active?.pauseSession()
        } else {
            active?.resetAudioCounter()
            active?.connect()
            captureStartAt = System.currentTimeMillis()
        }
        overlay?.setPaused(paused)
        updateNotification(if (paused) "已暂停 · 不计费，点右上角继续" else "正在采集系统音频…")
    }

    private fun render() {
        val target = if (liveTarget.isNotEmpty()) liveTarget.toString() else doneTarget
        val source = if (liveSource.isNotEmpty()) liveSource.toString() else doneSource
        overlay?.update(rolling(source), rolling(target))
    }

    /**
     * 滚动窗口：只保留最后 maxChars 个字符，按标点找切点，找不到就硬切。
     * 视频有 BGM/持续音效时服务端 VAD 可能迟迟不给 completed，没有这层收口
     * live 缓冲会无限增长，显示层怎么调都必然截断。
     */
    private fun rolling(text: String, maxChars: Int = 120): String {
        if (text.length <= maxChars) return text
        val start = text.length - maxChars
        var i = start
        while (i < text.length && text[i] !in "。？！；.!?;") i++
        return text.substring(if (i < text.length) i + 1 else start)
    }

    private fun startCapture() {
        val activeProjection = projection ?: return
        running = true

        captureThread = Thread {
            var recorder: AudioRecord? = null
            try {
                val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val nativeRate = audioManager
                    .getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
                    ?.toIntOrNull() ?: 48000

                val captureConfig = AudioPlaybackCaptureConfiguration.Builder(activeProjection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()

                val minBuffer = AudioRecord.getMinBufferSize(
                    nativeRate,
                    AudioFormat.CHANNEL_IN_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(nativeRate / 2 * 4)

                recorder = AudioRecord.Builder()
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(nativeRate)
                            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                            .build()
                    )
                    .setBufferSizeInBytes(minBuffer * 2)
                    .setAudioPlaybackCaptureConfig(captureConfig)
                    .build()

                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    main.post {
                        toast("AudioRecord 初始化失败，请确认已授予录音权限")
                        stopSelf()
                    }
                    return@Thread
                }

                record = recorder
                recorder.startRecording()
                updateNotification("正在采集系统音频 ${nativeRate}Hz → 16000Hz")

                val resampler = PcmResampler(nativeRate, TARGET_RATE)
                val readBuffer = ShortArray(8192)
                val mono = ShortArray(8192)
                val frame = ShortArray(FRAME_SAMPLES)
                val frameBytes = ByteArray(FRAME_SAMPLES * 2)
                var filled = 0

                // 延迟诊断：每 5 秒把上行队列和发送速率刷到通知栏，
                // 用来区分"网络积压"（Q 持续上涨）与"服务端变慢"（Q≈0 但延迟照涨）
                captureStartAt = System.currentTimeMillis()
                var lastDiagAt = 0L

                while (running) {
                    if (paused) {
                        // 暂停中：继续把录音缓冲读掉（防止 AudioRecord 溢出），但不推给服务端
                        recorder.read(readBuffer, 0, readBuffer.size)
                        Thread.sleep(50)
                        continue
                    }

                    val read = recorder.read(readBuffer, 0, readBuffer.size)
                    if (read <= 0) continue

                    val frames = read / 2
                    if (frames <= 0) continue

                    var i = 0
                    while (i < frames) {
                        mono[i] = ((readBuffer[i * 2].toInt() + readBuffer[i * 2 + 1].toInt()) / 2).toShort()
                        i++
                    }

                    val downsampled = resampler.process(mono, frames)
                    var offset = 0
                    while (offset < downsampled.size) {
                        val take = minOf(FRAME_SAMPLES - filled, downsampled.size - offset)
                        System.arraycopy(downsampled, offset, frame, filled, take)
                        filled += take
                        offset += take

                        if (filled == FRAME_SAMPLES) {
                            // 不要因为检测到静音就跳过发送 —— 服务端 VAD 正是靠静音判定句尾
                            var k = 0
                            while (k < FRAME_SAMPLES) {
                                val s = frame[k].toInt()
                                frameBytes[k * 2] = (s and 0xFF).toByte()
                                frameBytes[k * 2 + 1] = ((s shr 8) and 0xFF).toByte()
                                k++
                            }
                            client?.sendAudio(frameBytes, FRAME_SAMPLES)
                            filled = 0
                        }
                    }

                    val now = System.currentTimeMillis()
                    if (now - lastDiagAt > 5000) {
                        lastDiagAt = now
                        val queueKb = (client?.queueBytes ?: 0L) / 1024
                        val sentSec = client?.audioSeconds ?: 0L
                        val wallSec = (now - captureStartAt) / 1000
                        updateNotification("Q=${queueKb}KB · 发送${sentSec}s/实际${wallSec}s")
                        Log.d(TAG, "diag queue=${queueKb}KB sent=${sentSec}s wall=${wallSec}s")
                    }

                    val active = client
                    if (active != null && active.isReady && active.audioSeconds > SESSION_ROTATE_SECONDS) {
                        active.rotate { }
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "采集线程退出", t)
            } finally {
                try {
                    recorder?.stop()
                } catch (_: Throwable) {
                }
                try {
                    recorder?.release()
                } catch (_: Throwable) {
                }
            }
        }.also { it.start() }
    }

    override fun onDestroy() {
        running = false
        try {
            captureThread?.join(2000)
        } catch (_: Throwable) {
        }
        try {
            record?.stop()
        } catch (_: Throwable) {
        }
        // 走优雅收尾：等 session.finished，避免丢掉最后一段译文
        client?.shutdown()
        try {
            projection?.stop()
        } catch (_: Throwable) {
        }
        overlay?.hide()
        super.onDestroy()
    }

    private fun foregroundType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else {
            0
        }

    private fun buildNotification(text: String): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "实时字幕", NotificationManager.IMPORTANCE_LOW)
            )
        }

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, AudioCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("实时字幕运行中")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    null,
                    "停止",
                    stopIntent
                ).build()
            )
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val TAG = "LiveCaption"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val ACTION_STOP = "com.livecaption.STOP"

        /** 暂停/继续：暂停时停止推流（不计费），但服务与悬浮窗保留 */
        const val ACTION_TOGGLE_PAUSE = "com.livecaption.TOGGLE_PAUSE"

        private const val CHANNEL_ID = "livecaption"
        private const val NOTIFICATION_ID = 1001

        /** 模型要求 16kHz */
        private const val TARGET_RATE = 16000

        /** 20ms @16k，比官方示例的 100ms 小块，能省下几十毫秒上行延迟 */
        private const val FRAME_SAMPLES = 320

        /** 上下文随时间累积会让模型越跑越慢（实测延迟从 400ms 涨到 1-2 秒），15 分钟轮换一次 */
        private const val SESSION_ROTATE_SECONDS = 900L
    }
}
