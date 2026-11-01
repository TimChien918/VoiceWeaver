package com.speakmylife.app.aphasia.data.repository

import com.speakmylife.app.ai.DiagnosticLog
import com.speakmylife.app.aphasia.data.local.PhraseDao
import com.speakmylife.app.aphasia.data.local.PhraseEntity
import com.speakmylife.app.aphasia.data.local.PhraseVectorIndex
import com.speakmylife.app.aphasia.data.local.TextEmbedder
import com.speakmylife.app.aphasia.data.local.VectorCodec
import com.speakmylife.app.aphasia.domain.model.CommunicationPhrase
import com.speakmylife.app.aphasia.domain.model.IntentNode
import com.speakmylife.app.aphasia.domain.repository.PhrasebookRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 個人語句庫 + 裝置端語意檢索（Local RAG）。
 *
 * 索引是延遲建立的：App 啟動時不需要付這個成本，第一次檢索才建。
 * 之後每次寫入都同步更新該筆的向量，並標記索引為髒。
 */
class PhrasebookRepositoryImpl(
    private val dao: PhraseDao,
    private val embedder: TextEmbedder,
    private val intentTreeSource: suspend () -> List<IntentNode> = { emptyList() },
    private val now: () -> Long = System::currentTimeMillis,
) : PhrasebookRepository {

    private val index = PhraseVectorIndex()
    private val indexLock = Mutex()
    @Volatile private var indexDirty = true

    override fun observeAll(context: String?): Flow<List<CommunicationPhrase>> =
        (if (context == null) dao.observeAll() else dao.observeByContext(context))
            .map { rows -> rows.map { it.toDomain() } }

    override suspend fun upsert(phrase: CommunicationPhrase): Long = withContext(Dispatchers.Default) {
        // 寫入時就算好向量。留到檢索時才算會讓第一次查詢突然變慢，
        // 而且要處理「查詢中途資料變了」這種不必要的複雜度。
        val vector = embedder.embed(phrase.text)
        val id = dao.upsert(
            PhraseEntity(
                id = phrase.id,
                text = phrase.text,
                context = phrase.context,
                embeddingBlob = VectorCodec.encode(vector),
                embeddingVersion = embedder.version,
                useCount = phrase.useCount,
                lastUsedMillis = phrase.lastUsedMillis,
            )
        )
        indexDirty = true
        id
    }

    override suspend fun delete(id: Long) {
        dao.delete(id)
        indexDirty = true
    }

    override suspend fun markUsed(id: Long) = dao.markUsed(id, now())

    override suspend fun searchSimilar(query: String, topK: Int): List<CommunicationPhrase> =
        withContext(Dispatchers.Default) {
            if (query.isBlank()) return@withContext emptyList()
            ensureIndex()

            val hits = index.search(
                query = embedder.embed(query),
                topK = topK,
                // 有下限才有意義。把毫不相關的句子塞進 LLM 的上下文，
                // 只會讓它把使用者根本沒提到的東西寫進重組結果——
                // 這正是之前「我什麼都沒說卻得到一整句」那類幻覺的養分。
                minSimilarity = MIN_SIMILARITY,
            )
            if (hits.isEmpty()) return@withContext emptyList()

            val byId = dao.getByIds(hits.map { it.first }).associateBy { it.id }
            val results = hits.mapNotNull { (id, _) -> byId[id]?.toDomain() }
            DiagnosticLog.i(
                DiagnosticLog.Cat.LLM,
                "語句庫檢索「$query」→ ${results.size} 筆",
                hits.joinToString(" ") { (id, s) -> "#$id=%.2f".format(s) },
            )
            results
        }

    override fun observeIntentTree(): Flow<List<IntentNode>> = flow { emit(intentTreeSource()) }

    /** 重算所有向量。換編碼器版本後呼叫。 */
    suspend fun reindexAll() = withContext(Dispatchers.Default) {
        val stale = dao.getNeedingEmbedding(embedder.version)
        if (stale.isEmpty()) return@withContext
        for (row in stale) {
            dao.setEmbedding(row.id, VectorCodec.encode(embedder.embed(row.text)), embedder.version)
        }
        indexDirty = true
        DiagnosticLog.i(DiagnosticLog.Cat.LLM, "語句庫重建索引 ${stale.size} 筆（v${embedder.version}）")
    }

    private suspend fun ensureIndex() {
        if (!indexDirty && index.embeddingVersion == embedder.version) return
        indexLock.withLock {
            if (!indexDirty && index.embeddingVersion == embedder.version) return
            index.rebuild(dao.getEmbeddings(), embedder.version)
            indexDirty = false
        }
    }

    private fun PhraseEntity.toDomain() = CommunicationPhrase(
        id = id,
        text = text,
        context = context,
        embedding = embeddingBlob?.let { runCatching { VectorCodec.decode(it) }.getOrNull() },
        useCount = useCount,
        lastUsedMillis = lastUsedMillis,
    )

    companion object {
        /**
         * cosine 相似度下限。雜湊 n-gram 的分數分布比語意嵌入分散，
         * 0.25 大約對應「至少共用一個實詞」。換成真正的嵌入模型時要重新量測這個值。
         */
        const val MIN_SIMILARITY = 0.25f
    }
}
