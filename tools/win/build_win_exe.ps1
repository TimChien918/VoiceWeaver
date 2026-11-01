<#
  打包 VoiceWeaver 語音中心為「單一可執行檔」(.exe) — 自我引導安裝版。

  與舊的 build_win.ps1（自帶 conda-pack 環境的 .zip）不同，這個產物是一顆小小的
  PyInstaller exe（約 11MB）：使用者雙擊後，第一次會自動從公開來源下載並安裝 AI 環境
  （Miniforge → conda 環境 → pip 依賴 → 官方上游 GPT-SoVITS 引擎），之後秒開。
  詳見 README_WINDOWS.md。

  前置：一個裝了 pyinstaller + pillow 的 Python（任何系統 Python 即可，不需 AI 環境）。
      pip install pyinstaller pillow

  用法（在 repo 根目錄或任意處）：
      pwsh tools\win\build_win_exe.ps1                 # 用 PATH 上的 python
      $env:SML_BUILD_PY = 'C:\path\to\python.exe'; tools\win\build_win_exe.ps1

  產出：dist\VoiceWeaver語音中心.exe
#>
$ErrorActionPreference = 'Stop'

$WinDir = $PSScriptRoot
$Repo   = (Resolve-Path (Join-Path $WinDir '..\..')).Path
$GS     = Join-Path $Repo 'tools\GPT-SoVITS'
$Dist   = Join-Path $Repo 'dist'
$Py     = if ($env:SML_BUILD_PY) { $env:SML_BUILD_PY } else { 'python' }

Write-Host '==> 檢查打包用 Python（需 pyinstaller + pillow）'
& $Py -c "import PyInstaller, PIL" 2>$null
if ($LASTEXITCODE -ne 0) { throw "需先安裝：`n  $Py -m pip install pyinstaller pillow" }

Write-Host '==> 產生 App 圖示（聲波長條，藍青漸層）'
$Icon = Join-Path $GS 'AppIcon.ico'
& $Py (Join-Path $WinDir 'make_ico.py') $Icon

Write-Host '==> PyInstaller 打包（從正規後端檔即時打包，不需提交重複的 backend/）'
# 後端服務腳本與設定一起塞進 exe 的 backend/ 子目錄；首次安裝時複製到安裝位置。
$addData = @(
  "$GS\model_setup_server.py;.",          # 控制視窗用的後端程式庫
  "$GS\api_v2.py;backend",
  "$GS\image_gen_server.py;backend",
  "$GS\text_infer_server.py;backend",
  "$GS\sync_voice_models.py;backend",
  "$GS\GPT_SoVITS\configs;backend\GPT_SoVITS\configs"
)
$args = @(
  '-m','PyInstaller','--onefile','--windowed',
  '--name','VoiceWeaver語音中心',
  "--icon=$Icon",
  '--distpath',$Dist,
  '--workpath',"$env:TEMP\pyi_build",
  '--specpath',"$env:TEMP\pyi_build",
  # qrcode 在「掃描連線」功能裡才 import（try/except 內），PyInstaller 靜態分析可能漏掉 → 強制收進來
  '--collect-all','qrcode',
  '--noupx'
)
# 內嵌版本/產品資訊：未簽章 exe 仍可能被 SmartScreen 攔，但「其他資訊」會顯示
# 「VoiceWeaver 語音中心」產品名而非空白檔名，看起來正常許多（詳見 README_WINDOWS.md）。
$verFile = Join-Path $WinDir 'version_info.txt'
if (Test-Path $verFile) { $args += @('--version-file', $verFile) }
foreach ($d in $addData) { $args += @('--add-data', $d) }
$args += "$GS\voice_center_app.py"

& $Py @args
if ($LASTEXITCODE -ne 0) { throw 'PyInstaller 打包失敗' }

$exe = Join-Path $Dist 'VoiceWeaver語音中心.exe'
Write-Host ('==> 完成：{0}  ({1:N1} MB)' -f $exe, ((Get-Item $exe).Length / 1MB))
