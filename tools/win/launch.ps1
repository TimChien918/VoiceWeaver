<#
  VoiceWeaver 語音中心（Windows 發行版）— 雙擊即用。

  與 macOS 的 tools/dmg/launch.sh 同邏輯：這個版本是「自帶環境」的，發行資料夾內含
  conda-pack 打包好的 Python 環境（gpt-sovits-env.tar.gz）與全部後端程式碼（payload）。
  第一次開啟時會自動把環境解壓到使用者資料夾並完成重定位（conda-unpack），之後就秒開。

    首次：解壓 gpt-sovits-env（~2GB）+ conda-unpack + 複製後端程式碼 → 約 1~3 分鐘
    之後：直接啟動原生控制視窗（語音 9880 / 生圖 9881 / 文字 9882）

  生圖模型 SD-Turbo（~2.5GB）會在第一次生圖時自動下載（免金鑰）。
  語音合成(9880) 需要使用者自備的聲音模型，於控制視窗「模型 / 進階」分頁匯入後才會啟用。
#>
$ErrorActionPreference = 'Stop'
$Title = 'VoiceWeaver 語音中心'

# ---- 路徑 --------------------------------------------------------------------
$RES        = $PSScriptRoot                                   # 發行資料夾（含 env tar 與 payload）
$APPSUP     = Join-Path $env:LOCALAPPDATA 'VoiceWeaver'       # ≈ macOS 的 ~/Library/Application Support
$TOOLS      = Join-Path $APPSUP 'tools'
$ENV_DIR    = Join-Path $TOOLS 'gpt-sovits-env'
$GS         = Join-Path $TOOLS 'GPT-SoVITS'
$PY         = Join-Path $ENV_DIR 'python.exe'
$PYW        = Join-Path $ENV_DIR 'pythonw.exe'
$LOG_DIR    = Join-Path $APPSUP 'logs'
$MARK       = Join-Path $APPSUP '.code_version'               # 後端程式碼版本標記
$ENV_MARK   = Join-Path $APPSUP '.env_version'                # 環境版本標記
$ENV_TAR    = Join-Path $RES 'gpt-sovits-env.tar.gz'
$PAYLOAD    = Join-Path $RES 'payload'
$INSTALL_LOG = Join-Path $LOG_DIR 'install.log'

$VERSION     = (Get-Content (Join-Path $RES 'VERSION')     -ErrorAction SilentlyContinue) -join ''
$ENV_VERSION = (Get-Content (Join-Path $RES 'ENV_VERSION') -ErrorAction SilentlyContinue) -join ''
if (-not $VERSION)     { $VERSION = '1' }
if (-not $ENV_VERSION) { $ENV_VERSION = '1' }

New-Item -ItemType Directory -Force -Path $LOG_DIR | Out-Null

# ---- 小工具 ------------------------------------------------------------------
function Alert([string]$msg) {
  try { (New-Object -ComObject Wscript.Shell).Popup($msg, 0, $Title, 0x40) | Out-Null } catch {}
}
function Read-Mark([string]$path) {
  if (Test-Path $path) { return ((Get-Content $path -ErrorAction SilentlyContinue) -join '') }
  return ''
}

# ---- 單一實例：控制視窗已在跑就不再開第二個 ---------------------------------
$running = Get-CimInstance Win32_Process -Filter "Name='pythonw.exe'" -ErrorAction SilentlyContinue |
           Where-Object { $_.CommandLine -like '*voice_center_app.py*' }
if ($running) { exit 0 }

# ---- 安裝 / 更新 ------------------------------------------------------------
# (1) AI 環境（重，~2GB）：缺少、或環境版本不同時才重新解壓。
if ((-not (Test-Path $PY)) -or ((Read-Mark $ENV_MARK) -ne $ENV_VERSION)) {
  if (-not (Test-Path $ENV_TAR)) {
    Alert "安裝檔損壞：找不到內建環境（gpt-sovits-env.tar.gz）。請重新下載安裝包。"
    exit 1
  }
  Alert "VoiceWeaver 正在解壓 AI 環境（約 1~3 分鐘）。`n完成後會自動開啟，請勿關閉。"
  if (Test-Path $ENV_DIR) { Remove-Item -Recurse -Force $ENV_DIR -ErrorAction SilentlyContinue }
  New-Item -ItemType Directory -Force -Path $ENV_DIR | Out-Null
  # Windows 10 1803+ 內建 bsdtar（tar.exe），可直接解 .tar.gz
  & tar.exe -xzf "$ENV_TAR" -C "$ENV_DIR" 2>> $INSTALL_LOG
  if ($LASTEXITCODE -ne 0) {
    Alert "解壓 AI 環境失敗，請查看記錄：`n$INSTALL_LOG"
    exit 1
  }
  # conda-unpack 重定位（必須用環境自己的 python 執行）
  $unpackExe = Join-Path $ENV_DIR 'Scripts\conda-unpack.exe'
  $unpackPy  = Join-Path $ENV_DIR 'Scripts\conda-unpack-script.py'
  if (Test-Path $unpackExe) {
    & $unpackExe 2>> $INSTALL_LOG
  } elseif (Test-Path $unpackPy) {
    & $PY $unpackPy 2>> $INSTALL_LOG
  }
  Set-Content -Path $ENV_MARK -Value $ENV_VERSION -Encoding ASCII
}

if (-not (Test-Path $PY)) {
  Alert "安裝似乎未完成（找不到 Python）。請查看記錄：`n$INSTALL_LOG"
  exit 1
}

# (2) 後端程式碼（輕，~36M）：版本不同就刷新。
#     **不用 /MIR**，避免刪掉使用者同步來的 weights / pretrained_models / user_models / ref_audio。
New-Item -ItemType Directory -Force -Path $GS | Out-Null
if ((Read-Mark $MARK) -ne $VERSION) {
  # robocopy 回傳 0~7 皆為成功（8+ 才是錯誤）
  & robocopy "$PAYLOAD\GPT-SoVITS" "$GS" /E /NFL /NDL /NJH /NJS /NP /R:1 /W:1 *>> $INSTALL_LOG
  if ($LASTEXITCODE -ge 8) {
    Alert "更新程式碼失敗，請查看記錄：`n$INSTALL_LOG"
    exit 1
  }
  Set-Content -Path $MARK -Value $VERSION -Encoding ASCII
}

# ---- 啟動原生控制視窗 -------------------------------------------------------
# 伺服器的啟動/停止、adb 轉接、聲音模型同步、連線位址顯示都由控制視窗負責；
# 關閉視窗時會自動停止所有伺服器（含語音合成）。
if (-not $env:HF_HOME)       { $env:HF_HOME       = Join-Path $APPSUP '.cache\huggingface' }
if (-not $env:MPLCONFIGDIR)  { $env:MPLCONFIGDIR  = Join-Path $APPSUP '.cache\matplotlib' }
New-Item -ItemType Directory -Force -Path $env:HF_HOME, $env:MPLCONFIGDIR | Out-Null

$appPy = Join-Path $GS 'voice_center_app.py'
if (-not (Test-Path $appPy)) {
  Alert "找不到控制視窗程式（voice_center_app.py）。請重新下載安裝包。"
  exit 1
}

# 用 pythonw.exe 啟動（無主控台黑窗），LASTEXITCODE 設好後立即返回
Start-Process -FilePath $PYW -ArgumentList 'voice_center_app.py' -WorkingDirectory $GS
exit 0
