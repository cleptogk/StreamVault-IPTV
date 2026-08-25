package com.streamvault.player.timeshift

import com.streamvault.domain.model.StreamType
import java.io.File

/** Optional durable storage below the existing live-timeshift capture engine. */
interface LiveTimeshiftArchive {
    suspend fun startSession(sessionId: String, streamType: StreamType)

    /** Queues an immutable segment produced by the existing timeshift engine for archival. */
    fun archiveSegment(
        sessionId: String,
        source: File,
        durationMs: Long,
        capturedAtMs: Long
    )

    /** Restores the archived interval beginning at [pausedAtMs] into a locally seekable snapshot. */
    suspend fun restoreSince(
        sessionId: String,
        pausedAtMs: Long,
        targetDirectory: File
    ): ArchivedTimeshiftSnapshot?

    fun endSession(sessionId: String)
}

data class ArchivedTimeshiftSnapshot(
    val file: File,
    val durationMs: Long
)
