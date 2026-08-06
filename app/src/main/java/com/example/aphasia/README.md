# aphasia — Clean Architecture 骨架

新的失語症溝通引擎放這裡，與既有的 `com.example.{ai,ui,data}` 並存。
**刻意不直接原地改舊程式**：舊樹目前是可運作的 App，逐檔遷移比一次性重寫安全，
每個 Phase 結束都還能出一個裝得起來的 APK。

## 相依方向

```
presentation  ──▶  domain  ◀──  data
```

`domain` 不依賴 Android SDK、不依賴任何 framework。它只有資料類別、
repository 介面與 use case，因此可以用純 JVM 單元測試跑（不需要 Robolectric）。

## 各層職責

| 套件 | 內容 | Phase |
|---|---|---|
| `domain/model` | `Utterance`、`FeatureSequence`、`AcousticTemplate`、`MatchResult`、`CommunicationPhrase`、`IntentNode`、`ReconstructedSentence`、`VoiceProfile` | 1 ✅ |
| `domain/repository` | 5 個介面：Audio / AcousticTemplate / Phrasebook / LanguageModel / Speech | 1 ✅ |
| `domain/usecase` | `MatchUtterance`、`EnrollAcousticTemplate`、`ReconstructSentence`、`Speak` | 1 ✅ |
| `data/acoustic` | `AudioCaptureManager`(AudioRecord/PCM16)、`Fft`、`MfccExtractor`、`DtwMatcher`、`TemplateMatcher` | 2 ✅ |
| `data/local` | `AphasiaDatabase`(Room v1)、`VectorCodec`、`TextEmbedder`、`PhraseVectorIndex` | 3 ✅ |
| `data/remote` | `LlmTransport`、`AphasiaPrompts`、`LlmResponseParser`、`ReconstructionGuard` | 4 ✅ |
| `data/repository` | AudioFeature / AcousticTemplate / Phrasebook / LanguageModel 已實作；Speech 待 Phase 5 | 2–4 |
| `presentation/*` | `CommunicateViewModel/Screen`、`EnrollmentViewModel/Screen`、共用元件 | 5 ✅ |
| `di` | `AphasiaGraph`（手動組裝，此專案沒有用 Hilt） | 5 ✅ |

## 三個已定案的技術決策

**DTW 自己寫。** FastDTW 沒有維護中的 Maven 套件，而且現成實作普遍缺少
Sakoe-Chiba band（±10%）與路徑長度正規化——這兩件事在這裡不是最佳化而是正確性問題：
沒有正規化，長音天生距離就大，門檻校準不可能跨長度成立。

**TFLite 不拿來算 MFCC。** MFCC 是純 DSP（預強調 → 分幀加窗 → FFT → mel 濾波 → log → DCT），
丟進 TFLite 不會更快，只是多一層。TFLite 留給「學習型語音嵌入」那條路線，
它對構音障礙的發音變異容忍度應該優於原始 MFCC。做法是先用 MFCC 打通整條管線，
再以同一批登錄錄音 A/B，有實測差距才換。

**向量檢索不用 ObjectBox，用 Room。** 三個理由，任一個都足夠：
它的 Gradle plugin 不支援 configuration cache（`PrepareTask` 序列化 `DefaultProject` 失敗），
而本專案 `org.gradle.configuration-cache=true` 是全域開的，為它關掉會拖慢所有人的建置；
4.x 的 plugin marker 根本沒發佈到 Maven Central，只能靠舊式 buildscript classpath；
而實際規模是「數百～數千句取 Top-5」，暴力 cosine 在這個量級是次毫秒等級，HNSW 沒有實質收益。
`PhrasebookRepository.searchSimilar` 這個介面把它隔開了，日後量級真的上去要換不影響呼叫端。

## 資料庫

`aphasia_engine.db`，與既有的 `aphasia_assist_database` **分開**。
那個資料庫用 `fallbackToDestructiveMigration(true)`，schema 一改就整個清空——
對復健日誌是可接受的取捨，但這裡存的是聲學樣板與個人語句庫，
使用者要重新錄好幾次、系統要重新校準門檻才能重建。

因此這裡 `exportSchema = true`（schema JSON 在 `app/schemas/`，要進版控）
且**不加** destructive fallback：改 schema 就必須寫 migration，
忘了寫會在開發階段直接爆炸，而不是在使用者手機上默默清空。

## 為什麼 LLM 傳輸層沒有重寫

`data/remote/LlmTransport` 只有一個方法：送一段 prompt、拿回一段文字。
供應商排序（`SmartApiRouter`）、失敗換家、金鑰解析、診斷日誌這些既有實作已經在跑，
複製一份只會製造兩邊行為不一致的回歸。所以在 `HybridInferenceEngine` 與
`MediaPipeLLMEngine` 各開了一個 `complete()` 公開方法當缺口，新樹接在上面。

新樹自己掌握的是 **prompt 與輸出契約**——那才是規格要求重寫、也才是實際會出錯的部分。
好處是 `LanguageModelRepositoryImpl` 完全不碰網路就能測試。

## 進入點

新引擎掛在 `speak` 與 `speak_enroll` 兩條路由，從主畫面的按鈕進入。
**沒有加進底部導覽**：那六項在橫向手機已經逼近寬度上限（見 MainActivity 的
`compactRail` 註解），加第七項會把「設定」擠掉。要不要升成分頁、要不要取代
既有的重組流程，是等真機實測驗過之後的產品決定。
