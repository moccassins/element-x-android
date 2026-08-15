/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.voicetranscription.impl

import dev.zacsweers.metro.Inject
import io.element.android.features.voicetranscription.api.SttModel
import io.element.android.features.voicetranscription.api.SttModelDescriptor
import io.element.android.features.voicetranscription.api.SttModelFile
import io.element.android.features.voicetranscription.api.SttModelStatus
import io.element.android.features.voicetranscription.api.SttModels
import io.element.android.libraries.di.BaseDirectory
import io.element.android.libraries.di.annotations.AppCoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Owns the on-disk lifecycle of Whisper model artifacts.
 *
 * Downloads of *different* models run in parallel (they write independent
 * directories); concurrent requests for the *same* model await a single
 * in-flight download. Each download enforces a 30s inactivity timeout and
 * validates the encoder file by size before reporting the model as ready — a
 * truncated file (the FluffyChat "not all tensors loaded" failure mode) is
 * deleted and surfaced as an error.
 *
 * Models are stored under the application base directory (not the OS-evictable
 * cache) so a validated model is not silently removed under storage pressure.
 */
@Inject
class SttModelStore(
    @BaseDirectory private val baseDir: File,
    @AppCoroutineScope private val appScope: CoroutineScope,
) {
    private val rootDir = File(baseDir, "stt").apply { mkdirs() }

    private val mutableStatus = MutableStateFlow<Map<SttModel, SttModelStatus>>(emptyMap())
    val status: StateFlow<Map<SttModel, SttModelStatus>> = mutableStatus.asStateFlow()

    // Guarded exclusively by [downloadsLock] — see registerOrGet / unregister.
    private val activeDownloads = mutableMapOf<SttModel, Deferred<Unit>>()
    private val downloadsLock = Mutex()

    init {
        refresh()
    }

    fun statusFor(model: SttModel): SttModelStatus = mutableStatus.value[model] ?: SttModelStatus.NotDownloaded

    fun isReady(model: SttModel): Boolean = statusFor(model) is SttModelStatus.Ready

    /** Local path of [file] for [model]. */
    fun localFile(model: SttModel, file: SttModelFile): File {
        val descriptor = SttModels.forModel(model)
        return File(File(rootDir, descriptor.dirName), file.localName)
    }

    /** Directory holding [model]'s extracted files. */
    fun modelDir(model: SttModel): File = File(rootDir, SttModels.forModel(model).dirName)

    /**
     * Downloads [model] if necessary and validates it. Joins an in-flight
     * download when one exists. Throws on validation failure or IO error.
     */
    suspend fun ensureDownloaded(model: SttModel) {
        if (isReady(model)) return
        val deferred = registerOrGet(model)
        try {
            deferred.await()
        } finally {
            unregister(deferred, model)
        }
    }

    /** Starts (or joins) a background download. Non-suspending. */
    fun startDownload(model: SttModel) {
        if (isReady(model)) return
        appScope.launch {
            val deferred = registerOrGet(model)
            try {
                deferred.await()
            } catch (e: Exception) {
                Timber.w(e, "STT model download failed: %s", model.id)
            } finally {
                unregister(deferred, model)
            }
        }
    }

    /** Cancels any in-flight download of [model] and removes its files from disk. */
    suspend fun delete(model: SttModel): Boolean {
        val inFlight = downloadsLock.withLock { activeDownloads.remove(model) }
        inFlight?.cancel()
        return try {
            modelDir(model).deleteRecursively()
            // Only the deleted model changes state; do not rescan the others
            // (a full refresh would clobber in-flight Downloading progress).
            updateStatus(model) { SttModelStatus.NotDownloaded }
            true
        } catch (e: Exception) {
            Timber.w(e, "Failed to delete STT model %s", model.id)
            false
        }
    }

    /** Re-scans disk and updates [status]. */
    fun refresh() {
        mutableStatus.value = SttModels.all.associate { it.model to computeStatus(it) }
    }

    private fun computeStatus(descriptor: SttModelDescriptor): SttModelStatus =
        if (validate(descriptor)) SttModelStatus.Ready else SttModelStatus.NotDownloaded

    /**
     * A model is valid when every artifact exists and is at least its pinned
     * minimum size (a smaller file was truncated; a larger one is accepted so
     * upstream model updates do not invalidate the download).
     */
    private fun validate(descriptor: SttModelDescriptor): Boolean =
        listOf(descriptor.encoder, descriptor.decoder, descriptor.tokens).all { file ->
            val local = File(modelDir(descriptor.model), file.localName)
            local.exists() && local.length() >= file.minBytes
        }

    /**
     * Atomically returns the in-flight download for [model], or registers a new one.
     * All access to [activeDownloads] goes through this and [unregister] so the
     * dedup check-then-act is race-free.
     */
    private suspend fun registerOrGet(model: SttModel): Deferred<Unit> = downloadsLock.withLock {
        activeDownloads.getOrPut(model) { appScope.async { performDownload(model) } }
    }

    private suspend fun unregister(deferred: Deferred<Unit>, model: SttModel) {
        downloadsLock.withLock {
            if (activeDownloads[model] === deferred) activeDownloads.remove(model)
        }
    }

    private suspend fun performDownload(model: SttModel) {
        val descriptor = SttModels.forModel(model)
        updateStatus(model) { SttModelStatus.Downloading(0f) }
        try {
            val files = listOf(descriptor.encoder, descriptor.decoder, descriptor.tokens)
            // Total is known upfront from the pinned artifact sizes, so progress
            // is monotonically increasing across the three sequential downloads.
            val total = descriptor.totalBytes
            var received = 0L
            var lastPercent = -1
            for (file in files) {
                val dest = localFile(model, file).also { it.parentFile?.mkdirs() }
                downloadFile(
                    url = file.url,
                    dest = dest,
                    onChunk = { delta ->
                        received += delta
                        // Throttle to integer-percent changes to avoid thousands of emissions.
                        val percent = if (total > 0) (received * 100 / total).toInt() else 0
                        if (percent != lastPercent) {
                            lastPercent = percent
                            updateStatus(model) { SttModelStatus.Downloading((percent / 100f).coerceIn(0f, 1f)) }
                        }
                    },
                )
            }
            // Validate before declaring ready; corrupt files are removed.
            if (!validate(descriptor)) {
                modelDir(model).deleteRecursively()
                error("Downloaded model ${model.id} failed validation (corrupt or truncated).")
            }
            updateStatus(model) { SttModelStatus.Ready }
        } catch (e: Exception) {
            modelDir(model).deleteRecursively()
            updateStatus(model) { SttModelStatus.NotDownloaded }
            throw e
        }
    }

    private fun updateStatus(model: SttModel, transform: (SttModelStatus) -> SttModelStatus) {
        mutableStatus.update { current ->
            current.toMutableMap().apply { put(model, transform(current[model] ?: SttModelStatus.NotDownloaded)) }
        }
    }

    /**
     * Downloads [url] to [dest], invoking [onChunk] with each chunk size.
     */
    private suspend fun downloadFile(
        url: String,
        dest: File,
        onChunk: (Long) -> Unit,
    ): Long = withContext(Dispatchers.IO) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
        }
        try {
            conn.inputStream.use { input ->
                dest.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        if (!isActive) {
                            dest.delete()
                            throw CancellationException("STT download cancelled")
                        }
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        total += read
                        onChunk(read.toLong())
                    }
                    total
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 30_000
        const val READ_TIMEOUT_MS = 30_000
        const val BUFFER_SIZE = 64 * 1024
    }
}
