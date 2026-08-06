package com.example.aphasia

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.aphasia.data.acoustic.DtwMatcher
import com.example.aphasia.data.acoustic.MfccExtractor
import com.example.aphasia.data.local.AphasiaDatabase
import com.example.aphasia.data.local.HashingTextEmbedder
import com.example.aphasia.data.repository.AcousticTemplateRepositoryImpl
import com.example.aphasia.data.repository.PhrasebookRepositoryImpl
import com.example.aphasia.domain.model.CommunicationPhrase
import com.example.aphasia.domain.model.MatchResult
import com.example.aphasia.domain.model.Utterance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.PI
import kotlin.math.sin

/**
 * 真的寫進 SQLite 再讀回來。
 *
 * [PersistenceTest] 驗的是編解碼本身；這裡驗的是它經過 Room 的 BLOB 欄位之後
 * 仍然一模一樣——這兩件事會分開壞，而後者壞掉的症狀是「距離莫名其妙變大」，
 * 從外面完全看不出原因。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AphasiaDatabaseTest {

    private lateinit var db: AphasiaDatabase
    private val extractor = MfccExtractor()
    private val sr = Utterance.SAMPLE_RATE_HZ

    @Before fun setUp() {
        db = AphasiaDatabase.inMemory(ApplicationProvider.getApplicationContext<Context>())
    }

    @After fun tearDown() = db.close()

    private fun vowel(f1: Double, f2: Double, ms: Int, amp: Float = 0.5f): Utterance {
        val n = sr * ms / 1000
        val body = FloatArray(n) { i ->
            val t = i.toDouble() / sr
            ((1.0 * sin(2 * PI * 120 * t) + 0.8 * sin(2 * PI * f1 * t) +
                0.5 * sin(2 * PI * f2 * t)) / 2.3 * amp).toFloat()
        }
        val pad = sr / 10
        val out = FloatArray(pad * 2 + n)
        body.copyInto(out, pad)
        return Utterance(out, sr)
    }

    // ── 聲學樣板 ────────────────────────────────────────────────────────

    @Test
    fun `樣板存進資料庫再讀回來仍然能命中自己`() = runBlocking {
        val repo = AcousticTemplateRepositoryImpl(db.acousticTemplateDao(), extractor, now = { 1_000L })
        val takes = (0 until 4).map { vowel(700.0, 1200.0, 400 + it * 20) }
        val saved = repo.enroll("water", "I'd like some water, please.", takes)

        assertTrue("應取得資料庫 id", saved.id > 0)
        assertEquals(4, saved.exemplars.size)

        // 走完整條路：SQLite → blob 解碼 → MFCC → DTW
        val result = repo.match(vowel(700.0, 1200.0, 430))
        assertTrue("應命中，實得 $result", result is MatchResult.Hit)
        assertEquals("water", (result as MatchResult.Hit).template.label)
        assertEquals("I'd like some water, please.", result.template.spokenPhrase)
    }

    @Test
    fun `讀回來的門檻與寫進去的完全一致`() = runBlocking {
        val dao = db.acousticTemplateDao()
        val repo = AcousticTemplateRepositoryImpl(dao, extractor, now = { 1_000L })
        val takes = (0 until 4).map { vowel(700.0, 1200.0, 400 + it * 20) }
        val saved = repo.enroll("water", "Water, please.", takes)

        val reloaded = repo.observeAll().first().single()
        assertEquals(saved.rejectionThreshold, reloaded.rejectionThreshold, 0f)
        assertEquals(saved.marginThreshold, reloaded.marginThreshold, 0f)
        assertEquals(saved.exemplars.map { it.frameCount }, reloaded.exemplars.map { it.frameCount })

        // 逐格比對——Room 的 BLOB 欄位若有任何截斷或編碼問題會在這裡現形
        for (i in saved.exemplars.indices) {
            val a = saved.exemplars[i].frames
            val b = reloaded.exemplars[i].frames
            for (f in a.indices) assertTrue("第 $i 段第 $f 幀不一致", a[f].contentEquals(b[f]))
        }
        // 讀回來的樣板對自己的距離必須是 0
        assertEquals(0f, DtwMatcher.distance(saved.exemplars[0], reloaded.exemplars[0]), 1e-5f)
    }

    @Test
    fun `登錄次數不足會被擋下且不寫入任何東西`() = runBlocking {
        val dao = db.acousticTemplateDao()
        val repo = AcousticTemplateRepositoryImpl(dao, extractor, now = { 1_000L })
        val tooFew = listOf(vowel(700.0, 1200.0, 400), vowel(700.0, 1200.0, 420))

        val e = runCatching { repo.enroll("water", "Water.", tooFew) }.exceptionOrNull()
        assertTrue("應丟 IllegalArgumentException，實得 $e", e is IllegalArgumentException)
        assertEquals("失敗時不該留下半筆資料", 0, dao.count())
    }

    @Test
    fun `刪掉的樣板不會再被比對到`() = runBlocking {
        val repo = AcousticTemplateRepositoryImpl(db.acousticTemplateDao(), extractor, now = { 1_000L })
        val saved = repo.enroll("water", "Water.", (0 until 4).map { vowel(700.0, 1200.0, 400 + it * 20) })
        assertTrue(repo.match(vowel(700.0, 1200.0, 430)) is MatchResult.Hit)

        repo.delete(saved.id)
        assertEquals(MatchResult.NoMatch, repo.match(vowel(700.0, 1200.0, 430)))
    }

    @Test
    fun `blob 損毀時只略過那一筆而不是整個崩掉`() = runBlocking {
        val dao = db.acousticTemplateDao()
        val repo = AcousticTemplateRepositoryImpl(dao, extractor, now = { 1_000L })
        repo.enroll("water", "Water.", (0 until 4).map { vowel(700.0, 1200.0, 400 + it * 20) })
        repo.enroll("help", "Help me.", (0 until 4).map { vowel(350.0, 2400.0, 400 + it * 20) })

        // 把其中一筆的 blob 弄壞，模擬儲存損毀或不相容的舊格式
        val victim = dao.getAll().first { it.label == "water" }
        dao.update(victim.copy(exemplarBlob = ByteArray(16)))

        // 另一個樣板必須還能正常命中——使用者不該因為一筆損毀就完全無法溝通
        val result = repo.match(vowel(350.0, 2400.0, 420))
        assertTrue("help 應仍可命中，實得 $result", result is MatchResult.Hit)
        assertEquals("help", (result as MatchResult.Hit).template.label)
    }

    @Test
    fun `補錄會累加錄音並重新校準門檻`() = runBlocking {
        // 「唸了一樣的卻沒反應」的正解：實測同一個人講同一句，距離會在
        // 3.6 到 7.7 之間跳，3 次錄音蓋不住。比對取的是與任一次錄音的最小距離，
        // 所以多錄只會提高召回率。
        val repo = AcousticTemplateRepositoryImpl(db.acousticTemplateDao(), extractor, now = { 1_000L })
        val saved = repo.enroll("water", "Water.", (0 until 3).map { vowel(700.0, 1200.0, 400 + it * 20) })
        assertEquals(3, saved.exemplars.size)

        val more = repo.addTakes(saved.id, listOf(vowel(700.0, 1200.0, 520), vowel(700.0, 1200.0, 380)))
        assertEquals("錄音應累加而不是取代", 5, more.exemplars.size)

        val reloaded = repo.observeAll().first().single()
        assertEquals(5, reloaded.exemplars.size)
        assertEquals(more.rejectionThreshold, reloaded.rejectionThreshold, 0f)
        assertEquals("關鍵字與句子不該被動到", "water", reloaded.keyword)
        assertEquals("Water.", reloaded.spokenPhrase)
    }

    @Test
    fun `補錄之後原本認不出的發音變得認得出`() = runBlocking {
        val repo = AcousticTemplateRepositoryImpl(db.acousticTemplateDao(), extractor, now = { 1_000L })
        val saved = repo.enroll("water", "Water.", (0 until 3).map { vowel(700.0, 1200.0, 400 + it * 5) })

        // 一個偏離登錄範圍的講法（共振峰稍微飄掉，像同一個人狀況不同時的發音）
        val drifted = vowel(760.0, 1290.0, 470)
        assertEquals("補錄前應該認不出", MatchResult.NoMatch, repo.match(drifted))

        repo.addTakes(saved.id, listOf(drifted))
        val after = repo.match(vowel(760.0, 1290.0, 465))
        assertTrue("補錄後應該認得出，實得 $after", after is MatchResult.Hit)
    }

    @Test
    fun `補錄的錄音數有上限`() = runBlocking {
        // 比對是 O(樣板數 × 錄音數)，而且舊錄音的邊際貢獻會遞減
        val repo = AcousticTemplateRepositoryImpl(db.acousticTemplateDao(), extractor, now = { 1_000L })
        val saved = repo.enroll("water", "Water.", (0 until 3).map { vowel(700.0, 1200.0, 400 + it * 20) })
        val many = (0 until 10).map { vowel(700.0, 1200.0, 400 + it * 10) }
        val result = repo.addTakes(saved.id, many)
        assertEquals(AcousticTemplateRepositoryImpl.MAX_EXEMPLARS, result.exemplars.size)
    }

    @Test
    fun `補錄到不存在的樣板會被擋下`() = runBlocking {
        val repo = AcousticTemplateRepositoryImpl(db.acousticTemplateDao(), extractor, now = { 1_000L })
        val e = runCatching { repo.addTakes(999L, listOf(vowel(700.0, 1200.0, 400))) }.exceptionOrNull()
        assertTrue("應丟 IllegalArgumentException，實得 $e", e is IllegalArgumentException)
    }

    // ── 語句庫與檢索 ────────────────────────────────────────────────────

    @Test
    fun `語句寫入時就算好向量並且能被檢索到`() = runBlocking {
        val repo = PhrasebookRepositoryImpl(db.phraseDao(), HashingTextEmbedder(), now = { 5_000L })
        listOf(
            "I would like some water" to "kitchen",
            "I need my medication" to "clinic",
            "Please turn on the television" to "living-room",
        ).forEach { (text, ctx) -> repo.upsert(CommunicationPhrase(text = text, context = ctx)) }

        val hits = repo.searchSimilar("water please", topK = 3)
        assertTrue("應檢索到結果", hits.isNotEmpty())
        assertTrue("最相近的應含 water，實得 ${hits.map { it.text }}", hits.first().text.contains("water"))
    }

    @Test
    fun `檢索不到相關句子時回空而不是硬塞最不糟的那個`() = runBlocking {
        // 把不相關的句子當成上下文餵給 LLM，正是幻覺的養分
        val repo = PhrasebookRepositoryImpl(db.phraseDao(), HashingTextEmbedder(), now = { 5_000L })
        repo.upsert(CommunicationPhrase(text = "Please turn on the television"))
        repo.upsert(CommunicationPhrase(text = "Call my daughter tonight"))

        assertEquals(0, repo.searchSimilar("water").size)
    }

    @Test
    fun `新增語句之後索引會跟著更新`() = runBlocking {
        val repo = PhrasebookRepositoryImpl(db.phraseDao(), HashingTextEmbedder(), now = { 5_000L })
        repo.upsert(CommunicationPhrase(text = "Turn on the light"))
        assertEquals("一開始查不到", 0, repo.searchSimilar("water").size)

        repo.upsert(CommunicationPhrase(text = "I need cold water"))
        assertEquals("新增後應該查得到", 1, repo.searchSimilar("water").size)
    }

    @Test
    fun `刪除之後就檢索不到了`() = runBlocking {
        val repo = PhrasebookRepositoryImpl(db.phraseDao(), HashingTextEmbedder(), now = { 5_000L })
        val id = repo.upsert(CommunicationPhrase(text = "I need cold water"))
        assertEquals(1, repo.searchSimilar("water").size)

        repo.delete(id)
        assertEquals(0, repo.searchSimilar("water").size)
    }

    @Test
    fun `使用次數會累加`() = runBlocking {
        val dao = db.phraseDao()
        val repo = PhrasebookRepositoryImpl(dao, HashingTextEmbedder(), now = { 7_777L })
        val id = repo.upsert(CommunicationPhrase(text = "I need cold water"))

        repo.markUsed(id); repo.markUsed(id)
        val row = dao.getByIds(listOf(id)).single()
        assertEquals(2, row.useCount)
        assertEquals(7_777L, row.lastUsedMillis)
    }

    @Test
    fun `換編碼器版本後重建索引`() = runBlocking {
        val dao = db.phraseDao()
        val v1 = PhrasebookRepositoryImpl(dao, HashingTextEmbedder(), now = { 5_000L })
        v1.upsert(CommunicationPhrase(text = "I need cold water"))

        // 模擬換掉編碼器：維度與版本都變了
        val newEmbedder = object : com.example.aphasia.data.local.TextEmbedder {
            private val inner = HashingTextEmbedder(dimension = 128)
            override val dimension = 128
            override val version = 2
            override fun embed(text: String) = inner.embed(text)
        }
        val v2 = PhrasebookRepositoryImpl(dao, newEmbedder, now = { 5_000L })

        // 舊向量版本不符，必須被略過而不是拿去跟新查詢做內積
        assertEquals("重建前查不到", 0, v2.searchSimilar("water").size)
        v2.reindexAll()
        assertEquals("重建後查得到", 1, v2.searchSimilar("water").size)

        val row = dao.getByIds(listOf(1L)).single()
        assertEquals(2, row.embeddingVersion)
        assertNotNull(row.embeddingBlob)
        assertEquals("向量維度應已改為 128", 128 * 4, row.embeddingBlob!!.size)
    }
}
