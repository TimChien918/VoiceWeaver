<div align="center">

# 🗣️ VoiceWeaver

**為失語症、語言復健與臨場表達困難者打造的 AI 溝通輔助 App**

把零碎的詞 ＋ 所在地點 ＋ 相機看到的東西，交給 AI 重組成一句自然的話，再用 TTS／自訂語音念出來。

![Platform](https://img.shields.io/badge/platform-Android%207.0%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?logo=kotlin&logoColor=white)
![minSdk](https://img.shields.io/badge/minSdk-24-blue)
![Web companion](https://img.shields.io/badge/網頁版-Live-0a84ff)

[🌐 網頁版 Live Demo](https://timchien918.github.io/VoiceWeaver-Web/) ·
[網頁版原始碼](https://github.com/TimChien918/VoiceWeaver-Web) ·
[GPT-SoVITS 模型手冊](docs/GPT_SOVITS_MODEL_GUIDE.md)

</div>

---

> 把碎詞（例：`水、休息、現在`）＋ 目前地點 ＋ 相機看到的物品，
> 一起送進 AI 重組成自然句（例：「我口渴了，請給我一杯水，我想休息一下。」），再用 TTS／GPT-SoVITS 朗讀出來。

VoiceWeaver 有兩個版本，用同一個 Google 帳號登入即可雙向同步 API 金鑰與設定：

| 版本 | 說明 | 連結 |
| --- | --- | --- |
| **Android App**（本專案） | 完整功能：離線推理、相機、定位、眼動、語音復健、GPT-SoVITS | — |
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
| **語音重組** | 語音／文字輸入、碎詞整理、AI 或規則重組、TTS 朗讀、歷史保存 |
| **AAC 圖卡** | 搜尋、分類、組合句、情境推薦、自訂金額、強意圖保護 |
| **定位情境** | 手動地點、GPS、住家錨點、Geocoder、Overpass、離線 POI（台灣／美國） |
| **相機辨識** | 前／後／前後一起拍、獨立預覽、單次辨識、橫直向支援 |
| **語音復健** | 自訂或歷史目標句、AI 建議、跟讀錄音、發音評分、依場景統計 |
| **歷史紀錄** | 重組紀錄、收藏、刪除、清空、地點標籤 |
| **雲端同步** | Firebase 登入後，API 金鑰／供應商清單／Telegram 設定與網頁版即時互通 |
| **無障礙** | 字體倍率、TTS 語速、眼動凝視／眨眼掃描 |
| **主題與語言** | 科技／可愛／動漫／簡約風；繁中、English、日本語、한국어 |
| **GPT-SoVITS** | 開發者模式專屬：自訂語音、中英自動切換、模型匯入、合成測試 |
| **安全通報** | 緊急關鍵字偵測、GPS 通報、Telegram 通知、文字安全清理 |

## 快速開始

```bash
# 1. 取得原始碼
git clone https://github.com/TimChien918/VoiceWeaver.git
cd VoiceWeaver

# 2. 金鑰：複製範例並填入自己的金鑰（.env 已被 gitignore，切勿提交）
cp .env.example .env
#    GEMINI_API_KEY / GEMINI_IMAGE_API_KEY / GROQ_API_KEY / OPENROUTER_API_KEY
#    COHERE_API_KEY / GOOGLE_PLACES_API_KEY / TELEGRAM_BOT_TOKEN / TELEGRAM_DEFAULT_CHAT_ID

# 3. 雲端同步（選用）：到 Firebase Console 下載 google-services.json 放進 app/
#    （已被 gitignore，含 client 設定，不入版控）

# 4. 建置（用 Android Studio 內建 JBR；系統只有 JDK 8 時必須指定）
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleDebug      # 打包 Debug APK
./gradlew :app:installDebug       # 安裝到已連線裝置（先開 USB 偵錯）
./gradlew :app:testDebugUnitTest  # 單元測試
```

> **需要 JDK 17+**。Android Studio 內建的 JBR（21）最方便；用其他 JDK 8 會出現 `Gradle requires JVM 17 or later`。

## 技術架構

- **語言／UI**：Kotlin + Jetpack Compose
- **資料**：Room（歷史／復健／AAC／故事訓練）、DataStore（設定）
- **雲端**：Firebase Auth（Google／匿名）+ Firestore（金鑰／供應商／Telegram 與網頁版共用結構）
- **感測**：CameraX + ML Kit（影像標籤）、FusedLocation（GPS）、SpeechRecognizer／Vosk（語音轉文字）
- **網路**：Retrofit／OkHttp → 雲端 LLM、Overpass、Google Places、Telegram
- **推理分層**（依裝置能力自動選擇）：

  | 裝置等級 | 推理引擎 |
  | --- | --- |
  | 低階 | `RegexTemplateEngine` 規則模板，不載入大型模型 |
  | 中高階 | 本地推理或雲端 LLM |
  | 雲端自動 | 有網路走雲端，失敗自動回退本地 |

- **雲端提供者**：自動／僅本地／Groq · Llama 3.3 70B／OpenRouter · Gemini Flash／Cohere · Command-R

## 核心流程

### 語音重組
1. 使用者輸入碎詞（手動、語音、AAC 圖卡、歷史候選皆可）。
2. App 把碎詞 ＋ 目前地點 ＋ 相機標籤整合成 `GlobalTagsVault`。
3. 安全檢查先攔截高風險文字。
4. 推理引擎依設定選擇本地規則、雲端模型或 fallback，產生自然句。
5. TTS 或 GPT-SoVITS 朗讀，結果寫入歷史。

### AAC 圖卡
- 不方便打字／說話時，用圖卡快速組句：搜尋 → 選分類 → 點圖卡加入組合區 → 朗讀／重組／保存。
- 分類涵蓋：商品、飲食、金額、動作、需求、人物、地點、醫療、時間、問句、心情、緊急。
- **設計原則**：具體需求不會被誤判成翻譯指令（「一杯水」保留為飲水需求）；強意圖項目會進入安全判斷；自訂金額支援多幣別（NT$／US$／¥／₩／RMB／€）。

### 定位情境
回退順序：**手動選擇 → 住家半徑命中 → GPS + Geocoder → Overpass 附近 POI → 離線 POI → 規則分類 → 未知地點**。
- 住家錨點：可把目前 GPS 設為住家、設定地址與半徑（預設 150m），避免住家附近店家被誤判。
- 離線 POI 內含大量台灣與美國座標，無網路時仍可運作。

### 語音復健
1. 選目標句（手動／歷史／AI 建議／主畫面重組結果）。
2. 播放示範 → 使用者跟讀 → App 錄音轉文字。
3. 發音評估模組用 Levenshtein 比對目標與辨識結果，產生 0–100 分並寫入紀錄。
4. 統計最近練習與各地點平均分。開發者模式可進入「故事訓練」。

### 安全與緊急通報
- 偵測危機關鍵字 → 嘗試取得 GPS（失敗也會送出「位置未知」）→ 透過 Telegram Chat ID 通知。
- 文字進 LLM 前先攔截、LLM 輸出後 TTS 前再清理。
- ⚠️ Telegram Chat ID 由使用者自行填寫（**正式使用請填家屬或照護者的 Chat ID**），不內建任何預設值。

## GPT-SoVITS 自訂語音（電腦端）

GPT-SoVITS 是 Python + PyTorch 桌面框架，APK 無法直接執行 `.pth` 權重，因此採「**電腦跑 API server、手機連線**」的架構。

> **App 與安裝檔不內建任何語音模型或角色音色**——一律由使用者自行匯入有權使用的聲音；檔案存在手機，可在電腦間同步。

### 電腦端語音中心

`tools/` 內提供自帶環境的「語音中心」啟動器，會啟動本機服務（語音 9880／生圖 9881／文字 9882），
並自動 `adb reverse`、顯示手機連線位址、同步聲音模型；關閉視窗即停止所有服務。

| 平台 | 打包工具 | 啟動方式 |
| --- | --- | --- |
| **macOS** | `tools/dmg/build_dmg.sh` → `.dmg` | 雙擊 `VoiceWeaver語音中心.app` |
| **Windows** | `tools/win/build_win.ps1` → `.zip` | 雙擊 `VoiceWeaver語音中心.cmd`（見 [`tools/win/README_WINDOWS.md`](tools/win/README_WINDOWS.md)） |

> 兩個 build 腳本都把安裝檔輸出到同一個資料夾 `~/VoiceWeaver-安裝檔`（可用環境變數 `VW_DIST` 覆寫）。

### 手機連線方式

| 方式 | 設定 | 備註 |
| --- | --- | --- |
| **有線 USB**（推薦） | 電腦跑 `adb reverse tcp:9880 tcp:9880`，App 切「有線」（自動填 `http://127.0.0.1:9880`） | 最穩、延遲低 |
| **無線 Wi-Fi** | 手機與電腦同網段，API 位址填 `http://<電腦 IP>:9880` | URL 不要加 `/tts`，App 會自動呼叫 |
| **Tailscale** | 填 `100.x` 位址 | 跨網段 |

### 語音模型同步（手機 ↔ 電腦，免雲端）

聲音模型用無線 ADB 同步，像 USB 但走 Wi-Fi、不經任何雲端伺服器：

```bash
# Android 11+ 無線偵錯：首次配對 + 連線
python tools/GPT-SoVITS/sync_voice_models.py --pair <ip:port> <配對碼> --wifi <ip:port>
# 之後每次：
python tools/GPT-SoVITS/sync_voice_models.py --wifi <手機IP>
```

換模型完整手冊見 [`docs/GPT_SOVITS_MODEL_GUIDE.md`](docs/GPT_SOVITS_MODEL_GUIDE.md)。建議參考音檔為乾淨 3–10 秒、16kHz 以上 PCM WAV，逐字稿需與音檔逐字一致。

## 設定項目

| 設定 | 預設 | 說明 |
| --- | --- | --- |
| `theme` | 科技風 | 視覺風格（科技／可愛／動漫／簡約） |
| `language` | 繁體中文 | 介面語言（繁中／EN／日／韓） |
| `powerMode` | 標準 | 省電／標準／高效能 |
| `cloudProvider` | 自動 | 雲端或本地 AI 路由 |
| `ttsRate` | 0.85 | 朗讀速度（0.5–1.5） |
| `fontScale` | 1.0 | 全域字體倍率（0.8–1.8） |
| `autoSaveRehab` | true | 自動保存復健紀錄 |
| `eyeControlEnabled` | false | 眼動控制 |
| `eyeControlMode` | 凝視停留 | 凝視停留／眨眼掃描 |
| `eyeScanIntervalMs` | 1200 | 眨眼掃描速度（600–2200ms） |
| `cameraLens` | 後攝影機 | 後／前／前後一起拍 |
| `gptSovitEnabled` | false | GPT-SoVITS 實驗語音 |
| `gptSovitVoiceMode` | 自動切換 | 中文／英文／自動 |
| `gptSovitApiBaseUrl` | 空 | SoVITS API 位址 |
| `customBackgroundUri` | 空 | 自訂背景（使用者自選） |
| `developerMode` | false | 開發者功能 |
| `defaultCurrency` | 新台幣 | AAC 自訂金額預設幣別 |
| `emergencyChatId` | 空白 | Telegram 緊急通報接收者（使用者自行填寫） |
| `homeRadiusMeters` | 150 | 住家命中半徑 |

## 權限

| 權限 | 用途 |
| --- | --- |
| 麥克風 | 語音輸入、跟讀錄音 |
| 定位 | GPS 情境、住家錨點、緊急通報 |
| 相機 | 前／後鏡頭預覽與單次辨識 |
| 網路 | 雲端 AI、Overpass、Google Places、模型下載、Telegram |
| 儲存／媒體 | 匯入參考音檔、模型資料夾、自訂背景 |

## 專案結構

```text
app/src/main/java/com/example/
├── ui/        Compose 畫面與 UI 元件（VoiceWeaverScreen、AacScreen、RehabScreen、SettingsScreen…）
├── ai/        語音、定位、視覺辨識、推理、TTS、GPT-SoVITS、雲端同步、ViewModel
├── data/      Room、DataStore、AAC 資料、設定模型
├── safety/    安全檢查與緊急通報
└── net/       Telegram API

app/src/main/assets/
├── poi/         離線 POI（台灣／美國座標）
├── models/      本地模型資源
└── t2s_map.txt  繁→簡對照（送 GPT-SoVITS 前轉換）

tools/
├── GPT-SoVITS/  電腦端語音中心、API server、無線同步腳本
├── dmg/         macOS 語音中心打包（.dmg）
└── win/         Windows 語音中心打包（.zip）
```

Room 實體：`SpeechRecord`（重組歷史）、`RehabLog`（復健）、`AacComboLog`（AAC 組合／評分）、`StoryTrainingLog`（故事訓練）。更詳細的檔案地圖見 [`AGENTS.md`](AGENTS.md)。

## 隱私與授權

- **不內建任何語音模型／角色音色**：聲音由使用者自行匯入有權使用的素材；安裝檔與原始碼皆不含第三方語音資產。
- **金鑰不入版控**：`.env` 與 `app/google-services.json` 已被 `.gitignore`；API 金鑰只存在使用者裝置與其個人 Firestore。
- **聲紋屬生物特徵**：啟用 GPT-SoVITS／匯入錄音前需勾選知情同意。
- **第三方授權**：電腦端使用的 [GPT-SoVITS](https://github.com/RVC-Boss/GPT-SoVITS) 為 **AGPL-3.0**；打包 `.dmg`／`.zip` 散布時會一併附上其 `LICENSE`。
- 本專案程式碼本身尚未公開授權（保留所有權利）；如需重用請先聯絡作者。
