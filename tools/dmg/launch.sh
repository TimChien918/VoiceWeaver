#!/bin/bash
# 拾語者 Speak My Life 語音中心（發行版 / .dmg 安裝；Speak-My-Life語音中心.app）— 雙擊即用。
#
# 這個版本是「自帶環境」的：.app 內含 conda-pack 打包好的 Python 環境與全部
# 後端程式碼。第一次開啟時會自動把環境解壓到使用者資料夾並完成重定位，之後
# 就跟一般 App 一樣秒開。
#
#   首次：解壓 gpt-sovits-env（~2GB）+ conda-unpack + 複製後端程式碼 → 約 1~3 分鐘
#   之後：直接啟動 控制中心(9890) + 本機生圖(9881)
#
# 生圖模型 SD-Turbo（~2.5GB）會在第一次生圖時自動下載（免金鑰）。
# 語音合成(9880) 需要使用者自備的聲音模型，於控制中心匯入後才會啟用。

set -u

TITLE="拾語者 語音中心"

# ---- 路徑 --------------------------------------------------------------------
BUNDLE_DIR="$(cd "$(dirname "$0")/../.." && pwd)"           # …/Speak-My-Life語音中心.app
RES="$BUNDLE_DIR/Contents/Resources"                        # 內含 env tar 與 payload
APPSUP="$HOME/Library/Application Support/Speak-My-Life"
# 更名搬遷（原名 VoiceWeaver）：只有舊的 …/VoiceWeaver 就改名，並在舊位置留捷徑
# （解壓過的 Python 環境裡寫死了舊路徑，留捷徑就不必重裝）；任何一步失敗都沿用舊資料夾。
OLD_APPSUP="$HOME/Library/Application Support/VoiceWeaver"
if [ ! -e "$APPSUP" ] && [ -d "$OLD_APPSUP" ] && [ ! -L "$OLD_APPSUP" ]; then
  if mv "$OLD_APPSUP" "$APPSUP" 2>/dev/null; then
    ln -s "$APPSUP" "$OLD_APPSUP" 2>/dev/null || { mv "$APPSUP" "$OLD_APPSUP" 2>/dev/null; APPSUP="$OLD_APPSUP"; }
  else
    APPSUP="$OLD_APPSUP"
  fi
fi
TOOLS="$APPSUP/tools"
ENV_DIR="$TOOLS/gpt-sovits-env"
GS="$TOOLS/GPT-SoVITS"
PY="$ENV_DIR/bin/python"
LOG_DIR="$APPSUP/logs"
MARK="$APPSUP/.code_version"                                # 後端程式碼版本標記
ENV_MARK="$APPSUP/.env_version"                             # 環境版本標記
ENV_TAR="$RES/gpt-sovits-env.tar.gz"
PAYLOAD="$RES/payload"                                      # GPT-SoVITS 後端程式碼
VERSION="$(cat "$RES/VERSION" 2>/dev/null || echo 1)"          # 每次打包都不同（build 時間戳）
ENV_VERSION="$(cat "$RES/ENV_VERSION" 2>/dev/null || echo 1)"  # 環境變動時才不同
mkdir -p "$LOG_DIR" 2>/dev/null

# ---- 小工具 ------------------------------------------------------------------
notify() { /usr/bin/osascript -e "display notification \"$2\" with title \"$TITLE\" subtitle \"$1\"" >/dev/null 2>&1; }
alert()  { /usr/bin/osascript -e "display dialog \"$1\" with title \"$TITLE\" buttons {\"好\"} default button 1 with icon note" >/dev/null 2>&1; }
port_up() { /usr/sbin/lsof -nP -iTCP:"$1" -sTCP:LISTEN >/dev/null 2>&1; }
kill_port() { /usr/sbin/lsof -tiTCP:"$1" -sTCP:LISTEN 2>/dev/null | /usr/bin/xargs kill 2>/dev/null; }

# ---- 單一實例：控制台已在跑就不再開第二個 -----------------------------------
if /usr/bin/pgrep -f "voice_center_app.py" >/dev/null 2>&1; then
  exit 0
fi

# ---- 安裝 / 更新 ------------------------------------------------------------
# (1) AI 環境（重，~2GB）：缺少、或環境版本不同時才重新解壓。
if [ ! -x "$PY" ] || [ "$(cat "$ENV_MARK" 2>/dev/null)" != "$ENV_VERSION" ]; then
  if [ ! -f "$ENV_TAR" ]; then
    alert "安裝檔損壞：找不到內建環境（gpt-sovits-env.tar.gz）。請重新下載 .dmg。"
    exit 1
  fi
  notify "安裝/更新環境中" "正在解開 AI 環境，約需 1~3 分鐘，請勿關閉…"
  ( /usr/bin/osascript -e "display dialog \"拾語者 正在解壓 AI 環境（約 1~3 分鐘）。\n完成後會自動開啟。\n\n您可以先關閉此視窗。\" with title \"$TITLE\" buttons {\"好\"} default button 1 with icon note giving up after 8" >/dev/null 2>&1 ) &
  rm -rf "$ENV_DIR" 2>/dev/null
  mkdir -p "$ENV_DIR"
  if ! /usr/bin/tar -xzf "$ENV_TAR" -C "$ENV_DIR" 2>>"$LOG_DIR/install.log"; then
    alert "解壓 AI 環境失敗，請查看記錄：\n$LOG_DIR/install.log"
    exit 1
  fi
  # conda-unpack 重定位（shebang 是 /usr/bin/env python，PATH 不一定有 → 用環境自己的 python）
  [ -f "$ENV_DIR/bin/conda-unpack" ] && "$PY" "$ENV_DIR/bin/conda-unpack" >>"$LOG_DIR/install.log" 2>&1
  echo "$ENV_VERSION" > "$ENV_MARK"
fi

if [ ! -x "$PY" ]; then
  alert "安裝似乎未完成（找不到 Python）。請查看記錄：\n$LOG_DIR/install.log"
  exit 1
fi

# (2) 後端程式碼（輕，~36M）：版本不同就刷新。
#     **不用 --delete**，避免刪掉使用者同步來的 weights / pretrained_models / user_models / ref_audio。
mkdir -p "$GS"
if [ "$(cat "$MARK" 2>/dev/null)" != "$VERSION" ]; then
  notify "更新程式中" "正在套用最新版本…"
  /usr/bin/rsync -a "$PAYLOAD/GPT-SoVITS/" "$GS/" >>"$LOG_DIR/install.log" 2>&1 \
    || /bin/cp -R "$PAYLOAD/GPT-SoVITS/." "$GS/" 2>>"$LOG_DIR/install.log"
  echo "$VERSION" > "$MARK"
fi

# ---- 啟動原生控制視窗（不用網頁）-------------------------------------------
# 伺服器的啟動/停止、adb 轉接、聲音模型同步、連線位址顯示都由控制視窗負責；
# 關閉視窗時 App 內會自動停止所有伺服器（含語音合成）。
export HF_HOME="${HF_HOME:-$APPSUP/.cache/huggingface}"
export MPLCONFIGDIR="${MPLCONFIGDIR:-$APPSUP/.cache/matplotlib}"
mkdir -p "$HF_HOME" "$MPLCONFIGDIR"

if [ ! -f "$GS/voice_center_app.py" ]; then
  alert "找不到控制台程式（voice_center_app.py）。請重新下載 .dmg。"
  exit 1
fi
notify "啟動中" "正在開啟控制台…"
exec "$PY" "$GS/voice_center_app.py"
