/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.voicetranscription.test

import io.element.android.features.voicetranscription.api.SttModel
import io.element.android.features.voicetranscription.api.SttModelStatus
import io.element.android.features.voicetranscription.api.SttService
import io.element.android.libraries.matrix.api.core.EventId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * In-memory [SttService] fake for tests and previews. Records the operations
 * it receives so tests can assert on them.
 */
class FakeSttService(
    initialActiveModel: SttModel = SttModel.TINY,
    initialStatus: Map<SttModel, SttModelStatus> = emptyMap(),
) : SttService {
    private val activeModelStateHolder = MutableStateFlow(initialActiveModel)
    private val statusState = MutableStateFlow(initialStatus)
    private val cache = mutableMapOf<EventId, SttService.CachedTranscription>()

    val selectedModels = mutableListOf<SttModel>()
    val downloadedModels = mutableListOf<SttModel>()
    val deletedModels = mutableListOf<SttModel>()

    override val activeModel: SttModel get() = activeModelStateHolder.value
    override val activeModelState: StateFlow<SttModel> get() = activeModelStateHolder.asStateFlow()
    override val modelsStatus: StateFlow<Map<SttModel, SttModelStatus>> get() = statusState.asStateFlow()

    override fun setActiveModel(model: SttModel) {
        selectedModels += model
        activeModelStateHolder.value = model
    }

    override fun downloadModel(model: SttModel) {
        downloadedModels += model
    }

    override suspend fun deleteModel(model: SttModel): Boolean {
        deletedModels += model
        return true
    }

    override fun cachedTranscription(eventId: EventId): SttService.CachedTranscription? = cache[eventId]

    override fun invalidateTranscription(eventId: EventId) {
        cache.remove(eventId)
    }

    override suspend fun transcribe(
        eventId: EventId,
        audioFile: File,
        languageHint: String?,
        onProgress: (Float) -> Unit,
    ): Result<String> {
        val text = "transcript-${eventId.value}"
        onProgress(1f)
        cache[eventId] = SttService.CachedTranscription(text = text, modelId = activeModel?.id)
        return Result.success(text)
    }
}
