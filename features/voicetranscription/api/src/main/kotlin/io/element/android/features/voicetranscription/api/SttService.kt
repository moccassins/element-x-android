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

    /**
     * Per-model on-disk status, reactive. Always reflects the latest known
     * state for every model in [SttModels.all].
     */
    val modelsStatus: StateFlow<Map<SttModel, SttModelStatus>>

    /**
     * Selects the active model. If it is not yet downloaded this triggers a
     * background download followed by a warm-up so the next transcription is
     * fast. Persists the choice as the default.
     */
    fun setActiveModel(model: SttModel)

    /**
     * Starts (or joins an in-flight) download of [model]. Safe to call for a
     * model that is already [SttModelStatus.Ready] (no-op).
     */
    fun downloadModel(model: SttModel)

    /**
     * Deletes [model] from disk to reclaim storage. Refuses to delete the
     * active model. Downloads of [model] are cancelled.
     */
    suspend fun deleteModel(model: SttModel): Boolean

    /**
     * A cached transcript together with the id of the model that produced it.
     * The model id is `null` for entries created before it was recorded.
     */
    data class CachedTranscription(
        val text: String,
        val modelId: String?,
    )

    /**
     * Returns the cached transcript for [eventId], or `null` if it has not
     * been transcribed yet (or was evicted).
     */
    fun cachedTranscription(eventId: EventId): CachedTranscription?

    /** Drops the cached transcript for [eventId] so the next [transcribe] re-runs. */
    fun invalidateTranscription(eventId: EventId)

    /**
     * Transcribes the given decrypted voice [audioFile] (Matrix voice is Opus in
     * an Ogg container) using the active model.
     *
     * [languageHint] is a BCP-47-like language code (e.g. "de") derived from the
     * room's messages, used to configure the recognizer; `null` falls back to
     * the device locale. [onProgress] is invoked with `0f..1f` as transcription
     * chunks complete (one increment per 30 s of audio). The file is decoded
     * to 16 kHz mono PCM internally. The result is cached by [eventId]. If the
     * active model is not ready this returns a failure. Native work is
     * serialized internally; decoding runs concurrently with downloads and
     * warm-ups.
     */
    suspend fun transcribe(
        eventId: EventId,
        audioFile: java.io.File,
        languageHint: String? = null,
        onProgress: (Float) -> Unit = {},
    ): Result<String>
}
