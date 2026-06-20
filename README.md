# VoiceWeaver

> 📦 **電腦端已搬出本專案**（2026-06）：GPT-SoVITS／conda 環境／miniforge 已移到
> `~/VoiceWeaver-電腦端/`，與 Android 程式碼分離。下文的 `tools/GPT-SoVITS/`、
> `tools/gpt-sovits-env/`、`tools/miniforge3/` 請改讀為 `~/VoiceWeaver-電腦端/` 下的同名資料夾。
> 最簡單的啟動方式：雙擊 `~/Downloads/VoiceWeaver語音中心.app`。詳見 `tools/電腦端已搬移.md`。

VoiceWeaver 是一個 Android 溝通輔助 App，整合語音重組、AAC 圖卡、定位情境、相機物件辨識、語音復健、歷史紀錄、無障礙設定與開發者實驗功能。目標是讓失語、語言復健或臨場表達困難的使用者，可以用更少操作完成「我在哪裡、我想要什麼、我要怎麼說」。

## 功能總覽

| 分類 | 功能 |
| --- | --- |
| 語音重組 | 語音輸入、碎詞整理、AI/規則重組、TTS 朗讀、歷史保存 |
| AAC 圖卡 | 搜尋、下拉分類、組合句、情境推薦、自訂金額、強意圖保護 |
| 定位情境 | 手動地點、GPS、住家錨點、Geocoder、Overpass、離線 POI、台灣/美國支援 |
| 相機辨識 | 後鏡頭、前鏡頭、前後一起拍、獨立預覽、單次辨識、橫直向支援 |
| 復健 | 自訂練習句、歷史句挑選、AI 建議、跟讀錄音、發音評分、場景統計 |
| 歷史 | 重組紀錄、收藏、刪除、清空、地點標籤 |
| 無障礙 | 字體倍率、快速字體大小、TTS 語速、眼動控制、眨眼掃描 |
| 主題與語言 | 科技風、可愛風、動漫風、簡約風；繁中、英文、日文、韓文 |
| GPT-SoVITS | 開發者模式、Mac/PC server、中文/英文語音、自動切換、模型匯入、合成測試 |
| 背景 | 預設動漫背景、自訂背景、永久保存、預覽/刪除 |
| 安全 | 緊急關鍵字、GPS 通報、Telegram Chat ID、文字安全清理 |

## 畫面結構

### 主畫面：VoiceWeaver

主畫面負責「輸入、感知、重組、朗讀」。

- 顯示 App 標題與目前主題。
- 可以手動選擇目前地點。
- 可以按 GPS 取得目前位置。
- 可以按相機辨識環境標籤。
- 可以輸入或錄下碎詞。
- 可以把碎詞、地點、相機標籤一起送進重組流程。
- 可以朗讀重組後的句子。
- 可以把重組結果送到復健分頁練習。
- 可以展開 AAC 圖卡區，直接用圖卡組句。
- 支援橫向與直向版面。

### 復健分頁

復健分頁負責語音練習與紀錄。

- 未開始練習時，可輸入自訂目標句。
- 可從近期歷史句中挑選練習句。
- 可請 AI 產生練習句建議。
- 已開始練習時，可播放整句示範。
- 可逐字或逐段跟讀。
- 可錄音並由發音評估模組計分。
- 自動寫入復健紀錄。
- 顯示最近 20 筆練習紀錄。
- 依地點統計平均分數。
- 開發者模式下可進入故事訓練。

### 歷史分頁

歷史分頁保存使用者已產生的溝通句。

- 顯示所有重組紀錄。
- 顯示時間與地點標籤。
- 可收藏常用句。
- 可刪除單筆紀錄。
- 可清空全部紀錄。
- 收藏紀錄會以不同邊框提示。

### 設定分頁

設定分頁管理全 App 行為。

- 主題切換。
- 語言切換。
- 耗能模式。
- 雲端/本地 AI 提供者。
- TTS 語速。
- 字體倍率。
- 自動保存復健紀錄。
- 眼動控制。
- 相機鏡頭。
- 自訂背景。
- 緊急通報 Chat ID。
- 住家位置與半徑。
- 開發者模式與 GPT-SoVITS。
- 本地 AI 清理。
- 一鍵重置設定。

## 語音重組

### 輸入來源

- 手動文字輸入。
- 語音轉文字。
- AAC 圖卡組合。
- GPS 地點標籤。
- 相機辨識標籤。
- 近期歷史與復健候選句。

### 重組流程

1. 使用者輸入碎詞，例如「水、休息、現在」。
2. App 將碎詞、目前地點、相機標籤整合成 `GlobalTagsVault`。
3. 安全檢查會先攔截高風險文字。
4. 推理引擎依設定選擇本地規則、雲端模型或 fallback。
5. 產生自然句，例如「我口渴了，請給我一杯水，我想休息一下。」
6. TTS 或 GPT-SoVITS 朗讀。
7. 結果寫入歷史紀錄。

### 推理模式

- 低階裝置：使用 `RegexTemplateEngine`，避免載入大型模型。
- 中高階裝置：可使用本地或雲端推理。
- 雲端自動模式：有網路時使用雲端，失敗時回退本地。
- 本地優先：可強制僅使用本地規則。

### 支援的雲端提供者

- 自動。
- 僅本地。
- Groq · Llama 3.3 70B。
- OpenRouter · Gemini Flash。
- Cohere · Command-R。

## AAC 圖卡

AAC 區塊用於不方便打字或說話時快速組句。

### 操作方式

1. 打開主畫面的 AAC 圖卡區。
2. 使用搜尋欄輸入關鍵字。
3. 使用下拉分類選擇類別。
4. 點選圖卡加入組合區。
5. App 會依圖卡順序組成句子。
6. 可送出朗讀、重組或保存。

### 分類

| 類別 | 用途 | 例子 |
| --- | --- | --- |
| 商品 | 物品、證件、日用品 | 手機、鑰匙、錢包、證件、輪椅 |
| 飲食 | 食物、飲料、溫度甜度 | 一杯水、我要喝水、請給我水、少冰、無糖 |
| 金額 | 價格、數量、付款方式 | 多少錢、$100、1 包、現金、刷卡 |
| 動作 | 請求動作或回應 | 等一下、打電話、寫下來、我要買 |
| 需求 | 生理與環境需求 | 我要休息、口渴、肚子餓、需要充電 |
| 人物 | 對象或協助者 | 家人、醫生、護理師、店員、司機 |
| 地點 | 常見場景 | 家裡、醫院、藥局、餐廳、機場 |
| 醫療 | 症狀與醫療請求 | 不舒服、胸口痛、喘不過氣、需要藥 |
| 時間 | 時間詞 | 現在、等一下、今天、什麼時候相關語境 |
| 問句 | 直接提問 | 在哪裡？、什麼時候？、多少錢？ |
| 心情 | 情緒表達 | 緊張、害怕、開心、不安 |
| 緊急 | 高優先級求助 | 救命、需要救護車、請聯絡家人 |

### 設計原則

- 具體需求不會被當成翻譯指令。
- 「一杯水」保留為飲水需求，不改寫成「翻譯一杯水」。
- 「我要去」屬於行動意圖，不會被錯放到翻譯類。
- 「什麼時候？」放在問句類，搜尋也能找到。
- 強烈意圖項目會進入安全檢查與通報判斷。
- 自訂金額支援多幣別：新台幣、美元、日圓、韓圜、人民幣、歐元。

## 定位情境

定位功能會幫助 App 理解使用者所在場景，讓句子更貼近現場。

### 來源

- 手動地點下拉選單。
- GPS 單次定位。
- Android Geocoder。
- OpenStreetMap Overpass API。
- 離線 POI 資料。
- POI 規則分類。
- 使用者自訂住家位置。

### 支援地區

- 台灣常見場景。
- 美國常見場景。
- 離線 POI 檔內含大量台灣與美國座標資料。

### 住家錨點

- 使用者可以把目前 GPS 設為住家。
- 可設定住家地址文字。
- 可設定住家半徑，預設 150 公尺。
- 若 GPS 在半徑內，App 會優先判定為住家。
- 避免住家附近店家被誤判成目前位置。

### POI 回退順序

1. 使用者手動選擇。
2. 住家半徑命中。
3. GPS + Geocoder。
4. Overpass 查附近 POI。
5. 離線 POI。
6. 規則分類。
7. 未知地點。

## 相機與視覺辨識

相機功能用於取得環境標籤，輔助語音重組。

### 鏡頭模式

- 後攝影機。
- 前攝影機。
- 前後一起拍。

### 預覽與拍照

- 預覽用獨立按鈕開啟。
- 預覽關閉後會釋放 CameraX。
- 單次辨識會拍一張照片後自動釋放相機。
- 前後一起拍會依序拍後鏡頭與前鏡頭，再合併標籤。
- 支援橫向與直向使用。

### 使用情境

- 辨識使用者眼前物品。
- 辨識目前是否在桌邊、櫃台、病房等環境。
- 把相機標籤加入重組句，讓句子更具體。

## 語音輸出與 TTS

### Android TTS

- 預設使用 Android 系統 TTS。
- 支援語速調整。
- GPT-SoVITS 未設定或 API 失敗時會自動回退。

### GPT-SoVITS 實驗語音

GPT-SoVITS 是開發者模式專屬功能。

- 換模型完整手冊：[docs/GPT_SOVITS_MODEL_GUIDE.md](docs/GPT_SOVITS_MODEL_GUIDE.md)。
- 可開關 GPT-SoVITS。
- 可選自動、中文、英文。
- 自動模式會依文字語言切換中英文樣本。
- 可設定中文參考音檔。
- 可設定英文參考音檔。
- 可設定中文模型資料夾。
- 可設定英文模型資料夾。
- 可自動匯入整包下載資料夾。
- 可清除匯入暫存與測試資料。
- 可設定 API 位址。
- 可按「合成測試（中英連跑）」實際合成並播放。

### 模型資料夾格式

中文與英文各一包，每包內可放：

```text
SoVITS_weights/*.pth
GPT_weights/*.ckpt
```

v2/v3 模型也支援：

```text
SoVITS_weights_v2/*.pth
GPT_weights_v2/*.ckpt
SoVITS_weights_v3/*.pth
GPT_weights_v3/*.ckpt
```

自動匯入會依資料夾名稱判斷：

- 中文：`zh`、`chinese`、`中文`。
- 英文：`en`、`english`。

## GPT-SoVITS Server 連線方式

GPT-SoVITS 是 Python + PyTorch 桌面框架，Android APK 不能直接執行 `.pth` 權重。可靠做法是在 Mac/PC 跑 API server，手機透過同一個 Wi-Fi 連到電腦。

### 為什麼 Termux 會出現 `No matching distribution found for torch`

官方 PyTorch pip wheel 支援桌面/伺服器平台，例如 Linux、macOS、Windows，但不支援 Android/Termux 的 Python ABI。因此在 Termux 執行下面指令會失敗：

```sh
pip install torch --index-url https://download.pytorch.org/whl/cpu
```

錯誤會類似：

```text
ERROR: Could not find a version that satisfies the requirement torch
ERROR: No matching distribution found for torch
```

這不是使用者操作錯誤，而是平台不相容。Termux 可以安裝許多 Linux 工具，但它不是一般 glibc Linux；PyTorch 官方 wheel 不能直接套用。

### F-Droid 安裝 Termux

1. 手機瀏覽器打開 [F-Droid](https://f-droid.org)。
2. 下載並安裝 F-Droid APK。
3. 打開 F-Droid，等待第一次更新軟體庫。
4. 搜尋並安裝 [Termux](https://f-droid.org/en/packages/com.termux/)。
5. 不要使用 Google Play 版本 Termux。

### 推薦：電腦 LAN 方案

在電腦上啟動本專案已安裝好的 GPT-SoVITS：

```bash
cd /Users/timchien/AndroidStudioProjects/VoiceWeaver
./tools/GPT-SoVITS/start_api.sh
```

這兩行要在 Mac 的「終端機」執行，不是在 Termux 執行。Termux 是手機環境，看不到 Mac 的 `/Users/timchien/...` 資料夾。

查 Mac 的 Wi-Fi IP：

```bash
ipconfig getifaddr en0
```

假設查到 `192.168.1.100`，VoiceWeaver 的 GPT-SoVITS API 位址填：

```text
http://192.168.1.100:9880
```

注意：不要加 `/tts`，App 會自動呼叫 `/tts`。

### VoiceWeaver 內操作

無線（Wi-Fi）：

1. 設定 → 開發者模式 → GPT-SoVITS → 切「無線 (Wi-Fi LAN)」chip。
2. API 位址改成 `http://電腦IP:9880`（在 Mac 跑 `ipconfig getifaddr en0` 查）。
3. 手機與 Mac 連同一個 Wi-Fi。
4. 按「測試連線」綠燈 → 「🎤 合成測試（中英連跑）」。

有線（USB / adb reverse — 推薦）：

1. USB 線把手機接到 Mac，授權 USB 偵錯。
2. Mac 終端機跑 `adb reverse tcp:9880 tcp:9880`。
3. 設定 → 開發者模式 → GPT-SoVITS → 切「有線 (USB)」chip（API URL 自動填 `http://127.0.0.1:9880`）。
4. Mac 跑 `./tools/GPT-SoVITS/start_api.sh`。
5. 按「測試連線」綠燈 → 「🎤 合成測試（中英連跑）」。

USB 模式比 Wi-Fi 穩，不受路由器或網路限制影響，延遲也較低；出門復健時可直接拿 USB 線連電腦。

### 參考音檔放置

App 把預設角色 ref 音檔放在：

- 手機端：`/sdcard/Android/media/<package>/VoiceWeaver/voices/furina/ref_zh.wav` 與 `ref_en.wav`
- Mac 端：`/Users/timchien/AndroidStudioProjects/VoiceWeaver/tools/GPT-SoVITS/ref_audio/furina/`

SoVITS `api_v2.py` 要的是 server-side 絕對路徑，所以 App 在發 `/tts` 時會自動把手機 `file:///storage/...` URI 翻譯成 Mac 端 `MAC_REF_*_PATH` 常數（定義在 `LocalSovitsHelper.kt`）。如果要換成自備且已授權的角色聲音樣本，把上面兩個資料夾的 `ref_*.wav` 同步替換掉即可（建議乾淨的 3-10 秒人聲、16 kHz 以上 PCM WAV）。

`prompt_text`（音檔逐字稿）也定義在 `LocalSovitsHelper.PROMPT_ZH` / `PROMPT_EN`；替換 ref 音檔時也要同步改逐字稿，否則語音 clone 品質會掉。

### Termux 的角色

Termux 仍可從 F-Droid 安裝，用來查看說明、執行一般手機端 shell 工具，或做進階實驗。但目前不要用它直接安裝官方 PyTorch wheel 來跑 GPT-SoVITS；那條路線會卡在 `torch`。

## 本專案內的 GPT-SoVITS 工具

目前專案內已準備一套桌面版 GPT-SoVITS 工具：

- `tools/GPT-SoVITS/`：GPT-SoVITS 原始碼。
- `tools/gpt-sovits-env/`：Conda Python 環境。
- `tools/miniforge3/`：本地 Miniforge。
- `tools/GPT-SoVITS/user_models/GPT-SoVits-V2-models/`：使用者模型放置區。
- `tools/GPT-SoVITS/start_api.sh`：啟動本機 API。
- `tools/GPT-SoVITS/check_install.sh`：檢查安裝狀態。
- `tools/GPT-SoVITS/user_models/INSTALL_STATUS.md`：模型匯入狀態。

檢查安裝：

```bash
./tools/GPT-SoVITS/check_install.sh
```

啟動桌面 API：

```bash
./tools/GPT-SoVITS/start_api.sh
```

注意：若 `.ckpt` 或 `.pth` 只有 100 多 bytes，通常是 Git LFS pointer，不是完整模型權重，不能直接合成。

## 預設語音與背景

App 第一次啟動會自動把以下資源解壓到外部儲存區：

- `app/src/main/assets/voices/furina/`
- `app/src/main/assets/backgrounds/furina/`

輸出位置：

```text
/sdcard/Android/media/<package>/VoiceWeaver/
```

### 預設語音

- `ref_zh.wav`：中文參考音檔。
- `ref_en.wav`：英文參考音檔。
- `prompt_zh.txt`：中文參考逐字稿。
- `prompt_en.txt`：英文參考逐字稿。

### 預設背景

- `bg.png`：預設動漫背景。
- 設定會寫入 `customBackgroundUri`。
- 使用者可改用自訂背景。
- 背景設定會永久保存。
- 可預覽或刪除。

### 替換方式

1. 把自備的 `ref_zh.wav`、`ref_en.wav` 取代 assets 內同名檔。
2. 把背景圖取代 `assets/backgrounds/furina/bg.png`。
3. 重新 build APK。
4. 下一次啟動會重新複製到外部儲存。

也可以直接放到：

```text
/sdcard/Android/media/<package>/VoiceWeaver/voices/furina/
```

## 復健功能詳細說明

### 目標句來源

- 手動輸入。
- 歷史紀錄。
- AI 建議。
- 主畫面重組結果。

### 練習流程

1. 選擇目標句。
2. 播放示範語音。
3. 使用者跟讀。
4. App 錄音並轉文字。
5. 發音評估模組比較目標句與辨識結果。
6. 產生 0 到 100 分。
7. 寫入復健紀錄。

### 統計

- 最近練習紀錄。
- 依地點統計平均分。
- 可清空復健紀錄。

### 故事訓練

故事訓練是開發者模式功能。

- 只在開發者模式顯示入口。
- 高階裝置才會完整開啟。
- 使用圖片/情境卡提示說故事。
- 可請 AI 評論。
- 記錄本次分數。

## 緊急通報與安全

### 緊急通報

App 會偵測危機關鍵字，例如強烈醫療或求助意圖。

- 命中危機關鍵字時會嘗試取得 GPS。
- GPS 失敗也會送出位置未知訊息。
- 可透過 Telegram Chat ID 指定接收者。
- 預設 Chat ID 僅供測試，正式使用必須改成家屬或照護者。

### 安全文字處理

- 輸入進 LLM 前會做基本攔截。
- LLM 輸出後、TTS 前會再清理。
- AAC 強意圖項目會進入安全判斷。
- 本地 AI 可設定推理後清除快取與暫存。

## 無障礙與操作輔助

### 字體

- 全 App 字體倍率：0.8 到 1.8。
- 快速選項：一般、大、特大等。
- 設定會即時套用。

### TTS

- 語速範圍：0.5 到 1.5。
- 預設 0.85，較適合復健和照護場景。

### 眼動控制

- 可開關眼動控制。
- 凝視停留：看向字卡並停留約 1 秒選取。
- 眨眼掃描：高亮框自動移動，眨眼確認。
- 掃描間隔：600ms 到 2200ms。

## 主題、語言與外觀

### 主題

- 科技風。
- 可愛風。
- 動漫風。
- 簡約風。

### 語言

- 繁體中文。
- English。
- 日本語。
- 한국어。

### 背景顯示

- 沒有自訂背景時使用主題背景色。
- 有自訂背景時，主畫面與導航列會使用半透明覆蓋。
- 自訂背景會永久保存。

## 設定項目清單

| 設定 | 預設 | 說明 |
| --- | --- | --- |
| theme | 科技風 | App 視覺風格 |
| language | 繁體中文 | 介面語言 |
| powerMode | 標準 | 省電、標準、高效能 |
| cloudProvider | 自動 | 雲端或本地 AI 路由 |
| ttsRate | 0.85 | 朗讀速度 |
| fontScale | 1.0 | 全域字體倍率 |
| autoSaveRehab | true | 自動保存復健紀錄 |
| eyeControlEnabled | false | 眼動控制 |
| eyeControlMode | 凝視停留 | 凝視或眨眼掃描 |
| eyeScanIntervalMs | 1200 | 眨眼掃描速度 |
| cameraLens | 後攝影機 | 後、前、前後一起拍 |
| gptSovitEnabled | false | GPT-SoVITS 實驗語音 |
| gptSovitVoiceMode | 自動切換 | 中文/英文/自動 |
| gptSovitApiBaseUrl | 空 | SoVITS API 位址 |
| customBackgroundUri | 空 | 自訂背景 |
| autoLocalAiCleanup | true | 推理後清理本地 AI 快取 |
| developerMode | false | 開發者功能 |
| defaultCurrency | 新台幣 | AAC 自訂金額預設幣別 |
| emergencyChatId | 測試 ID | Telegram 緊急通報接收者 |
| homeRadiusMeters | 150 | 住家命中半徑 |

## 權限

| 權限 | 用途 |
| --- | --- |
| 麥克風 | 語音輸入、跟讀錄音 |
| 定位 | GPS 情境、住家錨點、緊急通報 |
| 相機 | 前/後鏡頭預覽與單次辨識 |
| 網路 | 雲端 AI、Overpass、Google Places、模型下載、Telegram |
| 儲存/媒體 | 匯入參考音檔、模型資料夾、自訂背景 |

## 資料保存

### Room

- `SpeechRecord`：語音重組歷史。
- `RehabLog`：復健練習紀錄。
- `AacComboLog`：AAC 組合與評分紀錄。
- `StoryTrainingLog`：故事訓練紀錄。

### DataStore

- App 設定。
- GPT-SoVITS URI。
- 自訂背景 URI。
- 住家位置。
- 緊急通報設定。

### Assets

- `app/src/main/assets/poi/`：離線 POI。
- `app/src/main/assets/voices/`：預設參考音檔。
- `app/src/main/assets/backgrounds/`：預設背景。
- `app/src/main/assets/models/`：本地模型資源。

## 專案結構

```text
app/src/main/java/com/example/ui/
```

Compose 畫面與 UI 元件。

```text
app/src/main/java/com/example/ai/
```

語音、定位、視覺辨識、推理、TTS、GPT-SoVITS、ViewModel。

```text
app/src/main/java/com/example/data/
```

Room、DataStore、AAC 資料、設定模型。

```text
app/src/main/java/com/example/safety/
```

安全檢查與緊急通報。

```text
app/src/main/java/com/example/net/
```

Telegram API。

```text
tools/GPT-SoVITS/
```

桌面版 GPT-SoVITS 工具與測試環境。

## 建置

使用 Android Studio 內建 JBR：

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:compileDebugKotlin
```

建置 Debug APK：

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug
```

## 測試

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest
```

檢查 GPT-SoVITS：

```bash
./tools/GPT-SoVITS/check_install.sh
```

## 安裝到裝置

先確認手機已開啟 USB 偵錯並連上：

```bash
/Users/timchien/Library/Android/sdk/platform-tools/adb devices
```

安裝 Debug 版：

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:installDebug
```

## 使用建議

- 一般使用：耗能模式選 `標準`，AI 提供者選 `自動`。
- 長時間外出：耗能模式選 `省電`，相機採單次辨識。
- 低視力或長輩使用：字體調到 `大` 或 `特大`。
- 需要眼動操作：開啟眼動控制並調整掃描間隔。
- 要用 GPT-SoVITS：先在 Mac/PC 跑 API server，再按 `測試連線`。
- 正式緊急通報：務必更換 Telegram Chat ID。

## 開發注意

- 不要提交 API key、Telegram token、正式醫療資料或私人 Chat ID。
- 相機預覽與單次辨識共用同一個鏡頭設定。
- 定位功能在無網路時會回退到離線 POI 與規則分類。
- AAC 項目應避免把具體需求誤分類成翻譯指令。
- GPT-SoVITS 的角色音色、參考音檔與模型權重必須確認授權。
- `.ckpt` / `.pth` 若只有 100 多 bytes，通常不是完整權重，而是 Git LFS pointer。
