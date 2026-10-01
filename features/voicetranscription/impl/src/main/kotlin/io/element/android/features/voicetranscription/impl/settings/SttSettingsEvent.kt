/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.voicetranscription.impl.settings

import io.element.android.features.voicetranscription.api.SttModel

sealed interface SttSettingsEvent {
    data class SetEnabled(val enabled: Boolean) : SttSettingsEvent
    data class Select(val model: SttModel) : SttSettingsEvent
    data class Download(val model: SttModel) : SttSettingsEvent
    data class Delete(val model: SttModel) : SttSettingsEvent
}
