package com.example.aphasia.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一個聲學樣板。登錄錄音的 MFCC 全部打包在 [exemplarBlob] 裡。
 *
 * 這是使用者花力氣錄出來的資料，重建成本高（要重新錄好幾次、重新校準門檻），
 * 所以這張表所在的資料庫用真正的 migration，不是 destructive fallback。
 */
@Entity(tableName = "acoustic_templates", indices = [Index("label")])
data class AcousticTemplateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    /** [com.example.aphasia.domain.model.MatchMode] 的名稱。存字串而不是序號：
     *  之後在 enum 中間插一個值時，序號會讓既有資料全部對錯。 */
    @ColumnInfo(name = "match_mode", defaultValue = "Keyword") val matchMode: String = "Keyword",
    /** 要聽的那幾個字。只給使用者看，比對完全靠波形。 */
    @ColumnInfo(name = "keyword", defaultValue = "") val keyword: String = "",
    /** 語句庫分類，只用來整理（雲端會照這個開資料夾）。自己加的是 "Custom"。 */
    @ColumnInfo(name = "category", defaultValue = "Custom") val category: String = "Custom",
    @ColumnInfo(name = "spoken_phrase") val spokenPhrase: String,
    /** [VectorCodec.encodeBundle] 的輸出 */
    @ColumnInfo(name = "exemplar_blob", typeAffinity = ColumnInfo.BLOB)
    val exemplarBlob: ByteArray,
    @ColumnInfo(name = "rejection_threshold") val rejectionThreshold: Float,
    @ColumnInfo(name = "margin_threshold") val marginThreshold: Float,
    @ColumnInfo(name = "created_at") val createdAtMillis: Long,
) {
    // Room 的 data class 帶 ByteArray 時，預設 equals 會比參考位址。
    // 這張表會進 Flow 做 distinctUntilChanged，比錯會造成不必要的重繪。
    override fun equals(other: Any?): Boolean =
        this === other || (other is AcousticTemplateEntity &&
            id == other.id && label == other.label && spokenPhrase == other.spokenPhrase &&
            matchMode == other.matchMode && keyword == other.keyword &&
            rejectionThreshold == other.rejectionThreshold &&
            marginThreshold == other.marginThreshold &&
            exemplarBlob.contentEquals(other.exemplarBlob))

    override fun hashCode(): Int =
        (((id.hashCode() * 31 + label.hashCode()) * 31 + spokenPhrase.hashCode()) * 31) +
            exemplarBlob.contentHashCode()
}

/**
 * 個人語句庫的一筆。[embeddingBlob] 為 null 表示尚未編碼，
 * 檢索時要略過而不是當成零向量——零向量跟任何東西的相似度都是 0，
 * 看起來像「不相關」，但實際上是「還不知道」。
 */
@Entity(
    tableName = "phrases",
    indices = [Index("context"), Index("last_used_at")],
)
data class PhraseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val context: String? = null,
    @ColumnInfo(name = "embedding", typeAffinity = ColumnInfo.BLOB)
    val embeddingBlob: ByteArray? = null,
    /** 產生這個向量的編碼器版本。換模型時要能認出哪些需要重算。 */
    @ColumnInfo(name = "embedding_version") val embeddingVersion: Int = 0,
    @ColumnInfo(name = "use_count") val useCount: Int = 0,
    @ColumnInfo(name = "last_used_at") val lastUsedMillis: Long = 0L,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is PhraseEntity &&
            id == other.id && text == other.text && context == other.context &&
            embeddingVersion == other.embeddingVersion && useCount == other.useCount &&
            lastUsedMillis == other.lastUsedMillis &&
            (embeddingBlob?.contentEquals(other.embeddingBlob ?: ByteArray(0)) ?: (other.embeddingBlob == null)))

    override fun hashCode(): Int =
        ((id.hashCode() * 31 + text.hashCode()) * 31 + (context?.hashCode() ?: 0)) * 31 + useCount
}
