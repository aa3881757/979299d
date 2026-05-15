package com.xiaoxun.redpacket

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.xiaoxun.redpacket.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var projectionManager: MediaProjectionManager

    private var sensitivity: Float = 0.65f
    private var intervalMs: Long = 120L

    private val pollHandler = Handler(Looper.getMainLooper())
    private val pollRunnable = object : Runnable {
        override fun run() {
            refreshAccessibilityBadge()
            pollHandler.postDelayed(this, 1000)
        }
    }

    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val data = result.data!!
            val svc = Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_START
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenCaptureService.EXTRA_DATA, data)
                putExtra(ScreenCaptureService.EXTRA_SENSITIVITY, sensitivity)
                putExtra(ScreenCaptureService.EXTRA_INTERVAL_MS, intervalMs)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svc)
            } else {
                startService(svc)
            }
            updateUiRunning(true)
        } else {
            Toast.makeText(this, R.string.toast_capture_denied, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        binding.btnOpenAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.btnOpenOverlay.setOnClickListener { openOverlaySettings() }

        binding.sensitivitySlider.addOnChangeListener { _, value, _ ->
            sensitivity = value / 100f
            binding.sensitivityValue.text = "${value.toInt()}%"
            ScreenCaptureService.updateConfig(this, sensitivity, intervalMs)
        }
        binding.intervalSlider.addOnChangeListener { _, value, _ ->
            intervalMs = value.toLong()
            binding.intervalValue.text = "${value.toInt()} ms"
            ScreenCaptureService.updateConfig(this, sensitivity, intervalMs)
        }

        binding.btnStartStop.setOnClickListener {
            if (ScreenCaptureService.isRunning) {
                stopService(Intent(this, ScreenCaptureService::class.java))
                updateUiRunning(false)
            } else {
                if (!isAccessibilityEnabled()) {
                    Toast.makeText(this, R.string.toast_need_accessibility, Toast.LENGTH_LONG).show()
                    binding.statusText.setText(R.string.status_need_accessibility)
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    return@setOnClickListener
                }
                if (!canDrawOverlay()) {
                    Toast.makeText(this, R.string.toast_need_overlay, Toast.LENGTH_LONG).show()
                    openOverlaySettings()
                    return@setOnClickListener
                }
                captureLauncher.launch(projectionManager.createScreenCaptureIntent())
            }
        }

        refreshAccessibilityBadge()
    }

    override fun onResume() {
        super.onResume()
        updateUiRunning(ScreenCaptureService.isRunning)
        refreshAccessibilityBadge()
        pollHandler.removeCallbacks(pollRunnable)
        pollHandler.post(pollRunnable)
    }

    override fun onPause() {
        super.onPause()
        pollHandler.removeCallbacks(pollRunnable)
    }

    private fun updateUiRunning(running: Boolean) {
        binding.btnStartStop.setText(if (running) R.string.btn_stop else R.string.btn_start)
        binding.statusText.setText(
            if (running) R.string.status_running else R.string.status_idle
        )
    }

    /** 更新無障礙開啟狀態 badge */
    private fun refreshAccessibilityBadge() {
        val on = isAccessibilityEnabled()
        binding.accessibilityStatusBadge.setText(
            if (on) R.string.status_on else R.string.status_off
        )
        binding.accessibilityStatusBadge.setTextColor(
            if (on) Color.parseColor("#2E7D32") else Color.parseColor("#B71C1C")
        )
    }

    private fun isAccessibilityEnabled(): Boolean {
        val expectedId = "$packageName/${AutoClickService::class.java.name}"
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabled = Settings.Secure.getInt(
            contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED, 0
        ) == 1
        if (!enabled) return false
        val list = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        if (TextUtils.isEmpty(list)) return false
        return list.split(':').any { it.equals(expectedId, ignoreCase = true) }
    }

    private fun canDrawOverlay(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else true
    }

    private fun openOverlaySettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            startActivity(Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            ))
        }
    }
}
