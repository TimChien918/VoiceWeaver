package com.example.aphasia.data.repository

import com.example.ai.DiagnosticLog
import com.example.aphasia.data.acoustic.DtwMatcher
import com.example.aphasia.data.acoustic.MfccExtractor
import com.example.aphasia.data.acoustic.TemplateMatcher
import com.example.aphasia.data.local.AcousticTemplateDao
import com.example.aphasia.data.local.AcousticTemplateEntity
import com.example.aphasia.data.local.VectorCodec
import com.example.aphasia.domain.model.AcousticTemplate
import com.example.aphasia.domain.model.MatchMode
import com.example.aphasia.domain.model.MatchResult
import com.example.aphasia.domain.model.Utterance
import com.example.aphasia.domain.repository.AcousticTemplateRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class AcousticTemplateRepositoryImpl(
    private val dao: AcousticTemplateDao,
    private val extractor: MfccExtractor = MfccExtractor(),
    private val now: () -> Long = System::currentTimeMillis,
    /**
     * 使用者可調的靈敏度倍率，乘在拒絕門檻上。
     *
     * 每次比對都重讀（而不是建構時抓一次）：使用者在畫面上調完滑桿之後
     * 下一次發聲就該生效，不該要重開 App。
     */
    private val sensitivity: () -> Float = { 1f },
) : AcousticTemplateRepository {

    override fun observeAll(): Flow<List<AcousticTemplate>> =
        dao.observeAll().map { rows -> rows.mapNotNull { it.toDomainOrNull() } }

    override suspend fun enroll(
        label: String,
        spokenPhrase: String,
        takes: List<Utterance>,
    ): AcousticTemplate = enroll(label, label, spokenPhrase, MatchMode.Keyword, takes)

    suspend fun enroll(
        label: String,
        keyword: String,
        spokenPhrase: String,
        matchMode: MatchMode,
        takes: List<Utterance>,
    ): AcousticTemplate = withContext(Dispatchers.Default) {
        require(takes.size >= AcousticTemplate.MIN_ENROLLMENT_TAKES) {
            "Need at least ${AcousticTemplate.MIN_ENROLLMENT_TAKES} recordings, got ${takes.size}"
        }
        val sequences = takes.map { extractor.extract(it) }
        val usable = sequences.filter { it.frameCount > 0 }
        require(usable.size >= AcousticTemplate.MIN_ENROLLMENT_TAKES) {
            "Only ${usable.size} of ${takes.size} recordings contained speech"
        }

        // 門檻由這幾次錄音彼此的距離決定。校準不出來就不要建樣板——
        // 硬給一個預設常數等於讓這個樣板的行為完全無法預測。
        val threshold = DtwMatcher.calibrateThreshold(usable)
            ?: throw IllegalArgumentException(
                "Recordings were too inconsistent to calibrate a threshold; ask the user to record again"
            )

        val entity = AcousticTemplateEntity(
            label = label,
            matchMode = matchMode.name,
            keyword = keyword,
            spokenPhrase = spokenPhrase,
            exemplarBlob = VectorCodec.encodeBundle(usable),
            rejectionThreshold = threshold,
            marginThreshold = AcousticTemplate.DEFAULT_MARGIN,
            createdAtMillis = now(),
        )
        val id = dao.insert(entity)
        // 組內距離要一起記：門檻就是從這些數字算出來的，
        // 而「登錄品質好不好」只看得出來一個門檻值是判斷不了的。
        val pairwise = buildList {
            for (i in usable.indices) for (j in i + 1 until usable.size) {
                add(DtwMatcher.distance(usable[i], usable[j]))
            }
        }.filter { it < Float.MAX_VALUE }
        DiagnosticLog.i(
            DiagnosticLog.Cat.STT,
            "登錄聲學樣板「$label」",
            "mode=$matchMode keyword=「$keyword」 takes=${usable.size}/${takes.size} " +
                "threshold=%.3f frames=%s 組內距離=%s".format(
                    threshold, usable.map { it.frameCount },
                    pairwise.joinToString("/") { "%.2f".format(it) },
                ),
        )
        syncUp(id, label, keyword, spokenPhrase, matchMode.name, threshold,
            AcousticTemplate.DEFAULT_MARGIN, entity.exemplarBlob, entity.createdAtMillis)
        AcousticTemplate(
            id = id,
            label = label,
            matchMode = matchMode,
            keyword = keyword,
            spokenPhrase = spokenPhrase,
            exemplars = usable,
            rejectionThreshold = threshold,
            marginThreshold = AcousticTemplate.DEFAULT_MARGIN,
            createdAtMillis = entity.createdAtMillis,
        )
    }

    /**
     * 把新錄音併進既有樣板，並重新校準門檻。
     *
     * 這是「唸了一樣的卻沒反應」的正解。實測同一個人講同一句，距離會在
     * 3.6 到 7.7 之間跳——3 次登錄錄音涵蓋不了真實的組內變異。
     * 比對取的是「與任一次錄音的最小距離」，所以多一次錄音只會提高召回率，
     * 不會讓別的音更容易誤觸發。
     */
    suspend fun addTakes(id: Long, takes: List<Utterance>): AcousticTemplate =
        withContext(Dispatchers.Default) {
            // 只查一次。之前查了兩次（一次轉領域物件、一次拿 entity 來 copy），
            // 第二次用 !! ——中間若被刪掉就會 NPE，而那在「補錄的同時
            // 另一個畫面刪掉同一筆」是真的做得到的。
            val row = dao.getById(id)
                ?: throw IllegalArgumentException("Template #$id not found")
            val existing = row.toDomainOrNull()
                ?: throw IllegalArgumentException("Template #$id has an unreadable blob")
            val fresh = takes.map { extractor.extract(it) }.filter { it.frameCount > 0 }
            require(fresh.isNotEmpty()) { "None of the new recordings contained speech" }

            val merged = (existing.exemplars + fresh).takeLast(MAX_EXEMPLARS)
            val threshold = DtwMatcher.calibrateThreshold(merged)
                ?: throw IllegalArgumentException(
                    "Recordings were too inconsistent to calibrate a threshold; ask the user to record again"
                )
            dao.update(
                row.copy(
                    exemplarBlob = VectorCodec.encodeBundle(merged),
                    rejectionThreshold = threshold,
                )
            )
            DiagnosticLog.i(
                DiagnosticLog.Cat.STT, "補錄「${existing.keyword}」",
                "錄音 ${existing.exemplars.size}→${merged.size} 門檻 %.3f→%.3f"
                    .format(existing.rejectionThreshold, threshold),
            )
            syncUp(id, existing.label, existing.keyword, existing.spokenPhrase,
                existing.matchMode.name, threshold, existing.marginThreshold,
                VectorCodec.encodeBundle(merged), existing.createdAtMillis)
            existing.copy(exemplars = merged, rejectionThreshold = threshold)
        }

    /**
     * 用目前的公式重算所有既有樣板的門檻。
     *
     * 校準公式從「全配對平均」改成「最近鄰」之後，舊資料的門檻仍是用舊公式算的
     *（實測有一筆膨脹到 21.9，把所有聲音都吞掉）。錄音本身還在，所以不需要
     * 請使用者重錄——直接重算就好。冪等，重複呼叫沒有副作用。
     */
    suspend fun recalibrateAll(): Int = withContext(Dispatchers.Default) {
        var changed = 0
        for (row in dao.getAll()) {
            val template = row.toDomainOrNull() ?: continue
            val fresh = DtwMatcher.calibrateThreshold(template.exemplars) ?: continue
            if (kotlin.math.abs(fresh - row.rejectionThreshold) < 0.01f) continue
            dao.update(row.copy(rejectionThreshold = fresh))
            DiagnosticLog.i(
                DiagnosticLog.Cat.STT, "重算門檻「${template.keyword}」",
                "%.3f → %.3f（%d 次錄音）".format(row.rejectionThreshold, fresh, template.exemplars.size),
            )
            changed++
        }
        changed
    }

    override suspend fun delete(id: Long) {
        dao.delete(id)
        // 刪除也要同步。少了這一步，換手機拉回來時會把使用者刻意刪掉的
        // 樣板救回來——而他刪掉多半正是因為那筆會誤觸發。
        runCatching { com.example.ai.CloudSync.deleteAcousticTemplate(id) }
    }

    /**
     * 把樣板推上雲端。連錄音波形一起傳——換手機不必重錄，
     * 而錄音正是這個功能最花使用者力氣的部分（一句要錄 5 次）。
     *
     * 失敗不影響本機：沒登入、沒網路都很正常，同步是加值不是前提。
     */
    private fun syncUp(
        id: Long, label: String, keyword: String, spokenPhrase: String,
        matchMode: String, rejection: Float, margin: Float,
        blob: ByteArray, createdAt: Long,
    ) {
        runCatching {
            com.example.ai.CloudSync.pushAcousticTemplate(
                id = id, label = label, keyword = keyword, spokenPhrase = spokenPhrase,
                matchMode = matchMode, rejectionThreshold = rejection, marginThreshold = margin,
                exemplarBlobB64 = android.util.Base64.encodeToString(blob, android.util.Base64.NO_WRAP),
                createdAt = createdAt,
            )
        }.onFailure {
            DiagnosticLog.w(DiagnosticLog.Cat.STT, "樣板同步失敗（不影響本機）", it.message.orEmpty())
        }
    }

    /**
     * 從雲端拉回樣板，合併進本機。
     *
     * 合併規則：**以 keyword 為準**，本機已經有同一個觸發詞就跳過。
     * 理由是本機的那筆是「這支手機上錄的、門檻也是照這支麥克風校準的」，
     * 雲端來的未必更好；而重複兩筆同觸發詞只會互相搶命中。
     *
     * 沒有錄音波形的（網頁版建的）會被跳過並回報數量——那種要在手機上補錄
     * 才會生效，直接寫進資料庫只會變成一筆永遠不會命中的死樣板。
     *
     * @return 實際匯入幾筆、以及有幾筆因為沒錄音而待補錄
     */
    suspend fun importFromCloud(
        remote: List<com.example.ai.CloudSync.RemoteTemplate>,
    ): Pair<Int, Int> = withContext(Dispatchers.Default) {
        val existingKeywords = dao.getAll().map { it.keyword.lowercase() }.toSet()
        var imported = 0
        var needsRecording = 0
        for (r in remote) {
            if (r.keyword.lowercase() in existingKeywords) continue
            if (r.exemplarBlobB64.isBlank()) { needsRecording++; continue }
            val blob = runCatching {
                android.util.Base64.decode(r.exemplarBlobB64, android.util.Base64.NO_WRAP)
            }.getOrNull() ?: continue
            // 解得開才寫進去：壞掉的 blob 會變成一筆永遠比對失敗的樣板，
            // 而使用者只會覺得「這個怎麼都叫不出來」。
            if (runCatching { VectorCodec.decodeBundle(blob) }.getOrNull().isNullOrEmpty()) continue
            dao.insert(
                AcousticTemplateEntity(
                    label = r.label, matchMode = r.matchMode, keyword = r.keyword,
                    spokenPhrase = r.spokenPhrase, exemplarBlob = blob,
                    rejectionThreshold = r.rejectionThreshold,
                    marginThreshold = r.marginThreshold,
                    createdAtMillis = r.createdAt.takeIf { it > 0 } ?: now(),
                )
            )
            imported++
        }
        DiagnosticLog.i(DiagnosticLog.Cat.STT, "從雲端匯入專屬發音",
            "匯入=$imported 待補錄=$needsRecording 雲端共=${remote.size}")
        imported to needsRecording
    }

    override suspend fun match(utterance: Utterance): MatchResult = withContext(Dispatchers.Default) {
        val templates = dao.getAll().mapNotNull { it.toDomainOrNull() }
        if (templates.isEmpty()) return@withContext MatchResult.NoMatch

        val started = System.nanoTime()
        val query = extractor.extract(utterance)
        val gain = sensitivity()
        val scores = TemplateMatcher.scoreAll(query, templates, gain)
        val result = TemplateMatcher.match(query, templates, sensitivity = gain)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000.0

        // **每一次都要記下所有樣板的距離與門檻，命中與否都要。**
        //
        // 之前沒中只寫「無相符樣板」，結果使用者回報「唸了一樣的卻沒反應」時
        // 完全無從判斷是差一點還是差很多——而那兩種要做的事完全相反
        //（差一點＝多錄幾次補涵蓋；差很多＝門檻或特徵有問題）。
        DiagnosticLog.i(
            DiagnosticLog.Cat.STT,
            "聲學比對 ${result.describe()}",
            "frames=${query.frameCount} 靈敏度=%.2f ${elapsedMs.format()}ms  ".format(gain) +
                scores.joinToString(" ") {
                    "${it.template.keyword}=%.3f/%.3f(x%.2f)".format(it.distance, it.threshold, it.ratio)
                },
        )
        result
    }

    /**
     * 比對但不下判斷：回傳每個樣板的距離與門檻。
     *
     * 給設定頁的「測試」用。使用者剛錄完一句話，需要看到實際數字才知道
     * 是差一點還是差很多——只說「沒命中」的話，他不知道該重錄還是該找我調參數。
     */
    suspend fun diagnose(utterance: Utterance): List<TemplateMatcher.Score> =
        withContext(Dispatchers.Default) {
            val templates = dao.getAll().mapNotNull { it.toDomainOrNull() }
            if (templates.isEmpty()) return@withContext emptyList()
            val query = extractor.extract(utterance)
            val scores = TemplateMatcher.scoreAll(query, templates, sensitivity())
            DiagnosticLog.i(
                DiagnosticLog.Cat.STT, "捷徑測試",
                "frames=${query.frameCount} " + scores.take(3).joinToString(" ") {
                    "${it.template.keyword}=%.3f/%.3f".format(it.distance, it.threshold)
                },
            )
            scores
        }

    private fun AcousticTemplateEntity.toDomainOrNull(): AcousticTemplate? = runCatching {
        AcousticTemplate(
            id = id,
            label = label,
            matchMode = runCatching { MatchMode.valueOf(matchMode) }.getOrDefault(MatchMode.Whole),
            keyword = keyword.ifBlank { label },
            spokenPhrase = spokenPhrase,
            exemplars = VectorCodec.decodeBundle(exemplarBlob),
            rejectionThreshold = rejectionThreshold,
            marginThreshold = marginThreshold,
            createdAtMillis = createdAtMillis,
        )
    }.onFailure {
        // blob 壞掉時略過這一筆而不是整個崩掉——其他樣板還能用，
        // 使用者至少不會因為一筆損毀就完全無法溝通。
        DiagnosticLog.e(DiagnosticLog.Cat.STT, "樣板 #$id（$label）的 blob 無法解碼，已略過", t = it)
    }.getOrNull()

    private fun MatchResult.describe(): String = when (this) {
        is MatchResult.Hit ->
            "命中「${template.label}」d=${distance.format()} margin=${margin.format()}" +
                (span?.let { " span=${it.first}-${it.last}幀" } ?: "")
        is MatchResult.Ambiguous ->
            "歧義 " + candidates.joinToString(" / ") { "${it.template.label}=${it.distance.format()}" }
        MatchResult.NoMatch -> "無相符樣板"
        MatchResult.NotSpeech -> "非發聲"
    }

    companion object {
        /**
         * 一個樣板最多留幾次錄音。
         *
         * 比對是 O(樣板數 × 錄音數)，而且再多的舊錄音對召回率的邊際貢獻很小
         *（發音會隨時間漂移，最近的幾次比較有代表性）。超過就丟最舊的。
         */
        const val MAX_EXEMPLARS = 8
    }

    private fun Float.format(): String = if (this >= Float.MAX_VALUE / 2) "∞" else "%.3f".format(this)
    private fun Double.format(): String = "%.1f".format(this)
}
