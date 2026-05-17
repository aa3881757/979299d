package com.xiaoxun.redpacket

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.xiaoxun.redpacket.databinding.ActivityActivationBinding

class ActivationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityActivationBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityActivationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val deviceId = LicenseManager.getDeviceId(this)
        binding.deviceIdText.text = deviceId

        binding.btnCopyDeviceId.setOnClickListener {
            val clip = (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            clip.setPrimaryClip(ClipData.newPlainText("device_id", deviceId))
            Toast.makeText(this, R.string.device_id_copied, Toast.LENGTH_SHORT).show()
        }

        // 預填先前儲存的卡密 (若有)
        LicenseManager.getStoredCard(this)?.let { binding.cardInput.setText(it) }

        binding.btnActivate.setOnClickListener {
            val raw = binding.cardInput.text?.toString().orEmpty()
            if (raw.isBlank()) {
                showStatus(getString(R.string.activation_empty), error = true)
                return@setOnClickListener
            }
            val r = LicenseManager.activate(this, raw)
            when (r) {
                is LicenseManager.Result.Ok -> {
                    val msg = getString(R.string.activation_success,
                        LicenseManager.formatDate(r.expireDate), r.daysLeft)
                    showStatus(msg, error = false)
                    Toast.makeText(this, R.string.activation_ok_toast, Toast.LENGTH_SHORT).show()
                    // 進主畫面
                    startActivity(Intent(this, MainActivity::class.java))
                    finish()
                }
                LicenseManager.Result.MalformedCard ->
                    showStatus(getString(R.string.activation_malformed), error = true)
                LicenseManager.Result.NotForThisDevice ->
                    showStatus(getString(R.string.activation_wrong_device), error = true)
                LicenseManager.Result.Expired ->
                    showStatus(getString(R.string.activation_expired), error = true)
            }
        }

        // 顯示當前狀態
        val current = LicenseManager.checkStored(this)
        if (current is LicenseManager.Result.Ok) {
            showStatus(
                getString(R.string.activation_success,
                    LicenseManager.formatDate(current.expireDate), current.daysLeft),
                error = false
            )
        }

        // 隱藏入口：長按裝置碼開啟卡密生成器 (管理員專用)
        binding.deviceIdText.setOnLongClickListener {
            startActivity(Intent(this, GeneratorActivity::class.java))
            true
        }
    }

    private fun showStatus(text: String, error: Boolean) {
        binding.activationStatus.text = text
        binding.activationStatus.setTextColor(
            if (error) Color.parseColor("#B71C1C") else Color.parseColor("#2E7D32")
        )
    }
}
