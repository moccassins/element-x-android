/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.voicetranscription.impl.settings

import androidx.compose.runtime.Immutable
import io.element.android.features.voicetranscription.api.SttModel
import io.element.android.features.voicetranscription.api.SttModelStatus
import kotlinx.collections.immutable.ImmutableList

@Immutable
data class SttModelUiState(
    val model: SttModel,
    val displaySizeMb: Int,
    val status: SttModelStatus,
    val isActive: Boolean,
)

@Immutable
data class SttSettingsState(
    val enabled: Boolean,
    val models: ImmutableList<SttModelUiState>,
    val eventSink: (SttSettingsEvent) -> Unit,
)
