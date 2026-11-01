package com.speakmylife.app.aphasia.data.repository

import com.speakmylife.app.ai.DiagnosticLog
import com.speakmylife.app.aphasia.data.acoustic.DtwMatcher
import com.speakmylife.app.aphasia.data.acoustic.MfccExtractor
import com.speakmylife.app.aphasia.data.acoustic.TemplateMatcher
import com.speakmylife.app.aphasia.data.local.AcousticTemplateDao
import com.speakmylife.app.aphasia.data.local.AcousticTemplateEntity
import com.speakmylife.app.aphasia.data.local.VectorCodec
import com.speakmylife.app.aphasia.domain.model.AcousticTemplate
import com.speakmylife.app.aphasia.domain.model.MatchMode
import com.speakmylife.app.aphasia.domain.model.MatchResult
import com.speakmylife.app.aphasia.domain.model.Utterance
import com.speakmylife.app.aphasia.domain.repository.AcousticTemplateRepository
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
    /**
     * 使用者自己 Drive 裡的那一份聲波模型。null＝不做（測試、或還沒授權 Drive）。
     *
     * 跟 Firestore 是兩條獨立的路：Firestore 即時但看不見，Drive 是他自己
     * 資料夾裡的檔案、備份得走也刪得掉。任一邊掛掉都不影響本機。
     */
    private val mirror: com.speakmylife.app.ai.AcousticMirror? = null,
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
        /** 語句庫分類，只用來整理（雲端照它開資料夾）。自己加的留 Custom。 */
        category: String = AcousticTemplate.CATEGORY_CUSTOM,
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
            category = AcousticTemplate.sanitizeCategory(category),
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
            AcousticTemplate.DEFAULT_MARGIN, entity.exemplarBlob, entity.createdAtMillis,
            entity.category)
        AcousticTemplate(
            id = id,
            label = label,
            matchMode = matchMode,
            keyword = keyword,
            category = entity.category,
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
                VectorCodec.encodeBundle(merged), existing.createdAtMillis,
                existing.category)
            existing.copy(exemplars = merged, rejectionThreshold = threshold)
        }

    /**
     * 用目前的公式重算所有既有樣板的門檻。
     *
     * 校準公式從「全配對平均」改成「最近鄰」之後，舊資料的門檻仍是用舊公式算的
     *（實測有一筆膨脹到 21.9，把所有聲音都吞掉）。錄音本身還在，所以不需要
     * 請使用者重錄——直接重算就好。冪等，重複呼叫沒有副作用。
     */
    /**
     * 補回既有樣板的分類。回實際改動的筆數。
     *
     * 分類是後來才加的欄位，Room 的 migration 只能給預設值 'Custom'——
     * SQL 讀不到語句庫那份 asset，猜錯會把使用者的東西歸到錯的地方。
     * 但**App 層讀得到**，所以在這裡用觸發詞把它對回去。
     *
     * 只動還停在 Custom 的那些：使用者或別台裝置已經給過分類的不要覆蓋。
     * 對不上語句庫的（自己加的句子）本來就該留在 Custom。
     *
     * @param lookup 觸發詞 → 分類。對不上時回 null。
     */
    suspend fun backfillCategories(lookup: (String) -> String?): Int = withContext(Dispatchers.Default) {
        var changed = 0
        for (row in dao.getAll()) {
            if (row.category != AcousticTemplate.CATEGORY_CUSTOM) continue
            val found = lookup(row.keyword)?.takeIf { it.isNotBlank() } ?: continue
            val category = AcousticTemplate.sanitizeCategory(found)
            if (category == row.category) continue
            dao.update(row.copy(category = category))
            changed++
            // 雲端那份也要跟著搬，否則手機顯示已分類、Drive 還平放在根目錄。
            // blob 解不開的就不要往上推：那筆已經壞了，覆蓋雲端只會讓
            // 另一台裝置上還好的那份也跟著壞掉。
            if (row.toDomainOrNull() != null) {
                syncUp(row.id, row.label, row.keyword, row.spokenPhrase, row.matchMode,
                    row.rejectionThreshold, row.marginThreshold, row.exemplarBlob,
                    row.createdAtMillis, category)
            }
        }
        if (changed > 0) {
            DiagnosticLog.i(DiagnosticLog.Cat.STT, "補回專屬發音的分類", "共 $changed 筆")
        }
        changed
    }

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
        // 刪除也要同步，而且**兩邊都要**。少刪任何一邊，換手機拉回來時都會把
        // 使用者刻意刪掉的樣板救回來——而他刪掉多半正是因為那筆會誤觸發。
        runCatching { com.speakmylife.app.ai.CloudSync.deleteAcousticTemplate(id) }
        runCatching { mirror?.delete(id) }
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
        category: String = AcousticTemplate.CATEGORY_CUSTOM,
    ) {
        val b64 = android.util.Base64.encodeToString(blob, android.util.Base64.NO_WRAP)
        runCatching {
            com.speakmylife.app.ai.CloudSync.pushAcousticTemplate(
                id = id, label = label, keyword = keyword, spokenPhrase = spokenPhrase,
                matchMode = matchMode, rejectionThreshold = rejection, marginThreshold = margin,
                exemplarBlobB64 = b64, createdAt = createdAt, category = category,
            )
        }.onFailure {
            DiagnosticLog.w(DiagnosticLog.Cat.STT, "樣板同步失敗（不影響本機）", it.message.orEmpty())
        }
        // 使用者自己 Drive 裡的那一份。分開 runCatching：Firestore 失敗
        // 不該讓 Drive 也跳過，兩條路本來就是為了互相補位。
        runCatching {
            mirror?.push(
                com.speakmylife.app.ai.CloudSync.RemoteTemplate(
                    remoteId = id.toString(), label = label, keyword = keyword,
                    spokenPhrase = spokenPhrase, matchMode = matchMode,
                    rejectionThreshold = rejection, marginThreshold = margin,
                    exemplarBlobB64 = b64, createdAt = createdAt,
                    category = category,
                )
            )
        }.onFailure {
            DiagnosticLog.w(DiagnosticLog.Cat.STT, "寫進 Drive 失敗（不影響本機）", it.message.orEmpty())
        }
    }

    /** [pullFromClouds] 的結果。[authoritative] 為真才可以拿來刪本機的東西。 */
    data class CloudSnapshot(
        val templates: List<com.speakmylife.app.ai.CloudSync.RemoteTemplate>,
        /**
         * 這份清單是不是「雲端真正的全貌」。
         *
         * 只要有任何一個來源讀失敗就是 false。**雲端是主、手機是從，
         * 所以雲端沒有的要刪掉——但前提是真的讀到了雲端。**
         * 讀失敗時拿到的空清單長得跟「雲端真的空了」一模一樣，
         * 分不出來就會在一次網路不順時把使用者所有的錄音清光。
         */
        val authoritative: Boolean,
    )

    /**
     * 從兩個雲端來源取回樣板：Firestore（即時、免 Drive 授權）與
     * 使用者自己 Drive 的 AcousticModels/。
     *
     * 兩邊都讀是因為它們會不同步：網頁版沒 Drive 授權時只寫得到 Firestore，
     * 而使用者可能自己把 Drive 那份搬到新帳號。以 keyword 去重、有錄音的優先。
     */
    suspend fun pullFromClouds(
        firestore: com.speakmylife.app.ai.CloudSync.TemplatePull,
    ): CloudSnapshot {
        val outcome = runCatching { mirror?.pull() }
            .getOrDefault(com.speakmylife.app.ai.AcousticMirror.Outcome.Failed)
        val fromDrive = (outcome as? com.speakmylife.app.ai.AcousticMirror.Outcome.Ok)?.templates.orEmpty()
        // 沒接 Drive（沒登入／沒授權／測試沒給 mirror）時它不是來源，
        // 不該擋住 Firestore 的判斷——不然沒授權 Drive 的人永遠同步不到刪除。
        // 只有「本來讀得到卻失敗」才要降級。
        val driveOk = outcome !is com.speakmylife.app.ai.AcousticMirror.Outcome.Failed

        DiagnosticLog.i(DiagnosticLog.Cat.STT, "雲端聲波模型",
            "Firestore ok=${firestore.ok} ${firestore.templates.size} 筆；" +
                "Drive=${outcome?.javaClass?.simpleName ?: "未接"} ${fromDrive.size} 筆")

        return CloudSnapshot(
            templates = com.speakmylife.app.ai.DriveAcousticMirror.merge(fromDrive, firestore.templates),
            authoritative = firestore.ok && driveOk,
        )
    }

    /** [importFromCloud] 的結果。 */
    data class ImportResult(
        /** 本機沒有、這次新加進來的。 */
        val imported: Int,
        /** 雲端只有文字設定（網頁版建的），要在這支手機補錄才會生效。 */
        val needsRecording: Int,
        /** 本機已經有、但要說的句子在別台裝置改過，這次跟著更新。 */
        val updated: Int,
        /** 雲端已經刪掉，這次跟著從本機刪除。 */
        val removed: Int = 0,
    ) {
        val changed: Boolean get() = imported > 0 || updated > 0 || removed > 0
    }

    /**
     * 剛建好的樣板不參與刪除判定的寬限時間。
     *
     * 推上雲端是背景進行的（不擋使用者操作），所以「剛錄完就切回設定頁」
     * 有機會在推上去之前就跑到同步——那時它還不在雲端，會被當成
     * 「雲端刪掉了」而把剛錄的 5 次錄音刪掉。
     */
    private val deleteGraceMillis = 5 * 60_000L

    /**
     * 從雲端拉回樣板，合併進本機。
     *
     * **以 keyword 為鍵**。本機已經有同一個觸發詞時：
     * - 錄音與門檻**不動**——本機那筆是這支手機錄的、門檻也是照這支麥克風
     *   校準的，換一支麥克風的數字套過來只會讓它不準。
     * - 但「要說出口的句子」會跟著更新。那是純文字，在哪台裝置改都一樣有效；
     *   不更新的話，照顧者在電腦上改了句子，患者的手機永遠還在講舊的。
     *
     * 沒有錄音波形的（網頁版建的）會被跳過並回報數量——那種要在手機上補錄
     * 才會生效，直接寫進資料庫只會變成一筆永遠不會命中的死樣板。
     *
     * @param authoritative 這份清單是不是雲端的全貌。**雲端是主、手機是從**，
     *   所以為真時「雲端沒有的」會一併從本機刪掉——在網頁上刪一筆，手機就跟著刪。
     *   為假（任一來源讀失敗）時只做新增與更新，絕不刪東西：那時的空缺分不出是
     *   「使用者刪掉了」還是「這次沒讀到」，而猜錯的代價是使用者的錄音沒了。
     */
    suspend fun importFromCloud(
        remote: List<com.speakmylife.app.ai.CloudSync.RemoteTemplate>,
        authoritative: Boolean = false,
    ): ImportResult = withContext(Dispatchers.Default) {
        val existing = dao.getAll().associateBy { it.keyword.lowercase() }
        var imported = 0
        var needsRecording = 0
        var updated = 0
        var removed = 0
        for (r in remote) {
            val local = existing[r.keyword.lowercase()]
            if (local != null) {
                // 只同步文字。碰錄音或門檻就是拿別支麥克風的校準結果蓋掉這支的。
                val newPhrase = r.spokenPhrase.trim()
                if (newPhrase.isNotBlank() && newPhrase != local.spokenPhrase) {
                    dao.update(local.copy(
                        spokenPhrase = newPhrase,
                        label = r.label.ifBlank { local.label },
                        category = AcousticTemplate.sanitizeCategory(r.category),
                    ))
                    updated++
                }
                continue
            }
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
                    category = AcousticTemplate.sanitizeCategory(r.category),
                    spokenPhrase = r.spokenPhrase, exemplarBlob = blob,
                    rejectionThreshold = r.rejectionThreshold,
                    marginThreshold = r.marginThreshold,
                    createdAtMillis = r.createdAt.takeIf { it > 0 } ?: now(),
                )
            )
            imported++
        }

        // 雲端是主：那邊刪掉的，這邊也要消失。只在確定讀到了雲端全貌時才做。
        if (authoritative) {
            val cloudKeywords = remote.map { it.keyword.lowercase().trim() }.toSet()
            val cutoff = now() - deleteGraceMillis
            for ((key, row) in existing) {
                if (key in cloudKeywords) continue
                // 剛錄好的還在背景上傳，這時它「不在雲端」是理所當然的
                if (row.createdAtMillis > cutoff) {
                    DiagnosticLog.i(DiagnosticLog.Cat.STT, "剛建立，暫不依雲端刪除",
                        "「${row.keyword}」建立於 ${row.createdAtMillis}")
                    continue
                }
                dao.delete(row.id)
                removed++
                DiagnosticLog.i(DiagnosticLog.Cat.STT, "雲端已刪除，本機跟著刪",
                    "「${row.keyword}」")
            }
        }

        DiagnosticLog.i(DiagnosticLog.Cat.STT, "從雲端匯入專屬發音",
            "匯入=$imported 更新=$updated 刪除=$removed 待補錄=$needsRecording " +
                "雲端共=${remote.size} 可信=$authoritative")
        ImportResult(imported, needsRecording, updated, removed)
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
            category = category,
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
