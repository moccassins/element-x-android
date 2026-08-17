/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.voicemessages.transcript

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemVoiceContent
import io.element.android.features.voicetranscription.api.SttModelStatus
import io.element.android.features.voicetranscription.api.SttService
import io.element.android.libraries.architecture.Presenter
import io.element.android.libraries.core.extensions.mapCatchingExceptions
import io.element.android.libraries.di.CacheDirectory
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.media.MatrixMediaLoader
import io.element.android.libraries.preferences.api.store.AppPreferencesStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File

/**
 * Drives the on-device transcript for a single voice message. It only fetches
 * and decrypts the attachment when the user taps "Transcribe", then hands the
 * file to [SttService] which owns the model lifecycle and result cache.
 */
class VoiceTranscriptPresenter @AssistedInject constructor(
    @Assisted private val content: TimelineItemVoiceContent,
    private val sttService: SttService,
    private val appPreferencesStore: AppPreferencesStore,
    private val mediaLoader: MatrixMediaLoader,
    @CacheDirectory private val cacheDir: File,
) : Presenter<VoiceTranscriptState> {
    @AssistedFactory
    fun interface Factory {
        fun create(content: TimelineItemVoiceContent): VoiceTranscriptPresenter
    }

    private var phase by mutableStateOf<VoiceTranscriptPhase>(VoiceTranscriptPhase.Idle)
    private var text by mutableStateOf<String?>(null)
    private var transcribedModelId by mutableStateOf<String?>(null)
    private var transcribeProgress by mutableStateOf<Float?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Composable
    override fun present(): VoiceTranscriptState {
        val eventId = content.eventId
        val visible by produceState(initialValue = false) {
            appPreferencesStore.getVoiceTranscriptionEnabledFlow().collect { value = it }
        }
        val activeModel by produceState(initialValue = sttService.activeModel) {
            sttService.activeModelState.collect { value = it }
        }
        val modelsStatus by produceState(initialValue = sttService.modelsStatus.value) {
            sttService.modelsStatus.collect { value = it }
        }

        if (!visible) {
            return VoiceTranscriptState(
                visible = false,
                phase = phase,
                text = text,
                transcribedModelId = transcribedModelId,
                downloadProgress = null,
                transcribeProgress = transcribeProgress,
                canTranscribe = false,
                eventSink = ::handleEvent,
            )
        }

        // Seed from the service cache once per event.
        if (eventId != null && text == null) {
            sttService.cachedTranscription(eventId)?.let { cached ->
                text = cached.text
                transcribedModelId = cached.modelId
            }
        }

        val downloadProgress = (modelsStatus[activeModel] as? SttModelStatus.Downloading)?.progress

        return VoiceTranscriptState(
            visible = visible && eventId != null,
            phase = phase,
            text = text,
            transcribedModelId = transcribedModelId ?: activeModel.id,
            downloadProgress = downloadProgress,
            transcribeProgress = transcribeProgress,
            canTranscribe = true,
            eventSink = ::handleEvent,
        )
    }

    private fun handleEvent(event: VoiceTranscriptEvent) {
        val eventId = content.eventId ?: return
        when (event) {
            VoiceTranscriptEvent.Transcribe -> transcribe(eventId)
            VoiceTranscriptEvent.Retranscribe -> {
                sttService.invalidateTranscription(eventId)
                text = null
                transcribe(eventId)
            }
        }
    }

    private fun transcribe(eventId: EventId) {
        scope.launch {
            phase = VoiceTranscriptPhase.Preparing
            transcribeProgress = null
            val audioFile = fetchAudioFile()
            if (audioFile == null) {
                phase = VoiceTranscriptPhase.Error("Could not download voice attachment")
                return@launch
            }
            try {
                sttService.transcribe(eventId, audioFile) { progress ->
                    transcribeProgress = progress
                }
                    .onSuccess { transcript ->
                        text = transcript
                        transcribedModelId = sttService.activeModel.id
                        phase = VoiceTranscriptPhase.Idle
                        transcribeProgress = null
                    }
                    .onFailure {
                        Timber.w(it, "Transcription failed")
                        phase = VoiceTranscriptPhase.Error("Transcription failed")
                        transcribeProgress = null
                    }
            } finally {
                audioFile.delete()
            }
        }
    }

    private suspend fun fetchAudioFile(): File? {
        val dir = File(cacheDir, "stt/audio").apply { mkdirs() }
        val dest = File.createTempFile("voice_", ".bin", dir)
        return mediaLoader.downloadMediaFile(
            source = content.mediaSource,
            mimeType = content.mimeType,
            filename = content.filename,
        ).mapCatchingExceptions { mediaFile ->
            val persisted = mediaFile.use { it.persist(dest.absolutePath) }
            if (persisted) dest else error("Failed to persist voice attachment")
        }.getOrElse {
            dest.delete()
            null
        }
    }
}
