# 小勛紅包助手 (XiaoxunRedPacket)

一個 Android APP，使用無障礙服務 + 螢幕擷取自動偵測並點擊兩種紅包按鈕：

- 黃色「去看看 >」按鈕
- 紅色金幣圖示

特色：
- ✅ 靈敏度可調 (50% ~ 100%)
- ✅ 偵測間隔可調 (200ms ~ 2000ms)
- ✅ 多尺度匹配，不同手機螢幕密度都能找到
- ✅ 簡約現代風 APP 圖示
- ✅ 透過 GitHub Actions 一鍵雲端編譯出 APK

---

## 🚀 透過瀏覽器產出 APK（不需要安裝任何工具）

整個流程都在瀏覽器中完成，5 分鐘可拿到可安裝的 APK。

### 步驟 1：上傳到 GitHub
1. 用瀏覽器登入 https://github.com 並建立一個新 repo（隨意取名，例如 `XiaoxunRedPacket`）
2. 在新 repo 頁面點 **Add file → Upload files**
3. 把本資料夾內**所有檔案與資料夾**全部拖進去（含 `app/`、`.github/`、`build.gradle` 等）
4. 拉到頁面底部按 **Commit changes**

### 步驟 2：等 GitHub 自動編譯
1. Push 完成後 GitHub Actions 會自動觸發。點上方 **Actions** 分頁
2. 點最新一筆 `Build APK` 工作流（一個藍色或綠色的勾）
3. 等大約 3~5 分鐘讓它跑完（會自動下載 Android SDK、編譯）

### 步驟 3：下載 APK
編譯成功後有**兩種**取得 APK 的方式：

**方式 A（推薦）**：到 repo 首頁右側 **Releases**，最新一筆 `build-1` 下就有 `XiaoxunRedPacket-debug.apk` 可直接下載到手機。

**方式 B**：在 Actions 那筆執行紀錄的最下方 **Artifacts** 區塊，下載 `XiaoxunRedPacket-APK.zip` 解開即是 APK。

### 步驟 4：安裝到手機
1. 把 APK 傳到手機（或直接用手機瀏覽器在 GitHub Releases 下載）
2. 點 APK 安裝。Android 會跳出「未知來源」警告，允許即可
3. 開啟「小勛紅包助手」

---

## 📱 APP 使用方式

第一次開啟時依序完成兩個授權：

1. **無障礙服務**：點主畫面「前往無障礙設定」→ 找到「小勛紅包助手」→ 開啟
2. **螢幕擷取**：點主畫面下方「開始自動點擊」時系統會跳出，按「立即開始」

之後在主畫面：
- 調整「靈敏度」滑桿（建議 80%；數字越高越嚴格）
- 調整「偵測間隔」（建議 500ms；越短越快但耗電）
- 切到要搶紅包的 APP，把畫面停在會出現紅包按鈕的頁面
- APP 偵測到時會自動點擊

按主畫面「停止」即可結束自動點擊。

---

## 🎨 替換目標圖片（重要！精準度提升關鍵）

預設內建兩張**示意圖**，做到「樣式相近就點」。如果想 100% 精準匹配你看到的那個按鈕，請：

1. 在你的手機 APP 裡截圖那兩個目標按鈕
2. 把按鈕**精準裁剪**出來（只留按鈕本身、不要背景）
3. 命名為 `target_button.png` 和 `target_coin.png`
4. 替換 repo 內 `app/src/main/res/drawable-nodpi/` 下的同名檔案
5. Commit → GitHub Actions 會自動重新編譯出新版 APK

**裁剪小技巧**：建議目標圖片大小在 60×60 ~ 300×120 像素之間，太大運算慢、太小匹配不準。

---

## 🛠 本地編譯（選用）

如果你有 Android Studio：
```bash
git clone <你的-repo-url>
cd XiaoxunRedPacket
./gradlew assembleDebug
# APK 在 app/build/outputs/apk/debug/app-debug.apk
```

---

## ⚙️ 技術細節

- 螢幕擷取：`MediaProjection` API（Android 5.0+）
- 模擬點擊：`AccessibilityService.dispatchGesture()`（Android 7.0+）
- 圖片匹配：純 Kotlin 範本匹配（粗掃 stride=4 → 精掃 stride=1），多尺度 (0.8x / 1.0x / 1.2x)，自帶中心顏色快速早退
- 最低 Android 版本：Android 7.0 (API 24)
- 簽名：debug keystore（GitHub Actions 預設）。要上架 Google Play 請改用自己的 keystore。

---

## ⚠️ 安全性與隱私

- 本 APP 只在「開始」後才擷取螢幕，按「停止」立即釋放
- 螢幕內容只在記憶體做匹配，不存任何檔案、不上傳網路
- 整個 APP 沒有任何網路權限

---

## 🐛 常見問題

| 問題 | 解法 |
|---|---|
| 點 GitHub Actions 沒看到 workflow | 把 repo 設為 Public，或在 Settings → Actions 啟用 |
| 編譯失敗 | 打開 Actions 看紅色那一步的錯誤訊息，多半是檔案路徑沒上傳完整 |
| 安裝 APK 被擋 | 在系統設定 → 安全性 → 允許未知來源 |
| 開啟無障礙後沒反應 | 把無障礙關掉再開一次；或重啟 APP |
| 偵測不到 | 把靈敏度降到 70%~75% 試試，或替換更精準的目標圖（見上面） |
| 誤點別的按鈕 | 把靈敏度提高到 88%~92% |

---

授權：MIT
