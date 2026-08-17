/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.voicetranscription.impl

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import io.element.android.features.voicetranscription.api.SttModel
import io.element.android.features.voicetranscription.api.SttModels
import io.element.android.features.voicetranscription.api.SttService
import io.element.android.libraries.di.annotations.AppCoroutineScope
import io.element.android.libraries.matrix.api.core.EventId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Default [SttService]. Native operations are serialized with [nativeLock]
 * (sherpa-onnx is not safe to call concurrently) while downloads run
 * independently. Exactly one model stays resident; an unload timer frees the
 * native memory 2 minutes after the last use.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DefaultSttService(
    @AppCoroutineScope private val appScope: CoroutineScope,
    private val modelStore: SttModelStore,
    private val preferences: SttPreferences,
) : SttService {
    private val engine = SttEngine()
    private val decoder = SttAudioDecoder()
    private val nativeLock = Mutex()
    private val cache = ConcurrentHashMap<EventId, SttService.CachedTranscription>()

    private val activeModelHolder = MutableStateFlow(SttModel.TINY)
    private var unloadJob: Job? = null

    init {
        appScope.launch {
            preferences.activeModelFlow.collect { model ->
                activeModelHolder.value = model
                ensureReady(model)
            }
        }
    }

    override val activeModel: SttModel get() = activeModelHolder.value
    override val activeModelState get() = activeModelHolder.asStateFlow()
    override val modelsStatus get() = modelStore.status

    override fun setActiveModel(model: SttModel) {
        appScope.launch {
            preferences.setActiveModel(model)
            ensureReady(model)
        }
    }

    override fun downloadModel(model: SttModel) {
        modelStore.startDownload(model)
    }

    override suspend fun deleteModel(model: SttModel): Boolean {
        if (model == activeModelHolder.value) return false
        val deleted = modelStore.delete(model)
        if (deleted && engine.isLoaded(model)) {
            nativeLock.withLock { engine.release() }
        }
        return deleted
    }

    override fun cachedTranscription(eventId: EventId): SttService.CachedTranscription? = cache[eventId]

    override fun invalidateTranscription(eventId: EventId) {
        cache.remove(eventId)
    }

    override suspend fun transcribe(
        eventId: EventId,
        audioFile: File,
        onProgress: (Float) -> Unit,
    ): Result<String> {
        cache[eventId]?.let { return Result.success(it.text) }
        val model = activeModelHolder.value
        return try {
            if (!modelStore.isReady(model)) {
                modelStore.ensureDownloaded(model)
            }
            val decoded = withContext(Dispatchers.IO) { decoder.decode(audioFile) }
            val text = nativeLock.withLock {
                if (!engine.isLoaded(model)) {
                    engine.load(SttModels.forModel(model), modelStore.modelDir(model))
                }
                rescheduleUnload()
                withContext(Dispatchers.Default) { engine.transcribe(decoded.samples, decoded.sampleRate, onProgress) }
            }
            cache[eventId] = SttService.CachedTranscription(text = text, modelId = model.id)
            Result.success(text)
        } catch (e: Exception) {
            Timber.w(e, "On-device transcription failed for %s", eventId.value)
            Result.failure(e)
        }
    }

    private fun ensureReady(model: SttModel) {
        appScope.launch {
            try {
                if (!modelStore.isReady(model)) {
                    modelStore.ensureDownloaded(model)
                }
                warmUp(model)
            } catch (e: Exception) {
                Timber.w(e, "Could not ready STT model %s", model.id)
            }
        }
    }

    private suspend fun warmUp(model: SttModel) {
        nativeLock.withLock {
            if (!engine.isLoaded(model)) {
                engine.load(SttModels.forModel(model), modelStore.modelDir(model))
            }
            rescheduleUnload()
        }
    }

    private fun rescheduleUnload() {
        unloadJob?.cancel()
        unloadJob = appScope.launch {
            delay(UNLOAD_DELAY_MS)
            nativeLock.withLock { engine.release() }
        }
    }

    private companion object {
        const val UNLOAD_DELAY_MS = 120_000L
    }
}
