package com.livecaption

import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 百炼 Realtime API 客户端（WebSocket 协议）。
 *
 * 要点：
 * 1. 连接后必须先发 session.update，收到 session.updated 之后才能推音频。
 * 2. 结束前必须发 session.finish 并等 session.finished，否则最后一段语音的结果会丢。
 * 3. qwen3.8 输出模态字段是 session.output_modalities；qwen3.5 是 session.modalities。
 * 4. qwen3.8 强制自动识别源语种；qwen3.5 可以通过 input_audio_transcription.language 锁定。
 */
class QwenTranslateClient(
    private val apiKey: String,
    private val workspaceId: String,
    private val model: String,
    private val targetLang: String,
    private val sourceLang: String,
    private val phrases: Map<String, String>,
    private val listener: Listener
) {

    interface Listener {
        fun onState(text: String)
        fun onSourceDelta(delta: String)
        fun onSourceCompleted(text: String)
        fun onTargetDelta(delta: String)
        fun onTargetCompleted(text: String)
        fun onFatal(message: String)
    }

    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val ready = AtomicBoolean(false)
    private val shuttingDown = AtomicBoolean(false)

    @Volatile private var socket: WebSocket? = null
    @Volatile private var finishedAt = 0L
    @Volatile private var audioMillis = 0L

    val audioSeconds: Long get() = audioMillis / 1000L
    val isReady: Boolean get() = ready.get()

    /** 上行发送队列积压字节数；持续为正且增长 = 网络跟不上，是延迟累积的直接证据 */
    val queueBytes: Long get() = socket?.queueSize() ?: 0L

    fun connect() {
        shuttingDown.set(false)
        ready.set(false)
        finishedAt = 0L

        val url = "wss://$workspaceId.cn-beijing.maas.aliyuncs.com/api-ws/v1/realtime?model=$model"
        listener.onState("正在连接百炼实时翻译…")

        socket = http.newWebSocket(
            Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $apiKey")
                .build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    listener.onState("已连接，等待会话就绪…")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    onEvent(text)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) = Unit

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    ready.set(false)
                    if (!shuttingDown.get()) {
                        listener.onFatal("连接中断：${t.message ?: t.javaClass.simpleName}")
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    ready.set(false)
                }
            }
        )
    }

    private fun onEvent(text: String) {
        val json = try {
            JSONObject(text)
        } catch (t: Throwable) {
            return
        }

        when (json.optString("type")) {
            "session.created" -> sendSessionUpdate()

            "session.updated" -> {
                ready.set(true)
                listener.onState("会话就绪，正在聆听系统音频…")
            }

            "conversation.item.input_audio_transcription.delta",
            "conversation.item.input_audio_transcription.text" ->
                listener.onSourceDelta(json.optString("delta").ifEmpty { json.optString("text") })

            "conversation.item.input_audio_transcription.completed" ->
                listener.onSourceCompleted(
                    json.optString("transcript").ifEmpty { json.optString("text") }
                )

            "response.text.delta",
            "response.audio_transcript.delta" ->
                listener.onTargetDelta(json.optString("delta"))

            "response.text.done",
            "response.audio_transcript.done" ->
                listener.onTargetCompleted(
                    json.optString("text").ifEmpty { json.optString("transcript") }
                )

            "session.finished" -> {
                finishedAt = System.currentTimeMillis()
                listener.onState("会话已结束")
            }

            "error" -> {
                // 错误详情在嵌套的 error 对象里（外层只有 event_id），之前读顶层 message
                // 读不到才退化成裸 JSON。现在优先取 code/message，弹出来的就是真实原因。
                val err = json.optJSONObject("error")
                val detail = listOfNotNull(
                    err?.optString("code")?.takeIf { it.isNotEmpty() },
                    err?.optString("message")?.takeIf { it.isNotEmpty() },
                    json.optString("message").takeIf { it.isNotEmpty() }
                ).joinToString(" ").ifEmpty { text.take(300) }
                listener.onFatal("服务端错误：$detail")
            }

            else -> if (json.has("code")) {
                listener.onFatal("服务端返回错误：" + text.take(300))
            }
        }
    }

    private fun sendSessionUpdate() {
        val translation = JSONObject().put("language", targetLang)

        if (phrases.isNotEmpty()) {
            val entries = JSONObject()
            phrases.forEach { (k, v) -> entries.put(k, v) }
            translation.put("corpus", JSONObject().put("phrases", entries))
        }

        val session = JSONObject()
        // 只输出文本：音频输出占总成本约 75%，看视频场景完全不需要译音
        if (model.contains("qwen3.5")) {
            session.put("modalities", JSONArray().put("text"))
            // 视频场景需要更激进的切句：BGM 会让默认的 1 秒静音判停永远等不到，
            // 句尾等待从 1000ms 降到 400ms 也能压低体感延迟
            session.put(
                "turn_detection",
                JSONObject()
                    .put("type", "server_vad")
                    .put("threshold", 0.4)
                    .put("silence_duration_ms", 400)
            )
        } else {
            session.put("output_modalities", JSONArray().put("text"))
            // speaker_detection 为多人对话设计（且 threshold 固定 0.5 不可调）；
            // 影视配音用声学 VAD 更对症。server_vad 是 3.8 文档中的合法取值。
            session.put(
                "audio",
                JSONObject().put(
                    "input",
                    JSONObject().put(
                        "turn_detection",
                        JSONObject().put("type", "server_vad")
                    )
                )
            )
        }
        session.put("translation", translation)

        // 锁定源语种只在 qwen3.5 上可用；3.8 强制自动识别
        if (model.contains("qwen3.5") && sourceLang.isNotBlank()) {
            session.put(
                "input_audio_transcription",
                JSONObject()
                    .put("model", "qwen3-asr-flash-realtime")
                    .put("language", sourceLang)
            )
        }

        socket?.send(
            JSONObject().put("type", "session.update").put("session", session).toString()
        )
        listener.onState("已下发会话配置")
    }

    /** 推一段 16kHz 单声道 16bit PCM */
    fun sendAudio(pcm: ByteArray, sampleCount: Int) {
        if (!ready.get()) return
        val base64 = Base64.encodeToString(pcm, Base64.NO_WRAP)
        socket?.send(
            JSONObject()
                .put("type", "input_audio_buffer.append")
                .put("audio", base64)
                .toString()
        )
        audioMillis += sampleCount * 1000L / 16000L
    }

    /**
     * 上下文轮换：音频输入 7 token/秒、最大输入 49152 token，约合 1.95 小时，
     * 所以长片要在快到上限时收尾并重开一条会话。
     */
    fun rotate(onDone: () -> Unit) {
        listener.onState("接近上下文上限，正在轮换会话…")
        audioMillis = 0L
        gracefulFinish { connect(); onDone() }
    }

    fun shutdown() {
        shuttingDown.set(true)
        gracefulFinish { }
    }

    /**
     * 暂停：优雅结束当前会话（发 session.finish 并等 session.finished），
     * 但客户端对象保留，之后可以再 connect() 恢复。停止推流 = 停止计费。
     * 与 shutdown() 的区别只在于 shuttingDown 标志：pause 后重连不会被误判成"中断"。
     */
    fun pauseSession() {
        gracefulFinish { }
    }

    /** 重置已发送音频计时，让通知栏的"发送/实际"诊断在恢复后仍然可信 */
    fun resetAudioCounter() {
        audioMillis = 0L
    }

    private fun gracefulFinish(after: () -> Unit) {
        val current = socket
        ready.set(false)
        if (current == null) {
            after()
            return
        }

        finishedAt = 0L
        val startedAt = System.currentTimeMillis()
        try {
            current.send(JSONObject().put("type", "session.finish").toString())
        } catch (_: Throwable) {
        }

        Thread {
            // 等 session.finished，最多 10 秒；服务端要先冲完缓冲尾音的识别+翻译
            // 才回 finished，3 秒经常不够 → 超时强关会被平台记成异常结束（400）
            while (System.currentTimeMillis() - startedAt < 10_000) {
                if (finishedAt > startedAt) break
                Thread.sleep(50)
            }
            try {
                current.close(1000, "finish")
            } catch (_: Throwable) {
            }
            after()
        }.start()
    }

    companion object
}
