package com.example.aphasia.data.repository

import com.example.aphasia.data.acoustic.AudioCaptureManager
import com.example.aphasia.data.acoustic.MfccExtractor
import com.example.aphasia.domain.model.FeatureSequence
import com.example.aphasia.domain.model.Utterance
import com.example.aphasia.domain.repository.AudioFeatureRepository
import kotlinx.coroutines.flow.Flow

/**
 * 把 [AudioCaptureManager]（Android）與 [MfccExtractor]（純 DSP）接起來。
 *
 * 兩者刻意分開：特徵抽取沒有 Android 相依，才能用 JVM 測試驗證數值正確性。
 */
class AudioFeatureRepositoryImpl(
    private val capture: AudioCaptureManager,
    private val extractor: MfccExtractor = MfccExtractor(),
) : AudioFeatureRepository {

    override val liveAmplitude: Flow<Float> = capture.liveAmplitude

    override suspend fun record(maxDurationMs: Long): Utterance =
        capture.record(maxDurationMs).getOrThrow()

    /** 由使用者鬆手結束的錄音（按住說話）。 */
    suspend fun recordUntil(maxDurationMs: Long, shouldStop: () -> Boolean): Result<Utterance> =
        capture.record(maxDurationMs, shouldStop)

    override fun extractFeatures(utterance: Utterance): FeatureSequence =
        extractor.extract(utterance)
}
