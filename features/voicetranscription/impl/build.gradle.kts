import extension.setupDependencyInjection
import extension.testCommonDependencies

/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

plugins {
    id("io.element.android-compose-library")
    id("kotlin-parcelize")
}

android {
    namespace = "io.element.android.features.voicetranscription.impl"
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

setupDependencyInjection()

dependencies {
    api(projects.features.voicetranscription.api)
    implementation(projects.libraries.core)
    implementation(projects.libraries.di)
    implementation(projects.libraries.architecture)
    implementation(projects.libraries.designsystem)
    implementation(projects.libraries.uiStrings)
    implementation(projects.libraries.matrix.api)
    implementation(projects.libraries.preferences.api)
    implementation(libs.coroutines.core)
    implementation(libs.timber)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.sherpa.onnx)

    testCommonDependencies(libs, true)
    testImplementation(projects.features.voicetranscription.test)
    testImplementation(projects.libraries.preferences.test)
}
