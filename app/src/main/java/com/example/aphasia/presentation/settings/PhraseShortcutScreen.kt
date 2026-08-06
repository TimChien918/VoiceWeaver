package com.example.aphasia.presentation.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.aphasia.data.local.PhraseLibrarySource
import com.example.aphasia.domain.model.AcousticTemplate
import com.example.aphasia.presentation.common.AmplitudeRing
import com.example.aphasia.presentation.common.MessageBar
import com.example.aphasia.presentation.common.SectionHeader
import com.example.aphasia.presentation.common.VSpace
import coil.compose.AsyncImage
import com.example.ui.I18n
import com.example.ui.currentIosScheme

/**
 * 「我的專屬發音」設定畫面。
 *
 * 兩個狀態：清單（挑一句）與登錄（唸三次）。刻意不做成兩個路由——
 * 照顧者常常一次設定好幾句，來回跳頁面比原地切換慢。
 */
@Composable
fun PhraseShortcutScreen(viewModel: PhraseShortcutViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    if (state.recordingFor != null) {
        EnrollmentStep(state, viewModel)
    } else {
        LibraryStep(state, viewModel, onBack)
    }
}

// ── 清單 ────────────────────────────────────────────────────────────────

@Composable
private fun LibraryStep(
    state: PhraseShortcutViewModel.UiState,
    viewModel: PhraseShortcutViewModel,
    onBack: () -> Unit,
) {
    val c = currentIosScheme()

    // 自己鋪底色。不鋪的話會吃到 Scaffold 的 palette.darkBg（偏深的灰），
    // 但卡片用的是 iOS 色系的白——那兩個本來不是一組，配起來對比過強，
    // 跟底部導覽列的淺色也對不上。c.bg 才是白卡片的配對背景。
    Column(Modifier.fillMaxSize().background(c.bg)) {
        // 標題列。原本只靠底部導覽離開，等於沒有「回上一頁」——
        // 這是從設定進來的子畫面，使用者的預期是左上角有返回。
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = I18n.tr("返回"), tint = c.label,
                )
            }
            Text(
                I18n.tr("我的專屬發音"),
                color = c.label, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            // 說明預設收起。它有四行，展開時會把整個上半部吃掉，
            // 而使用者只有第一次需要讀。
            TextButton(onClick = { viewModel.toggleHelp() }) {
                Text(
                    if (state.helpExpanded) I18n.tr("收起說明") else I18n.tr("這是什麼？"),
                    color = c.blue, fontSize = 14.sp,
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.message?.let { item { MessageBar(it) { viewModel.dismissMessage() } } }

            if (state.helpExpanded) {
                item {
                    Text(
                        I18n.tr("挑一句，然後把畫面上那幾個字唸三次。之後你講的話裡帶到那幾個字，App 就會說出整句——比對走聲波形狀，不經過語音轉文字，所以發音不標準也沒關係。"),
                        color = c.secondary, fontSize = 14.sp,
                    )
                }
            }

            state.justSaved?.let { kw ->
                item {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = c.green.copy(alpha = 0.14f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            I18n.tr("「%s」已啟用").format(kw),
                            color = c.label, fontSize = 17.sp,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }

            // ── 測試 ────────────────────────────────────────────────────
            // 放在已啟用清單的正上方：剛設定完最想做的就是確認它管不管用。
            if (state.enrolled.isNotEmpty()) {
                item { TestCard(state, viewModel) }
            }

            if (state.enrolled.isNotEmpty()) {
                item { SectionHeader(I18n.tr("已啟用（%d）").format(state.enrolled.size), c.secondary) }
                items(state.enrolled, key = { it.id }) { t ->
                    EnrolledRow(
                        template = t,
                        // 樣板只存 keyword，圖示鍵是句庫的 id，所以要回查。
                        // 自訂的句子查不到，PhraseIcon 會留白。
                        iconKey = state.library
                            .firstOrNull { it.keyword.equals(t.keyword, ignoreCase = true) }?.id,
                        onAddTakes = { viewModel.beginAddTakes(t) },
                        onDelete = { viewModel.delete(t) },
                    )
                }
            }

            // 同步放在「加入新的」上面：換手機時第一件想做的事是把舊的拿回來，
            // 而不是重新設定一次。
            item {
                Surface(
                    onClick = { if (!state.syncing) viewModel.syncFromCloud() },
                    shape = RoundedCornerShape(12.dp),
                    color = c.card,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.CloudDownload, contentDescription = null,
                            tint = if (state.syncing) c.secondary else c.blue,
                        )
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(
                                if (state.syncing) I18n.tr("取回中…") else I18n.tr("從雲端取回"),
                                color = c.label, fontSize = 17.sp,
                            )
                            Text(
                                I18n.tr("換手機時把設定與錄音一起拿回來，不必重錄"),
                                color = c.secondary, fontSize = 13.sp,
                            )
                        }
                    }
                }
            }

            item { SectionHeader(I18n.tr("加入新的"), c.secondary) }
            item {
                Surface(
                    onClick = { viewModel.beginCustom() },
                    shape = RoundedCornerShape(12.dp),
                    color = c.card,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Add, contentDescription = null, tint = c.blue)
                        Text(
                            I18n.tr("自己想一句"),
                            color = c.label, fontSize = 17.sp,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                }
            }

            // 已啟用的不再出現在清單裡。同一句同時出現在兩個區塊，
            // 使用者會以為是兩個不同的東西，也不知道該點哪一個。
            val remaining = state.library.filter { it.keyword.lowercase() !in state.enrolledKeywords }
            remaining.map { it.category }.distinct().forEach { category ->
                item { SectionHeader(category, c.secondary) }
                items(remaining.filter { it.category == category }, key = { it.id }) { entry ->
                    LibraryRow(entry) { viewModel.beginEnrollment(entry) }
                }
            }

            item { VSpace(24) }
        }
    }
}

/**
 * 測試卡片：按住講一句話，看會不會命中。
 *
 * 顯示距離與門檻的原始數字。對使用者這是「差一點還是差很多」，
 * 對我這是校準 KEYWORD_THRESHOLD_FACTOR 唯一的真實資料來源——
 * 目前那個 0.75 是合成訊號訂的，沒有用真人錄音驗證過。
 */
@Composable
private fun TestCard(
    state: PhraseShortcutViewModel.UiState,
    viewModel: PhraseShortcutViewModel,
) {
    val c = currentIosScheme()
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = c.card,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(I18n.tr("測試看看"), color = c.label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(
                I18n.tr("按住，講一整句話。命中的話會唸出來。"),
                color = c.secondary, fontSize = 13.sp,
            )
            VSpace(12)

            // 靈敏度就放在測試旁邊：調完立刻按下去看效果，
            // 而不是跑到別的畫面調完再回來猜有沒有變。
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(I18n.tr("靈敏度"), color = c.secondary, fontSize = 13.sp)
                Spacer(Modifier.width(10.dp))
                PhraseShortcutViewModel.Sensitivity.entries.forEach { level ->
                    val selected = state.sensitivity == level
                    Surface(
                        onClick = { viewModel.setSensitivity(level) },
                        shape = RoundedCornerShape(8.dp),
                        color = if (selected) c.blue else c.blue.copy(alpha = 0.10f),
                        modifier = Modifier.padding(end = 6.dp),
                    ) {
                        Text(
                            I18n.tr(level.labelKey),
                            color = if (selected) Color.White else c.blue,
                            fontSize = 13.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
            }
            VSpace(12)

            Row(verticalAlignment = Alignment.CenterVertically) {
                AmplitudeRing(
                    amplitude = state.amplitude,
                    active = state.testing,
                    ringColor = c.blue,
                    ringSize = 96,
                ) {
                    Surface(
                        shape = CircleShape,
                        color = if (state.testing) c.red else c.blue,
                        modifier = Modifier
                            .size(72.dp)
                            .semantics {
                                contentDescription = if (state.testing) "Listening" else "Hold to test"
                            }
                            .pointerInput(state.testing) {
                                detectTapGestures(onPress = {
                                    viewModel.startTest()
                                    tryAwaitRelease()
                                    viewModel.stopTest()
                                })
                            },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Mic, contentDescription = null,
                                tint = c.card, modifier = Modifier.size(34.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    val result = state.testResult
                    when {
                        state.testing -> Text(I18n.tr("聽著…"), color = c.secondary, fontSize = 15.sp)
                        result == null -> Text(
                            I18n.tr("還沒測過"), color = c.secondary, fontSize = 15.sp,
                        )
                        result.notSpeech -> Text(
                            I18n.tr("沒有聽到聲音"), color = c.red, fontSize = 15.sp,
                        )
                        result.hit -> {
                            val best = result.best!!
                            Text(
                                "✓ " + best.template.spokenPhrase,
                                color = c.green, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                            )
                            Text(
                                "%.2f / %.2f".format(best.distance, best.threshold),
                                color = c.secondary, fontSize = 12.sp,
                            )
                        }
                        else -> {
                            Text(
                                I18n.tr("✗ 沒有命中"), color = c.red,
                                fontSize = 16.sp, fontWeight = FontWeight.Medium,
                            )
                            // 最接近的那個也要顯示。使用者才知道是「差一點」
                            // 還是「完全不像」——這兩種要做的事完全不同。
                            result.best?.let { b ->
                                Text(
                                    I18n.tr("最接近：%s").format(b.template.keyword) +
                                        "  %.2f / %.2f".format(b.distance, b.threshold),
                                    color = c.secondary, fontSize = 12.sp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 句庫圖示。檔案在 `assets/phrase_icons/<key>.png`，鍵就是句庫的 id。
 *
 * 走 assets 而不是 res/drawable：這些圖是跟著 phrase_library_en.json 一起長的，
 * 用同一套鍵值對應才不會有兩份要維護。而 drawable 要用 getIdentifier 動態查表，
 * 那個 API 慢、又會被 R8 的資源縮減砍掉。
 *
 * 自訂句子沒有對應的圖，[key] 傳 null 就留白——不畫佔位圖示，
 * 一個看不懂的通用符號比空白更容易讓人誤會。
 */
@Composable
private fun PhraseIcon(key: String?, size: Int = 44) {
    if (key.isNullOrBlank()) {
        Spacer(Modifier.width(size.dp))
        return
    }
    AsyncImage(
        model = "file:///android_asset/phrase_icons/$key.png",
        contentDescription = null,   // 旁邊就是句子本身，讀屏重複唸一次沒有幫助
        modifier = Modifier.size(size.dp),
    )
}

@Composable
private fun LibraryRow(entry: PhraseLibrarySource.Entry, onClick: () -> Unit) {
    val c = currentIosScheme()
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = c.card,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            PhraseIcon(entry.id)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.sentence, color = c.label, fontSize = 17.sp, lineHeight = 22.sp)
                // 關鍵字才是使用者要唸的東西，顯眼一點
                Text(
                    I18n.tr("聽到：") + "「${entry.keyword}」",
                    color = c.blue, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                )
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Default.Mic, contentDescription = I18n.tr("錄音啟用"),
                tint = c.secondary, modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun EnrolledRow(
    template: AcousticTemplate,
    iconKey: String?,
    onAddTakes: () -> Unit,
    onDelete: () -> Unit,
) {
    val c = currentIosScheme()
    Surface(shape = RoundedCornerShape(12.dp), color = c.card, modifier = Modifier.fillMaxWidth()) {
        // 動作獨立成第二行，不跟句子搶同一行的寬度。
        //
        // 原本「圖示 + 句子 + 再教一次 + 刪除」全擠在一行，兩顆按鈕吃掉約 140dp，
        // 剩下的寬度讓句子被迫斷成兩行，圖示又垂直置中，整列看起來是散的。
        // 句子是這張卡片最重要的東西，應該拿到整行寬度。
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PhraseIcon(iconKey)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(template.spokenPhrase, color = c.label, fontSize = 17.sp, lineHeight = 22.sp)
                    Text(
                        I18n.tr("聽到：") + "「${template.keyword}」",
                        color = c.blue, fontSize = 14.sp,
                    )
                    // 門檻數字不放這裡。對一般使用者是噪音，而需要它的時候
                    //（比對一直失敗）測試卡片會直接顯示這次的距離與門檻，
                    // 那才是有脈絡、看得懂的呈現方式。
                }
            }
            Row(
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                // 「再教一次」：認不出來時最有效的補救，所以維持文字標籤而不是
                // 換成一個看不懂的圖示——這是使用者最需要找得到的按鈕。
                TextButton(onClick = onAddTakes) {
                    Text(I18n.tr("再教一次"), color = c.blue, fontSize = 14.sp)
                }
                // 刪除鍵維持紅色（破壞性動作的慣例），但縮小。
                // 原本 24dp 的實心垃圾桶在卡片上比句子本身還搶眼。
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete, contentDescription = I18n.tr("刪除"),
                        tint = c.red.copy(alpha = 0.85f), modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

// ── 登錄 ────────────────────────────────────────────────────────────────

@Composable
private fun EnrollmentStep(
    state: PhraseShortcutViewModel.UiState,
    viewModel: PhraseShortcutViewModel,
) {
    val c = currentIosScheme()
    val entry = state.recordingFor ?: return
    val keyword = if (state.isCustom) state.customKeyword else entry.keyword

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(c.bg).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { VSpace(8) }
        state.message?.let { item { MessageBar(it) { viewModel.dismissMessage() } } }

        if (state.isCustom) {
            item {
                OutlinedTextField(
                    value = state.customKeyword,
                    onValueChange = viewModel::setCustomKeyword,
                    label = { Text(I18n.tr("要聽的字")) },
                    placeholder = { Text("need water") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedTextField(
                    value = state.customSentence,
                    onValueChange = viewModel::setCustomSentence,
                    label = { Text(I18n.tr("要說出口的話")) },
                    placeholder = { Text("I need some water, please.") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PhraseIcon(state.enrollIconKey, size = 64)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        if (state.addingTo != null) {
                            Text(
                                I18n.tr("再教一次（會加進原本的錄音，不會蓋掉）"),
                                color = c.blue, fontSize = 14.sp,
                            )
                        }
                        Text(entry.sentence, color = c.label, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        item {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = c.blue.copy(alpha = 0.10f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(I18n.tr("請唸這幾個字"), color = c.secondary, fontSize = 14.sp)
                    Text(
                        keyword.ifBlank { "…" },
                        color = c.blue, fontSize = 28.sp, fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                val pace = state.nextPace
                Text(
                    pace?.instruction ?: I18n.tr("錄好了，可以儲存。"),
                    color = if (pace != null) c.secondary else c.green,
                    fontSize = 17.sp, fontWeight = FontWeight.Medium,
                )
                VSpace(12)
                AmplitudeRing(
                    amplitude = state.amplitude,
                    active = state.recording,
                    ringColor = c.blue,
                    ringSize = 170,
                ) {
                    Surface(
                        shape = CircleShape,
                        color = when {
                            pace == null -> c.green
                            state.recording -> c.red
                            else -> c.blue
                        },
                        modifier = Modifier
                            .size(120.dp)
                            .semantics {
                                contentDescription = if (state.recording) {
                                    "Recording. Release when you finish."
                                } else "Hold to record"
                            }
                            .pointerInput(pace, state.recording) {
                                if (pace == null) return@pointerInput
                                detectTapGestures(onPress = {
                                    viewModel.startTake()
                                    tryAwaitRelease()
                                    viewModel.stopTake()
                                })
                            },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                if (pace == null) Icons.Default.Check else Icons.Default.Mic,
                                contentDescription = null, tint = c.card,
                                modifier = Modifier.size(52.dp),
                            )
                        }
                    }
                }
                VSpace(8)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.paces.forEachIndexed { i, p ->
                        val done = i < state.takes.size
                        Surface(
                            shape = CircleShape,
                            color = if (done) c.green else c.secondary.copy(alpha = 0.3f),
                            modifier = Modifier
                                .size(14.dp)
                                .semantics {
                                    contentDescription = "${p.name} recording ${if (done) "done" else "pending"}"
                                },
                        ) {}
                    }
                }
                if (state.takes.isNotEmpty()) {
                    TextButton(onClick = { viewModel.undoLastTake() }) { Text(I18n.tr("重錄上一次")) }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { viewModel.save() },
                    enabled = state.canSave,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = c.blue,
                        contentColor = Color.White,
                        disabledContainerColor = c.separator,
                        disabledContentColor = c.secondary,
                    ),
                    modifier = Modifier.weight(1f).height(52.dp),
                ) { Text(I18n.tr("啟用"), fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
                TextButton(
                    onClick = { viewModel.cancelEnrollment() },
                    modifier = Modifier.height(56.dp),
                ) { Text(I18n.tr("取消")) }
            }
        }
        item { VSpace(24) }
    }
}
