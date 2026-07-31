# 自動化測試側門

App 的幾個關鍵行為要靠「時間累積」才會出現——推薦列要按滿三張卡才浮出來、
時間衰減要等七天才看得出差別、地點加成要人真的走到醫院。手動測要花好幾天，
而且測不到邊界。這套側門讓你**一秒重現原本要等一週的狀態**。

## 安全設計

三層防護，缺一不可：

| 層 | 機制 | 擋掉什麼 |
|---|---|---|
| 1 | 接收端 `TestCommandReceiver` 與其 manifest 宣告**只存在於 `app/src/debug/`** | 正式版 APK 裡連 class 都沒有，外界沒有任何管道能觸發 |
| 2 | 每個對外入口第一行 `if (!BuildConfig.DEBUG) return`（編譯期常數，R8 判定不可達） | 邏輯層的第二道保險 |
| 3 | 每次啟動產生隨機權杖，寫進 App 私有外部目錄（分區儲存下其他 App 讀不到） | 同機惡意程式拿不到權杖，送不進任何指令 |

另外，會刪資料的指令（`reset_data`）額外要求 `confirm=true`，避免測試腳本手滑。

驗證方式：

```bash
./gradlew :app:processReleaseMainManifest
grep -c TestCommandReceiver app/build/intermediates/packaged_manifests/release/*/AndroidManifest.xml
```

正式版應為 `0`。

## 用法

```bash
./tools/vw-test.sh <command> [key=value ...]
```

權杖由腳本自動取得。結果走廣播的 `resultData` 回傳（不依賴 logcat——
部分機型如 ASUS ROG 會把一般 App 的 log 整個過濾掉）。

### 指令

| 指令 | 參數 | 作用 |
|---|---|---|
| `ping` | — | 連通性檢查 |
| `dump` | — | 匯出完整狀態 JSON（UI + 設定 + 排序統計） |
| `nav` | `route=` | 跳分頁（`main`/`aac`/`rehab`/`report`/`history`/`settings`） |
| `inject_usage` | `itemId=` `score=` `ageDays=` `location=` | 偽造圖卡使用統計 |
| `clear_usage` | — | 清空統計，回到冷啟動 |
| `inject_location` | `tag=` 或 `lat=` `lon=` | 注入地點（tag 直接設定；經緯度會跑完整 Geocoder） |
| `search` | `query=` | 填入 AAC 搜尋框 |
| `force_permission` | `permission=` `granted=true/false/clear` | 覆寫權限狀態（不重啟 App）。鍵不分大小寫，內部統一轉大寫 |
| `set_font_scale` | `scale=` | 字級（0.8 ~ 1.8） |
| `set_theme` | `theme=` `highContrast=` | 主題（`Tech`/`Cute`/`Anime`/`Minimal`） |
| `set_language` | `language=` | 語言（`ZhTW`/`En`/`Ja`/`Ko`）——版面最容易在非中文語系爆掉 |
| `jank` | `on=true/false` | 掉幀監控；關閉時回報 dropped / worst |
| `reset_data` | `confirm=true` | 清空全部歷史與統計 |

`ageDays` 讓統計「看起來是幾天前按的」——半衰期是 7 天，所以 `score=8 ageDays=7`
會衰減成 4。不用改系統時間就能驗證衰減曲線。

`force_permission` 跟 `adb shell pm grant/revoke` 互補：`pm revoke` 會殺掉 App 行程，
看不到「授權在使用中被撤銷」那一瞬間畫面怎麼切；要驗證真實授權流程時仍該用 `pm`。

### 例：驗證推薦列的冷啟動門檻

```bash
./tools/vw-test.sh nav route=aac
./tools/vw-test.sh clear_usage
./tools/vw-test.sh dump | python3 -c "import sys,json;print(json.load(sys.stdin)['aac_ui'])"
# feed_visible=False

./tools/vw-test.sh inject_usage itemId=p_cig score=5
./tools/vw-test.sh inject_usage itemId=p_bag score=4
# 仍為 False——門檻是 3 張

./tools/vw-test.sh inject_usage itemId=p_receipt score=6
# feed_visible=True
```

圖卡 id 可從 `app/src/main/java/com/example/data/AacData.kt` 查。

### dump 欄位速查（腳本請照這些鍵名讀，不要猜）

| 區塊 | 鍵 |
|---|---|
| `current_route` | 目前分頁（main/aac/rehab/report/history/settings） |
| `aac_ui` | `search_query`・`selected_category`・`grid_item_count`・`feed_item_count`・`feed_visible`・`sentence_length`・`flashcard_mode` |
| `main_ui` | `stt_state`・`reconstruction_state`（sealed class 名：Idle/Loading/…） |
| `history_ui` | `count` |
| `rehab_ui` | `log_count` |
| `settings` | `theme`・`language`・`font_scale`・`high_contrast`・`tts_rate`・`severity_mode`・`developer_mode` |
| `forced_permissions` | 被覆寫的權限（大寫鍵），空 = 全跟隨系統 |

注意：`aac_ui` 是 AAC 畫面**組成時**回報的快照——人在別的分頁時 `search` 指令
不會立即生效（注入值等 AAC 畫面下次組成才消費），dump 讀到的也會是上次的快照。
要驗證搜尋，先 `nav route=aac` 再 `search`，隔一秒再 `dump`。

## 實測結果（ASUS ROG / Android 15）

| 項目 | 結果 |
|---|---|
| 冷啟動門檻（0 / 2 / 3 張） | 隱藏 / 隱藏 / 顯示 ✅ |
| 時間衰減 0・7・14・21 天（score=8） | 8.00 / 4.00 / 2.00 / 1.00 ✅ |
| 地點注入 + 跨分頁保留 | 切到設定再切回，地點與 DataStore 皆保留 ✅ |
| GPS 座標反解（25.0339, 121.5645） | 「餐廳·滿藝廚房」 ✅ |
| 搜尋注入（「水」） | 9 筆結果、推薦列自動隱藏 ✅ |
| 權限覆寫 | 🔒 授權按鈕正確顯示 ✅ |
| 錯誤權杖 / 未知指令 / 無 confirm 的 reset | 全部正確拒絕 ✅ |
