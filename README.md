# VoiceWeaver

VoiceWeaver 是一款協助溝通的 Android App，專為**失語症、語言復健或臨場表達困難**的使用者設計。
目標是用最少的操作，幫使用者完成「我在哪裡、我想要什麼、我要怎麼說」：

> 把碎詞（例：`水、休息、現在`）＋ 目前地點 ＋ 相機看到的物品，
> 一起送進 AI 重組成自然句（例：「我口渴了，請給我一杯水，我想休息一下。」），再用 TTS / GPT-SoVITS 朗讀出來。

---

## 功能總覽

| 分類 | 重點功能 |
| --- | --- |
| **語音重組** | 語音／文字輸入、碎詞整理、AI 或規則重組、TTS 朗讀、歷史保存 |
| **AAC 圖卡** | 搜尋、分類、組合句、情境推薦、自訂金額、強意圖保護 |
| **定位情境** | 手動地點、GPS、住家錨點、Geocoder、Overpass、離線 POI（台灣／美國） |
| **相機辨識** | 前／後／前後一起拍、獨立預覽、單次辨識、橫直向支援 |
| **語音復健** | 自訂或歷史目標句、AI 建議、跟讀錄音、發音評分、依場景統計 |
| **歷史紀錄** | 重組紀錄、收藏、刪除、清空、地點標籤 |
| **無障礙** | 字體倍率、TTS 語速、眼動凝視 / 眨眼掃描 |
| **主題與語言** | 科技／可愛／動漫／簡約風；繁中、English、日本語、한국어 |
| **GPT-SoVITS** | 開發者模式專屬：自訂語音、中英自動切換、模型匯入、合成測試 |
| **安全通報** | 緊急關鍵字偵測、GPS 通報、Telegram 通知、文字安全清理 |

---

## 技術架構

- **語言／UI**：Kotlin + Jetpack Compose
- **資料**：Room（歷史 / 復健 / AAC / 故事訓練）、DataStore（設定）
- **感測**：CameraX + ML Kit（影像標籤）、FusedLocation（GPS）、SpeechRecognizer（語音轉文字）
- **網路**：Retrofit / OkHttp → 雲端 LLM、Overpass、Google Places、Telegram
- **推理分層**（依裝置能力自動選擇）：

  | 裝置等級 | 推理引擎 |
  | --- | --- |
  | 低階 | `RegexTemplateEngine` 規則模板，不載入大型模型 |
  | 中高階 | 本地推理或雲端 LLM |
  | 雲端自動 | 有網路走雲端，失敗自動回退本地 |

- **雲端提供者**：自動 / 僅本地 / Groq · Llama 3.3 70B / OpenRouter · Gemini Flash / Cohere · Command-R

---

## 主要畫面

| 分頁 | 職責 |
| --- | --- |
| **主畫面** | 輸入碎詞、選地點、相機辨識、重組、朗讀；可展開 AAC 圖卡直接組句 |
| **復健** | 選目標句 → 播放示範 → 跟讀錄音 → 發音評分 → 寫入紀錄並依地點統計 |
| **歷史** | 檢視 / 收藏 / 刪除 / 清空所有重組句，附時間與地點標籤 |
| **設定** | 主題、語言、耗能模式、AI 提供者、字體、無障礙、相機、背景、緊急通報、住家、開發者模式 |

---

## 核心流程

### 語音重組
1. 使用者輸入碎詞（手動、語音、AAC 圖卡、歷史候選皆可）。
2. App 把碎詞 ＋ 目前地點 ＋ 相機標籤整合成 `GlobalTagsVault`。
3. 安全檢查先攔截高風險文字。
4. 推理引擎依設定選擇本地規則、雲端模型或 fallback，產生自然句。
5. TTS 或 GPT-SoVITS 朗讀，結果寫入歷史。

### AAC 圖卡
- 不方便打字／說話時，用圖卡快速組句：搜尋 → 選分類 → 點圖卡加入組合區 → 朗讀 / 重組 / 保存。
- 分類涵蓋：商品、飲食、金額、動作、需求、人物、地點、醫療、時間、問句、心情、緊急。
- **設計原則**：具體需求不會被誤判成翻譯指令（「一杯水」保留為飲水需求）；強意圖項目會進入安全判斷；自訂金額支援多幣別（NT$ / US$ / ¥ / ₩ / RMB / €）。

### 定位情境
回退順序：**手動選擇 → 住家半徑命中 → GPS + Geocoder → Overpass 附近 POI → 離線 POI → 規則分類 → 未知地點**。
- 住家錨點：可把目前 GPS 設為住家、設定地址與半徑（預設 150m），避免住家附近店家被誤判。
- 離線 POI 內含大量台灣與美國座標，無網路時仍可運作。

### 相機辨識
- 鏡頭模式：後 / 前 / 前後一起拍（依序拍攝後合併標籤）。
- 預覽與單次辨識共用鏡頭設定；單次辨識拍一張後自動釋放 CameraX，省電。

### 語音復健
1. 選目標句（手動 / 歷史 / AI 建議 / 主畫面重組結果）。
2. 播放示範 → 使用者跟讀 → App 錄音轉文字。
3. 發音評估模組比對目標與辨識結果，產生 0–100 分並寫入紀錄。
4. 統計最近練習與各地點平均分。開發者模式可進入「故事訓練」。

### 安全與緊急通報
- 偵測危機關鍵字 → 嘗試取得 GPS（失敗也會送出「位置未知」）→ 透過 Telegram Chat ID 通知。
- 文字進 LLM 前先攔截、LLM 輸出後 TTS 前再清理。
- ⚠️ 預設 Chat ID 僅供測試，**正式使用務必改成家屬或照護者的 Chat ID**。

---

## GPT-SoVITS 語音（開發者模式）

GPT-SoVITS 是 Python + PyTorch 桌面框架，APK 無法直接執行 `.pth` 權重，因此採「電腦跑 API server，手機連線」的架構。
> Termux 無法安裝官方 PyTorch wheel（會卡在 `No matching distribution found for torch`），這是平台不相容、非操作錯誤。

### 電腦端語音中心（一鍵啟動）

`tools/` 內提供自帶環境的「語音中心」啟動器，會啟動本機服務（語音 9880 / 生圖 9881 / 文字 9882），
並自動 `adb reverse`、顯示手機連線位址、同步聲音模型；關閉視窗即停止所有服務。

| 平台 | 打包工具 | 啟動方式 |
| --- | --- | --- |
| **macOS** | `tools/dmg/build_dmg.sh` → `.dmg` | 雙擊 `VoiceWeaver語音中心.app` |
| **Windows** | `tools/win/build_win.ps1` → `.zip` | 雙擊 `VoiceWeaver語音中心.cmd`（見 [`tools/win/README_WINDOWS.md`](tools/win/README_WINDOWS.md)） |

手動啟動語音 API（開發用）：

```bash
./tools/GPT-SoVITS/start_api.sh        # 語音 API（9880）
```

### 手機連線方式
| 方式 | 設定 | 備註 |
| --- | --- | --- |
| **有線 USB**（推薦） | 電腦跑 `adb reverse tcp:9880 tcp:9880`，App 切「有線」chip（自動填 `http://127.0.0.1:9880`） | 最穩、延遲低，出門復健可直接接線 |
| **無線 Wi-Fi** | 手機與電腦同網段，API 位址填 `http://<電腦 IP>:9880`（Mac 用 `ipconfig getifaddr en0` 查） | 注意：URL 不要加 `/tts`，App 會自動呼叫 |
| **Tailscale** | 填 `100.x` 位址 | 跨網段，控制中心會顯示三種位址 |

設定路徑：**設定 → 開發者模式 → GPT-SoVITS**，切連線方式 → 「測試連線」綠燈 → 「🎤 合成測試（中英連跑）」。

### 自訂語音
- 換模型完整手冊見 [`docs/GPT_SOVITS_MODEL_GUIDE.md`](docs/GPT_SOVITS_MODEL_GUIDE.md)。
- 參考音檔放在手機 `…/VoiceWeaver/voices/furina/ref_zh.wav`、`ref_en.wav`；App 發 `/tts` 時會把手機 URI 自動翻譯成電腦端絕對路徑（`LocalSovitsHelper.kt`）。
- 替換 ref 音檔時，逐字稿 `PROMPT_ZH` / `PROMPT_EN` 也要同步改，否則 clone 品質會掉。建議乾淨 3–10 秒、16kHz 以上 PCM WAV。
- ⚠️ 角色音色與模型權重請務必確認**授權**。`.ckpt` / `.pth` 若只有 100 多 bytes 通常是 Git LFS pointer，不是完整權重。

---

## 預設資源

App 首次啟動會把 assets 解壓到 `/sdcard/Android/media/<package>/VoiceWeaver/`：
- `voices/furina/`：中英參考音檔 + 逐字稿
- `backgrounds/furina/bg.png`：預設動漫背景（可改自訂背景，永久保存、可預覽 / 刪除）

替換方式：取代 `app/src/main/assets/` 下同名檔 → 重新 build → 下次啟動重新複製。

---

## 環境變數

複製 `.env.example` 為 `.env` 並填入自己的金鑰（**切勿提交 `.env`**）：

```
GEMINI_API_KEY / GEMINI_IMAGE_API_KEY / GROQ_API_KEY / OPENROUTER_API_KEY
COHERE_API_KEY / GOOGLE_PLACES_API_KEY / TELEGRAM_BOT_TOKEN / TELEGRAM_DEFAULT_CHAT_ID
```

---

## 建置與安裝

使用 Android Studio 內建 JBR：

```bash
# 編譯
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:compileDebugKotlin

# 打包 Debug APK
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug

# 單元測試
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest

# 安裝到已連線裝置（先開 USB 偵錯）
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:installDebug
```

---

## 設定項目

| 設定 | 預設 | 說明 |
| --- | --- | --- |
| `theme` | 科技風 | 視覺風格（科技 / 可愛 / 動漫 / 簡約） |
| `language` | 繁體中文 | 介面語言（繁中 / EN / 日 / 韓） |
| `powerMode` | 標準 | 省電 / 標準 / 高效能 |
| `cloudProvider` | 自動 | 雲端或本地 AI 路由 |
| `ttsRate` | 0.85 | 朗讀速度（0.5–1.5） |
| `fontScale` | 1.0 | 全域字體倍率（0.8–1.8） |
| `autoSaveRehab` | true | 自動保存復健紀錄 |
| `eyeControlEnabled` | false | 眼動控制 |
| `eyeControlMode` | 凝視停留 | 凝視停留 / 眨眼掃描 |
| `eyeScanIntervalMs` | 1200 | 眨眼掃描速度（600–2200ms） |
| `cameraLens` | 後攝影機 | 後 / 前 / 前後一起拍 |
| `gptSovitEnabled` | false | GPT-SoVITS 實驗語音 |
| `gptSovitVoiceMode` | 自動切換 | 中文 / 英文 / 自動 |
| `gptSovitApiBaseUrl` | 空 | SoVITS API 位址 |
| `customBackgroundUri` | 空 | 自訂背景 |
| `autoLocalAiCleanup` | true | 推理後清理本地 AI 快取 |
| `developerMode` | false | 開發者功能 |
| `defaultCurrency` | 新台幣 | AAC 自訂金額預設幣別 |
| `emergencyChatId` | 空白 | Telegram 緊急通報接收者（由使用者自行填寫） |
| `homeRadiusMeters` | 150 | 住家命中半徑 |

---

## 權限

| 權限 | 用途 |
| --- | --- |
| 麥克風 | 語音輸入、跟讀錄音 |
| 定位 | GPS 情境、住家錨點、緊急通報 |
| 相機 | 前／後鏡頭預覽與單次辨識 |
| 網路 | 雲端 AI、Overpass、Google Places、模型下載、Telegram |
| 儲存／媒體 | 匯入參考音檔、模型資料夾、自訂背景 |

---

## 專案結構

```text
app/src/main/java/com/example/
├── ui/        Compose 畫面與 UI 元件（VoiceWeaverScreen、AacScreen、SettingsScreen…）
├── ai/        語音、定位、視覺辨識、推理、TTS、GPT-SoVITS、ViewModel
├── data/      Room、DataStore、AAC 資料、設定模型
├── safety/    安全檢查與緊急通報
└── net/       Telegram API

app/src/main/assets/
├── poi/         離線 POI
├── voices/      預設參考音檔
├── backgrounds/ 預設背景
└── models/      本地模型資源

tools/
├── GPT-SoVITS/  桌面版 GPT-SoVITS 工具與測試環境
├── dmg/         macOS 語音中心打包（.dmg）
└── win/         Windows 語音中心打包（.zip）
```

Room 實體：`SpeechRecord`（重組歷史）、`RehabLog`（復健）、`AacComboLog`（AAC 組合 / 評分）、`StoryTrainingLog`（故事訓練）。

更詳細的檔案地圖與搜尋規則見 [`AGENTS.md`](AGENTS.md)。

---

## 使用建議

| 情境 | 建議設定 |
| --- | --- |
| 一般使用 | 耗能 `標準`、AI `自動` |
| 長時間外出 | 耗能 `省電`、相機用單次辨識 |
| 低視力 / 長輩 | 字體調到 `大` 或 `特大` |
| 需要眼動操作 | 開啟眼動控制並調整掃描間隔 |
| 使用 GPT-SoVITS | 先在電腦跑 API server，再按「測試連線」 |
| 正式緊急通報 | **務必更換 Telegram Chat ID** |

---

## 開發注意

- 不要提交 API key、Telegram token、正式醫療資料或私人 Chat ID。
- 定位無網路時會回退離線 POI 與規則分類；AAC 項目避免把具體需求誤分類成翻譯指令。
- GPT-SoVITS 的角色音色、參考音檔與模型權重必須確認授權。
- 大型模型 / runtime 資料夾（`tools/miniforge3/`、`*weights*/`、`assets/models/`…）預設不要瀏覽，搜尋已用 `.ignore` 排除。
