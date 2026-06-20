# dmgbuild 設定：產生「拖到應用程式」的安裝視窗（含背景圖、App 圖示、Applications 捷徑）。
# 路徑由環境變數帶入：APP_PATH（.app）、BG_PATH（背景圖）、ICON_PATH（卷宗圖示 .icns）。
import os

app = os.environ["APP_PATH"]
appname = os.path.basename(app)

format = "UDZO"
compression_level = 9

files = [app]
symlinks = {"應用程式": "/Applications"}

icon = os.environ.get("ICON_PATH") or None          # 掛載卷宗的圖示
background = os.environ["BG_PATH"]

# 視窗與圖示版面（對齊 background.png 的箭頭）
window_rect = ((360, 220), (660, 400))
default_view = "icon-view"
icon_size = 128
text_size = 13
icon_locations = {
    appname: (170, 200),
    "應用程式": (490, 200),
}

# 乾淨的安裝視窗：不顯示工具列/側欄/狀態列
show_status_bar = False
show_tab_view = False
show_toolbar = False
show_pathbar = False
show_sidebar = False
list_icon_size = 16
