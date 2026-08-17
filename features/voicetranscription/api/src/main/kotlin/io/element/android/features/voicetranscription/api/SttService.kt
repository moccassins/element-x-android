/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.voicetranscription.api

import io.element.android.libraries.matrix.api.core.EventId
import kotlinx.coroutines.flow.StateFlow

/**
 * On-device speech-to-text service.
 *
 * Implementations keep at most one Whisper model resident in native memory
 * (the sherpa-onnx engine is not safe to call concurrently) while downloads
 * run in parallel. Transcription results are cached in memory keyed by
 * [EventId] and never persisted: transcripts are cheap to regenerate.
 */
interface SttService {
    /** Currently selected model. Defaults to [SttModel.TINY]. */
    val activeModel: SttModel

    /** Reactive variant of [activeModel]. */
    val activeModelState: StateFlow<SttModel>

    /** Per-model on-disk status, reactive. */
    val modelsStatus: StateFlow<Map<SttModel, SttModelStatus>>

    /**
     * Selects the active model. If it is not yet downloaded this triggers a
     * background download followed by a warm-up.
     */
    fun setActiveModel(model: SttModel)

    /** Starts (or joins an in-flight) download of [model]. No-op when already ready. */
    fun downloadModel(model: SttModel)

    /**
     * Deletes [model] from disk to reclaim storage. Refuses to delete the
     * active model. Downloads of [model] are cancelled.
     */
    suspend fun deleteModel(model: SttModel): Boolean

    /**
     * A cached transcript together with the id of the model that produced it.
     */
    data class CachedTranscription(
        val text: String,
        val modelId: String?,
    )

    /** Returns the cached transcript for [eventId], or `null` if there is none. */
    fun cachedTranscription(eventId: EventId): CachedTranscription?

    /** Drops the cached transcript for [eventId] so the next [transcribe] re-runs. */
    fun invalidateTranscription(eventId: EventId)

    /**
     * Transcribes the decrypted voice [audioFile] using the active model.
     * The spoken language is detected by Whisper itself. [onProgress] is
     * invoked with `0f..1f` as 30 s chunks complete. The result is cached by
     * [eventId].
     */
    suspend fun transcribe(
        eventId: EventId,
        audioFile: java.io.File,
        onProgress: (Float) -> Unit = {},
    ): Result<String>
}
