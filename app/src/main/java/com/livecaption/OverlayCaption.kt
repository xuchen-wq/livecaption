package com.livecaption

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * 悬浮字幕：用 TYPE_APPLICATION_OVERLAY 叠在其他 App（含浏览器全屏视频）之上。
 *
 * 注意：部分对安全敏感的应用会调用 setHideOverlayWindows(true) 主动隐藏悬浮窗，
 * 视频类和浏览器一般不会，但遇到"字幕突然不见"时先怀疑这个。
 */
class OverlayCaption(private val context: Context) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var root: LinearLayout? = null
    private var tvSource: TextView? = null
    private var tvTarget: TextView? = null
    private var stopView: TextView? = null

    fun show(bottomMarginPx: Int) {
        if (root != null) return

        val source = TextView(context).apply {
            setTextColor(Color.parseColor("#CCFFFFFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, 0, 0, dp(2))
        }
        val target = TextView(context).apply {
            setTextColor(Color.WHITE)
            // 自动缩放字号：长句缩小到放得下为止，而不是省略号截断（API 26+ 框架原生）
            setAutoSizeTextTypeUniformWithConfiguration(12, 20, 1, TypedValue.COMPLEX_UNIT_SP)
            maxLines = 4
            setShadowLayer(6f, 0f, 1f, Color.BLACK)
        }

        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setBackgroundColor(Color.parseColor("#B3000000"))
            addView(source)
            addView(target)
            visibility = View.GONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            y = bottomMarginPx
        }

        try {
            windowManager.addView(box, params)
            root = box
            tvSource = source
            tvTarget = target
            attachDrag(box, params)
            showStopButton()
        } catch (t: Throwable) {
            root = null
            tvSource = null
            tvTarget = null
        }
    }

    /**
     * 停止按钮：单独一层悬浮窗，常驻右上角，不随字幕条隐藏。
     * 点它 → 停服务 → onDestroy 走优雅收尾（session.finish）→ 立即停止计费。
     */
    private fun showStopButton() {
        val stop = TextView(context).apply {
            text = "❙❙ 暂停"
            contentDescription = "暂停字幕"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(20).toFloat()
                setColor(Color.parseColor("#CC000000"))
                setStroke(dp(1), Color.parseColor("#66FFFFFF"))
            }
            setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener {
                context.startService(
                    Intent(context, AudioCaptureService::class.java)
                        .setAction(AudioCaptureService.ACTION_TOGGLE_PAUSE)
                )
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            dp(36),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(10)
            y = dp(48)
        }

        try {
            windowManager.addView(stop, params)
            stopView = stop
        } catch (t: Throwable) {
            stopView = null
        }
    }

    /** 由服务在暂停/继续时回调，刷新按钮文案 */
    fun setPaused(paused: Boolean) {
        stopView?.apply {
            text = if (paused) "▶ 继续" else "❙❙ 暂停"
            contentDescription = if (paused) "继续字幕" else "暂停字幕"
        }
    }

    /** 按住字幕上下拖动即可调整位置，松手后记住；位移小于 touchSlop 视为误触不移动 */
    private fun attachDrag(box: LinearLayout, params: WindowManager.LayoutParams) {
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        var downRawY = 0f
        var startMargin = 0
        var dragging = false

        box.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downRawY = event.rawY
                    startMargin = params.y
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = downRawY - event.rawY
                    if (!dragging && abs(dy) > touchSlop) dragging = true
                    if (dragging) {
                        params.y = (startMargin + dy.toInt()).coerceAtLeast(0)
                        windowManager.updateViewLayout(box, params)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) Prefs(context).bottomMargin = params.y
                    dragging = false
                }
            }
            true
        }
    }

    fun update(sourceText: String, targetText: String) {
        val box = root ?: return
        val src = tvSource ?: return
        val dst = tvTarget ?: return

        src.text = sourceText
        src.visibility = if (sourceText.isBlank()) View.GONE else View.VISIBLE
        dst.text = targetText

        box.visibility =
            if (sourceText.isBlank() && targetText.isBlank()) View.GONE else View.VISIBLE
    }

    fun hide() {
        stopView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Throwable) {
            }
        }
        stopView = null

        val box = root ?: return
        try {
            windowManager.removeView(box)
        } catch (_: Throwable) {
        }
        root = null
        tvSource = null
        tvTarget = null
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
