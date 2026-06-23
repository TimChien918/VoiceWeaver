#!/usr/bin/env bash
# 打包 VoiceWeaver 語音中心為可下載的 .dmg（自帶 Python 環境 + 後端程式碼）。
#
# 用法：
#   tools/dmg/build_dmg.sh
#
# 前置：先用 conda-pack 產生 /tmp/vwdmg/gpt-sovits-env.tar.gz，且
#       /tmp/vwdmg/payload/GPT-SoVITS/ 已備妥精簡後端程式碼。
#
# 產出：~/Downloads/VoiceWeaver-語音中心.dmg
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
STAGE="/tmp/vwdmg"
ENV_TAR="$STAGE/gpt-sovits-env.tar.gz"
PAYLOAD="$STAGE/payload"
APP_NAME="VoiceWeaver語音中心"
VERSION="${VW_VERSION:-$(date +%Y%m%d%H%M%S)}"     # 每次打包唯一 → 安裝端一定會更新程式碼

BUILD="$STAGE/build"
APP="$BUILD/$APP_NAME.app"
DMG_ROOT="$STAGE/dmgroot"
# 所有安裝檔（.dmg / .apk / .exe）集中在同一個資料夾，可用 VW_DIST 覆寫。
DIST="${VW_DIST:-$HOME/VoiceWeaver-安裝檔}"
mkdir -p "$DIST"
OUT_DMG="$DIST/VoiceWeaver-語音中心.dmg"

echo "==> 檢查素材"
[ -f "$ENV_TAR" ] || { echo "缺少 $ENV_TAR（請先跑 conda-pack）"; exit 1; }
[ -d "$PAYLOAD/GPT-SoVITS" ] || { echo "缺少 $PAYLOAD/GPT-SoVITS"; exit 1; }
echo "    env tar : $(du -h "$ENV_TAR" | cut -f1)"
echo "    payload : $(du -sh "$PAYLOAD" | cut -f1)"

echo "==> 重建 .app 結構"
rm -rf "$APP" "$DMG_ROOT"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"

# Info.plist
cat > "$APP/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleName</key><string>VoiceWeaver 語音中心</string>
    <key>CFBundleDisplayName</key><string>VoiceWeaver 語音中心</string>
    <key>CFBundleIdentifier</key><string>com.example.voiceweaver.voicecenter</string>
    <key>CFBundleVersion</key><string>1.0</string>
    <key>CFBundleShortVersionString</key><string>1.0</string>
    <key>CFBundlePackageType</key><string>APPL</string>
    <key>CFBundleExecutable</key><string>launch</string>
    <key>CFBundleIconFile</key><string>AppIcon</string>
    <key>LSMinimumSystemVersion</key><string>11.0</string>
    <key>NSHighResolutionCapable</key><true/>
    <key>LSApplicationCategoryType</key><string>public.app-category.utilities</string>
</dict>
</plist>
PLIST

# 啟動腳本
cp "$PROJECT_ROOT/tools/dmg/launch.sh" "$APP/Contents/MacOS/launch"
chmod +x "$APP/Contents/MacOS/launch"

# 圖示：用產生器做一個 AppIcon.icns 放進 App（聲波長條，藍青漸層）
PY="${VW_ENV_PY:-$HOME/VoiceWeaver-電腦端/gpt-sovits-env/bin/python}"
DMGDIR="$PROJECT_ROOT/tools/dmg"
ICON="$APP/Contents/Resources/AppIcon.icns"
if "$PY" "$DMGDIR/make_icon.py" "$ICON" >/dev/null 2>&1; then
  echo "    App 圖示已產生"
else
  echo "    (圖示產生失敗，略過)"
fi

echo "==> 放入內建環境與後端程式碼"
cp "$ENV_TAR" "$APP/Contents/Resources/gpt-sovits-env.tar.gz"
mkdir -p "$APP/Contents/Resources/payload"
/usr/bin/rsync -a "$PAYLOAD/GPT-SoVITS" "$APP/Contents/Resources/payload/"
echo "$VERSION" > "$APP/Contents/Resources/VERSION"
# 環境版本：用 env tar 的大小+修改時間當指紋，環境有變才會在安裝端重新解壓
stat -f "%z-%m" "$ENV_TAR" > "$APP/Contents/Resources/ENV_VERSION" 2>/dev/null || echo "$VERSION" > "$APP/Contents/Resources/ENV_VERSION"

# ── 安全網：絕不把聲音模型/權重打包進 DMG（AGPL + 官方錄音版權，不可轉散布）──
RP="$APP/Contents/Resources/payload/GPT-SoVITS"
find "$RP" -type d \( -name '*weights*' -o -name 'pretrained_models' -o -name 'user_models' \
   -o -name 'ref_audio' -o -name 'G2PWModel' -o -name '.cache' -o -name '__pycache__' \) \
   -prune -exec rm -rf {} + 2>/dev/null || true
LEAK="$(find "$RP" -type d -name '*weights*' 2>/dev/null)"
if [ -n "$LEAK" ]; then
  echo "✗ 偵測到權重資料夾仍在 payload，為保護版權中止打包："; echo "$LEAK"; exit 1
fi
echo "    payload(已過濾權重) : $(du -sh "$RP" | cut -f1)"

echo "==> ad-hoc 簽章（含圖示）"
codesign --force --deep --sign - "$APP" 2>/dev/null || echo "    (簽章略過)"

echo "==> 產生安裝視窗背景圖"
BG="$STAGE/background.png"
"$PY" "$DMGDIR/make_background.py" "$BG" >/dev/null 2>&1 || echo "    (背景圖略過)"

echo "==> 建立「拖到應用程式」安裝 DMG（dmgbuild）"
rm -f "$OUT_DMG"
OUT="$OUT_DMG" SET="$DMGDIR/dmgbuild_settings.py" APP_PATH="$APP" BG_PATH="$BG" ICON_PATH="$ICON" \
  "$PY" -c "import dmgbuild, os; dmgbuild.build_dmg(os.environ['OUT'], 'VoiceWeaver 語音中心', settings_file=os.environ['SET'])"

echo "==> 完成"
echo "    $OUT_DMG"
du -h "$OUT_DMG" | cut -f1
