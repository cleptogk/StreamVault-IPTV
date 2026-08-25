package com.streamvault.player.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.streamvault.player.timeshift.PlaybackTimeshiftCaptureSink
import com.streamvault.player.timeshift.PlaybackTimeshiftSegmentCapture
import java.io.ByteArrayOutputStream
import java.io.IOException

@UnstableApi
internal class PlaybackTimeshiftDataSourceFactory(
    private val upstream: DataSource.Factory,
    private val sink: PlaybackTimeshiftCaptureSink
) : DataSource.Factory {
    override fun createDataSource(): DataSource = PlaybackTimeshiftDataSource(upstream.createDataSource(), sink)
}

/**
 * Tees Media3's existing HLS reads into the timeshift manager. It never performs network I/O,
 * so enabling rewind cannot consume an additional provider connection.
 */
@UnstableApi
private class PlaybackTimeshiftDataSource(
    private val upstream: DataSource,
    private val sink: PlaybackTimeshiftCaptureSink
) : DataSource {
    private var requestedUrl: String? = null
    private var playlistBuffer: ByteArrayOutputStream? = null
    private var segmentCapture: PlaybackTimeshiftSegmentCapture? = null
    private var expectedLength = C.LENGTH_UNSET.toLong()
    private var bytesRead = 0L
    private var reachedEnd = false

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        resetCapture(abort = true)
        return try {
            upstream.open(dataSpec).also { length ->
                expectedLength = length
                val actualUri = upstream.uri ?: dataSpec.uri
                val url = actualUri.toString()
                requestedUrl = url
                if (actualUri.scheme in setOf("http", "https")) {
                    if (isPlaylist(actualUri, upstream.responseHeaders)) {
                        playlistBuffer = ByteArrayOutputStream()
                    } else {
                        segmentCapture = sink.openSegment(url)
                    }
                }
            }
        } catch (t: Throwable) {
            resetCapture(abort = true)
            throw t
        }
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        return try {
            upstream.read(buffer, offset, length).also { count ->
                when {
                    count > 0 -> {
                        bytesRead += count
                        playlistBuffer?.takeIf { it.size() < MAX_PLAYLIST_BYTES }?.let { output ->
                            val allowed = minOf(count, MAX_PLAYLIST_BYTES - output.size())
                            if (allowed > 0) output.write(buffer, offset, allowed)
                        }
                        segmentCapture?.write(buffer, offset, count)
                    }
                    count == C.RESULT_END_OF_INPUT -> reachedEnd = true
                }
            }
        } catch (t: Throwable) {
            resetCapture(abort = true)
            throw t
        }
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    @Throws(IOException::class)
    override fun close() {
        var closeFailure: Throwable? = null
        try {
            upstream.close()
        } catch (t: Throwable) {
            closeFailure = t
        } finally {
            val complete = closeFailure == null &&
                (reachedEnd || (expectedLength >= 0L && bytesRead >= expectedLength))
            if (complete) {
                val url = requestedUrl
                val playlist = playlistBuffer
                if (url != null && playlist != null && playlist.size() < MAX_PLAYLIST_BYTES) {
                    sink.observePlaylist(url, playlist.toByteArray())
                }
                segmentCapture?.complete()
                resetCapture(abort = false)
            } else {
                resetCapture(abort = true)
            }
        }
        closeFailure?.let { throw it }
    }

    private fun resetCapture(abort: Boolean) {
        if (abort) segmentCapture?.abort()
        requestedUrl = null
        playlistBuffer = null
        segmentCapture = null
        expectedLength = C.LENGTH_UNSET.toLong()
        bytesRead = 0L
        reachedEnd = false
    }

    private fun isPlaylist(uri: Uri, headers: Map<String, List<String>>): Boolean {
        if (uri.path.orEmpty().lowercase().endsWith(".m3u8")) return true
        val contentType = headers.entries
            .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
            ?.value
            ?.firstOrNull()
            .orEmpty()
            .lowercase()
        return "mpegurl" in contentType || "m3u8" in contentType
    }

    private companion object {
        const val MAX_PLAYLIST_BYTES = 2 * 1024 * 1024
    }
}
