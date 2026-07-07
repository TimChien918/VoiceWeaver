<div align="center">

# 🗣️ VoiceWeaver

**為失語症、語言復健與臨場表達困難者打造的 AI 溝通輔助生態系**

把零碎的詞 ＋ 所在地點 ＋ 相機看到的東西，交給 AI 重組成自然句；
AI 全判讀語音復健評分；鼻控無障礙操作；自己的聲音自己合成。

![Platform](https://img.shields.io/badge/Android-7.0%2B-3DDC84?logo=android&logoColor=white)
![Web](https://img.shields.io/badge/網頁版-Live-0a84ff)
![Desktop](https://img.shields.io/badge/語音中心-macOS%20%7C%20Windows-8a2be2)
![i18n](https://img.shields.io/badge/介面語言-繁中%20%7C%20EN%20%7C%20日%20%7C%20한-ff9500)
![Kotlin](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?logo=kotlin&logoColor=white)

[🌐 網頁版 Live](https://timchien918.github.io/VoiceWeaver-Web/) ·
[📦 下載安裝檔（Releases）](https://github.com/TimChien918/VoiceWeaver/releases) ·
[網頁版原始碼](https://github.com/TimChien918/VoiceWeaver-Web) ·
[GPT-SoVITS 模型手冊](docs/GPT_SOVITS_MODEL_GUIDE.md)

</div>

---

## 三個入口，一套生態

| 入口 | 說明 | 取得方式 |
| --- | --- | --- |
| **📱 Android App** | 完整功能：離線推理、相機、定位、鼻控、語音復健、GPT-SoVITS 角色語音 | [Releases → `v1.3.0-android`](https://github.com/TimChien918/VoiceWeaver/releases/tag/v1.3.0-android) 或自行建置 |
| **🌐 網頁輕量版** | 純瀏覽器免安裝；可把語音／生圖／文字運算丟給自己的電腦跑 | [直接開啟](https://timchien918.github.io/VoiceWeaver-Web/) |
| **🖥 語音中心（macOS / Windows）** | 桌面 App：GPT-SoVITS 語音合成、SD-Turbo 生圖、Qwen 文字推論、聲音模型管理與手機同步 | [Releases → `v1.2.1-mac`](https://github.com/TimChien918/VoiceWeaver/releases/tag/v1.2.1-mac) / [`v1.1.0-win`](https://github.com/TimChien918/VoiceWeaver/releases/tag/v1.1.0-win) |

- 手機與網頁用**同一個 Google 帳號**登入即雙向同步 API 金鑰、供應商清單與設定。
- 三個入口的介面都支援**繁體中文／English／日本語／한국어**一鍵切換；切換語言會連動語音模型篩選與朗讀語言。

## 功能總覽

| 分類 | 重點功能 |
| --- | --- |
| **語音重組** | 語音／文字輸入、碎詞整理、AI 重組自然句、TTS 朗讀、多語朗讀（中英日韓）、複製分享、我的最愛 |
| **AAC 圖卡** | 分類搜尋、組合句、情境推薦、大圖卡模式、自訂金額、自訂圖卡（JSON）、強意圖保護 |
| **語音復健** | AI 全判讀（語意／流暢／語氣，非字元差異）、**每次評分獨立且低溫求穩**、錯誤字高亮、整段分句逐句練、streak 連續天數、每日提醒 |
| **成績單** | Firestore 同步日誌、趨勢折線圖、正向情緒統計、CSV／PDF 匯出、Telegram 排程推送 |
| **定位情境** | GPS、住家錨點、Geocoder、Overpass、離線 POI（台灣／美國） |
| **相機辨識** | 前／後攝影機、AI 認物品、加入情境 |
| **無障礙** | 鼻控游標（MediaPipe 鼻尖追蹤、靈敏度即時調整）、dwell 停留點選、超大字、高對比 |
| **安全通報** | 危機關鍵字偵測 → GPS ＋ Telegram 即時通知家人、視訊通話、語音安全鎖定 |
| **角色語音** | GPT-SoVITS 自訂聲音（電腦跑、手機／網頁連線），依介面語言自動篩選對應語言的模型 |

## 快速開始（自行建置 Android App）

```bash
# 1. 取得原始碼
git clone https://github.com/TimChien918/VoiceWeaver.git
cd VoiceWeaver

# 2. 金鑰：複製範例並填入（.env 已被 gitignore，切勿提交）
cp .env.example .env
#    GEMINI_API_KEY / GROQ_API_KEY / OPENROUTER_API_KEY
#    COHERE_API_KEY / GOOGLE_PLACES_API_KEY / TELEGRAM_BOT_TOKEN

# 3. 雲端同步（選用）：到 Firebase Console 下載 google-services.json 放進 app/
#    （已被 gitignore，不入版控）

# 4. 建置（需要 JDK 17+；Android Studio 內建 JBR 最方便）
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleDebug      # 打包 Debug APK
./gradlew :app:installDebug       # 安裝到已連線裝置
```

> **需要 JDK 17+**。系統 JDK 8 會出現 `Gradle requires JVM 17 or later`，請改用 Android Studio 內建 JBR。

## 🖥 語音中心（macOS / Windows 桌面 App）

自帶 Python 環境的桌面應用（Tkinter），下載 `.dmg`／`.exe` 安裝即用，首次開啟自動解壓環境（約 2GB）。
一個視窗管理三個 AI 服務＋手機連線＋聲音模型：

| 埠 | 服務 | 說明 |
| --- | --- | --- |
| `9880` | 語音合成（GPT-SoVITS） | 需自備聲音模型；手機／網頁的角色語音由此輸出 |
| `9881` | 本機生圖（SD-Turbo） | 首次生圖自動下載模型（免金鑰） |
| `9882` | 文字推論（Qwen2.5-1.5B） | 句子重組／復健評分的「電腦接手」來源 |
| `9879` | 橋接（含 CORS） | 手機語言同步 ＋ 網頁版的語音／生圖／文字代理 |

**聲音模型管理**：2 欄宮格清單、語言篩選（中／英／日／韓，跟手機介面語言自動連動）、單個與批量資料夾匯入、完整欄位編輯（.ckpt／.pth／參考音檔／參考文字，換音檔自動帶入逐字稿）、語言自動偵測（依參考文字的文字種類：諺文→韓、假名→日、漢字→中、拉丁→英）。

**手機連線三選一**：
| 方式 | 設定 |
| --- | --- |
| 有線 USB（推薦） | 插線即可，自動 `adb reverse`；首次同步後自動開啟無線並記住 IP |
| 無線 Wi-Fi | App 填 `http://<電腦IP>:9880`，或掃語音中心的 QR Code 自動填入 |
| Tailscale（外網） | 填 `100.x` 位址；4G／5G 也能連 |

**聲音模型隨身同步**：模型放手機 `/sdcard/VoiceWeaver/voice_models/`，USB 連接時電腦自動雙向同步（聯集、防誤刪安全閥）。換一台電腦，插上手機就能把聲音帶過去。

> 打包腳本：`tools/dmg/build_dmg.sh`（macOS）、`tools/win/build_win.ps1`（Windows）。安全網會強制剔除任何模型權重，**安裝檔絕不內含聲音模型**。

## 🌐 網頁版（免安裝）

- 純瀏覽器＋自備 API 金鑰即可用：重組、AAC、復健評分、成績單、生圖。
- **讓電腦幫忙跑運算**：設定頁啟用後，語音（GPT-SoVITS 角色聲音）、生圖（SD-Turbo）、文字（Qwen）都交給你的電腦，連不上自動退回雲端／瀏覽器。
  - 同一台電腦：免設定，直接「偵測連線」（走 `127.0.0.1:9879`）。
  - 遠端：電腦跑 `tailscale serve --bg 9879`，網頁填 `https://主機.xxx.ts.net`。
  - ⚠️ 受瀏覽器混合內容限制，HTTPS 網頁無法連區網 `http://192.168.x.x`——僅同機與 Tailscale 兩種連法。

## 技術架構

- **Android**：Kotlin + Jetpack Compose；Room（歷史／復健）＋ DataStore（設定）；WorkManager（提醒／推送）；CameraX + ML Kit；Vosk（離線 STT，逐字信心度）
- **雲端**：Firebase Auth（Google／匿名）＋ Firestore（金鑰、設定、復健日誌，與網頁版共用結構）
- **多語系**：strings.xml 四語資源 ＋ `I18n.tr()` 執行期翻譯層（覆蓋 lambda／ViewModel 內的動態訊息）；網頁 `data-i18n` 屬性驅動；語音中心 `I18N` 字典＋標題列語言切換（記住選擇）
- **推理分層**（依裝置能力與網路自動路由）：

  | 層級 | 引擎 | 說明 |
  | --- | --- | --- |
  | 電腦接手 | `OffloadManager` → 語音中心 9880/9881/9882 | USB／區網／Tailscale，實測 RTT 分級 |
  | 雲端 | `CloudInferenceEngine` | Gemini / Groq / OpenRouter / Cohere…，多金鑰固定順序備援 |
  | 裝置端 | `MediaPipeLLMEngine` | Gemma 3n E2B / Qwen（MediaPipe .task） |
  | 備援 | `RegexTemplateEngine` | 規則模板，零依賴 |

- **復健評分（四層備援、刻意求穩）**：電腦 Qwen → 雲端 LLM → 裝置端 Gemma → Levenshtein。
  每次評估**完全獨立**（提示詞明確禁止參考先前分數）、低溫取樣（0.1–0.2）、供應商固定順序不輪替——同樣的表現拿到一致的分數。

## 設定項目（Android）

| 設定 | 預設 | 說明 |
| --- | --- | --- |
| `language` | 繁體中文 | 介面＋朗讀語言（繁中／EN／日／韓），連動聲音模型篩選 |
| `theme` / `highContrast` | 科技風 / off | 視覺風格、高對比（黑底黃文） |
| `fontScale` | 1.0 | 全域字體倍率（0.8–1.8） |
| `noseSensitivity` | 1.0 | 鼻控靈敏度（0.5–2.0，即時生效） |
| `dailyReminderEnabled` / `Hour` | off / 9 | 每日復健提醒 |
| `reportPushFrequency` | none | 成績單 Telegram 推送（none／daily／weekly） |
| `gptSovitApiBaseUrl` | — | 語音中心位址（可存多台電腦、掃 QR 自動填） |
| `emergencyChatId` | 空白 | Telegram 緊急通報接收者（不內建預設值） |
| `customAacItems` | 空 | 自訂 AAC 圖卡（JSON） |

## 權限（Android）

| 權限 | 用途 |
| --- | --- |
| 麥克風 | 語音輸入、跟讀錄音 |
| 定位 | GPS 情境、住家錨點、緊急通報 |
| 相機 | 前／後鏡頭辨識、鼻控追蹤 |
| 網路 | 雲端 AI、語音中心連線、Telegram |
| 通知 | 每日提醒、成績單推送 |
| 儲存／媒體 | 參考音檔匯入、CSV／PDF 匯出 |

## 專案結構

```text
app/src/main/java/com/example/
├── ui/
│   ├── VoiceWeaverScreen.kt      語音重組（最愛、多語朗讀、複製分享）
│   ├── AacScreen.kt              AAC 圖卡（大圖卡模式、自訂圖卡）
│   ├── RehabScreen.kt            語音復健（AI 評分、錯誤高亮、整段練習）
│   ├── report/ReportScreen.kt    成績單（趨勢圖、CSV/PDF 匯出）
│   ├── CrisisConversationWindow  危機介入（視訊、語音訊息、家人指令）
│   ├── I18n.kt                   執行期翻譯層（動態訊息四語）
│   └── settings/ + theme/        設定、進階設定、AppPalette
├── ai/
│   ├── VoiceWeaverCore.kt        核心 ViewModel + InferenceEngine 介面
│   ├── CloudInferenceEngine.kt   雲端推理（電腦接手 → 雲端 → fallback）
│   ├── MediaPipeLLMEngine.kt     裝置端 Gemma/Qwen
│   ├── OffloadManager.kt         電腦接手（連線分級、/reconstruct 代理）
│   ├── LocalSovitsHelper.kt      GPT-SoVITS 連線與模型切換
│   └── SpeechToTextManager.kt    Vosk 離線 STT
├── work/                          WorkManager（提醒、推送、匯出）
└── MainActivity.kt

app/src/main/assets/
├── nose_tracker.html   鼻控 WebView（MediaPipe Face Mesh，四語狀態訊息）
└── poi/                離線 POI（台灣／美國）

tools/
├── GPT-SoVITS/
│   ├── voice_center_app.py     語音中心 GUI（Mac/Win 共用，四語介面）
│   ├── model_setup_server.py   模型管理後端（掃描、匯入、歷史）
│   ├── text_infer_server.py    Qwen 文字推論（9882）
│   ├── image_gen_server.py     SD-Turbo 生圖（9881）
│   └── sync_voice_models.py    手機↔電腦雙向模型同步
├── dmg/build_dmg.sh    macOS .dmg 打包（權重安全網）
└── win/build_win.ps1   Windows 打包
```

## 隱私與授權

- **不內建任何語音模型／角色音色**：聲音由使用者自行匯入有權使用的素材；安裝檔打包時安全網強制剔除權重。
- **金鑰不入版控**：`.env` 與 `app/google-services.json` 已被 `.gitignore`；API 金鑰只存在使用者裝置與其個人 Firestore。
- **聲紋屬生物特徵**：啟用 GPT-SoVITS／匯入錄音前需勾選知情同意（聲音主體同意、僅個人非商業溝通用途、不得冒充他人、不上傳雲端）。
- **危機通報**：畫面與聲音僅傳給使用者自行設定的家人 Telegram，不經任何第三方伺服器。
- **第三方授權**：[GPT-SoVITS](https://github.com/RVC-Boss/GPT-SoVITS) 為 **AGPL-3.0**，`.dmg`／`.exe` 散布時隨附其 `LICENSE`。
- 本專案程式碼本身尚未公開授權（保留所有權利）；如需重用請先聯絡作者。
