# VoiceWeaver GPT-SoVITS 換模型手冊

> 📦 **電腦端已搬出本專案**（2026-06）：本手冊中的 `tools/GPT-SoVITS/` 一律改讀為
> `~/VoiceWeaver-電腦端/GPT-SoVITS/`（conda 環境、miniforge 同樣在 `~/VoiceWeaver-電腦端/` 下）。
> 你的聲音模型（user_models / *weights*）都已隨資料夾一起搬過去，內容不變。

這份手冊說明如何在 VoiceWeaver 專案中更換 GPT-SoVITS 模型。請只使用你自己錄製、取得明確授權，或 license 明確允許的模型與參考音檔。

## 目前架構

VoiceWeaver 的 GPT-SoVITS 分成兩邊：

| 位置 | 功能 |
| --- | --- |
| Mac/PC | 執行 `tools/GPT-SoVITS/api_v2.py`，真正載入 `.ckpt` 與 `.pth` |
| Android App | 只呼叫 API，不直接跑 PyTorch 模型 |

因此換模型主要是在 Mac/PC 端操作，App 只需要設定 API 位址、參考音檔和開關。

## 重要檔案

| 檔案 | 用途 |
| --- | --- |
| `tools/GPT-SoVITS/start_api.sh` | 啟動 GPT-SoVITS API |
| `tools/GPT-SoVITS/stop_api.sh` | 停止 9880 port 上的 API |
| `tools/GPT-SoVITS/GPT_SoVITS/configs/voiceweaver_tts_infer.yaml` | VoiceWeaver 預設載入模型 |
| `tools/GPT-SoVITS/ref_audio/furina/ref_zh.wav` | 中文參考音檔槽位 |
| `tools/GPT-SoVITS/ref_audio/furina/ref_en.wav` | 英文參考音檔槽位 |
| `app/src/main/java/com/example/ai/LocalSovitsHelper.kt` | App 端參考音檔路徑與 prompt 文字 |

## 模型需要哪些檔案

至少需要：

| 類型 | 副檔名 | 說明 |
| --- | --- | --- |
| GPT / Text2Semantic | `.ckpt` | 控制語意到語音 token |
| SoVITS | `.pth` | 控制聲學與音色 |
| 參考音檔 | `.wav` | 給 API 做 prompt/reference |
| 參考逐字稿 | 文字 | 必須和參考音檔內容一致 |

如果 `.ckpt` 或 `.pth` 只有 100 多 bytes，通常是 Git LFS pointer，不是真模型。

檢查大小：

```bash
ls -lh path/to/model.ckpt path/to/model.pth
```

## 官方底模

目前專案預設使用官方 MIT v2 底模：

```yaml
t2s_weights_path: GPT_SoVITS/pretrained_models/gsv-v2final-pretrained/s1bert25hz-5kh-longer-epoch=12-step=369668.ckpt
vits_weights_path: GPT_SoVITS/pretrained_models/gsv-v2final-pretrained/s2G2333k.pth
version: v2
```

優點：

- 授權清楚。
- 最穩定。
- 不依賴特定角色或真人聲線。
- 適合測 App 流程。

限制：

- 不是特定角色聲音。
- 音色主要由參考音檔影響，但不等於完整 clone。

## 快速切回官方底模

如果模型壞掉、聲音怪、或 server log 出現大量錯誤，先切回官方底模：

```bash
cd /Users/timchien/AndroidStudioProjects/VoiceWeaver
./tools/GPT-SoVITS/use_genshin_v2.sh base
```

確認：

```bash
curl http://127.0.0.1:9880/health
```

應看到：

```json
{
  "ok": true,
  "service": "GPT-SoVITS",
  "version": "v2"
}
```

## 安裝自訂授權模型

以下用 `MyVoice` 當範例。

## 電腦 localhost 圖形設定頁

如果你不想手動複製 `.ckpt`、`.pth`、`.wav` 和改 YAML，可以在 Mac 上開本機設定頁。

先啟動 GPT-SoVITS API：

```bash
cd /Users/timchien/AndroidStudioProjects/VoiceWeaver
./tools/GPT-SoVITS/start_api.sh
```

另開一個終端機啟動設定頁：

```bash
cd /Users/timchien/AndroidStudioProjects/VoiceWeaver
./tools/GPT-SoVITS/start_model_setup_page.sh
```

瀏覽器打開：

```text
http://127.0.0.1:9890
```

在頁面上選：

- GPT 模型 `.ckpt`
- SoVITS 模型 `.pth`
- 參考音檔 `.wav`
- 模型版本
- 參考音檔逐字稿
- 測試句

按「套用並測試模型」後，設定頁會自動：

1. 複製模型到 `GPT_weights_<version>/` 與 `SoVITS_weights_<version>/`。
2. 複製參考音檔到 `tools/GPT-SoVITS/ref_audio/voiceweaver_custom/`。
3. 更新 `voiceweaver_tts_infer.yaml`。
4. 更新 App 端 `LocalSovitsHelper.kt` 的中文 ref 路徑與 prompt。
5. 呼叫 `/set_sovits_weights` 與 `/set_gpt_weights` 熱切換目前 server。
6. 呼叫 `/tts` 合成測試音檔。

測試輸出會在：

```text
/private/tmp/voiceweaver_model_setup_test.wav
```

播放：

```bash
afplay /private/tmp/voiceweaver_model_setup_test.wav
```

建立資料夾：

```bash
cd /Users/timchien/AndroidStudioProjects/VoiceWeaver
mkdir -p tools/GPT-SoVITS/user_models/MyVoice/GPT_weights_v2
mkdir -p tools/GPT-SoVITS/user_models/MyVoice/SoVITS_weights_v2
mkdir -p tools/GPT-SoVITS/user_models/MyVoice/ref_audio
```

把檔案放成：

```text
tools/GPT-SoVITS/user_models/MyVoice/
├── GPT_weights_v2/
│   └── my_voice.ckpt
├── SoVITS_weights_v2/
│   └── my_voice.pth
└── ref_audio/
    ├── ref_zh.wav
    └── prompt_zh.txt
```

建議參考音檔：

- 3 到 10 秒。
- 乾淨人聲，少背景音。
- WAV 格式。
- 16 kHz 以上，建議 32 kHz 或 44.1 kHz。
- 逐字稿必須完全對上音檔。

## 把模型複製到 GPT-SoVITS 標準權重資料夾

v2 模型：

```bash
cp tools/GPT-SoVITS/user_models/MyVoice/GPT_weights_v2/my_voice.ckpt \
  tools/GPT-SoVITS/GPT_weights_v2/my_voice.ckpt

cp tools/GPT-SoVITS/user_models/MyVoice/SoVITS_weights_v2/my_voice.pth \
  tools/GPT-SoVITS/SoVITS_weights_v2/my_voice.pth
```

如果是 v4 模型，放到：

```bash
mkdir -p tools/GPT-SoVITS/GPT_weights_v4
mkdir -p tools/GPT-SoVITS/SoVITS_weights_v4
```

再複製：

```bash
cp path/to/my_voice_v4.ckpt tools/GPT-SoVITS/GPT_weights_v4/my_voice_v4.ckpt
cp path/to/my_voice_v4.pth tools/GPT-SoVITS/SoVITS_weights_v4/my_voice_v4.pth
```

## 修改啟動設定

打開：

```text
tools/GPT-SoVITS/GPT_SoVITS/configs/voiceweaver_tts_infer.yaml
```

v2 範例：

```yaml
custom:
  bert_base_path: GPT_SoVITS/pretrained_models/chinese-roberta-wwm-ext-large
  cnhuhbert_base_path: GPT_SoVITS/pretrained_models/chinese-hubert-base
  device: cpu
  is_half: false
  t2s_weights_path: GPT_weights_v2/my_voice.ckpt
  version: v2
  vits_weights_path: SoVITS_weights_v2/my_voice.pth
```

v4 範例：

```yaml
custom:
  bert_base_path: GPT_SoVITS/pretrained_models/chinese-roberta-wwm-ext-large
  cnhuhbert_base_path: GPT_SoVITS/pretrained_models/chinese-hubert-base
  device: cpu
  is_half: false
  t2s_weights_path: GPT_SoVITS/pretrained_models/s1v3.ckpt
  version: v4
  vits_weights_path: GPT_SoVITS/pretrained_models/gsv-v4-pretrained/s2Gv4.pth
```

如果你的 v4 是「LoRA / 小模型」或自訓練輸出，請依模型提供者說明填入實際 `.ckpt` 與 `.pth`。v4 需要的底模包含：

```text
GPT_SoVITS/pretrained_models/s1v3.ckpt
GPT_SoVITS/pretrained_models/gsv-v4-pretrained/s2Gv4.pth
GPT_SoVITS/pretrained_models/gsv-v4-pretrained/vocoder.pth
```

## 熱切換模型

server 已經在跑時，可以不重啟，直接呼叫 API：

```bash
curl -G --data-urlencode "weights_path=SoVITS_weights_v2/my_voice.pth" \
  http://127.0.0.1:9880/set_sovits_weights

curl -G --data-urlencode "weights_path=GPT_weights_v2/my_voice.ckpt" \
  http://127.0.0.1:9880/set_gpt_weights
```

成功會看到：

```json
{"message":"success"}
```

如果失敗，先不要繼續測 App，先看錯誤訊息。

## 重啟 server

停止：

```bash
cd /Users/timchien/AndroidStudioProjects/VoiceWeaver
./tools/GPT-SoVITS/stop_api.sh
```

啟動：

```bash
./tools/GPT-SoVITS/start_api.sh
```

檢查：

```bash
curl http://127.0.0.1:9880/health
```

## 實際合成測試

中文測試：

```bash
curl -sS -X POST http://127.0.0.1:9880/tts \
  -H 'Content-Type: application/json' \
  -d '{
    "text": "你好，這是自訂模型測試。",
    "text_lang": "zh",
    "ref_audio_path": "/Users/timchien/AndroidStudioProjects/VoiceWeaver/tools/GPT-SoVITS/ref_audio/furina/ref_zh.wav",
    "prompt_text": "我的耳目曾经遍及整个大陆，为我探查情报。",
    "prompt_lang": "zh",
    "media_type": "wav",
    "streaming_mode": false,
    "text_split_method": "cut5",
    "batch_size": 1,
    "top_k": 15,
    "top_p": 1.0,
    "temperature": 1.0,
    "repetition_penalty": 1.35,
    "parallel_infer": true,
    "speed_factor": 1.0
  }' \
  -o /private/tmp/voiceweaver_model_test.wav
```

確認音檔：

```bash
file /private/tmp/voiceweaver_model_test.wav
ls -lh /private/tmp/voiceweaver_model_test.wav
```

## 更換參考音檔

Mac 端參考音檔槽位：

```text
tools/GPT-SoVITS/ref_audio/furina/ref_zh.wav
tools/GPT-SoVITS/ref_audio/furina/ref_en.wav
```

替換中文參考音檔：

```bash
cp tools/GPT-SoVITS/user_models/MyVoice/ref_audio/ref_zh.wav \
  tools/GPT-SoVITS/ref_audio/furina/ref_zh.wav
```

然後修改 App 內 prompt：

```text
app/src/main/java/com/example/ai/LocalSovitsHelper.kt
```

找到：

```kotlin
const val PROMPT_ZH = "..."
const val PROMPT_EN = "..."
```

改成和你的 `ref_zh.wav` / `ref_en.wav` 完全一致的逐字稿。

## App 端設定

1. 打開 VoiceWeaver。
2. 進入設定。
3. 開啟開發者模式。
4. 開啟 GPT-SoVITS 實驗語音。
5. API 位址填：

```text
http://127.0.0.1:9880
```

如果手機用 Wi-Fi 連 Mac，填：

```text
http://Mac的IP:9880
```

Mac 查 IP：

```bash
ipconfig getifaddr en0
```

USB 連線推薦：

```bash
adb reverse tcp:9880 tcp:9880
```

然後 App 填：

```text
http://127.0.0.1:9880
```

## 常見錯誤

### `/health` 404

舊版 `api_v2.py` 沒有 `/health`。本專案已補上 `/health`，請重啟 server。

### `PytorchStreamReader failed reading zip archive`

模型檔壞掉或沒有下載完整。重新下載 `.ckpt`。

### `.ckpt` 或 `.pth` 只有 100 多 bytes

這通常是 Git LFS pointer，不是真模型。需要用 `git lfs pull`、Hugging Face CLI、ModelScope SDK 或瀏覽器下載真正檔案。

### 大量 `_IncompatibleKeys`

代表 GPT/SoVITS 權重版本或架構不匹配。處理順序：

1. 確認 `version` 是否正確。
2. 確認 `.ckpt` 和 `.pth` 是同一組模型。
3. 切回官方底模確認 server 沒壞。
4. 換成相容模型。

### 聲音不像或很平

常見原因：

- 參考音檔太短或太吵。
- prompt 文字和音檔不一致。
- 模型本身訓練不足。
- 使用底模時只靠 3 到 10 秒參考音檔，不會等於完整聲線模型。
- 語速、temperature、top_p 設定太保守或太激進。

### v4 不支援 streaming

GPT-SoVITS v3/v4 對 streaming 和分桶處理有額外限制。測試時建議：

```json
"streaming_mode": false,
"parallel_infer": true
```

## 建議工作流程

1. 先用官方底模確認 server 正常。
2. 放入自訂模型 `.ckpt` / `.pth`。
3. 修改 `voiceweaver_tts_infer.yaml`。
4. 重啟 server。
5. 打 `/health`。
6. 用 curl 直接合成 wav。
7. 確認 wav 正常後，再進 App 測試。
8. 最後才調整 App 的測試句、語速與活潑參數。

## 安全與授權檢查

換模型前請確認：

- 你有權使用訓練資料。
- 模型 license 允許你的用途。
- 參考音檔不是未授權的遊戲、動畫、名人或他人錄音。
- 不把模型或生成聲音用於冒充他人。
- 對外分享時保留原模型 license 與來源資訊。
