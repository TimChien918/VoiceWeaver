# VoiceWeaver 語音中心 — Windows 版

這是 macOS 版「語音中心」（`tools/dmg/`）的 Windows 對應實作，**邏輯完全相同**：
發行包自帶一份 conda 打包好的 Python 環境與後端程式碼，使用者雙擊啟動器後，第一次會自動把環境
解壓到使用者資料夾並完成重定位（conda-unpack），之後就秒開一個原生控制視窗，負責啟動／停止
三個本機服務、顯示手機連線位址、`adb reverse` 轉接與聲音模型同步。

| 對照 | macOS (`tools/dmg/`) | Windows (`tools/win/`) |
| --- | --- | --- |
| 啟動器 | `launch.sh` | `launch.ps1` + `VoiceWeaver語音中心.cmd` |
| 打包腳本 | `build_dmg.sh` | `build_win.ps1` |
| 圖示產生器 | `make_icon.py`（`.icns`） | `make_ico.py`（`.ico`） |
| 安裝資料夾 | `~/Library/Application Support/VoiceWeaver` | `%LOCALAPPDATA%\VoiceWeaver` |
| 環境 python | `gpt-sovits-env/bin/python` | `gpt-sovits-env\python.exe` |
| 重定位 | `bin/conda-unpack` | `Scripts\conda-unpack.exe` |
| 解壓工具 | `tar`（系統內建） | `tar.exe`（Win10 1803+ 內建） |
| 程式碼更新 | `rsync`（不 `--delete`） | `robocopy`（不 `/MIR`） |
| 發行格式 | `.dmg`（拖到應用程式） | `.zip`（解壓到任意位置） |
| 服務埠 | 9880 語音 / 9881 生圖 / 9882 文字 | 同左 |

控制視窗本體（`voice_center_app.py`）與後端（`model_setup_server.py`）已改為**跨平台**：
深色模式偵測、`adb`/Tailscale 路徑、區網 IP、結束服務（`netstat`+`taskkill`）、試聽／檔案總管顯示
都會依作業系統自動切換。這些檔案位於 `~/VoiceWeaver-電腦端/GPT-SoVITS/`（已搬出 Android 專案）。

---

## ⚠️ 重要：conda 環境不可跨平台

conda 環境是**平台相依**的——macOS 上打包的 `gpt-sovits-env.tar.gz` 不能拿到 Windows 跑（torch、
原生 `.so`/`.dll`、`python.exe` 都不同）。因此 **Windows 安裝包必須在一台 Windows 電腦上重建環境後再打包**。

GPU 注意：macOS 走 Apple **MPS**；Windows 上 `pip install torch` 預設是 **CPU 版**（語音/生圖會較慢）。
若有 NVIDIA 顯卡，請改裝 CUDA 版 torch（見 <https://pytorch.org/get-started/locally/>）。

---

## A. 在 Windows 上直接執行（開發 / 已有環境）

若該 Windows 機器已有 `%USERPROFILE%\VoiceWeaver-電腦端\`（含 `GPT-SoVITS\` 與 `gpt-sovits-env\`）：

```powershell
& "$env:USERPROFILE\VoiceWeaver-電腦端\gpt-sovits-env\python.exe" `
  "$env:USERPROFILE\VoiceWeaver-電腦端\GPT-SoVITS\voice_center_app.py"
```

控制視窗會自動啟動三個服務並嘗試 `adb reverse`。語音(9880) 需先在「模型 / 進階」分頁匯入聲音模型。

---

## B. 打包成可下載安裝包（給沒有環境的使用者）

### 1) 在 Windows 上建立並打包 conda 環境

```powershell
# 假設已用 Miniforge 在 Windows 建好 gpt-sovits-env（裝好 torch / diffusers / fastapi…）
conda activate gpt-sovits-env
pip install conda-pack
$Stage = "$env:TEMP\vwwin"
New-Item -ItemType Directory -Force "$Stage" | Out-Null
conda pack -n gpt-sovits-env -o "$Stage\gpt-sovits-env.tar.gz" --format tar.gz
```

### 2) 備妥精簡後端 payload（不含任何權重）

```powershell
robocopy "$env:USERPROFILE\VoiceWeaver-電腦端\GPT-SoVITS" "$Stage\payload\GPT-SoVITS" /E `
  /XD pretrained_models user_models ref_audio G2PWModel .cache __pycache__ .git
# *weights* 目錄不要複製；build_win.ps1 還有一道安全網會強制清除並在偵測到時中止。
```

### 3) 打包

```powershell
tools\win\build_win.ps1
# 產出：%USERPROFILE%\Downloads\VoiceWeaver-語音中心-Windows.zip
```

可用環境變數覆寫：`VW_WIN_STAGE`（素材目錄）、`VW_ENV_PY`（產圖示用的 python）、`VW_VERSION`。

---

## C. 使用者端安裝與啟動

1. 解壓 `VoiceWeaver-語音中心-Windows.zip` 到任意位置（例：桌面）。
2. 雙擊資料夾內的 **`VoiceWeaver語音中心.cmd`**。
3. 首次會解壓 AI 環境（約 1~3 分鐘）→ 自動開啟控制視窗；之後秒開。
4. 想要桌面捷徑：對 `.cmd` 按右鍵 →「傳送到」→「桌面（建立捷徑）」，再到捷徑內容把圖示換成
   同資料夾的 `AppIcon.ico`。

> 若 Windows SmartScreen 攔截，按「更多資訊」→「仍要執行」。`.cmd` 只是用隱藏視窗呼叫
> `launch.ps1`（`-ExecutionPolicy Bypass`），不會更動系統 PowerShell 原則。

---

## 手機怎麼連（與 macOS 相同）

控制視窗「控制台」分頁會即時顯示三種位址，擇一填到 App 的伺服器位址欄：

- **有線 USB**（推薦）：手機填 `http://127.0.0.1:9880`；接上 USB 後控制視窗會自動 `adb reverse`。
  找不到 `adb` 時會自動下載 Google 官方 platform-tools（Windows 版）。
- **無線 Wi-Fi**：填電腦區網 IP，例 `http://192.168.x.x:9880`（手機與電腦同網段）。
- **全域網 Tailscale**：電腦與手機都裝並登入同帳號後，填 `http://100.x.x.x:9880`
  （4G/5G/外網皆可，下載 <https://tailscale.com/download>）。

關閉控制視窗 → 自動停止所有服務（含語音合成）。
