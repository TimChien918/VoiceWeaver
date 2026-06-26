<div align="center">

# 🗣️ VoiceWeaver

**為失語症、語言復健與臨場表達困難者打造的 AI 溝通輔助 App**

把零碎的詞 ＋ 所在地點 ＋ 相機看到的東西，交給 AI 重組成自然句；AI 評分語音復健；鼻控無障礙操作。

![Platform](https://img.shields.io/badge/platform-Android%207.0%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?logo=kotlin&logoColor=white)
![minSdk](https://img.shields.io/badge/minSdk-24-blue)
![Web companion](https://img.shields.io/badge/網頁版-Live-0a84ff)

[🌐 網頁版 Live Demo](https://timchien918.github.io/VoiceWeaver-Web/) ·
[網頁版原始碼](https://github.com/TimChien918/VoiceWeaver-Web) ·
[GPT-SoVITS 模型手冊](docs/GPT_SOVITS_MODEL_GUIDE.md)

</div>

---

VoiceWeaver 有兩個版本，用同一個 Google 帳號登入即可雙向同步 API 金鑰與設定：

| 版本 | 說明 | 連結 |
| --- | --- | --- |
| **Android App**（本專案） | 完整功能：離線推理、相機、定位、鼻控、語音復健、GPT-SoVITS | — |
| **網頁輕量版** | 純瀏覽器／API，免安裝，可直接用 | [Live Demo](https://timchien918.github.io/VoiceWeaver-Web/) |

## 目錄

- [功能總覽](#功能總覽)
- [快速開始](#快速開始)
- [技術架構](#技術架構)
- [核心流程](#核心流程)
- [GPT-SoVITS 自訂語音（電腦端）](#gpt-sovits-自訂語音電腦端)
- [設定項目](#設定項目)
- [權限](#權限)
- [專案結構](#專案結構)
- [隱私與授權](#隱私與授權)

## 功能總覽

| 分類 | 重點功能 |
| --- | --- |
| **語音重組** | 語音／文字輸入、碎詞整理、AI 重組自然句、TTS 朗讀、多語朗讀（中英日韓）、一鍵複製分享、我的最愛快捷 |
| **AAC 圖卡** | 分類搜尋、組合句、情境推薦、自訂金額、自訂圖卡（JSON 格式）、強意圖保護 |
| **語音復健** | AI 全判讀（語氣/斷句/語義，非字元差異）、錯誤字高亮、整段分句逐句練習、streak 連續天數、每日定時提醒、歷史統計 |
| **成績單** | Firestore 同步復健日誌、趨勢折線圖、連續天數、正向情緒統計、CSV／PDF 匯出、Telegram 排程推送 |
| **定位情境** | 手動地點、GPS、住家錨點、Geocoder、Overpass、離線 POI（台灣／美國） |
| **相機辨識** | 前／後攝影機、AI 認物品、加入情境 |
| **雲端同步** | Firebase 登入，API 金鑰／供應商清單與網頁版即時互通；Telegram 設定雲端保存 |
| **無障礙** | 鼻控（靈敏度即時調整面板）、超大字體、高對比模式（黑底黃文）、停留點選（dwell）、字體倍率、TTS 語速 |
| **安全通報** | 危機關鍵字偵測、GPS 定位、Telegram 即時通知 |
| **GPT-SoVITS** | 電腦端 API server、USB／Wi-Fi 連線、自訂語音、中英自動切換 |

## 快速開始

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

# 4. 建置（需要 JDK 17+；Android Studio 內建 JBR 21 最方便）
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleDebug      # 打包 Debug APK
./gradlew :app:installDebug       # 安裝到已連線裝置
```

> **需要 JDK 17+**。用系統 JDK 8 會出現 `Gradle requires JVM 17 or later`，請改用 Android Studio 內建 JBR。

## 技術架構

- **語言／UI**：Kotlin + Jetpack Compose
- **資料**：Room（歷史／復健）、DataStore（設定）
- **雲端**：Firebase Auth（Google／匿名）+ Firestore（金鑰、設定、復健日誌與網頁版共用結構）
- **背景任務**：WorkManager（每日復健提醒、成績單排程推送）
- **感測**：CameraX + ML Kit（影像標籤）、FusedLocation（GPS）、SpeechRecognizer（語音轉文字）
- **推理分層**（依裝置能力與網路自動選擇）：

  | 層級 | 引擎 | 說明 |
  | --- | --- | --- |
  | 電腦端 offload | `OffloadManager` → GPT-SoVITS / 電腦 LLM | 走區域網路 |
  | 雲端 | `CloudInferenceEngine` | Groq / OpenRouter / Cohere / Gemini |
  | 裝置端 AI | `MediaPipeLLMEngine` | Gemma 3n E2B / Qwen（MediaPipe） |
  | 備援 | `RegexTemplateEngine` | 規則模板，零依賴 |

- **語音復健評分四層備援**：電腦 offload → 雲端 LLM → 裝置端 Gemma → Levenshtein（所有引擎均實作 `InferenceEngine.scoreRehab()`）

## 核心流程

### 語音重組

1. 使用者輸入碎詞（手動、語音、AAC 圖卡皆可）。
2. App 整合碎詞 ＋ 地點 ＋ 相機標籤。
3. 安全模組先攔截高風險文字。
4. 推理引擎依設定路由，輸出自然句。
5. TTS 朗讀，可選多語言（中英日韓）；結果可複製、分享、加入最愛。

### 語音復健（AI 全判讀）

1. 選目標句：手動輸入、AI 推薦、或開啟「整段分句」逐句練習（自動切分並顯示進度）。
2. 播放示範 → 使用者跟讀 → App STT 錄音轉文字。
3. **AI 語義評分**：所有引擎（含離線 Gemma）均透過 `scoreRehab()` 介面，依語氣、斷句、語義全面判讀，而非純字元差異。
4. 結果顯示分數、AI 評語、錯誤字高亮（紅框標示漏讀詞）。
5. 紀錄推送 Firestore，連續天數（streak）自動計算；WorkManager 在指定時段發送提醒通知。

### AAC 圖卡

- 分類涵蓋：商品、飲食、金額、動作、需求、人物、地點、醫療、時間、問句、心情、緊急。
- 支援自訂圖卡（設定頁填入 JSON：`[{"label":"…","emoji":"…","category":"…"}]`）。
- 組合後可朗讀、重組成自然句、或保存到歷史。

### 無障礙操作

- **鼻控（Nose Tracker）**：WebView 內建 MediaPipe Face Mesh，偵測鼻尖移動控制游標，靈敏度可在設定頁即時滑桿調整（注入 `window.egSetSensitivity()`）。
- **高對比模式**：純黑底、黃色強調（#FFE600）、白字，適合弱視使用者。
- **停留點選（Dwell）**：凝視目標一段時間自動點擊，免手操作。

### 成績單與匯出

- 成績單讀取 Firestore `rehabLogs`，支援今日／本月／本年三段維度。
- 統計：練習次數、平均分、連續天數、正向情緒字眼出現次數。
- 折線趨勢圖（Canvas 繪製，依時間 bucket 聚合）。
- CSV 匯出（FileProvider 分享）、PDF 匯出（`PdfDocument` 生成）。
- WorkManager `ReportPushWorker`：可排程定期自動發送摘要到 Telegram。

### 安全與緊急通報

- 偵測危機關鍵字 → 嘗試取得 GPS → 透過 Telegram Chat ID 通知。
- 文字進 LLM 前先攔截，輸出後 TTS 前再清理。
- ⚠️ Telegram Chat ID 由使用者自行填寫，**不內建任何預設值**。

## GPT-SoVITS 自訂語音（電腦端）

GPT-SoVITS 是 Python + PyTorch 桌面框架，APK 無法直接執行，因此採「**電腦跑 API server、手機連線**」的架構。

> **App 不內建任何語音模型或角色音色**——由使用者自行匯入有權使用的聲音。

### 手機連線方式

| 方式 | 設定 |
| --- | --- |
| **有線 USB**（推薦） | `adb reverse tcp:9880 tcp:9880`，App 切「有線」 |
| **無線 Wi-Fi** | App 填 `http://<電腦IP>:9880` |
| **Tailscale** | 填 `100.x` 位址 |

詳見 [`docs/GPT_SOVITS_MODEL_GUIDE.md`](docs/GPT_SOVITS_MODEL_GUIDE.md)。

## 設定項目

| 設定 | 預設 | 說明 |
| --- | --- | --- |
| `theme` | 科技風 | 視覺風格 |
| `language` | 繁體中文 | 介面語言（繁中／EN／日／韓） |
| `highContrast` | false | 高對比模式（黑底黃文） |
| `fontScale` | 1.0 | 全域字體倍率（0.8–1.8） |
| `noseSensitivity` | 1.0 | 鼻控靈敏度（0.5–2.0） |
| `dailyReminderEnabled` | false | 每日復健提醒 |
| `dailyReminderHour` | 9 | 提醒時段（0–23） |
| `reportPushFrequency` | none | 成績單推送頻率（none／daily／weekly） |
| `ttsRate` | 0.85 | 朗讀速度（0.5–1.5） |
| `cloudProvider` | 自動 | 雲端或本地 AI 路由 |
| `autoSaveRehab` | true | 自動保存復健紀錄 |
| `noseSensitivity` | 1.0 | 鼻控靈敏度倍率 |
| `customAacItems` | 空 | 自訂 AAC 圖卡（JSON） |
| `emergencyChatId` | 空白 | Telegram 緊急通報接收者 |
| `gptSovitEnabled` | false | GPT-SoVITS 實驗語音 |

## 權限

| 權限 | 用途 |
| --- | --- |
| 麥克風 | 語音輸入、跟讀錄音 |
| 定位 | GPS 情境、住家錨點、緊急通報 |
| 相機 | 前／後鏡頭預覽與辨識 |
| 網路 | 雲端 AI、Overpass、Google Places、Telegram |
| 通知（POST_NOTIFICATIONS） | 每日復健提醒、成績單推送 |
| 儲存／媒體 | 匯入參考音檔、CSV／PDF 匯出 |

## 專案結構

```text
app/src/main/java/com/example/
├── ui/
│   ├── VoiceWeaverScreen.kt    語音重組（含最愛、多語朗讀、複製分享）
│   ├── AacScreen.kt            AAC 圖卡（含自訂圖卡）
│   ├── RehabScreen.kt          語音復健（AI 評分、錯誤高亮、整段練習、streak）
│   ├── report/ReportScreen.kt  成績單（趨勢圖、CSV/PDF 匯出）
│   ├── settings/               設定畫面（無障礙、提醒、自訂圖卡）
│   └── theme/                  AppPalette（含 HighContrastPalette）、Theme
├── ai/
│   ├── VoiceWeaverCore.kt      核心 ViewModel + InferenceEngine 介面 + scoreRehab()
│   ├── CloudInferenceEngine.kt 雲端推理（offload → 雲端 → fallback）
│   ├── MediaPipeLLMEngine.kt   裝置端 Gemma / Qwen AI 評分
│   ├── PronunciationEvaluator.kt Levenshtein 備援 + wrongChars()
│   ├── TtsModule.kt            TTS（含多語 speakInLocale）
│   └── CloudSync.kt            Firestore 金鑰同步 + pushRehabLog()
├── data/
│   └── SettingsRepository.kt  DataStore（含 noseSensitivity、highContrast、提醒設定）
├── work/
│   ├── RehabScheduler.kt       WorkManager 排程（每日提醒、成績單推送）
│   └── ReportExporter.kt       CSV / PDF 生成 + FileProvider 分享
└── MainActivity.kt             鼻控靈敏度注入、高對比主題切換

app/src/main/assets/
├── nose_tracker.html   鼻控 WebView（MediaPipe Face Mesh + egSetSensitivity）
├── poi/                離線 POI（台灣／美國）
└── t2s_map.txt         繁→簡對照

tools/
├── GPT-SoVITS/   電腦端語音中心 + 無線同步
├── dmg/          macOS 打包（.dmg）
└── win/          Windows 打包（.zip）
```

## 隱私與授權

- **不內建任何語音模型／角色音色**：聲音由使用者自行匯入有權使用的素材。
- **金鑰不入版控**：`.env` 與 `app/google-services.json` 已被 `.gitignore`；API 金鑰只存在使用者裝置與其個人 Firestore。
- **聲紋屬生物特徵**：啟用 GPT-SoVITS／匯入錄音前需勾選知情同意。
- **第三方授權**：[GPT-SoVITS](https://github.com/RVC-Boss/GPT-SoVITS) 為 **AGPL-3.0**；打包 `.dmg`／`.zip` 散布時會附上其 `LICENSE`。
- 本專案程式碼本身尚未公開授權（保留所有權利）；如需重用請先聯絡作者。
