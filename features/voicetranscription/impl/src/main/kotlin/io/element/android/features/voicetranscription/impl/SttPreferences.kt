/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.voicetranscription.impl

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.zacsweers.metro.Inject
import io.element.android.features.voicetranscription.api.SttModel
import io.element.android.libraries.preferences.api.store.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Persists the user's chosen default [SttModel]. There is no per-message
 * override: the choice made here (and from the room overflow menu) is the
 * single source of truth, matching the validated FluffyChat UX.
 */
@Inject
class SttPreferences(
    preferenceDataStoreFactory: PreferenceDataStoreFactory,
) {
    private val store = preferenceDataStoreFactory.create("elementx_stt")

    /** Defaults to [SttModel.TINY] — the smallest download, best device support. */
    val activeModelFlow: Flow<SttModel> = store.data
        .map { prefs -> SttModel.fromId(prefs[KEY_ACTIVE_MODEL]) ?: SttModel.TINY }
        .distinctUntilChanged()

    suspend fun setActiveModel(model: SttModel) {
        store.edit { prefs ->
            prefs[KEY_ACTIVE_MODEL] = model.id
        }
    }

    private companion object {
        val KEY_ACTIVE_MODEL = stringPreferencesKey("active_model")
    }
}
