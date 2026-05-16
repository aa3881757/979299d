package com.xiaoxun.redpacket

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 離線卡密驗證
 *
 * 卡密格式：YYYYMMDD-XXXX-XXXX-XXXX
 *   - YYYYMMDD：到期日 (含當日)
 *   - XXXX-XXXX-XXXX：SHA256(deviceId + ":" + YYYYMMDD + ":" + SECRET) 的前 12 位 hex
 *
 * 驗證流程：
 *   1. 拆解卡密 → 取出 expDate 與 sig
 *   2. 用本機 deviceId 重算 expectedSig
 *   3. expectedSig != sig → 卡密無效 (不屬於本機 / 被修改)
 *   4. today > expDate → 卡密過期
 *   5. 否則啟用，把卡密+到期日存到 SharedPreferences
 */
object LicenseManager {

    /**
     *  正式版請改成隨機字串並保密，不要 commit 到公開 repo。
     *  變更 SECRET 會讓現有所有卡密失效。
     */
    private const val SECRET = "XiaoxunRedPacket_2026_K9p4F!8q3vL2Z"

    private const val PREFS_NAME = "license"
    private const val KEY_CARD = "card"
    private const val KEY_EXP = "exp_yyyymmdd"

    sealed class Result {
        data class Ok(val expireDate: String, val daysLeft: Int) : Result()
        data object NotForThisDevice : Result()
        data object Expired : Result()
        data object MalformedCard : Result()
    }

    /** 取得目前裝置碼 (Android ID) */
    @SuppressLint("HardwareIds")
    fun getDeviceId(ctx: Context): String {
        val id = Settings.Secure.getString(
            ctx.contentResolver, Settings.Secure.ANDROID_ID
        ) ?: ""
        return if (id.isEmpty()) "UNKNOWN" else id.uppercase(Locale.US)
    }

    /** 是否已啟用 (卡密在 SharedPreferences 中 + 還沒過期 + 簽章正確) */
    fun isActivated(ctx: Context): Boolean = checkStored(ctx) is Result.Ok

    /** 檢查目前儲存的卡密狀態 */
    fun checkStored(ctx: Context): Result {
        val card = getStoredCard(ctx) ?: return Result.MalformedCard
        return verify(ctx, card)
    }

    /** 驗證一張新卡密 (純驗證，不存) */
    fun verify(ctx: Context, rawCard: String): Result {
        val card = rawCard.trim().uppercase(Locale.US)
        // 期望格式：YYYYMMDD-XXXX-XXXX-XXXX (8+1+4+1+4+1+4 = 23)
        val regex = Regex("^([0-9]{8})-([0-9A-F]{4})-([0-9A-F]{4})-([0-9A-F]{4})$")
        val m = regex.matchEntire(card) ?: return Result.MalformedCard

        val expDate = m.groupValues[1]
        val providedSig = m.groupValues[2] + m.groupValues[3] + m.groupValues[4]

        val deviceId = getDeviceId(ctx)
        val expectedSig = computeSig(deviceId, expDate)
        if (!expectedSig.equals(providedSig, ignoreCase = true)) {
            return Result.NotForThisDevice
        }

        val daysLeft = daysUntil(expDate)
        if (daysLeft < 0) return Result.Expired

        return Result.Ok(expDate, daysLeft)
    }

    /** 驗證並啟用：成功時把卡密存到 SharedPreferences */
    fun activate(ctx: Context, rawCard: String): Result {
        val r = verify(ctx, rawCard)
        if (r is Result.Ok) {
            val card = rawCard.trim().uppercase(Locale.US)
            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(KEY_CARD, card)
                .putString(KEY_EXP, r.expireDate)
                .apply()
        }
        return r
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
    }

    fun getStoredCard(ctx: Context): String? {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CARD, null)
    }

    fun getStoredExpireDate(ctx: Context): String? {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_EXP, null)
    }

    /** 卡密簽章演算法 (與生成工具一致) */
    private fun computeSig(deviceId: String, expDate: String): String {
        val raw = "$deviceId:$expDate:$SECRET"
        val md = MessageDigest.getInstance("SHA-256")
        val hash = md.digest(raw.toByteArray(Charsets.UTF_8))
        val hex = hash.joinToString("") { "%02x".format(it) }
        return hex.substring(0, 12).uppercase(Locale.US)
    }

    /** 計算離到期日還剩幾天 (負數=已過期) */
    fun daysUntil(expDateYmd: String): Int {
        val sdf = SimpleDateFormat("yyyyMMdd", Locale.US)
        sdf.timeZone = TimeZone.getDefault()
        val exp = try { sdf.parse(expDateYmd) ?: return -1 } catch (_: Exception) { return -1 }
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        val today = cal.time
        val diff = exp.time - today.time
        return (diff / (24L * 60 * 60 * 1000)).toInt()
    }

    /** 格式化日期 yyyyMMdd → yyyy/MM/dd */
    fun formatDate(ymd: String): String {
        if (ymd.length != 8) return ymd
        return "${ymd.substring(0,4)}/${ymd.substring(4,6)}/${ymd.substring(6,8)}"
    }
}
