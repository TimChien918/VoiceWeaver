# tools/GPT-SoVITS — 電腦端語音中心（拾語者 Speak My Life 自訂部分）

這裡放的是 **拾語者 Speak My Life（原名 VoiceWeaver）專屬**的電腦端控制台、伺服器與啟動腳本（跨平台：macOS / Windows / Linux）。
上游 GPT-SoVITS 框架本體、conda 環境、模型權重、參考音檔**不在此**（體積大／版權／已 `.gitignore`），
需另外安裝。

## 檔案
| 檔案 | 作用 | 埠 |
| --- | --- | --- |
| `voice_center_app.py` | 原生控制視窗（Tkinter）：啟停服務、連線位址、adb 轉接、聲音模型同步 | — |
| `model_setup_server.py` | 後端函式庫：模型清單／切換／匯入／試聽（供控制台 import） | — |
| `api_v2.py` | GPT-SoVITS 語音合成 API | 9880 |
| `image_gen_server.py` | 本機生圖（SD-Turbo，免金鑰） | 9881 |
| `text_infer_server.py` | 本機文字推論（Qwen） | 9882 |
| `sync_voice_models.py` | USB 雙向同步手機聲音模型（純 adb + stdlib） | — |
| `start_*.sh` / `stop_api.sh` / `check_install.sh` | 開發用 CLI 啟停／檢查 | — |
| `GPT_SoVITS/configs/speakmylife_tts_infer.yaml` | 拾語者語音設定 | — |
| `sml_migrate.py` / `sml_migrate.sh` | 更名搬遷（舊 VoiceWeaver 資料夾／Drive 曲庫／設定檔自動改名；測試：`python3 test_sml_migrate.py`） | — |

裝置選擇皆跨平台：**CUDA（NVIDIA）→ MPS（Apple）→ CPU** 自動選用。

## 怎麼讓它「能用」
1. 安裝上游 GPT-SoVITS 框架與相依（conda 環境、`GPT_SoVITS/pretrained_models/`）。
2. 把本資料夾的檔案放到該框架根目錄。
3. 打包或部署時 `sml_migrate.py`、`sml_migrate.sh` 要跟其他 `.py` 放在一起。
4. 啟動控制台：`<env>/python(.exe) voice_center_app.py`。

打包成自帶環境的安裝包：macOS 用 [`../dmg/build_dmg.sh`](../dmg/build_dmg.sh)，
Windows 用 [`../win/build_win.ps1`](../win/README_WINDOWS.md)。
