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
import java.security.MessageDigest

/**
 * Owns the on-disk lifecycle of Whisper model artifacts.
 *
 * Downloads of *different* models run in parallel (they write independent
 * directories); concurrent requests for the *same* model await a single
 * in-flight download. Nothing about the remote files is pinned in code:
 * before a download the expected size and content hash are probed from the
 * server (HuggingFace exposes the SHA-256 of LFS files and the git-blob
 * SHA-1 of plain files via `X-Linked-ETag`), and every downloaded artifact
 * is verified against them — a truncated or corrupted file is deleted and
 * surfaced as an error. A `.verified` marker recording the accepted sizes
 * makes later re-validation cheap (no re-hashing of hundreds of MB).
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

    /** Directory holding [model]'s files. */
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
     * A model is valid when a `.verified` marker exists (written after a
     * successful download) and every artifact listed in it is still present
     * at exactly the accepted size.
     */
    private fun validate(descriptor: SttModelDescriptor): Boolean {
        val marker = File(modelDir(descriptor.model), VERIFIED_MARKER)
        if (!marker.isFile) return false
        val entries = marker.readLines().filter { it.isNotBlank() }
        if (entries.isEmpty()) return false
        return entries.all { entry ->
            val size = entry.split(' ').getOrNull(1)?.toLongOrNull() ?: return false
            val local = File(modelDir(descriptor.model), entry.substringBefore(' '))
            local.exists() && local.length() == size
        }
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
            // Probe size and hash of every artifact first: the exact total makes
            // download progress monotonic, and the hashes gate the files below.
            val metas = descriptor.files.associate { it to probe(it.url) }
            val total = metas.values.sumOf { it.size }
            require(total > 0) { "Could not determine the size of model ${model.id}" }
            var received = 0L
            var lastPercent = -1
            for (file in descriptor.files) {
                val dest = localFile(model, file).also { it.parentFile?.mkdirs() }
                downloadFile(
                    url = file.url,
                    dest = dest,
                    onChunk = { delta ->
                        received += delta
                        // Throttle to integer-percent changes to avoid thousands of emissions.
                        val percent = (received * 100 / total).toInt()
                        if (percent != lastPercent) {
                            lastPercent = percent
                            updateStatus(model) { SttModelStatus.Downloading((percent / 100f).coerceIn(0f, 1f)) }
                        }
                    },
                )
                val meta = metas.getValue(file)
                verify(dest, meta)?.let { reason ->
                    error("Downloaded ${model.id}/${file.localName} failed verification: $reason")
                }
            }
            writeVerifiedMarker(model, metas)
            updateStatus(model) { SttModelStatus.Ready }
        } catch (e: Exception) {
            modelDir(model).deleteRecursively()
            updateStatus(model) { SttModelStatus.NotDownloaded }
            throw e
        }
    }

    /** Server-provided expectations for one artifact. */
    private data class FileMeta(val size: Long, val etagHex: String?)

    /**
     * HEAD-probes [url] for its exact size and content hash. HuggingFace
     * answers LFS files (the ONNX models) with a SHA-256 `X-Linked-ETag` and
     * their exact size via `X-Linked-Size`. Plain files carry a git-blob
     * SHA-1 `X-Linked-ETag` on the first response, but its `Content-Length`
     * is the HTML redirect page — the exact size only shows when following
     * the redirect once, or by probing a single byte via a Range request.
     */
    private suspend fun probe(url: String): FileMeta = withContext(Dispatchers.IO) {
        val conn = open(url).apply { requestMethod = "HEAD" }
        try {
            val etag = conn.getHeaderField("X-Linked-ETag")?.trim('"', ' ')
            val linkedSize = conn.getHeaderField("X-Linked-Size")?.toLongOrNull()
            val size = linkedSize
                ?: conn.getHeaderField("Location")?.let { location ->
                    // Plain file: the redirect target knows the real size.
                    sizeOfRedirectTarget(location, base = url)
                }
                ?: probeSizeWithRange(url)
            require(size != null && size > 0) { "Could not probe the size of $url" }
            FileMeta(size, etag)
        } finally {
            conn.disconnect()
        }
    }

    /** Follows [location] once and returns the `Content-Length` of the target. */
    private fun sizeOfRedirectTarget(location: String, base: String): Long? {
        val second = open(location, base = base).apply { requestMethod = "HEAD" }
        return try {
            second.getHeaderField("Content-Length")?.toLongOrNull()?.takeIf { it > 0 }
        } finally {
            second.disconnect()
        }
    }

    /**
     * Fallback size probe: a 1-byte Range GET reports the full length in
     * `Content-Range` (`bytes 0-0/816730`) without transferring the file.
     */
    private fun probeSizeWithRange(url: String): Long? {
        val conn = open(url, followRedirects = true).apply {
            requestMethod = "GET"
            setRequestProperty("Range", "bytes=0-0")
        }
        return try {
            conn.getHeaderField("Content-Range")
                ?.substringAfterLast('/')
                ?.toLongOrNull()
                ?.takeIf { it > 0 }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Returns `null` when [dest] matches [meta], otherwise the reason. The
     * size must match exactly; the hash comparison depends on what the server
     * exposed (SHA-256 of the content for LFS files, git-blob SHA-1 for plain
     * files; no hash check when no usable ETag was served).
     */
    private suspend fun verify(dest: File, meta: FileMeta): String? = withContext(Dispatchers.IO) {
        if (dest.length() != meta.size) return@withContext "expected ${meta.size} bytes, got ${dest.length()}"
        val etag = meta.etagHex?.lowercase() ?: return@withContext null
        when (etag.length) {
            SHA256_HEX_LENGTH -> if (hashFile(dest, "SHA-256", prefix = null) == etag) null else "SHA-256 mismatch"
            SHA1_HEX_LENGTH -> if (hashFile(dest, "SHA-1", prefix = "blob ${dest.length()}\u0000") == etag) null else "git-blob SHA-1 mismatch"
            else -> null.also { Timber.w("Ignoring unsupported ETag for %s", dest.name) }
        }
    }

    /** Hex digest of [file], optionally prefixed (git blob header). */
    private fun hashFile(file: File, algorithm: String, prefix: String?): String {
        val digest = MessageDigest.getInstance(algorithm)
        prefix?.toByteArray(Charsets.US_ASCII)?.let { digest.update(it) }
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Records the accepted artifacts so later re-validation needs no re-hashing. */
    private fun writeVerifiedMarker(model: SttModel, metas: Map<SttModelFile, FileMeta>) {
        val lines = metas.entries.sortedBy { it.key.localName }.joinToString("\n") { (file, meta) ->
            "${file.localName} ${meta.size} ${meta.etagHex ?: "-"}"
        }
        File(modelDir(model), VERIFIED_MARKER).writeText(lines)
    }

    private fun updateStatus(model: SttModel, transform: (SttModelStatus) -> SttModelStatus) {
        mutableStatus.update { current ->
            current.toMutableMap().apply { put(model, transform(current[model] ?: SttModelStatus.NotDownloaded)) }
        }
    }

    private fun open(url: String, base: String? = null, followRedirects: Boolean = false): HttpURLConnection {
        val resolved = if (base != null && !url.startsWith("http")) URL(URL(base), url) else URL(url)
        return (resolved.openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = followRedirects
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
        val conn = open(url, followRedirects = true).apply { requestMethod = "GET" }
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
        const val VERIFIED_MARKER = ".verified"
        const val SHA256_HEX_LENGTH = 64
        const val SHA1_HEX_LENGTH = 40
    }
}
