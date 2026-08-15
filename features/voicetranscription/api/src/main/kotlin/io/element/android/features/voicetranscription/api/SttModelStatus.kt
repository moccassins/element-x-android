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
 * Lifecycle of a single [SttModel] on disk.
 */
@Immutable
sealed interface SttModelStatus {
    /** Not present locally (or present but corrupt and removed). */
    data object NotDownloaded : SttModelStatus

    /** Download in progress. [progress] is in the `0f..1f` range. */
    data class Downloading(val progress: Float) : SttModelStatus

    /** All artifacts are present and validated on disk. */
    data object Ready : SttModelStatus
}
