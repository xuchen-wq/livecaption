package com.livecaption

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText

class MainActivity : android.app.Activity() {

    private lateinit var prefs: Prefs
    private lateinit var settingsCard: MaterialCardView
    private lateinit var tvStatus: TextView
    private lateinit var etApiKey: TextInputEditText
    private lateinit var etWorkspace: TextInputEditText
    private lateinit var etModel: TextInputEditText
    private lateinit var etTarget: TextInputEditText
    private lateinit var etSource: TextInputEditText
    private lateinit var etPhrases: TextInputEditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(R.layout.activity_main)

        settingsCard = findViewById(R.id.cardSettings)
        tvStatus = findViewById(R.id.tvStatus)
        etApiKey = findViewById(R.id.etApiKey)
        etWorkspace = findViewById(R.id.etWorkspace)
        etModel = findViewById(R.id.etModel)
        etTarget = findViewById(R.id.etTarget)
        etSource = findViewById(R.id.etSource)
        etPhrases = findViewById(R.id.etPhrases)

        findViewById<MaterialButton>(R.id.btnStart).setOnClickListener { startCaption() }
        findViewById<MaterialButton>(R.id.btnStop).setOnClickListener {
            stopService(Intent(this, AudioCaptureService::class.java))
            toast("已停止")
        }
        findViewById<MaterialButton>(R.id.btnToggleSettings).setOnClickListener {
            settingsCard.visibility =
                if (settingsCard.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        findViewById<MaterialButton>(R.id.btnSave).setOnClickListener {
            save()
            toast("已保存")
        }

        load()
    }

    override fun onResume() {
        super.onResume()
        tvStatus.text = buildString {
            append("• API Key：")
            append(if (prefs.apiKey.isBlank()) "未配置" else "已配置")
            appendLine()
            append("• 悬浮窗权限：")
            append(if (Settings.canDrawOverlays(this@MainActivity)) "已授予" else "未授予")
            appendLine()
            append("点「开始悬浮字幕」后在系统弹窗里同意屏幕捕获，字幕会浮在屏幕底部（可拖动）。看的时候屏幕右上角常驻一个按钮，点一下暂停（暂停即停止计费），再点一下继续。")
        }
    }

    private fun load() {
        etApiKey.setText(prefs.apiKey)
        etWorkspace.setText(prefs.workspaceId)
        etModel.setText(prefs.model)
        etTarget.setText(prefs.targetLang)
        etSource.setText(prefs.sourceLang)
        etPhrases.setText(prefs.phraseRaw)
    }

    private fun save() {
        prefs.apiKey = etApiKey.text.toString().trim()
        prefs.workspaceId = etWorkspace.text.toString().trim()
        prefs.model = etModel.text.toString().trim().ifEmpty { Prefs.DEFAULT_MODEL }
        prefs.targetLang = etTarget.text.toString().trim().ifEmpty { "zh" }
        prefs.sourceLang = etSource.text.toString().trim()
        prefs.phraseRaw = etPhrases.text.toString().trim()
    }

    private fun startCaption() {
        save()

        if (prefs.apiKey.isBlank() || prefs.workspaceId.isBlank()) {
            toast("请先在「连接设置」里填写 API Key 和业务空间 ID")
            settingsCard.visibility = View.VISIBLE
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            toast("请先授予悬浮窗权限")
            requestOverlayPermission()
            return
        }

        if (!hasRequiredPermissions()) {
            requestPermissions(requiredPermissions(), REQUEST_PERMISSIONS)
            return
        }

        requestCapture()
    }

    private fun requiredPermissions(): Array<String> {
        val list = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return list.toTypedArray()
    }

    private fun hasRequiredPermissions(): Boolean = requiredPermissions().all {
        checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestOverlayPermission() {
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
        )
    }

    private fun requestCapture() {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSIONS) return
        if (hasRequiredPermissions()) {
            requestCapture()
        } else {
            toast("缺少录音权限，无法采集系统音频")
        }
    }

    @Deprecated("MediaProjection 授权结果必须用 onActivityResult 接收")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CAPTURE) return

        if (resultCode != RESULT_OK || data == null) {
            toast("未获得屏幕捕获授权")
            return
        }

        val intent = Intent(this, AudioCaptureService::class.java)
            .putExtra(AudioCaptureService.EXTRA_RESULT_CODE, resultCode)
            .putExtra(AudioCaptureService.EXTRA_RESULT_DATA, data)

        startForegroundService(intent)
        toast("已启动。切到浏览器播放视频，右上角按钮可暂停/继续")
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val REQUEST_CAPTURE = 1001
        private const val REQUEST_PERMISSIONS = 1002
    }
}
