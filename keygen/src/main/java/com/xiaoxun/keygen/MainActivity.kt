package com.xiaoxun.keygen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val secret = "XiaoxunRedPacket_2026_K9p4F!8q3vL2Z"
    private lateinit var deviceInput: EditText
    private lateinit var daysInput: EditText
    private lateinit var resultText: TextView
    private lateinit var metaText: TextView
    private var currentCard: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "小勛卡密生成器"
        buildUi()
    }

    private fun buildUi() {
        val root = ScrollView(this).apply { setBackgroundColor(Color.rgb(255, 247, 244)) }
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(28))
        }
        root.addView(wrap)

        val title = TextView(this).apply {
            text = "🧧 小勛卡密生成器"
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(80, 20, 15))
            gravity = Gravity.CENTER
        }
        wrap.addView(title, LinearLayout.LayoutParams(-1, -2))

        val sub = TextView(this).apply {
            text = "輸入使用者裝置碼與有效天數，離線產生專屬卡密。"
            textSize = 15f
            setTextColor(Color.rgb(120, 80, 70))
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(22))
        }
        wrap.addView(sub)

        wrap.addView(label("使用者裝置碼"))
        deviceInput = EditText(this).apply {
            hint = "貼上 APP 顯示的裝置碼"
            setSingleLine(true)
            textSize = 17f
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            imeOptions = EditorInfo.IME_ACTION_NEXT
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        wrap.addView(deviceInput, LinearLayout.LayoutParams(-1, -2))

        wrap.addView(label("有效天數"))
        daysInput = EditText(this).apply {
            setText("30")
            hint = "例如 30"
            setSingleLine(true)
            textSize = 17f
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            imeOptions = EditorInfo.IME_ACTION_DONE
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        wrap.addView(daysInput, LinearLayout.LayoutParams(-1, -2))

        val chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(6))
        }
        for (d in listOf(7, 30, 90, 365)) {
            chips.addView(smallButton("$d 天") { daysInput.setText(d.toString()); generate() }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(dp(3),0,dp(3),0) })
        }
        wrap.addView(chips)

        val genBtn = Button(this).apply {
            text = "產生卡密"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setOnClickListener { generate() }
        }
        wrap.addView(genBtn, LinearLayout.LayoutParams(-1, dp(54)).apply { setMargins(0, dp(12), 0, dp(10)) })

        resultText = TextView(this).apply {
            text = "尚未產生"
            textSize = 25f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.rgb(183, 28, 28))
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(18), dp(10), dp(8))
        }
        wrap.addView(resultText, LinearLayout.LayoutParams(-1, -2))

        metaText = TextView(this).apply {
            text = ""
            textSize = 14f
            setTextColor(Color.rgb(100, 70, 65))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(14))
        }
        wrap.addView(metaText)

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(Button(this).apply { text = "複製"; setOnClickListener { copyCard() } }, LinearLayout.LayoutParams(0, dp(50), 1f).apply { setMargins(0,0,dp(5),0) })
        actions.addView(Button(this).apply { text = "分享"; setOnClickListener { shareCard() } }, LinearLayout.LayoutParams(0, dp(50), 1f).apply { setMargins(dp(5),0,0,0) })
        wrap.addView(actions)

        val note = TextView(this).apply {
            text = "格式：YYYYMMDD-XXXX-XXXX-XXXX\n與小勛紅包助手 APP 的 LicenseManager 演算法相同。"
            textSize = 13f
            setTextColor(Color.rgb(130, 90, 80))
            gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, 0)
        }
        wrap.addView(note)

        setContentView(root)
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.rgb(55, 30, 25))
        setPadding(0, dp(14), 0, dp(6))
    }

    private fun smallButton(text: String, click: () -> Unit): Button = Button(this).apply {
        this.text = text
        textSize = 13f
        setOnClickListener { click() }
    }

    private fun generate() {
        val dev = deviceInput.text?.toString()?.trim()?.uppercase(Locale.US)?.replace(" ", "").orEmpty()
        val days = daysInput.text?.toString()?.trim()?.toIntOrNull() ?: 0
        if (dev.length < 8) {
            toast("請輸入有效裝置碼（至少 8 碼）")
            return
        }
        if (days <= 0 || days > 9999) {
            toast("有效天數需為 1～9999")
            return
        }
        deviceInput.setText(dev)

        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, days)
        }
        val expDate: Date = cal.time
        val ymd = SimpleDateFormat("yyyyMMdd", Locale.US).format(expDate)
        val pretty = SimpleDateFormat("yyyy/MM/dd", Locale.US).format(expDate)
        val sig = computeSig(dev, ymd)
        currentCard = ymd + "-" + sig.substring(0, 4) + "-" + sig.substring(4, 8) + "-" + sig.substring(8, 12)
        resultText.text = currentCard
        metaText.text = "裝置碼：$dev\n到期日：$pretty（$days 天）\nSHA-256 前 12 碼：$sig"
        toast("已產生卡密")
    }

    private fun computeSig(dev: String, ymd: String): String {
        val raw = "$dev:$ymd:$secret"
        val hash = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }.substring(0, 12).uppercase(Locale.US)
    }

    private fun copyCard() {
        if (currentCard.isEmpty()) generate()
        if (currentCard.isEmpty()) return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("xiaoxun-card", currentCard))
        toast("已複製")
    }

    private fun shareCard() {
        if (currentCard.isEmpty()) generate()
        if (currentCard.isEmpty()) return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "小勛紅包助手 卡密：\n$currentCard")
        }
        startActivity(Intent.createChooser(intent, "分享卡密"))
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
