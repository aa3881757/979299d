package com.xiaoxun.redpacket

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.xiaoxun.redpacket.databinding.ActivityGeneratorBinding
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 隱藏卡密生成器 (給管理員用)
 * 只能透過長按 ActivationActivity 底部的「開發者」資訊區開啟，不在 launcher 顯示。
 */
class GeneratorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGeneratorBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGeneratorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnFillMyDevice.setOnClickListener {
            binding.genDeviceInput.setText(LicenseManager.getDeviceId(this))
        }
        binding.btnDays7.setOnClickListener { binding.genDaysInput.setText("7") }
        binding.btnDays30.setOnClickListener { binding.genDaysInput.setText("30") }
        binding.btnDays90.setOnClickListener { binding.genDaysInput.setText("90") }
        binding.btnDays365.setOnClickListener { binding.genDaysInput.setText("365") }

        binding.btnGenerate.setOnClickListener { generate() }
        binding.btnCopyGenCard.setOnClickListener { copy() }
        binding.btnShareGenCard.setOnClickListener { share() }
    }

    private fun generate() {
        val dev = binding.genDeviceInput.text?.toString()?.trim()?.uppercase(Locale.US).orEmpty()
        val days = binding.genDaysInput.text?.toString()?.trim()?.toIntOrNull() ?: 0
        if (dev.length < 8) {
            Toast.makeText(this, R.string.gen_err_device, Toast.LENGTH_SHORT).show()
            return
        }
        if (days <= 0 || days > 9999) {
            Toast.makeText(this, R.string.gen_err_days, Toast.LENGTH_SHORT).show()
            return
        }

        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, days)
        }
        val expDate: Date = cal.time
        val ymd = SimpleDateFormat("yyyyMMdd", Locale.US).format(expDate)
        val pretty = SimpleDateFormat("yyyy/MM/dd", Locale.US).format(expDate)

        val sig = computeSig(dev, ymd)
        val card = ymd + "-" + sig.substring(0,4) + "-" +
                   sig.substring(4,8) + "-" + sig.substring(8,12)

        binding.genMetaText.text = getString(R.string.gen_meta, dev, pretty, days, sig)
        binding.genCardText.text = card
        binding.resultCard.visibility = android.view.View.VISIBLE

        // 本機自我驗證一次 (僅當生給自己時才會通過 deviceId 比對)
        val verify = LicenseManager.verify(this, card)
        if (verify is LicenseManager.Result.NotForThisDevice) {
            // 預期：當你輸入別人裝置碼時會走這條，這正常
        }
    }

    private fun computeSig(dev: String, ymd: String): String {
        // 必須與 LicenseManager.computeSig 完全一致
        val raw = "$dev:$ymd:${LICENSE_SECRET()}"
        val md = MessageDigest.getInstance("SHA-256")
        val hash = md.digest(raw.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
            .substring(0, 12).uppercase(Locale.US)
    }

    /** 透過 reflection / 或直接拿 LicenseManager 暴露的方法。簡單起見用同步常數 */
    private fun LICENSE_SECRET(): String = "XiaoxunRedPacket_2026_K9p4F!8q3vL2Z"

    private fun copy() {
        val text = binding.genCardText.text?.toString().orEmpty()
        if (text.isEmpty()) return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("card", text))
        Toast.makeText(this, R.string.gen_copied, Toast.LENGTH_SHORT).show()
    }

    private fun share() {
        val text = binding.genCardText.text?.toString().orEmpty()
        if (text.isEmpty()) return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "小勛紅包助手 卡密：\n$text")
        }
        startActivity(Intent.createChooser(intent, getString(R.string.gen_share)))
    }
}
