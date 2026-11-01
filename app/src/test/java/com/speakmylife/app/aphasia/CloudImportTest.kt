package com.speakmylife.app.aphasia

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.speakmylife.app.ai.CloudSync
import com.speakmylife.app.aphasia.data.acoustic.MfccExtractor
import com.speakmylife.app.aphasia.data.local.AphasiaDatabase
import com.speakmylife.app.aphasia.data.local.VectorCodec
import com.speakmylife.app.aphasia.data.repository.AcousticTemplateRepositoryImpl
import com.speakmylife.app.aphasia.domain.model.MatchMode
import com.speakmylife.app.aphasia.domain.model.Utterance
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.PI
import kotlin.math.sin

/**
 * 從雲端把專屬發音併回本機。
 *
 * 這裡真正要守住的是一條界線：**文字可以從別台裝置蓋過來，錄音與門檻不行。**
 * 門檻是照這支手機的麥克風校準的，換一支的數字套過來就會不準；
 * 而錄音是使用者一句講 5 次錄出來的，被蓋掉就要整個重來。
 * 反過來說，句子只是文字，照顧者在電腦上改完卻沒進到手機，
 * 患者就會一直講舊的那句——那也是壞的。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CloudImportTest {

    private lateinit var db: AphasiaDatabase
    private lateinit var repo: AcousticTemplateRepositoryImpl
    private val sr = Utterance.SAMPLE_RATE_HZ

    @Before fun setUp() {
        db = AphasiaDatabase.inMemory(ApplicationProvider.getApplicationContext<Context>())
        repo = AcousticTemplateRepositoryImpl(db.acousticTemplateDao(), MfccExtractor())
    }

    @After fun tearDown() = db.close()

    private fun vowel(f1: Double, f2: Double, ms: Int): Utterance {
        val n = sr * ms / 1000
        val body = FloatArray(n) { i ->
            val t = i.toDouble() / sr
            ((1.0 * sin(2 * PI * 120 * t) + 0.8 * sin(2 * PI * f1 * t) +
                0.5 * sin(2 * PI * f2 * t)) / 2.3 * 0.5).toFloat()
        }
        val pad = sr / 10
        val out = FloatArray(pad * 2 + n)
        body.copyInto(out, pad)
        return Utterance(out, sr)
    }

    private fun takes(f1: Double, f2: Double) =
        listOf(vowel(f1, f2, 300), vowel(f1, f2, 320), vowel(f1, f2, 290))

    /** 造一筆「有錄音」的雲端資料（用真的 MFCC 打包，解得開才算數）。 */
    private fun remoteWithAudio(
        keyword: String, phrase: String, f1: Double = 700.0, f2: Double = 1200.0,
        createdAt: Long = 1_000L,
    ): CloudSync.RemoteTemplate {
        val seqs = takes(f1, f2).map { MfccExtractor().extract(it) }
        val blob = VectorCodec.encodeBundle(seqs)
        return CloudSync.RemoteTemplate(
            remoteId = keyword, label = keyword, keyword = keyword, spokenPhrase = phrase,
            matchMode = "Keyword", rejectionThreshold = 7.5f, marginThreshold = 0.15f,
            exemplarBlobB64 = android.util.Base64.encodeToString(blob, android.util.Base64.NO_WRAP),
            createdAt = createdAt,
        )
    }

    private fun remoteTextOnly(keyword: String, phrase: String) =
        CloudSync.RemoteTemplate(
            remoteId = keyword, label = keyword, keyword = keyword, spokenPhrase = phrase,
            matchMode = "Keyword", rejectionThreshold = 0f, marginThreshold = 0.15f,
            exemplarBlobB64 = "", createdAt = 1_000L,
        )

    // ── 新增 ───────────────────────────────────────────────────────────

    @Test
    fun `本機沒有的會連錄音一起收下`() = runBlocking {
        val r = repo.importFromCloud(listOf(remoteWithAudio("need water", "I need some water")))
        assertEquals(1, r.imported)
        assertEquals(0, r.needsRecording)
        val saved = db.acousticTemplateDao().getAll().single()
        assertEquals("I need some water", saved.spokenPhrase)
        assertTrue("錄音要一起進來，不然使用者得重錄 5 次",
            VectorCodec.decodeBundle(saved.exemplarBlob).isNotEmpty())
    }

    @Test
    fun `只有文字設定的算待補錄而不是寫進去`() = runBlocking {
        // 直接寫進資料庫的話會變成一筆永遠不會命中的死樣板，
        // 而使用者只會覺得「這個怎麼都叫不出來」
        val r = repo.importFromCloud(listOf(remoteTextOnly("need food", "I am hungry")))
        assertEquals(0, r.imported)
        assertEquals(1, r.needsRecording)
        assertEquals(0, db.acousticTemplateDao().getAll().size)
    }

    @Test
    fun `blob 壞掉的那筆會被跳過`() = runBlocking {
        val bad = remoteWithAudio("need water", "x").copy(exemplarBlobB64 = "!!!not base64!!!")
        val r = repo.importFromCloud(listOf(bad, remoteWithAudio("help me", "Please help me")))
        assertEquals("壞的跳過、好的照收", 1, r.imported)
        assertEquals("help me", db.acousticTemplateDao().getAll().single().keyword)
    }

    // ── 已存在：文字更新，錄音與門檻不動 ──────────────────────────────

    @Test
    fun `別台裝置改了句子會同步過來`() = runBlocking {
        repo.enroll("need water", "need water", "I need some water", MatchMode.Keyword, takes(700.0, 1200.0))
        val r = repo.importFromCloud(listOf(remoteWithAudio("need water", "I want water now")))
        assertEquals(0, r.imported)
        assertEquals(1, r.updated)
        assertEquals("I want water now", db.acousticTemplateDao().getAll().single().spokenPhrase)
    }

    @Test
    fun `同步句子時不會動到錄音與門檻`() = runBlocking {
        // 門檻是照這支麥克風校準的，換一支的數字套過來就會不準；
        // 錄音更是使用者一句講 5 次錄出來的。
        repo.enroll("need water", "need water", "舊句子", MatchMode.Keyword, takes(700.0, 1200.0))
        val before = db.acousticTemplateDao().getAll().single()

        // 雲端那筆的錄音、門檻都不一樣（另一支手機錄的）
        val remote = remoteWithAudio("need water", "新句子", f1 = 350.0, f2 = 2400.0)
            .copy(rejectionThreshold = 99f, marginThreshold = 0.9f)
        repo.importFromCloud(listOf(remote))

        val after = db.acousticTemplateDao().getAll().single()
        assertEquals("句子要更新", "新句子", after.spokenPhrase)
        assertEquals("門檻不能被蓋掉", before.rejectionThreshold, after.rejectionThreshold, 1e-6f)
        assertEquals("margin 不能被蓋掉", before.marginThreshold, after.marginThreshold, 1e-6f)
        assertTrue("錄音不能被蓋掉", before.exemplarBlob.contentEquals(after.exemplarBlob))
    }

    @Test
    fun `句子沒變就不算更新`() = runBlocking {
        // 每次開畫面都自動同步，這裡誤判的話會變成每次都跳一個「更新了 N 筆」
        repo.enroll("need water", "need water", "I need some water", MatchMode.Keyword, takes(700.0, 1200.0))
        val r = repo.importFromCloud(listOf(remoteWithAudio("need water", "I need some water")))
        assertEquals(0, r.updated)
        assertFalse(r.changed)
    }

    @Test
    fun `雲端句子是空的時候不覆蓋本機`() = runBlocking {
        // 空字串代表「那邊沒有設好」，不是「使用者想清空」——
        // 蓋過去的話這個捷徑就變成講出一句空話
        repo.enroll("need water", "need water", "I need some water", MatchMode.Keyword, takes(700.0, 1200.0))
        val r = repo.importFromCloud(listOf(remoteWithAudio("need water", "   ")))
        assertEquals(0, r.updated)
        assertEquals("I need some water", db.acousticTemplateDao().getAll().single().spokenPhrase)
    }

    @Test
    fun `網頁版建的純文字也能更新已存在的句子`() = runBlocking {
        // 網頁版永遠只寫得出文字設定。它改的句子必須進得了手機，
        // 否則照顧者在電腦上改完，患者的手機永遠還在講舊的。
        repo.enroll("need water", "need water", "舊句子", MatchMode.Keyword, takes(700.0, 1200.0))
        val r = repo.importFromCloud(listOf(remoteTextOnly("need water", "新句子")))
        assertEquals(1, r.updated)
        assertEquals(0, r.needsRecording)   // 本機已經有錄音了，不算待補錄
        assertEquals("新句子", db.acousticTemplateDao().getAll().single().spokenPhrase)
    }

    @Test
    fun `觸發詞比對不分大小寫`() = runBlocking {
        repo.enroll("Need Water", "Need Water", "舊句子", MatchMode.Keyword, takes(700.0, 1200.0))
        val r = repo.importFromCloud(listOf(remoteTextOnly("need water", "新句子")))
        assertEquals("不該又長出一筆重複的", 1, r.updated)
        assertEquals(1, db.acousticTemplateDao().getAll().size)
    }

    @Test
    fun `重複同步不會一直長出新樣板`() = runBlocking {
        // 每次開畫面都會自動跑一次，這裡沒擋住的話清單會越滾越長
        val remote = listOf(remoteWithAudio("need water", "I need some water"))
        repeat(3) { repo.importFromCloud(remote) }
        assertEquals(1, db.acousticTemplateDao().getAll().size)
    }

    @Test
    fun `雲端是空的不會動到本機任何東西`() = runBlocking {
        // pull 失敗時回的就是空清單。這裡若做了任何「清掉本機多餘的」動作，
        // 一次網路不順就會把使用者的錄音全部洗掉。
        repo.enroll("need water", "need water", "I need some water", MatchMode.Keyword, takes(700.0, 1200.0))
        val before = db.acousticTemplateDao().getAll().single()
        val r = repo.importFromCloud(emptyList())
        assertFalse(r.changed)
        val after = db.acousticTemplateDao().getAll().single()
        assertEquals(before.spokenPhrase, after.spokenPhrase)
        assertTrue(before.exemplarBlob.contentEquals(after.exemplarBlob))
    }

    @Test
    fun `多筆混合時各走各的路`() = runBlocking {
        repo.enroll("need water", "need water", "舊句子", MatchMode.Keyword, takes(700.0, 1200.0))
        val r = repo.importFromCloud(listOf(
            remoteWithAudio("need water", "新句子"),                    // 更新
            remoteWithAudio("help me", "Please help me", 350.0, 2400.0), // 新增
            remoteTextOnly("need food", "I am hungry"),                  // 待補錄
        ))
        assertEquals(1, r.imported)
        assertEquals(1, r.updated)
        assertEquals(1, r.needsRecording)
        assertEquals(setOf("need water", "help me"),
            db.acousticTemplateDao().getAll().map { it.keyword }.toSet())
    }

    // ── 雲端是主：那邊刪掉的，這邊也要消失 ────────────────────────────

    /** 造一筆「已經放很久」的本機樣板（避開剛建立的寬限期）。 */
    private suspend fun enrollOld(keyword: String, phrase: String, f1: Double, f2: Double) {
        val old = AcousticTemplateRepositoryImpl(
            db.acousticTemplateDao(), MfccExtractor(),
            now = { System.currentTimeMillis() - 24L * 3600_000 },   // 一天前
        )
        old.enroll(keyword, keyword, phrase, MatchMode.Keyword, takes(f1, f2))
    }

    @Test
    fun `網頁上刪掉一筆手機也會刪掉`() = runBlocking {
        enrollOld("need water", "I need some water", 700.0, 1200.0)
        enrollOld("help me", "Please help me", 350.0, 2400.0)

        // 雲端只剩一筆（另一筆在網頁上被刪了）
        val r = repo.importFromCloud(
            listOf(remoteWithAudio("help me", "Please help me")), authoritative = true)

        assertEquals(1, r.removed)
        assertEquals("help me", db.acousticTemplateDao().getAll().single().keyword)
    }

    @Test
    fun `雲端全空時本機也清空`() = runBlocking {
        enrollOld("need water", "I need some water", 700.0, 1200.0)
        val r = repo.importFromCloud(emptyList(), authoritative = true)
        assertEquals(1, r.removed)
        assertEquals(0, db.acousticTemplateDao().getAll().size)
    }

    @Test
    fun `讀不到雲端時絕不刪任何東西`() = runBlocking {
        // 這是整條刪除邏輯的安全閥。pull 失敗回的也是空清單，
        // 分不出來就會在一次網路不順時把使用者所有的錄音清光——
        // 那些是一句講 5 次錄出來的，沒有地方能重新下載。
        enrollOld("need water", "I need some water", 700.0, 1200.0)
        enrollOld("help me", "Please help me", 350.0, 2400.0)

        val r = repo.importFromCloud(emptyList(), authoritative = false)

        assertEquals(0, r.removed)
        assertFalse(r.changed)
        assertEquals(2, db.acousticTemplateDao().getAll().size)
    }

    @Test
    fun `預設不刪除`() = runBlocking {
        // 呼叫端忘了傳 authoritative 時，寧可少刪也不要多刪
        enrollOld("need water", "I need some water", 700.0, 1200.0)
        assertEquals(0, repo.importFromCloud(emptyList()).removed)
        assertEquals(1, db.acousticTemplateDao().getAll().size)
    }

    @Test
    fun `剛錄好還在上傳的不會被誤刪`() = runBlocking {
        // 推上雲端是背景進行的。剛錄完就切回設定頁時，它還沒到雲端——
        // 那時把它當成「雲端刪掉了」，使用者剛講的 5 次錄音就沒了。
        repo.enroll("need water", "need water", "I need some water",
            MatchMode.Keyword, takes(700.0, 1200.0))
        val r = repo.importFromCloud(emptyList(), authoritative = true)
        assertEquals(0, r.removed)
        assertEquals(1, db.acousticTemplateDao().getAll().size)
    }

    @Test
    fun `刪除時新增與更新照常進行`() = runBlocking {
        enrollOld("need water", "舊句子", 700.0, 1200.0)
        enrollOld("old one", "要被刪掉的", 500.0, 1800.0)

        val r = repo.importFromCloud(listOf(
            remoteWithAudio("need water", "新句子"),                      // 更新
            remoteWithAudio("help me", "Please help me", 350.0, 2400.0),  // 新增
        ), authoritative = true)

        assertEquals(1, r.imported)
        assertEquals(1, r.updated)
        assertEquals(1, r.removed)
        assertEquals(setOf("need water", "help me"),
            db.acousticTemplateDao().getAll().map { it.keyword }.toSet())
    }

    @Test
    fun `只有文字設定的雲端項目也算存在不會刪掉本機的錄音`() = runBlocking {
        // 網頁版建的那筆沒有錄音波形，但它在雲端是「存在」的。
        // 把它當成不存在而刪掉本機那筆，等於網頁上改個字就毀掉手機的錄音。
        enrollOld("need water", "I need some water", 700.0, 1200.0)
        val r = repo.importFromCloud(
            listOf(remoteTextOnly("need water", "I need some water")), authoritative = true)
        assertEquals(0, r.removed)
        assertEquals(1, db.acousticTemplateDao().getAll().size)
    }

    @Test
    fun `刪除比對不分大小寫與前後空白`() = runBlocking {
        enrollOld("Need Water", "I need some water", 700.0, 1200.0)
        val r = repo.importFromCloud(
            listOf(remoteTextOnly(" need water ", "I need some water")), authoritative = true)
        assertEquals("大小寫不同不該被當成兩筆而誤刪", 0, r.removed)
        assertEquals(1, db.acousticTemplateDao().getAll().size)
    }

    // ── 什麼時候才敢刪：pullFromClouds 的權威性判斷 ──────────────────

    private fun mirrorOf(outcome: com.speakmylife.app.ai.AcousticMirror.Outcome) =
        object : com.speakmylife.app.ai.AcousticMirror {
            override fun push(t: CloudSync.RemoteTemplate) {}
            override fun delete(id: Long) {}
            override suspend fun pull() = outcome
        }

    private fun repoWith(mirror: com.speakmylife.app.ai.AcousticMirror?) =
        AcousticTemplateRepositoryImpl(db.acousticTemplateDao(), MfccExtractor(), mirror = mirror)

    @Test
    fun `兩邊都讀到才敢刪`() = runBlocking {
        val snap = repoWith(mirrorOf(com.speakmylife.app.ai.AcousticMirror.Outcome.Ok(emptyList())))
            .pullFromClouds(CloudSync.TemplatePull(ok = true, templates = emptyList()))
        assertTrue(snap.authoritative)
    }

    @Test
    fun `Firestore 讀失敗就不敢刪`() = runBlocking {
        val snap = repoWith(mirrorOf(com.speakmylife.app.ai.AcousticMirror.Outcome.Ok(emptyList())))
            .pullFromClouds(CloudSync.TemplatePull(ok = false, templates = emptyList()))
        assertFalse(snap.authoritative)
    }

    @Test
    fun `Drive 讀失敗就不敢刪`() = runBlocking {
        val snap = repoWith(mirrorOf(com.speakmylife.app.ai.AcousticMirror.Outcome.Failed))
            .pullFromClouds(CloudSync.TemplatePull(ok = true, templates = emptyList()))
        assertFalse(snap.authoritative)
    }

    @Test
    fun `沒授權 Drive 的人照樣同步得到刪除`() = runBlocking {
        // Drive 沒接就不是一個來源。把它當成「讀失敗」的話，
        // 只用 Google 登入但沒給 Drive 權限的人永遠等不到刪除同步。
        val snap = repoWith(mirrorOf(com.speakmylife.app.ai.AcousticMirror.Outcome.Unavailable))
            .pullFromClouds(CloudSync.TemplatePull(ok = true, templates = emptyList()))
        assertTrue(snap.authoritative)
    }

    @Test
    fun `Drive 讀失敗時本機的錄音一個都不會少`() = runBlocking {
        enrollOld("need water", "I need some water", 700.0, 1200.0)
        val repo2 = repoWith(mirrorOf(com.speakmylife.app.ai.AcousticMirror.Outcome.Failed))
        val snap = repo2.pullFromClouds(CloudSync.TemplatePull(ok = true, templates = emptyList()))
        val r = repo2.importFromCloud(snap.templates, snap.authoritative)
        assertEquals(0, r.removed)
        assertEquals(1, db.acousticTemplateDao().getAll().size)
    }

    @Test
    fun `Drive 上獨有的項目不會被當成已刪除`() = runBlocking {
        // 兩邊會不同步（網頁版沒 Drive 授權時只寫得到 Firestore，反之亦然）。
        // 判定「雲端有沒有」要看兩邊的聯集，不是只看其中一邊。
        enrollOld("need water", "I need some water", 700.0, 1200.0)
        val onlyOnDrive = com.speakmylife.app.ai.AcousticMirror.Outcome.Ok(
            listOf(remoteTextOnly("need water", "I need some water")))
        val repo2 = repoWith(mirrorOf(onlyOnDrive))
        val snap = repo2.pullFromClouds(CloudSync.TemplatePull(ok = true, templates = emptyList()))
        assertTrue(snap.authoritative)
        assertEquals(0, repo2.importFromCloud(snap.templates, snap.authoritative).removed)
        assertEquals(1, db.acousticTemplateDao().getAll().size)
    }

    @Test
    fun `更新後的樣板仍然認得原本的發音`() = runBlocking {
        // 只改文字卻把比對弄壞的話，症狀是「本來會動的突然不動了」
        repo.enroll("need water", "need water", "舊句子", MatchMode.Keyword, takes(700.0, 1200.0))
        repo.importFromCloud(listOf(remoteTextOnly("need water", "新句子")))
        val hit = repo.match(vowel(700.0, 1200.0, 305))
        assertNotEquals(com.speakmylife.app.aphasia.domain.model.MatchResult.NoMatch, hit)
    }
}
