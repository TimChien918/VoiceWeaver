# 拾語者 Speak My Life 語音中心 — Windows 版

> 產品已更名為「拾語者 Speak My Life」（原名 VoiceWeaver）。視窗標題與 exe 產品資訊用新名字；
> 檔名（`VoiceWeaver語音中心.exe`）與資料夾（`%LOCALAPPDATA%\VoiceWeaver`）沿用舊名以維持既有安裝相容。

macOS 版「語音中心」（`tools/dmg/`）的 Windows 對應實作。控制視窗本體
（`tools/GPT-SoVITS/voice_center_app.py`）與後端（`model_setup_server.py`、`sync_voice_models.py`）
皆為**跨平台**：深色模式偵測、`adb`/Tailscale 路徑、區網 IP、結束服務（`netstat`+`taskkill`）、
試聽／檔案總管顯示都依作業系統自動切換。

> **設計理念：使用者打開急用。** 所以 Windows 版是「**小安裝包 + 首次自動安裝**」——
> 安裝包本身只有幾 MB / 幾十 KB，第一次開啟時才自動把約 2GB 的 AI 環境從**公開來源**裝好，
> 不需要任何人預先打包、上傳 2GB 檔案。之後每次都是秒開。

控制視窗負責啟動／停止三個本機服務（語音 9880 / 生圖 9881 / 文字 9882）、顯示手機連線位址、
`adb reverse` 轉接與聲音模型雙向同步；關閉視窗即停止所有服務。

---

## 兩種發行格式（擇一）

| | 單一 exe（推薦） | 小 zip 安裝包 |
| --- | --- | --- |
| 產物 | `VoiceWeaver語音中心.exe`（約 11 MB） | `VoiceWeaver-語音中心-安裝包.zip`（約 60 KB） |
| 打包腳本 | `tools/win/build_win_exe.ps1` | 把 `tools/win/*` ＋ 控制程式壓成 zip |
| 使用者操作 | 雙擊 `.exe` | 解壓 → 雙擊 `VoiceWeaver語音中心.cmd` |
| 首次安裝畫面 | 內建 tkinter 進度視窗 | WinForms 進度視窗（`launch.ps1`） |

兩者首次安裝的內容完全一樣，只是進入點不同。exe 自帶後端程式碼；zip 由 `launch.ps1`
搭配同資料夾的後端檔。

---

## 首次安裝會自動做什麼

全程有進度視窗，安裝紀錄寫在 `%LOCALAPPDATA%\VoiceWeaver\logs\install.log`。

1. **Miniforge**（官方 conda-forge 發行版，含 Python 3.10）→ `%LOCALAPPDATA%\VoiceWeaver\tools\miniforge3`
2. **建立 `gpt-sovits-env` 環境**並 pip 安裝依賴（見下方「相依版本」）
3. **後端程式碼**（`api_v2.py` / `image_gen_server.py` / `text_infer_server.py` / 設定）— 自帶在安裝包內
4. **GPT-SoVITS 推論引擎**（`GPT_SoVITS/` + `tools/`）— 取自官方上游 `RVC-Boss/GPT-SoVITS`

模型則隨需下載：SD-Turbo（生圖）與 Qwen（文字）第一次使用時自動從 HuggingFace 抓（免金鑰）；
聲音模型(9880) 由使用者在手機端錄製／訓練後，透過 USB **自動雙向同步**或在「模型 / 進階」分頁匯入。

### 快速路徑（選用）：自帶或下載既成環境

若想跳過 pip、直接解壓一份 conda-pack 好的環境（最快最穩）：

- 安裝包資料夾放一份 `gpt-sovits-env.tar.gz`，或
- 設定環境變數 `VW_ENV_URL` 指向該檔的下載網址。

`launch.ps1` / exe 偵測到就會改用「下載／解壓既成環境 + `conda-unpack` 重定位」，而非 pip 自建。
（conda 環境平台相依，此檔必須在 **Windows** 上用 `conda pack` 產生。）

---

## 相依版本（為何要釘選）

三個服務共用同一個 Python 環境，所以版本必須**互相相容**。以下組合是踩過雷後確認可用的：

| 套件 | 版本 | 原因 |
| --- | --- | --- |
| `torch` / `torchaudio` | `2.4.1` | 更新版 torchaudio 會強制改用 `torchcodec`（需 FFmpeg DLL，Windows 沒有）。 |
| `diffusers` | `0.31.0` | 更新版需要 torch ≥ 2.5 的 op schema，與上面衝突。 |
| `transformers` | `>=4.43,<=4.50` | GPT-SoVITS 依賴的 `peft` 會 `import HybridCache`，新版 transformers 已移除。 |
| `jieba_fast` | **shim** | 中文前端直接 `import jieba_fast`，但 Windows 無預編譯輪子（需 C 編譯器）。安裝時建立一個把 `jieba_fast` 轉接到純 Python `jieba` 的相容 shim。 |
| `pyopenjtalk` | 選用 | 只用於**日語**前端，Windows 無預編譯輪子；裝不起來會自動略過，不影響生圖／文字／中文語音。 |

### GPU / CUDA 自動偵測

安裝時用 `nvidia-smi --query-gpu=compute_cap` 偵測 GPU 運算能力：

- **compute capability ≥ 5.0（Maxwell 以後）** → 裝 **CUDA 版** torch（cu121，約 2.5GB），三服務皆 GPU 加速。
- 否則（含 Kepler 等老卡、或舊驅動查不到）→ 裝 **CPU 版**。
  > 現代 PyTorch 的 CUDA 輪子已放棄支援 Kepler（compute 3.x，如 GTX 7xx）；硬裝 CUDA 版在這些卡上會
  > 直接跑不起來，所以門檻會讓它們正確退回 CPU。

生圖／文字服務以 `torch.cuda.is_available()` 自動選裝置；語音(9880) 需在設定檔寫入 `device: cuda`，
控制視窗啟動時會偵測並設定 `VOICEWEAVER_DEVICE=cuda`，切換模型時 `update_config` 即寫成 GPU（半精度）。

---

## 打包（給開發者）

### 單一 exe（推薦）

```powershell
# 任何系統 Python 皆可（不需 AI 環境），先裝打包工具：
python -m pip install pyinstaller pillow
# 打包：
pwsh tools\win\build_win_exe.ps1
# 產出：dist\VoiceWeaver語音中心.exe
```

`build_win_exe.ps1` 會即時把正規的後端檔（`api_v2.py`、各 server、`GPT_SoVITS/configs`）塞進 exe，
不需在 repo 提交重複的 `backend/` 目錄。

### 既成環境 zip（快速路徑用）

`tools/win/build_win.ps1` 仍可把「自帶 conda-pack 環境」打包成 zip（需先在 Windows 上
`conda pack` 出 `gpt-sovits-env.tar.gz` 與後端 payload）。詳見該腳本檔頭。

---

## 使用者端安裝與啟動

1. 取得 `VoiceWeaver語音中心.exe`（或解壓 zip）。
2. 雙擊執行。第一次會跳出安裝進度視窗，自動下載安裝 AI 環境（約 2GB，視網速 10–30 分鐘），
   完成後自動開啟控制台；**之後秒開**。
3. 想要桌面捷徑：直接把 exe 放桌面即可（zip 版可對 `.cmd` 右鍵 →「傳送到」→「桌面（建立捷徑）」）。

安裝若中途失敗，視窗會顯示「重試安裝」與「開啟安裝紀錄」（`install.log`）。

### 關於 SmartScreen「Windows 已保護您的電腦」

第一次執行時可能跳出藍色的 **Microsoft Defender SmartScreen**「已防止某個無法辨識的應用程式啟動」。
這**不是病毒警告**，而是 Windows 對「**未經程式碼簽章 + 從網路下載**」的 exe 一律先攔。本專案
未購買程式碼簽章憑證，所以會被歸類為「無法辨識」。exe 已內嵌產品資訊，按「其他資訊」會看到
產品名稱顯示 **拾語者 語音中心**。處理方式（任選）：

1. **點過去（一次性）**：按「**其他資訊**」→「**仍要執行**」。
2. **解除封鎖（讓它不再跳）**：對 exe 按右鍵 →「**內容**」→ 最下面勾「**解除封鎖 (Unblock)**」→ 確定。
   或 PowerShell：`Unblock-File "路徑\VoiceWeaver語音中心.exe"`。這會移除「來自網路」標記，之後不再攔。
3. **本機自行打包**：用 `build_win_exe.ps1` 在自己電腦打包出的 exe 屬「本機產生」、沒有網路標記，
   SmartScreen 不會攔（別放進 OneDrive 同步資料夾，雲端回抓會重新加上網路標記）。
4. **改用 zip 安裝包**：入口是 `.cmd` 啟動器而非 exe，不會觸發這個「無法辨識的應用程式」攔截。

> **完整消除（給發行者）**：唯一能對所有下載者根本免除 SmartScreen 的方式是用 **Authenticode 程式碼
> 簽章憑證**簽 exe（EV 憑證即時生效，一般憑證需累積下載量建立信譽）。自簽憑證無法消除 SmartScreen
> （它看的是微軟雲端信譽，不是本機信任），僅能去除 UAC「不明發行者」提示。

---

## 手機怎麼連（與 macOS 相同）

控制視窗「控制台」分頁會即時顯示三種位址，擇一填到 App 的伺服器位址欄：

- **有線 USB**（推薦）：手機填 `http://127.0.0.1:9880`；接上 USB 後控制視窗會自動 `adb reverse`，
  找不到 `adb` 時自動下載 Google 官方 platform-tools（Windows 版）。接上 USB 也會**自動雙向同步聲音模型**。
- **無線 Wi-Fi**：填電腦區網 IP，例 `http://192.168.x.x:9880`（手機與電腦同網段）。
- **全域網 Tailscale**：兩端都裝並登入同帳號後，填 `http://100.x.x.x:9880`（4G/5G/外網皆可）。

關閉控制視窗 → 自動停止所有服務（含語音合成）。

---

## 安裝位置與疑難排解

| 項目 | 路徑 |
| --- | --- |
| AI 環境 / 後端 / 引擎 | `%LOCALAPPDATA%\VoiceWeaver\tools\` |
| 安裝紀錄 | `%LOCALAPPDATA%\VoiceWeaver\logs\install.log` |
| 模型快取（HF） | `%LOCALAPPDATA%\VoiceWeaver\.cache\huggingface` |

- **語音 9880 起不來**：先確認「模型 / 進階」分頁看得到聲音模型（USB 同步後應有）。看 `install.log`
  確認 GPT-SoVITS 引擎與依賴是否裝好；引擎若上次安裝缺漏，控制視窗下次開啟會背景自動補裝。
- **想重裝**：刪掉整個 `%LOCALAPPDATA%\VoiceWeaver` 資料夾，再開一次即可全新安裝。
- **`.ps1` 中文亂碼 / 解析失敗**：PowerShell 5.1 需 UTF-8 **with BOM** 才能正確解析含中文的 `.ps1`。
