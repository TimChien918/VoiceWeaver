# shellcheck shell=bash
# 拾語者 Speak My Life（原名 VoiceWeaver）更名搬遷——給啟動腳本 source 用。
#   . "$ROOT/sml_migrate.sh"
# 規則與 sml_migrate.py 相同：先找 ~/Speak-My-Life-電腦端；沒有但有 ~/VoiceWeaver-電腦端
# → 改名，並在舊位置留捷徑（conda／miniforge 內寫死的舊路徑照樣能用）；
# 改名或留捷徑失敗 → 退回、沿用舊路徑。設了 SML_GS_HOME 就完全不動。
sml_migrate_home() {
  [ -n "${SML_GS_HOME:-}" ] && return 0
  local new="$HOME/Speak-My-Life-電腦端" old="$HOME/VoiceWeaver-電腦端"
  if [ -e "$new" ] || [ -L "$new" ]; then return 0; fi
  if [ ! -d "$old" ] || [ -L "$old" ]; then return 0; fi
  if mv "$old" "$new" 2>/dev/null; then
    if ln -s "$new" "$old" 2>/dev/null; then
      echo "[migrate] 已把 $old 改名為 $new" >&2
    else
      mv "$new" "$old" 2>/dev/null || true
      echo "[migrate] 無法在舊位置留捷徑，沿用 $old" >&2
    fi
  else
    echo "[migrate] 改名失敗，沿用 $old" >&2
  fi
  return 0
}
sml_migrate_home
