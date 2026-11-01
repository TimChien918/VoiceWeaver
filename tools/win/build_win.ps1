<#
  打包拾語者 Speak My Life 語音中心為可下載的 Windows 安裝包（自帶 Python 環境 + 後端程式碼）。
  與 macOS 的 tools/dmg/build_dmg.sh 同邏輯，只是產物是 .zip 資料夾而非 .dmg。

  用法（在 Windows PowerShell）：
      tools\win\build_win.ps1

  前置：先在「Windows」上用 conda-pack 產生環境壓縮檔，且備妥精簡後端程式碼——
    1) $Stage\gpt-sovits-env.tar.gz          ← Windows 版 conda 環境（conda-pack 產出）
    2) $Stage\payload\GPT-SoVITS\            ← 後端程式碼（不含權重）
  （$Stage 預設 %TEMP%\smlwin，可用環境變數 SML_WIN_STAGE 覆寫。）

  產出：%USERPROFILE%\Speak-My-Life-安裝檔\Speak-My-Life-語音中心-Windows.zip
#>
$ErrorActionPreference = 'Stop'

$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$Stage   = if ($env:SML_WIN_STAGE) { $env:SML_WIN_STAGE } else { Join-Path $env:TEMP 'smlwin' }
$EnvTar  = Join-Path $Stage 'gpt-sovits-env.tar.gz'
$Payload = Join-Path $Stage 'payload'
$AppName = 'Speak-My-Life語音中心'
$Version = if ($env:SML_VERSION) { $env:SML_VERSION } else { Get-Date -Format 'yyyyMMddHHmmss' }

$Build   = Join-Path $Stage 'build'
$Dist    = Join-Path $Build $AppName
# 所有安裝檔（.dmg / .apk / .exe）集中在同一個資料夾，可用 SML_DIST 覆寫。
$DistDir = if ($env:SML_DIST) { $env:SML_DIST } else { Join-Path ([Environment]::GetFolderPath('UserProfile')) 'Speak-My-Life-安裝檔' }
$OutZip  = Join-Path $DistDir 'Speak-My-Life-語音中心-Windows.zip'
$WinDir  = $PSScriptRoot
$UserHome = [Environment]::GetFolderPath('UserProfile')
# 電腦端資料夾：~\Speak-My-Life-電腦端（還沒改名就用舊的 ~\VoiceWeaver-電腦端；改名由語音中心啟動時處理）
$GsHome  = Join-Path $UserHome 'Speak-My-Life-電腦端'
if (-not (Test-Path $GsHome)) { $GsHome = Join-Path $UserHome 'VoiceWeaver-電腦端' }
$EnvPy   = if ($env:SML_ENV_PY) { $env:SML_ENV_PY } else { Join-Path $GsHome 'gpt-sovits-env\python.exe' }

Write-Host '==> 檢查素材'
if (-not (Test-Path $EnvTar))                       { throw "缺少 $EnvTar（請先在 Windows 跑 conda-pack）" }
if (-not (Test-Path (Join-Path $Payload 'GPT-SoVITS'))) { throw "缺少 $Payload\GPT-SoVITS" }
Write-Host ("    env tar : {0:N0} MB" -f ((Get-Item $EnvTar).Length / 1MB))

Write-Host '==> 重建發行資料夾結構'
if (Test-Path $Dist) { Remove-Item -Recurse -Force $Dist }
New-Item -ItemType Directory -Force -Path $Dist | Out-Null

# 啟動器
Copy-Item (Join-Path $WinDir 'launch.ps1')              -Destination $Dist
Copy-Item (Join-Path $WinDir 'Speak-My-Life語音中心.cmd') -Destination $Dist

# 圖示（聲波長條，藍青漸層）— 失敗就略過
$Icon = Join-Path $Dist 'AppIcon.ico'
if (Test-Path $EnvPy) {
  try { & $EnvPy (Join-Path $WinDir 'make_ico.py') $Icon; Write-Host '    App 圖示已產生' }
  catch { Write-Host '    (圖示產生失敗，略過)' }
} else {
  Write-Host "    (找不到 env python：$EnvPy，略過圖示)"
}

Write-Host '==> 放入內建環境與後端程式碼'
Copy-Item $EnvTar -Destination (Join-Path $Dist 'gpt-sovits-env.tar.gz')
$DistPayload = Join-Path $Dist 'payload'
New-Item -ItemType Directory -Force -Path $DistPayload | Out-Null
& robocopy (Join-Path $Payload 'GPT-SoVITS') (Join-Path $DistPayload 'GPT-SoVITS') /E /NFL /NDL /NJH /NJS /NP *> $null
if ($LASTEXITCODE -ge 8) { throw 'robocopy 複製 payload 失敗' }

Set-Content -Path (Join-Path $Dist 'VERSION') -Value $Version -Encoding ASCII
# 環境版本：用 env tar 的大小+修改時間當指紋，環境有變才會在安裝端重新解壓
$envItem = Get-Item $EnvTar
Set-Content -Path (Join-Path $Dist 'ENV_VERSION') `
  -Value ("{0}-{1}" -f $envItem.Length, $envItem.LastWriteTimeUtc.Ticks) -Encoding ASCII

# ── 安全網：絕不把聲音模型/權重打包進安裝包（AGPL + 官方錄音版權，不可轉散布）──
$RP = Join-Path $DistPayload 'GPT-SoVITS'
$banned = @('*weights*','pretrained_models','user_models','ref_audio','G2PWModel','.cache','__pycache__')
foreach ($pat in $banned) {
  Get-ChildItem -Path $RP -Recurse -Directory -Filter $pat -ErrorAction SilentlyContinue |
    ForEach-Object { Remove-Item -Recurse -Force $_.FullName -ErrorAction SilentlyContinue }
}
$leak = Get-ChildItem -Path $RP -Recurse -Directory -Filter '*weights*' -ErrorAction SilentlyContinue
if ($leak) {
  Write-Host '✗ 偵測到權重資料夾仍在 payload，為保護版權中止打包：'
  $leak | ForEach-Object { Write-Host "    $($_.FullName)" }
  throw '打包中止'
}

Write-Host '==> 壓縮為安裝包 .zip'
if (Test-Path $OutZip) { Remove-Item -Force $OutZip }
New-Item -ItemType Directory -Force -Path (Split-Path $OutZip) | Out-Null
Compress-Archive -Path $Dist -DestinationPath $OutZip -CompressionLevel Optimal

Write-Host '==> 完成'
Write-Host "    $OutZip"
Write-Host ("    {0:N0} MB" -f ((Get-Item $OutZip).Length / 1MB))
