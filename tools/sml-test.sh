#!/usr/bin/env bash
#
# 拾語者 Speak My Life 自動化測試操控台（只對 debug 版有效）
#
# 用法：
#   ./tools/sml-test.sh ping
#   ./tools/sml-test.sh dump
#   ./tools/sml-test.sh inject_usage itemId=p_cig score=8 ageDays=0
#   ./tools/sml-test.sh inject_location tag=醫院
#   ./tools/sml-test.sh nav route=aac
#   ./tools/sml-test.sh reset_data confirm=true
#
# 結果走廣播的 resultData 回傳（`am broadcast` 會把它印出來），不依賴 logcat——
# 部分機型（實測 ASUS ROG）會把一般 App 的 log 整個過濾掉，撈不到東西。
set -euo pipefail

PKG="com.speakmylife.app"
ACTION="com.speakmylife.app.TEST_COMMAND"
TOKEN_FILE="files/sml_test_token"

die() { echo "✗ $*" >&2; exit 1; }

command -v adb >/dev/null || die "找不到 adb，請確認 Android SDK platform-tools 在 PATH 裡"
[ -n "$(adb devices | sed '1d' | grep -w device || true)" ] || die "沒有連上任何裝置（adb devices 是空的）"

# 權杖存在 App 的內部儲存：任何 App 都讀不到（含有「所有檔案存取權」的檔案管理器），
# 只有 run-as 進得去，而 run-as 只對可除錯的 App 有效——正好就是我們要的信任邊界。
fetch_token() {
  adb shell "run-as $PKG cat $TOKEN_FILE 2>/dev/null" | tr -d '\r\n'
}

TOKEN="$(fetch_token)"
if [ -z "$TOKEN" ]; then
  echo "· 沒有權杖檔，啟動 App 產生…" >&2
  adb shell am start -n "$PKG/com.speakmylife.app.MainActivity" >/dev/null
  sleep 3
  TOKEN="$(fetch_token)"
fi
[ -n "$TOKEN" ] || die "拿不到權杖。確認裝的是 debug 版（release 版沒有這個側門）"

[ $# -ge 1 ] || die "用法：$0 <command> [key=value ...]"
CMD="$1"; shift

ARGS=(--es command "$CMD" --es token "$TOKEN")
for kv in "$@"; do
  [[ "$kv" == *=* ]] || die "參數要寫成 key=value，收到：$kv"
  ARGS+=(--es "${kv%%=*}" "${kv#*=}")
done

# 一律用 --es 送字串，由 TestHooks 自己轉型；--ez/--ef 在 Bundle 裡型別不一致，
# 統一成字串反而少一層踩雷。
OUT="$(adb shell am broadcast -a "$ACTION" -p "$PKG" "${ARGS[@]}" 2>&1)"

echo "$OUT" | grep -q "Broadcast completed" || die "廣播沒送到：$OUT"

# 取出 data="…"：跨行的 JSON 也要完整撈回來，所以用 perl 做非貪婪的整段比對。
RESULT="$(printf '%s' "$OUT" | perl -0777 -ne 'print $1 if /data="(.*)"\s*$/s')"

if [ -z "$RESULT" ]; then
  die "App 沒有回應。可能是還沒開起來，或裝的是 release 版"
fi

printf '%s\n' "$RESULT"
[[ "$RESULT" == ERR* ]] && exit 1 || exit 0
