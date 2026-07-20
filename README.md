<div align="center">

# 🗣️ VoiceWeaver

**為失語症、語言復健與臨場表達困難者打造的 AI 溝通輔助生態系**

把零碎的詞 ＋ 所在地點 ＋ 相機看到的東西，交給 AI 重組成自然句；
AI 全判讀語音復健評分；鼻控無障礙操作；自己的聲音、自己的雲端曲庫，隨處合成；
給看不懂手機的長輩：一塊「會說話的紙圖卡」。

![Platform](https://img.shields.io/badge/Android-7.0%2B-3DDC84?logo=android&logoColor=white)
![Web](https://img.shields.io/badge/網頁版-Live-0a84ff)
![Desktop](https://img.shields.io/badge/語音中心-macOS%20%7C%20Windows-8a2be2)
![Colab](https://img.shields.io/badge/雲端算力-Colab%20GPU-f9ab00?logo=googlecolab&logoColor=white)
![Drive](https://img.shields.io/badge/曲庫-Google%20Drive-4285F4?logo=googledrive&logoColor=white)
![i18n](https://img.shields.io/badge/介面語言-繁中%20%7C%20EN%20%7C%20日%20%7C%20한-ff9500)
![Kotlin](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?logo=kotlin&logoColor=white)

[🌐 網頁版 Live](https://timchien918.github.io/VoiceWeaver-Web/) ·
[📦 下載安裝檔（Releases）](https://github.com/TimChien918/VoiceWeaver/releases) ·
[網頁版原始碼](https://github.com/TimChien918/VoiceWeaver-Web) ·
[GPT-SoVITS 模型手冊](docs/GPT_SOVITS_MODEL_GUIDE.md)

</div>

---

## 四個入口，一套生態

| 入口 | 說明 | 取得方式 |
| --- | --- | --- |
| **📱 Android App** | 完整功能：離線推理、相機、定位、鼻控、語音復健、GPT-SoVITS 角色語音、雲端曲庫瀏覽與下載 | [Releases](https://github.com/TimChien918/VoiceWeaver/releases) 或自行建置 |
| **🌐 網頁輕量版** | 純瀏覽器免安裝；輕重症雙軌介面（含高齡防呆模式）；可把語音／生圖／文字運算丟給電腦或 Colab 跑 | [直接開啟](https://timchien918.github.io/VoiceWeaver-Web/) |
| **🖥 語音中心（macOS / Windows）** | 桌面 App：GPT-SoVITS 語音合成、SD-Turbo 生圖、Qwen 文字推論、**雲端曲庫管理（Google Drive）** | [Releases](https://github.com/TimChien918/VoiceWeaver/releases) |
| **☁️ Colab GPU（免費算力）** | 免安裝的雲端運算：同一份 Google Drive 曲庫、免費 GPU 跑語音合成，手機／網頁走 ngrok 連線 | [notebook](tools/colab/VoiceWeaver_GPT_SoVITS_Colab.ipynb) |

- 手機、網頁、電腦用**同一個 Google 帳號**登入即互通：API 金鑰／設定雙向同步（Firestore），語音曲庫共用同一個 Drive。
- 四個入口的介面都支援**繁體中文／English／日本語／한국어**一鍵切換；切換語言會連動語音模型篩選與朗讀語言。

## 功能總覽

| 分類 | 重點功能 |
| --- | --- |
| **語音重組** | 語音／文字輸入、碎詞整理、AI 重組自然句、TTS 朗讀、多語朗讀（中英日韓）、複製分享、我的最愛 |
| **AAC 圖卡** | 分類點選、整句緩衝（點卡進句、一次連貫朗讀）、費茲傑羅詞性色碼（黃名詞／綠動詞／藍形容詞）、詞性預測排序、超大字級網格自動降級、相機拍照自訂圖卡 |
| **🧓 高齡防呆模式（網頁）** | 去科技化全螢幕「紙圖卡」、單一情境鎖定（2–4 張卡怎麼按都不會跳走）、點卡即輕快朗讀＋提示音、照護者 PIN 退出、自動全螢幕＋螢幕常亮 |
| **語音復健** | AI 全判讀（語意／流暢／語氣，非字元差異）、**每次評分獨立且低溫求穩**、錯誤字高亮、整段分句逐句練、streak 連續天數、每日提醒 |
| **成績單** | Firestore 同步日誌、趨勢折線圖、正向情緒統計、CSV／PDF 匯出、Telegram 排程推送 |
| **定位情境** | GPS、住家錨點、Geocoder、Overpass、離線 POI（台灣／美國） |
| **相機辨識** | 前／後攝影機、AI 認物品、加入情境 |
| **無障礙** | 鼻控游標（MediaPipe 鼻尖追蹤、靈敏度即時調整）、dwell 停留點選、觸控防呆（防手抖連點／捲動誤觸／禁長按）、超大字、高對比 |
| **安全通報** | 危機關鍵字偵測 → GPS ＋ Telegram 即時通知家人、視訊通話、語音安全鎖定 |
| **角色語音（情緒感知）** | GPT-SoVITS 自訂聲音，依句子自動偵測情緒（開心／難過／恐懼／驚訝／中立）切換對應語氣的參考音；電腦／Colab 跑，手機／網頁連線 |
| **雲端曲庫** | Google Drive 是**唯一曲庫**，本機只是快取——手機／網頁／電腦都看同一份角色清單（依語言分區、已下載排前），未下載的角色一鍵抓到運算端，合成前自動確保就緒 |

## 🧓 輕重症雙軌介面（網頁版）

同一個網頁，兩種模式，給照護者一鍵切換：

- **🟩 輕症模式（語法訓練）**：AAC 圖卡點選「只進整句緩衝」不即時朗讀，組完整句按「朗讀」一次連貫唸出（保留主動受語序訓練）。費茲傑羅詞性色碼當視覺錨點；點了動詞下一步自動把名詞排前面（SVO 引導）。
- **🟨 重症／高齡防呆模式（建立信任）**：針對看不懂手機、對科技排斥的長輩——
  - **去科技化**：進入後自動全螢幕，整個畫面只剩 2–4 張超大卡片，像一張實體塑膠墊板；無選單、無按鈕、無返回鍵。
  - **單一情境鎖定**：照護者鎖定「吃飯」「上廁所」等情境（或自己拍的照片卡），長輩怎麼按都不會跳到看不懂的畫面。
  - **點卡即回饋**：立即輕快朗讀（GPT-SoVITS 走「開心」情緒參考音；瀏覽器 TTS 則 pitch 1.2）＋提示音＋放大動畫，建立因果認知。
  - **防呆觸控**：所有觸發只認 `pointerup`、逐鍵防連點（手抖同點狂按只算一次）、捲動位移不觸發、全面禁止長按。
  - **照護者出口**：連點右上角隱形區 5 下 → 輸入 4 位 PIN；螢幕常亮（wakeLock）不熄滅。
  - **相機自訂圖卡**：直接拍長輩熟悉的物品／家人照片做成卡片——看到認得的東西最能建立信任。

## ☁️ 雲端曲庫（Google Drive 是唯一事實來源）

聲音模型不再各裝置各存一份。架構是：

```
Google Drive（VoiceWeaver/<語言>/<角色>/）  ←── 唯一曲庫，登入哪個 Google 帳號就是哪份
        │
        │  電腦／Colab 用 Drive API／掛載讀取
        ▼
運算端本機快取（沒有的角色會自動下載，下載完就是「已下載」）
        │
        │  /catalog（瀏覽整庫＋下載狀態）／/prepare（隨選下載）／/speak（合成）
        ▼
手機／網頁（不存模型、不合成，只送文字、收 WAV）
```

- **一次 Google 登入 ＝ Firebase 身分 ＋ Drive 存取權**：電腦端登入時會自動把兩者綁在一起（`drive.file` 範圍，只碰 App 自己建立的檔案，不觸及你 Drive 裡其他東西）。
- **匯入即上傳**：語音中心匯入單個／批量資料夾時，直接寫進曲庫的情緒結構並上傳到你自己的 Drive；不用手動搬檔案。
- **資料夾格式**：`<語言 ZH/EN/JA/KO>/<角色>/{*.ckpt, *.pth, reference_audios/**/emotions/【情緒】逐字稿.wav}`——檔名的【情緒】標籤決定該顆參考音對應哪種語氣。
- **`catalog.json`**：曲庫根目錄的目錄索引（角色／語言／情緒／大小），Mac 與 Colab 兩端寫出的格式完全一致，互為可信來源。
- **隨選下載＋續傳**：合成前運算端會自動確保角色已在本機；沒有就從 Drive 抓（支援中斷續傳，慢速網路也不必整包重來）。下載狀態是**實測**的（.ckpt＋.pth＋參考音都齊才算），已下載 🟢、未下載 ☁️，清單依語言分區、已下載排最前。
- **管理**：語音中心可「移除下載」（清本機快取、雲端保留）或「從雲端刪除角色」（丟 Drive 垃圾桶，可自行還原，會影響所有裝置看到的曲庫）。

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
一個視窗管理三個 AI 服務＋手機連線＋雲端曲庫：

| 埠 | 服務 | 說明 |
| --- | --- | --- |
| `9880` | 語音合成（GPT-SoVITS） | 首次啟動可一鍵下載基礎模型（約 1.6GB）；角色模型來自雲端曲庫 |
| `9881` | 本機生圖（SD-Turbo） | 首次生圖自動下載模型（免金鑰） |
| `9882` | 文字推論（Qwen2.5-1.5B） | 句子重組／復健評分的「電腦接手」來源 |
| `9879` | 橋接（含 CORS） | 手機語言同步 ＋ 網頁版的語音／生圖／文字／曲庫代理（`/catalog`、`/prepare`、`/speak`…） |

**雲端曲庫管理**：Google 登入（一次綁定 Firebase＋Drive）、2 欄宮格清單（顯示每個角色的下載狀態／大小／情緒）、下載⇄移除下載一鍵切換、單個與批量資料夾匯入（自動上傳到 Drive）、從雲端刪除角色、語言篩選（中／英／日／韓，跟手機介面語言自動連動）。

**手機連線三選一**：
| 方式 | 設定 |
| --- | --- |
| 有線 USB（推薦） | 插線即可，自動 `adb reverse`；首次同步後自動開啟無線並記住 IP |
| 無線 Wi-Fi | App 填 `http://<電腦IP>:9880`，或掃語音中心的 QR Code 自動填入 |
| Tailscale（外網） | 填 `100.x` 位址；4G／5G 也能連 |

> 打包腳本：`tools/dmg/build_dmg.sh`（macOS）、`tools/win/build_win.ps1`（Windows）。安全網會強制剔除任何模型權重，**安裝檔絕不內含聲音模型**——模型只透過使用者自己的 Google Drive 流動。

## ☁️ Colab GPU（免費雲端算力）

不想讓自己的電腦跑推論、或電腦效能不夠時，用 [Google Colab](https://colab.research.google.com) 的免費 GPU：

1. 上傳 `tools/colab/VoiceWeaver_GPT_SoVITS_Colab.ipynb` 到 Colab，選 GPU（T4）執行階段。
2. 依序執行每個 cell：安裝相依 → 掛載 Drive（同一個帳號的曲庫，會自動建 ZH/EN/JA/KO 範本並印逐角色健檢報告）→ 啟動服務 → 開 ngrok 通道。
3. 手機或網頁的「連接電腦」設定頁填入 ngrok 給的公開網址，或直接按「☁️ 用此 Colab 網址連線」。
4. Colab 端的橋接（`voiceweaver_bridge.py`）與語音中心走同一套情緒偵測、同一份 `catalog.json` 格式，行為完全一致。

> ngrok 授權碼透過配對碼從你的帳號雲端取用，不需要把授權碼貼進 notebook。

### Colab 連線疑難排解

| 症狀 | 原因與處理 |
| --- | --- |
| `ERR_NGROK_9038`（Invalid URL / trailing '/'） | 固定網域欄位貼了整串網址——**已自動清洗**（notebook 與網頁都會把 `https://…/` 轉成純網域），更新 notebook 後重跑即可 |
| 網頁偵測不到 Colab、但手機連得上 | ngrok 免費版對瀏覽器插「警告攔截頁」——網頁**已自動帶** `ngrok-skip-browser-warning` 繞過；若還是不行，確認跑的是最新 notebook（舊版橋接未放行該標頭） |
| 連上了但顯示「雲端上沒有任何語音模型」 | 舊版 notebook 沒把 `MODELS_ROOT` 傳給橋接、看不到 Drive 曲庫——用最新 notebook 重跑 |
| 下載成功卻一直顯示「未下載」 | 舊版橋接不回報下載狀態；新版網頁會直接提示「伺服器版本較舊，請用最新 notebook 重啟」 |

## 🌐 網頁版（免安裝）

- 開頁一顆大按鈕「**▶ 直接開始**」（免帳號、資料存本機）；照護者可用 Google 登入做跨裝置同步。會記住上次使用的分頁。
- 純瀏覽器＋自備 API 金鑰即可用：重組、AAC（含防呆雙軌模式）、復健評分、成績單、生圖。
- **讓電腦／Colab 幫忙跑運算**：設定頁啟用後，語音（GPT-SoVITS 角色聲音，情緒感知）、生圖（SD-Turbo）、文字（Qwen）都交給你的電腦或 Colab，連不上自動退回雲端／瀏覽器。
  - 同一台電腦：免設定，直接「偵測連線」（走 `127.0.0.1:9879`）。
  - Colab：填 ngrok 固定網域後按「☁️ 用此 Colab 網址連線」一鍵套用。
  - 遠端電腦：跑 `tailscale serve --bg 9879`，網頁填 `https://主機.xxx.ts.net`。
  - ⚠️ 受瀏覽器混合內容限制，HTTPS 網頁無法連區網 `http://192.168.x.x`——僅同機、Colab（ngrok）、Tailscale 三種連法。
- **雲端曲庫瀏覽**：整個 Drive 曲庫依語言分區（每區含 N/M 已下載統計），已下載 🟢 排最前，未下載 ☁️ 一鍵請運算端下載（含進度與續傳）。

## 技術架構

- **Android**：Kotlin + Jetpack Compose；Room（歷史／復健）＋ DataStore（設定）；WorkManager（提醒／推送）；CameraX + ML Kit；Vosk（離線 STT，逐字信心度）
- **網頁**：純 Vanilla JS + ES Modules（無框架、無建置步驟，GitHub Pages 直接部署）；`interaction.js` 統一觸控防呆；`kiosk.js` 防呆模式；`predict.js` 詞性預測
- **雲端同步**：Firebase Auth（Google／匿名）＋ Firestore（金鑰、設定、復健日誌，與網頁版共用結構）；電腦端額外用 Google OAuth（`drive.file`）存取使用者自己的 Drive 曲庫，同一次登入會把 Firebase 身分與 Drive 授權綁在一起
- **多語系**：strings.xml 四語資源 ＋ `I18n.tr()` 執行期翻譯層（覆蓋 lambda／ViewModel 內的動態訊息）；網頁 `data-i18n` 屬性驅動；語音中心 `I18N` 字典＋標題列語言切換（記住選擇）
- **推理分層**（依裝置能力與網路自動路由）：

  | 層級 | 引擎 | 說明 |
  | --- | --- | --- |
  | 電腦／Colab 接手 | `OffloadManager` → 9880/9881/9882（橋接 9879） | USB／區網／Tailscale／ngrok，實測 RTT 分級 |
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
| `gptSovitApiBaseUrl` | — | 電腦／Colab 位址（可存多台，連不上自動換下一個） |
| `emergencyChatId` | 空白 | Telegram 緊急通報接收者（不內建預設值） |
| `customAacItems` | 空 | 自訂 AAC 圖卡（JSON） |

## 權限（Android）

| 權限 | 用途 |
| --- | --- |
| 麥克風 | 語音輸入、跟讀錄音 |
| 定位 | GPS 情境、住家錨點、緊急通報 |
| 相機 | 前／後鏡頭辨識、鼻控追蹤 |
| 網路 | 雲端 AI、電腦／Colab 連線、Telegram |
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
│   ├── SettingsScreen.kt         設定＋雲端曲庫瀏覽（CloudLibraryPanel）＋ngrok/Colab
│   ├── I18n.kt                   執行期翻譯層（動態訊息四語）
│   └── settings/ + theme/        進階設定、AppPalette
├── ai/
│   ├── VoiceWeaverCore.kt        核心 ViewModel + InferenceEngine 介面
│   ├── CloudInferenceEngine.kt   雲端推理（電腦接手 → 雲端 → fallback）
│   ├── MediaPipeLLMEngine.kt     裝置端 Gemma/Qwen
│   ├── OffloadManager.kt         電腦接手（連線分級、/reconstruct 代理）
│   ├── LocalSovitsHelper.kt      GPT-SoVITS 連線、情緒偵測、雲端曲庫瀏覽/下載
│   └── SpeechToTextManager.kt    Vosk 離線 STT
├── work/                          WorkManager（提醒、推送、匯出）
└── MainActivity.kt

app/src/main/assets/
├── nose_tracker.html   鼻控 WebView（MediaPipe Face Mesh，四語狀態訊息）
└── poi/                離線 POI（台灣／美國）

tools/
├── GPT-SoVITS/
│   ├── voice_center_app.py     語音中心 GUI（Mac/Win 共用，四語介面；雲端曲庫管理）
│   ├── drive_uploader.py       Google Drive 曲庫（OAuth 登入合一、上傳/下載/刪除、續傳）
│   ├── emotion_models.py       情緒資料夾掃描＋偵測＋catalog（api_v2／語音中心／Colab 共用邏輯）
│   ├── api_v2.py               GPT-SoVITS 推論 API（9880）＋ /catalog /prepare
│   ├── model_setup_server.py   模型管理後端（掃描、匯入）
│   ├── text_infer_server.py    Qwen 文字推論（9882）
│   ├── image_gen_server.py     SD-Turbo 生圖（9881）
│   └── sync_voice_models.py    USB 手機↔電腦模型同步（次要路徑，雲端曲庫為主）
├── colab/
│   ├── VoiceWeaver_GPT_SoVITS_Colab.ipynb   雲端算力 notebook（掛 Drive、健檢、起服務、開 ngrok）
│   └── voiceweaver_bridge.py                Colab 端橋接（與語音中心同一套情緒/曲庫邏輯，內嵌於 notebook）
├── dmg/build_dmg.sh    macOS .dmg 打包（權重安全網）
└── win/build_win.ps1   Windows 打包
```

## 隱私與授權

- **不內建任何語音模型／角色音色**：聲音由使用者自行匯入有權使用的素材；安裝檔打包時安全網強制剔除權重。
- **曲庫存在使用者自己的 Google Drive**：電腦端用 OAuth `drive.file` 範圍存取——App 只能看到／管理它自己建立的檔案，碰不到你 Drive 裡其他東西；不會有任何檔案經過開發者。
- **金鑰不入版控**：`.env`、`app/google-services.json`、`google_client_secret.json` 皆已 `.gitignore`；API 金鑰只存在使用者裝置與其個人 Firestore。
- **聲紋屬生物特徵**：啟用 GPT-SoVITS／匯入錄音前需勾選知情同意（聲音主體同意、僅個人非商業溝通用途、不得冒充他人）。
- **危機通報**：畫面與聲音僅傳給使用者自行設定的家人 Telegram，不經任何第三方伺服器。
- **第三方授權**：[GPT-SoVITS](https://github.com/RVC-Boss/GPT-SoVITS) 為 **AGPL-3.0**，`.dmg`／`.exe` 散布時隨附其 `LICENSE`。
- 本專案程式碼本身尚未公開授權（保留所有權利）；如需重用請先聯絡作者。
