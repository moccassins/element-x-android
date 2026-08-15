/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.voicetranscription.impl.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import dev.zacsweers.metro.Inject
import io.element.android.features.voicetranscription.api.SttModelStatus
import io.element.android.features.voicetranscription.api.SttModels
import io.element.android.features.voicetranscription.api.SttService
import io.element.android.libraries.architecture.Presenter
import io.element.android.libraries.preferences.api.store.AppPreferencesStore
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.launch

@Inject
class SttSettingsPresenter(
    private val sttService: SttService,
    private val appPreferencesStore: AppPreferencesStore,
) : Presenter<SttSettingsState> {
    @Composable
    override fun present(): SttSettingsState {
        val enabled by produceState(initialValue = false) {
            appPreferencesStore.getVoiceTranscriptionEnabledFlow().collect { value = it }
        }
        val activeModel by produceState(initialValue = sttService.activeModel) {
            sttService.activeModelState.collect { value = it }
        }
        val statuses by produceState(initialValue = sttService.modelsStatus.value) {
            sttService.modelsStatus.collect { value = it }
        }
        val scope = rememberCoroutineScope()

        fun handleEvent(event: SttSettingsEvent) {
            when (event) {
                is SttSettingsEvent.SetEnabled -> scope.launch {
                    appPreferencesStore.setVoiceTranscriptionEnabled(event.enabled)
                }
                is SttSettingsEvent.Select -> if (enabled) sttService.setActiveModel(event.model)
                is SttSettingsEvent.Download -> if (enabled) sttService.downloadModel(event.model)
                is SttSettingsEvent.Delete -> if (enabled) scope.launch { sttService.deleteModel(event.model) }
            }
        }

        val models = SttModels.all.map { descriptor ->
            SttModelUiState(
                model = descriptor.model,
                displaySizeMb = descriptor.displaySizeMb,
                status = statuses[descriptor.model] ?: SttModelStatus.NotDownloaded,
                isActive = activeModel == descriptor.model,
            )
        }.toImmutableList()

        return SttSettingsState(
            enabled = enabled,
            models = models,
            eventSink = ::handleEvent,
        )
    }
}
