package com.livecaption

import android.content.Context

class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("livecaption", Context.MODE_PRIVATE)

    var apiKey: String
        get() = sp.getString("apiKey", "") ?: ""
        set(v) = sp.edit().putString("apiKey", v).apply()

    var workspaceId: String
        get() = sp.getString("workspaceId", "") ?: ""
        set(v) = sp.edit().putString("workspaceId", v).apply()

    var model: String
        get() = sp.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(v) = sp.edit().putString("model", v).apply()

    /** 译文目标语种，例如 zh / en */
    var targetLang: String
        get() = sp.getString("targetLang", "zh") ?: "zh"
        set(v) = sp.edit().putString("targetLang", v).apply()

    /** 源语种。仅 qwen3.5 支持指定；留空表示自动识别 */
    var sourceLang: String
        get() = sp.getString("sourceLang", "") ?: ""
        set(v) = sp.edit().putString("sourceLang", v).apply()

    /** 热词，每行一条，格式 源词=译法 */
    var phraseRaw: String
        get() = sp.getString("phrases", "") ?: ""
        set(v) = sp.edit().putString("phrases", v).apply()

    /** 字幕距离屏幕底部的距离（px） */
    var bottomMargin: Int
        get() = sp.getInt("bottomMargin", 160)
        set(v) = sp.edit().putInt("bottomMargin", v).apply()

    fun phrases(): Map<String, String> =
        phraseRaw.split('\n')
            .mapNotNull { line ->
                val i = line.indexOf('=')
                if (i <= 0) null
                else line.substring(0, i).trim() to line.substring(i + 1).trim()
            }
            .filter { it.first.isNotEmpty() && it.second.isNotEmpty() }
            .toMap()

    companion object {
        const val DEFAULT_MODEL = "qwen3.8-livetranslate-flash-realtime"
        const val MODEL_35 = "qwen3.5-livetranslate-flash-realtime"
    }
}
