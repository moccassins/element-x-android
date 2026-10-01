/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.voicetranscription.impl.settings

import app.cash.molecule.RecompositionMode
import app.cash.molecule.moleculeFlow
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.element.android.features.voicetranscription.api.SttModel
import io.element.android.features.voicetranscription.api.SttModelStatus
import io.element.android.features.voicetranscription.test.FakeSttService
import io.element.android.libraries.preferences.test.InMemoryAppPreferencesStore
import io.element.android.tests.testutils.WarmUpRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class SttSettingsPresenterTest {
    @get:Rule
    val warmUpRule = WarmUpRule()

    @Test
    fun `present - exposes all models with tiny preselected`() = runTest {
        val service = FakeSttService(
            initialStatus = mapOf(SttModel.BASE to SttModelStatus.Ready),
        )
        val preferences = InMemoryAppPreferencesStore(voiceTranscriptionEnabled = true)
        val presenter = SttSettingsPresenter(service, preferences)
        moleculeFlow(RecompositionMode.Immediate) {
            presenter.present()
        }.test {
            // First emission has the initial (disabled) value before the preference flow is collected
            assertThat(awaitItem().enabled).isFalse()
            val state = awaitItem()
            assertThat(state.enabled).isTrue()
            assertThat(state.models).hasSize(3)
            assertThat(state.models.first { it.isActive }.model).isEqualTo(SttModel.TINY)
            assertThat(service.selectedModels).isEmpty()
            val base = state.models.first { it.model == SttModel.BASE }
            assertThat(base.status).isEqualTo(SttModelStatus.Ready)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - the feature is disabled by default`() = runTest {
        val presenter = SttSettingsPresenter(FakeSttService(), InMemoryAppPreferencesStore())
        moleculeFlow(RecompositionMode.Immediate) {
            presenter.present()
        }.test {
            val state = awaitItem()
            assertThat(state.enabled).isFalse()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - toggling the feature persists the preference`() = runTest {
        val preferences = InMemoryAppPreferencesStore()
        val presenter = SttSettingsPresenter(FakeSttService(), preferences)
        moleculeFlow(RecompositionMode.Immediate) {
            presenter.present()
        }.test {
            val state = awaitItem()
            assertThat(state.enabled).isFalse()
            state.eventSink(SttSettingsEvent.SetEnabled(true))
            assertThat(preferences.getVoiceTranscriptionEnabledFlow().first()).isTrue()
            val updated = awaitItem()
            assertThat(updated.enabled).isTrue()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - model events are ignored while the feature is disabled`() = runTest {
        val service = FakeSttService()
        val presenter = SttSettingsPresenter(service, InMemoryAppPreferencesStore())
        moleculeFlow(RecompositionMode.Immediate) {
            presenter.present()
        }.test {
            val state = awaitItem()
            assertThat(state.enabled).isFalse()
            state.eventSink(SttSettingsEvent.Select(SttModel.BASE))
            state.eventSink(SttSettingsEvent.Download(SttModel.TINY))
            state.eventSink(SttSettingsEvent.Delete(SttModel.TINY))
            assertThat(service.selectedModels).isEmpty()
            assertThat(service.downloadedModels).isEmpty()
            assertThat(service.deletedModels).isEmpty()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - selecting a model forwards to the service and marks it active`() = runTest {
        val service = FakeSttService()
        val preferences = InMemoryAppPreferencesStore(voiceTranscriptionEnabled = true)
        val presenter = SttSettingsPresenter(service, preferences)
        moleculeFlow(RecompositionMode.Immediate) {
            presenter.present()
        }.test {
            // Skip the initial (disabled) emission before the preference flow is collected
            awaitItem()
            val state = awaitItem()
            state.eventSink(SttSettingsEvent.Select(SttModel.BASE))
            // The fake applies the selection synchronously.
            assertThat(service.selectedModels).containsExactly(SttModel.BASE)
            val updated = awaitItem()
            val active = updated.models.first { it.isActive }
            assertThat(active.model).isEqualTo(SttModel.BASE)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - download event is forwarded to the service`() = runTest {
        val service = FakeSttService(initialActiveModel = SttModel.BASE)
        val preferences = InMemoryAppPreferencesStore(voiceTranscriptionEnabled = true)
        val presenter = SttSettingsPresenter(service, preferences)
        moleculeFlow(RecompositionMode.Immediate) {
            presenter.present()
        }.test {
            // Skip the initial (disabled) emission before the preference flow is collected
            awaitItem()
            val state = awaitItem()
            state.eventSink(SttSettingsEvent.Download(SttModel.TINY))
            assertThat(service.downloadedModels).containsExactly(SttModel.TINY)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - model selection is ignored while a transcription is running`() = runTest {
        val service = FakeSttService(initialIsTranscribing = true)
        val preferences = InMemoryAppPreferencesStore(voiceTranscriptionEnabled = true)
        val presenter = SttSettingsPresenter(service, preferences)
        moleculeFlow(RecompositionMode.Immediate) {
            presenter.present()
        }.test {
            // Skip the initial (disabled) emission before the preference flow is collected
            awaitItem()
            val state = awaitItem()
            assertThat(state.isTranscribing).isTrue()
            state.eventSink(SttSettingsEvent.Select(SttModel.BASE))
            assertThat(service.selectedModels).isEmpty()
            cancelAndIgnoreRemainingEvents()
        }
    }
}
