package com.streamvault.player.timeshift

/** Receives bytes already fetched by Media3 so timeshift never opens a second provider stream. */
interface PlaybackTimeshiftCaptureSink {
    fun observePlaylist(url: String, body: ByteArray)
    fun openSegment(url: String): PlaybackTimeshiftSegmentCapture?
}

interface PlaybackTimeshiftSegmentCapture {
    fun write(buffer: ByteArray, offset: Int, length: Int)
    fun complete()
    fun abort()
}
