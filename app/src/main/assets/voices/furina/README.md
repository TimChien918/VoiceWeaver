# Furina 參考語音資料夾

這個資料夾被 App 視為**預設語音**（GPT-SoVITS 參考音檔）。第一次啟動時，
`FurinaAssetProvider` 會把這裡的檔案複製到外部儲存區並把 URI 寫進設定，
不需要手動匯入。

## 必要檔案

| 檔名 | 用途 |
|---|---|
| `ref_zh.wav` | 中文合成的參考音檔（建議 3-10 秒、單聲道、16 kHz 以上 PCM WAV） |
| `ref_en.wav` | 英文合成的參考音檔（同上） |
| `prompt_zh.txt` | 對應 `ref_zh.wav` 的中文逐字稿（一行即可） |
| `prompt_en.txt` | 對應 `ref_en.wav` 的英文逐字稿 |

## 版權注意

App 並未內嵌《原神》的任何官方錄音；隨包附的 `ref_*.wav` 為**短靜音占位**，
僅供 App 啟動時不會崩潰用。請依下列其中一個方式替換：

1. **自行錄製**：找配音愛好者錄 3-10 秒乾淨清晰的語音當參考音檔。
2. **自用版權範圍內取得**：例如 mod 社群釋出的 CC 授權樣本。
3. **付費資源**：HoYoverse 官方授權商品（如 OST CD）的個人非商用片段。

替換完成後**重啟 App**即可使用自備且已授權的角色音色。

## 進階：自定義 GPT-SoVITS 模型

把自備且已授權的 `.pth` (SoVITS) 和 `.ckpt` (GPT) 權重放在
`/sdcard/Android/media/<package>/VoiceWeaver/GPT-SoVITS/Furina_ZH/` 與
`Furina_EN/` 即可被「自動匯入」流程偵測。
