package com.xiaoxun.redpacket

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

/**
 * v1.13 ChatGPT 橋接器
 *
 * 不做：本機 OCR、本機麻將辨識、自動讀 ChatGPT 回應
 * 只做：把當前螢幕擷取的 bitmap 存到 cache、用 FileProvider 取 URI、
 *      丟出 ACTION_SEND 分享意圖，使用者自己選 ChatGPT / Chrome / 任何 AI app。
 *
 * 觸發時機：事件觸發（浮窗按鈕、規則卡關）。
 * 完全不在背景輪詢。
 */
object ChatGptBridge {

    private const val TAG = "ChatGptBridge"
    private const val SHARE_AUTHORITY_SUFFIX = ".fileprovider"
    private const val CACHE_DIR = "ai_share"
    private const val FILE_NAME = "mahjong_advice.png"

    /** 預設 prompt：可以由呼叫端覆寫 */
    const val DEFAULT_PROMPT =
        "這是我在 WePlay 麻將的牌況截圖。請用最簡短的方式告訴我："
        // 中間沒換行，下面接續
    const val DEFAULT_PROMPT_DETAIL = """
1) 是否已聽牌？聽哪些張？
2) 若還沒聽，建議打哪一張？
3) 一句話原因即可，不用長篇大論。
"""

    /**
     * 把畫面送去 ChatGPT。
     * @param context Service / Activity context 都可
     * @param bitmap 當前螢幕（呼叫端負責 crop / 縮放）
     * @param prompt 預設用 DEFAULT_PROMPT；可帶 OCR 萃出的文字作為補充
     * @return 是否成功啟動 chooser
     */
    fun askWithScreenshot(
        context: Context,
        bitmap: Bitmap,
        prompt: String = DEFAULT_PROMPT + DEFAULT_PROMPT_DETAIL
    ): Boolean {
        return try {
            val uri = saveBitmapToShareUri(context, bitmap) ?: return false
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, prompt)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooser = Intent.createChooser(intent, "送給 AI 助手").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "askWithScreenshot failed", t)
            false
        }
    }

    /**
     * 純文字版（給 v1.14 自動 OCR 流程預留入口）：
     * 開啟 ChatGPT 網頁，把 prompt 帶入 Intent.EXTRA_TEXT，使用者貼上即可。
     */
    fun askWithTextOnly(context: Context, prompt: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, prompt)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooser = Intent.createChooser(intent, "送給 AI 助手").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "askWithTextOnly failed", t)
            false
        }
    }

    private fun saveBitmapToShareUri(context: Context, bitmap: Bitmap): Uri? {
        return try {
            val dir = File(context.cacheDir, CACHE_DIR).apply { mkdirs() }
            val file = File(dir, FILE_NAME)
            FileOutputStream(file).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 90, fos)
            }
            val authority = context.packageName + SHARE_AUTHORITY_SUFFIX
            FileProvider.getUriForFile(context, authority, file)
        } catch (t: Throwable) {
            Log.e(TAG, "saveBitmapToShareUri failed", t)
            null
        }
    }
}
