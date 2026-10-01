/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.voicemessages.transcript

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Provides the optional [VoiceTranscriptPresenter.Factory]. `null` when the
 * on-device transcription feature is not wired (e.g. in previews or screens
 * that don't support it), in which case no transcript UI is rendered.
 */
val LocalVoiceTranscriptPresenterFactory = staticCompositionLocalOf<VoiceTranscriptPresenter.Factory?> { null }
