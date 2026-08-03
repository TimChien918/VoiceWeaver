<#
  用「自簽程式碼簽章憑證」把 VoiceWeaver exe 簽成發行者 = TimChien918。

  重點認知（老實說）：
  - 自簽憑證能讓「發行者」從「不明的發行者」變成 TimChien918，並在「安裝過該憑證的電腦上」
    讓 UAC / 檔案簽章顯示為已驗證。
  - 但自簽憑證「沒有微軟雲端信譽」，所以「無法消除 SmartScreen」（首次執行對從網路下載的檔
    仍可能跳，只是發行者會顯示 TimChien918 而非「不明」）。要對所有下載者根本消除 SmartScreen，
    只能用付費的 Authenticode（EV）憑證。詳見 README_WINDOWS.md。

  用法：
    pwsh tools\win\sign_win_exe.ps1                     # 簽 dist\VoiceWeaver語音中心.exe
    pwsh tools\win\sign_win_exe.ps1 -Exe C:\path\x.exe  # 簽指定檔
    pwsh tools\win\sign_win_exe.ps1 -Trust             # 順便把憑證裝進「本機使用者」信任區
                                                        #（本機才會顯示為已驗證；會匯出 .cer）
#>
param(
  [string]$Exe = (Join-Path (Split-Path $PSScriptRoot -Parent) '..\dist\VoiceWeaver語音中心.exe'),
  [string]$Subject = 'CN=TimChien918',
  [switch]$Trust
)
$ErrorActionPreference = 'Stop'
$Exe = (Resolve-Path $Exe).Path

# 1) 建立或沿用同名的程式碼簽章憑證（私鑰留在 Cert:\CurrentUser\My，日後重簽沿用同一張，
#    這樣發行者身分穩定不變）。
$cert = Get-ChildItem Cert:\CurrentUser\My |
  Where-Object { $_.Subject -eq $Subject -and $_.HasPrivateKey } | Select-Object -First 1
if (-not $cert) {
  Write-Host "==> 建立自簽程式碼簽章憑證：$Subject"
  $cert = New-SelfSignedCertificate -Type CodeSigningCert -Subject $Subject `
    -CertStoreLocation Cert:\CurrentUser\My -KeyUsage DigitalSignature `
    -KeyExportPolicy Exportable -FriendlyName 'VoiceWeaver code signing (TimChien918)' `
    -NotAfter (Get-Date).AddYears(5)
} else { Write-Host "==> 沿用既有憑證：$($cert.Thumbprint)" }

# 2) 簽章（SHA256 + 時間戳記；有時間戳，憑證到期後既有簽章仍有效）。
Write-Host "==> 簽章：$Exe"
$sig = Set-AuthenticodeSignature -FilePath $Exe -Certificate $cert -HashAlgorithm SHA256 `
  -TimestampServer 'http://timestamp.digicert.com'
Write-Host "    狀態 = $($sig.Status)   發行者 = $($cert.Subject)"

# 3) （選用）把公開憑證裝進「本機使用者」的受信任根 + 受信任發行者，
#    這樣本機的 UAC / 簽章驗證會顯示為「已驗證：TimChien918」。
#    別台電腦要顯示為已驗證，需自行匯入下面匯出的 .cer（會跳一次 Windows 安全確認）。
if ($Trust) {
  $cer = Join-Path (Split-Path $Exe -Parent) 'TimChien918-codesign.cer'
  Export-Certificate -Cert $cert -FilePath $cer | Out-Null
  $pub = New-Object System.Security.Cryptography.X509Certificates.X509Certificate2 $cer
  foreach ($name in 'Root','TrustedPublisher') {
    $store = New-Object System.Security.Cryptography.X509Certificates.X509Store($name,'CurrentUser')
    $store.Open('ReadWrite'); $store.Add($pub); $store.Close()
    Write-Host "    已信任：CurrentUser\$name"
  }
  Write-Host "    公開憑證匯出：$cer （別台電腦要驗證可匯入這個檔）"
  $sig = Get-AuthenticodeSignature -FilePath $Exe
  Write-Host "==> 本機重新驗證：$($sig.Status)"
}
