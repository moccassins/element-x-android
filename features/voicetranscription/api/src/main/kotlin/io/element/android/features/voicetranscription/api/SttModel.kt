/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.voicetranscription.api

import androidx.compose.runtime.Immutable

/**
 * Whisper model tiers exposed to the user for on-device transcription.
 *
 * Multilingual (non `.en`) variants are used so the same model handles
 * English and German (and more) voice messages.
 */
@Immutable
enum class SttModel(val id: String) {
    TINY("tiny"),
    BASE("base"),
    SMALL("small");

    companion object {
        fun fromId(id: String?): SttModel? = entries.firstOrNull { it.id == id }
    }
}
