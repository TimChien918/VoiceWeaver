<#
  拾語者 Speak My Life 語音中心（Windows）— 首次開啟自我引導安裝，之後秒開。

  設計理念：使用者「打開急用」。所以這個啟動器是「小安裝包 + 首次自動下載」——
  雙擊後若偵測到本機還沒有 AI 環境，會自動從公開來源把所有東西裝好：
      1) Miniforge（conda 發行版，內含 Python）          ← 官方 conda-forge
      2) gpt-sovits-env 環境 + 全部 Python 依賴            ← conda-forge / PyPI
      3) 後端程式碼（api_v2 / 生圖 / 文字推論 …）          ← GitHub Release/原始碼
  模型（SD-Turbo 生圖、Qwen 文字）會在「第一次使用該功能時」自動下載（免金鑰）。
  聲音模型(9880) 由使用者在控制視窗「模型/進階」分頁自行匯入。

  「快速路徑」：若安裝包自帶 gpt-sovits-env.tar.gz，或設定了 $env:SML_ENV_URL
  指向預先 conda-pack 好的環境壓縮檔，就改用「下載/解壓既成環境」（最快最穩）。

  全程有進度視窗，安裝紀錄寫在 %LOCALAPPDATA%\Speak-My-Life\logs\install.log。
  （更名前的 %LOCALAPPDATA%\VoiceWeaver 第一次啟動時自動改名過來，舊位置留 junction。）
#>
$ErrorActionPreference = 'Stop'
$Title = '拾語者 語音中心'

# ── 路徑 ─────────────────────────────────────────────────────────────────
$RES      = $PSScriptRoot                                  # 安裝包資料夾
$APPSUP   = Join-Path $env:LOCALAPPDATA 'VoiceWeaver'
$TOOLS    = Join-Path $APPSUP 'tools'
$CONDA    = Join-Path $TOOLS 'miniforge3'                  # conda 安裝位置
$ENV_DIR  = Join-Path $TOOLS 'gpt-sovits-env'             # 目標環境
$GS       = Join-Path $TOOLS 'GPT-SoVITS'                  # 後端程式碼
$PY       = Join-Path $ENV_DIR 'python.exe'
$PYW      = Join-Path $ENV_DIR 'pythonw.exe'
$LOG_DIR  = Join-Path $APPSUP 'logs'
$LOG      = Join-Path $LOG_DIR 'install.log'
$READY    = Join-Path $APPSUP '.ready'                     # 安裝完成標記（含版本）

# 後端程式碼：自帶在安裝包內（私有 repo，不從網路取）
$CONTROL_VER   = '3'                                       # 控制程式碼版本（更新就刷新後端）

# 快速路徑：既成環境（自帶或下載）
$ENV_TAR_LOCAL = Join-Path $RES 'gpt-sovits-env.tar.gz'
$ENV_URL       = $env:SML_ENV_URL                           # 可選：預打包環境的下載網址

# Python 執行環境：python-build-standalone（免安裝、解壓即用、內含 pip）——比 Miniforge NSIS 可靠
$PBS_TAG = '20260623'
$PBS_URL = "https://github.com/astral-sh/python-build-standalone/releases/download/$PBS_TAG/cpython-3.10.20+$PBS_TAG-x86_64-pc-windows-msvc-install_only.tar.gz"

# Python / 依賴版本
$PY_VERSION = '3.10'

New-Item -ItemType Directory -Force -Path $LOG_DIR, $TOOLS | Out-Null

function Log([string]$m) {
  $ts = Get-Date -Format 'HH:mm:ss'
  "[$ts] $m" | Out-File -FilePath $LOG -Append -Encoding utf8
}
function Alert([string]$m) {
  try { (New-Object -ComObject Wscript.Shell).Popup($m, 0, $Title, 0x40) | Out-Null } catch {}
}

# ── 進度視窗（WinForms；安裝時讓使用者看得到在動，而不是黑窗） ──────────────
Add-Type -AssemblyName System.Windows.Forms | Out-Null
Add-Type -AssemblyName System.Drawing | Out-Null

$script:Form = $null
$script:Bar  = $null
$script:Lbl  = $null

function Show-Progress {
  $f = New-Object System.Windows.Forms.Form
  $f.Text = $Title
  $f.Size = New-Object System.Drawing.Size(460, 170)
  $f.StartPosition = 'CenterScreen'
  $f.FormBorderStyle = 'FixedDialog'
  $f.MaximizeBox = $false; $f.MinimizeBox = $false
  $f.BackColor = [System.Drawing.Color]::FromArgb(26, 26, 46)
  $f.TopMost = $true

  $t = New-Object System.Windows.Forms.Label
  $t.Text = '首次設定中，請稍候…'
  $t.ForeColor = [System.Drawing.Color]::White
  $t.Font = New-Object System.Drawing.Font('Microsoft JhengHei UI', 13, [System.Drawing.FontStyle]::Bold)
  $t.Location = New-Object System.Drawing.Point(24, 18)
  $t.Size = New-Object System.Drawing.Size(410, 28)
  $f.Controls.Add($t)

  $l = New-Object System.Windows.Forms.Label
  $l.Text = '準備中…'
  $l.ForeColor = [System.Drawing.Color]::FromArgb(170, 170, 200)
  $l.Font = New-Object System.Drawing.Font('Microsoft JhengHei UI', 9)
  $l.Location = New-Object System.Drawing.Point(24, 52)
  $l.Size = New-Object System.Drawing.Size(410, 22)
  $f.Controls.Add($l)

  $b = New-Object System.Windows.Forms.ProgressBar
  $b.Style = 'Marquee'; $b.MarqueeAnimationSpeed = 30
  $b.Location = New-Object System.Drawing.Point(24, 82)
  $b.Size = New-Object System.Drawing.Size(406, 22)
  $f.Controls.Add($b)

  $h = New-Object System.Windows.Forms.Label
  $h.Text = '只有第一次需要安裝（約 2GB），完成後即秒開。'
  $h.ForeColor = [System.Drawing.Color]::FromArgb(120, 120, 150)
  $h.Font = New-Object System.Drawing.Font('Microsoft JhengHei UI', 8)
  $h.Location = New-Object System.Drawing.Point(24, 112)
  $h.Size = New-Object System.Drawing.Size(410, 20)
  $f.Controls.Add($h)

  $f.Show(); $f.Refresh()
  $script:Form = $f; $script:Bar = $b; $script:Lbl = $l
}
function Set-Step([string]$msg, [int]$pct = -1) {
  Log $msg
  if ($script:Lbl) { $script:Lbl.Text = $msg }
  if ($script:Bar) {
    if ($pct -ge 0) { $script:Bar.Style = 'Continuous'; $script:Bar.Value = [Math]::Min(100, $pct) }
    else            { $script:Bar.Style = 'Marquee' }
  }
  if ($script:Form) { $script:Form.Refresh(); [System.Windows.Forms.Application]::DoEvents() }
}
function Close-Progress { if ($script:Form) { $script:Form.Close(); $script:Form = $null } }

# ── 單一實例：控制視窗已在跑就帶到前景 ────────────────────────────────────
$running = Get-CimInstance Win32_Process -Filter "Name='pythonw.exe'" -ErrorAction SilentlyContinue |
           Where-Object { $_.CommandLine -like '*voice_center_app.py*' }
if (-not $running) {
  $running = Get-CimInstance Win32_Process -Filter "Name='python.exe'" -ErrorAction SilentlyContinue |
             Where-Object { $_.CommandLine -like '*voice_center_app.py*' }
}
if ($running) { Log '控制視窗已在執行，結束。'; exit 0 }

# ── 下載小工具（curl 優先，回退 BITS / WebClient） ──────────────────────
function Download([string]$url, [string]$dest, [string]$label) {
  Set-Step "下載 $label …"
  $dir = Split-Path $dest; New-Item -ItemType Directory -Force -Path $dir | Out-Null
  if (Test-Path $dest) { Remove-Item -Force $dest -ErrorAction SilentlyContinue }
  # 1) curl.exe（Win10 1803+ 內建）
  try {
    & curl.exe -fSL --retry 3 --retry-delay 2 -o $dest $url 2>> $LOG
    if ($LASTEXITCODE -eq 0 -and (Test-Path $dest)) { return }
  } catch { Log "curl 失敗：$_" }
  # 2) BITS
  try {
    Import-Module BitsTransfer -ErrorAction Stop
    Start-BitsTransfer -Source $url -Destination $dest -DisplayName $label -ErrorAction Stop
    if (Test-Path $dest) { return }
  } catch { Log "BITS 失敗：$_" }
  # 3) WebClient
  try {
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    (New-Object Net.WebClient).DownloadFile($url, $dest)
    if (Test-Path $dest) { return }
  } catch { Log "WebClient 失敗：$_" }
  throw "下載失敗：$label（$url）"
}

# ── 安裝主流程 ─────────────────────────────────────────────────────────
function Read-Mark([string]$p) { if (Test-Path $p) { (Get-Content $p -Raw -EA SilentlyContinue).Trim() } else { '' } }

$didSetup = $false
try {
  if (-not (Test-Path $PY)) {
    $didSetup = $true
    Show-Progress
    Log "===== 開始安裝 $(Get-Date) ====="

    # ── A. 環境：快速路徑（既成 tar.gz）優先，否則用 conda 自建 ──
    $tar = $null
    if (Test-Path $ENV_TAR_LOCAL) { $tar = $ENV_TAR_LOCAL; Log "使用自帶環境：$tar" }
    elseif ($ENV_URL) {
      $tar = Join-Path $TOOLS 'gpt-sovits-env.tar.gz'
      Download $ENV_URL $tar '預打包 AI 環境（約 2GB）'
    }

    if ($tar) {
      Set-Step '解壓 AI 環境（約 1~3 分鐘）…'
      if (Test-Path $ENV_DIR) { Remove-Item -Recurse -Force $ENV_DIR -EA SilentlyContinue }
      New-Item -ItemType Directory -Force -Path $ENV_DIR | Out-Null
      & tar.exe -xzf "$tar" -C "$ENV_DIR" 2>> $LOG
      if ($LASTEXITCODE -ne 0) { throw '解壓 AI 環境失敗' }
      $unpack = Join-Path $ENV_DIR 'Scripts\conda-unpack.exe'
      if (Test-Path $unpack) { Set-Step 'conda-unpack 重定位…'; & $unpack 2>> $LOG }
    }
    else {
      # ── 自建：python-build-standalone（解壓即用）→ pip 裝依賴（全自動，無需任何人預打包）──
      if (-not (Test-Path $PY)) {
        $tgz = Join-Path $env:TEMP 'sml_python.tar.gz'
        Download $PBS_URL $tgz 'Python 執行環境（約 38MB）'
        Set-Step '解壓 Python 執行環境…'
        $extractTmp = Join-Path $TOOLS '_pyextract'
        if (Test-Path $extractTmp) { Remove-Item -Recurse -Force $extractTmp -EA SilentlyContinue }
        New-Item -ItemType Directory -Force $extractTmp | Out-Null
        & tar.exe -xzf "$tgz" -C "$extractTmp" 2>> $LOG
        if ($LASTEXITCODE -ne 0) { throw '解壓 Python 執行環境失敗' }
        $srcPy = Join-Path $extractTmp 'python'
        if (-not (Test-Path (Join-Path $srcPy 'python.exe'))) { throw 'Python 環境結構非預期' }
        if (Test-Path $ENV_DIR) { Remove-Item -Recurse -Force $ENV_DIR -EA SilentlyContinue }
        Move-Item $srcPy $ENV_DIR
        Remove-Item -Recurse -Force $extractTmp -EA SilentlyContinue
        Remove-Item -Force $tgz -EA SilentlyContinue
      }
      if (-not (Test-Path $PY)) { throw 'Python 執行環境建立失敗' }

      & $PY -m pip install --upgrade pip 2>> $LOG

      # ── 偵測支援的 NVIDIA GPU（compute capability >= 5.0）決定 torch 輪子 ──
      $torchIndex = 'https://download.pytorch.org/whl/cpu'
      try {
        $caps = & nvidia-smi --query-gpu=compute_cap --format=csv,noheader 2>$null
        $maxCap = ($caps | ForEach-Object { [double]($_.Trim()) } | Measure-Object -Maximum).Maximum
        if ($maxCap -ge 5.0) { $torchIndex = 'https://download.pytorch.org/whl/cu121'; Log "偵測到 NVIDIA GPU (cap $maxCap) → CUDA torch" }
      } catch {}

      # ── 核心依賴：互相相容的釘選版本（torch 2.4.1 / diffusers 0.31 / transformers<=4.50）──
      if ($torchIndex -like '*cu121*') { Set-Step '偵測到 NVIDIA GPU，安裝 CUDA 版 AI 依賴（約 2.5GB）…' }
      else { Set-Step '安裝核心 AI 依賴（CPU 版 torch / diffusers / transformers …）…' }
      $coreGroups = @(
        @('--index-url',$torchIndex,'torch==2.4.1','torchaudio==2.4.1'),
        @('numpy<2','Pillow','safetensors'),
        @('diffusers==0.31.0','transformers>=4.43,<=4.50','accelerate'),
        @('fastapi','uvicorn')
      )
      foreach ($g in $coreGroups) { & $PY -m pip install @g 2>> $LOG }
      & $PY -c 'import torch, diffusers, transformers, fastapi, PIL' 2>> $LOG
      if ($LASTEXITCODE -ne 0) { throw '核心 AI 依賴安裝失敗（torch / diffusers / transformers）' }

      # ── 語音額外依賴（GPT-SoVITS 9880 用，逐項安裝、失敗不中止）──
      Set-Step '安裝語音合成元件…'
      foreach ($pkg in @('scipy','librosa==0.10.2','soundfile','numba','gradio<5','einops','ffmpeg-python','onnxruntime','tqdm','funasr','cn2an','pypinyin','g2p_en','modelscope','sentencepiece','peft<0.18.0','chardet','PyYAML','psutil','jieba','split-lang','fast_langdetect','wordsegment','rotary_embedding_torch','ToJyutping','opencc','x_transformers','torchmetrics<=1.5','pydantic<=2.10.6','ctranslate2>=4.0,<5','av>=11','pytorch-lightning','qrcode')) {
        & $PY -m pip install $pkg 2>> $LOG
      }
      # pyopenjtalk：日語前端，Windows 無預編譯輪子需 C++ 編譯——盡力而為，失敗略過
      Set-Step '安裝日語元件（可選，失敗自動略過）…'
      & $PY -m pip install pyopenjtalk 2>> $LOG
      if ($LASTEXITCODE -ne 0) { Log 'pyopenjtalk 安裝失敗（需 C++ 編譯器）——已略過。' }

      # jieba_fast：中文前端直接 import 它，但 Windows 無預編譯輪子 → 用 jieba 做相容 shim
      $sp = (& $PY -c 'import site,sys; sys.stdout.write(site.getsitepackages()[-1])' 2>$null)
      if (-not $sp) { $sp = Join-Path $ENV_DIR 'Lib\site-packages' }
      $jf = Join-Path $sp 'jieba_fast'
      New-Item -ItemType Directory -Force -Path $jf | Out-Null
      Set-Content -Path (Join-Path $jf '__init__.py') -Encoding utf8 -Value "import jieba as _j`nfrom jieba import *`nglobals().update({k: getattr(_j, k) for k in dir(_j) if not k.startswith('__')})"
      Set-Content -Path (Join-Path $jf 'posseg.py') -Encoding utf8 -Value "from jieba.posseg import *`nimport jieba.posseg as _p`nglobals().update({k: getattr(_p, k) for k in dir(_p) if not k.startswith('__')})"
    }
  }

  if (-not (Test-Path $PY)) { throw '環境準備未完成（找不到 python.exe）' }

  # ── B. 後端程式碼：自帶在安裝包內（私有 repo，不從網路取） ──
  $needCode = (-not (Test-Path (Join-Path $GS 'image_gen_server.py'))) -or ((Read-Mark $READY) -ne $CONTROL_VER)
  if ($needCode) {
    if ($script:Form -eq $null) { Show-Progress }
    Set-Step '部署後端程式碼…'
    $srcBackend = Join-Path $RES 'backend'
    if (-not (Test-Path $srcBackend)) { throw "找不到內建後端程式碼：$srcBackend" }
    New-Item -ItemType Directory -Force -Path $GS | Out-Null
    # 不刪使用者同步來的權重/模型；只覆蓋程式碼
    & robocopy "$srcBackend" "$GS" /E /XD weights pretrained_models user_models ref_audio .cache __pycache__ /NFL /NDL /NJH /NJS /NP /R:1 /W:1 *>> $LOG
    if ($LASTEXITCODE -ge 8) { throw '複製後端程式碼失敗' }
    # Windows 版控制 / 後端程式庫（自帶在安裝包內）
    foreach ($f in @('voice_center_app.py', 'model_setup_server.py')) {
      $bundled = Join-Path $RES $f
      if (Test-Path $bundled) { Copy-Item -Force $bundled (Join-Path $GS $f) }
    }
  }

  # ── C. GPT-SoVITS 推論引擎程式碼（語音 9880 用；取自官方上游 RVC-Boss）──
  if (-not (Test-Path (Join-Path $GS 'GPT_SoVITS\TTS_infer_pack\TTS.py'))) {
    try {
      if ($script:Form -eq $null) { Show-Progress }
      Set-Step '部署語音引擎程式碼…'
      $ezip = Join-Path $env:TEMP 'gptsovits_src.zip'
      Download 'https://codeload.github.com/RVC-Boss/GPT-SoVITS/zip/refs/heads/main' $ezip '語音引擎'
      $etmp = Join-Path $env:TEMP 'gptsovits_src'
      if (Test-Path $etmp) { Remove-Item -Recurse -Force $etmp -EA SilentlyContinue }
      Expand-Archive -Path $ezip -DestinationPath $etmp -Force
      $eroot = (Get-ChildItem $etmp -Directory | Select-Object -First 1).FullName
      # 保留使用者同步來的 configs / pretrained_models，只覆蓋引擎程式碼
      & robocopy (Join-Path $eroot 'GPT_SoVITS') (Join-Path $GS 'GPT_SoVITS') /E /XD configs pretrained_models __pycache__ /NFL /NDL /NJH /NJS /NP /R:1 /W:1 *>> $LOG
      & robocopy (Join-Path $eroot 'tools') (Join-Path $GS 'tools') /E /XD __pycache__ /NFL /NDL /NJH /NJS /NP /R:1 /W:1 *>> $LOG
      Remove-Item -Recurse -Force $etmp, $ezip -EA SilentlyContinue
      Log '語音引擎程式碼已部署。'
    } catch { Log "語音引擎部署失敗（不影響生圖/文字）：$_" }
  }

  Set-Content -Path $READY -Value $CONTROL_VER -Encoding ASCII
  Log '安裝/更新完成。'
}
catch {
  Close-Progress
  Log "安裝失敗：$_"
  Alert "安裝未完成：`n$_`n`n詳細紀錄：`n$LOG"
  exit 1
}

# ── C. 啟動控制視窗 ───────────────────────────────────────────────────
if (-not $env:HF_HOME)      { $env:HF_HOME      = Join-Path $APPSUP '.cache\huggingface' }
if (-not $env:MPLCONFIGDIR) { $env:MPLCONFIGDIR = Join-Path $APPSUP '.cache\matplotlib' }
New-Item -ItemType Directory -Force -Path $env:HF_HOME, $env:MPLCONFIGDIR | Out-Null

$appPy = Join-Path $GS 'voice_center_app.py'
if (-not (Test-Path $appPy)) {
  Close-Progress
  Alert "找不到控制程式（voice_center_app.py）。請重新下載安裝包。"
  exit 1
}

if ($didSetup) { Set-Step '完成，正在開啟控制視窗…' ; Start-Sleep -Milliseconds 600 }
Close-Progress

$launcher = if (Test-Path $PYW) { $PYW } else { $PY }
Start-Process -FilePath $launcher -ArgumentList 'voice_center_app.py' -WorkingDirectory $GS
exit 0
