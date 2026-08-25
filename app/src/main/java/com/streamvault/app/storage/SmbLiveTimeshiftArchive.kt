package com.streamvault.app.storage

import com.streamvault.data.storage.SmbStorageClient
import com.streamvault.data.storage.SmbStorageProfile
import com.streamvault.data.storage.SmbStorageProfileStore
import com.streamvault.domain.model.StreamType
import com.streamvault.player.timeshift.ArchivedTimeshiftSnapshot
import com.streamvault.player.timeshift.LiveTimeshiftArchive
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

/** SMB durability adapter used by StreamVault's existing live-timeshift sessions. */
@Singleton
class SmbLiveTimeshiftArchive @Inject constructor(
    private val profileStore: SmbStorageProfileStore,
    private val smbClient: SmbStorageClient
) : LiveTimeshiftArchive {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessionProfiles = ConcurrentHashMap<String, SmbStorageProfile>()
    private val sessionTypes = ConcurrentHashMap<String, StreamType>()
    private val pendingUploads = ConcurrentHashMap<String, MutableSet<Job>>()

    override suspend fun startSession(sessionId: String, streamType: StreamType) {
        val profile = profileStore.enabledProfile() ?: return
        sessionProfiles[sessionId] = profile
        sessionTypes[sessionId] = streamType
    }

    override fun archiveSegment(
        sessionId: String,
        source: File,
        durationMs: Long,
        capturedAtMs: Long
    ) {
        val profile = sessionProfiles[sessionId] ?: return
        if (!source.isFile) return
        val safeSourceName = source.name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val archiveName = "${capturedAtMs}_${durationMs.coerceAtLeast(0L)}_$safeSourceName"
        val job = scope.launch {
            smbClient.uploadFile(
                profile = profile,
                folder = "${profile.timeshiftFolder.trim('/')}/$sessionId",
                relativePath = archiveName,
                source = source
            )
        }
        val jobs = pendingUploads.computeIfAbsent(sessionId) { ConcurrentHashMap.newKeySet() }
        jobs += job
        job.invokeOnCompletion { jobs -= job }
    }

    override suspend fun restoreSince(
        sessionId: String,
        pausedAtMs: Long,
        targetDirectory: File
    ): ArchivedTimeshiftSnapshot? {
        val profile = sessionProfiles[sessionId] ?: return null
        val streamType = sessionTypes[sessionId] ?: return null
        if (streamType == StreamType.DASH || streamType == StreamType.SMOOTH_STREAMING) return null

        pendingUploads[sessionId]?.toList()?.joinAll()
        val remoteFolder = "${profile.timeshiftFolder.trim('/')}/$sessionId"
        val archived = smbClient.listFiles(profile, remoteFolder)
            .mapNotNull { remote -> parseArchivedSegment(remote.name) }
            .filter { segment ->
                segment.durationMs > 0L && segment.capturedAtMs >= pausedAtMs - segment.durationMs
            }
            .sortedBy(ArchivedSegment::capturedAtMs)
        if (archived.isEmpty()) return null

        targetDirectory.mkdirs()
        val restored = archived.mapIndexed { index, segment ->
            val extension = segment.originalName.substringAfterLast('.', "ts")
            val target = File(targetDirectory, "segment-$index.$extension")
            smbClient.downloadFile(profile, remoteFolder, segment.archiveName, target)
            segment to target
        }
        val totalDurationMs = archived.sumOf(ArchivedSegment::durationMs)
        val snapshotFile = if (streamType == StreamType.HLS) {
            val targetDurationSeconds = archived.maxOf { ((it.durationMs + 999L) / 1000L).coerceAtLeast(1L) }
            File(targetDirectory, "index.m3u8").also { playlist ->
                playlist.writeText(buildString {
                    appendLine("#EXTM3U")
                    appendLine("#EXT-X-VERSION:3")
                    appendLine("#EXT-X-TARGETDURATION:$targetDurationSeconds")
                    appendLine("#EXT-X-MEDIA-SEQUENCE:0")
                    restored.forEach { (segment, file) ->
                        appendLine("#EXTINF:${"%.3f".format(java.util.Locale.US, segment.durationMs / 1000.0)},")
                        appendLine(file.name)
                    }
                    appendLine("#EXT-X-ENDLIST")
                })
            }
        } else {
            File(targetDirectory, "buffer.ts").also { outputFile ->
                outputFile.outputStream().buffered(256 * 1024).use { output ->
                    restored.forEach { (_, file) -> file.inputStream().buffered().use { it.copyTo(output, 256 * 1024) } }
                }
            }
        }
        return ArchivedTimeshiftSnapshot(snapshotFile, totalDurationMs)
    }

    override fun endSession(sessionId: String) {
        pendingUploads.remove(sessionId)
        sessionProfiles.remove(sessionId)
        sessionTypes.remove(sessionId)
    }

    private fun parseArchivedSegment(name: String): ArchivedSegment? {
        val first = name.indexOf('_')
        val second = name.indexOf('_', first + 1)
        if (first <= 0 || second <= first + 1 || second >= name.lastIndex) return null
        return ArchivedSegment(
            archiveName = name,
            capturedAtMs = name.substring(0, first).toLongOrNull() ?: return null,
            durationMs = name.substring(first + 1, second).toLongOrNull() ?: return null,
            originalName = name.substring(second + 1)
        )
    }

    private data class ArchivedSegment(
        val archiveName: String,
        val capturedAtMs: Long,
        val durationMs: Long,
        val originalName: String
    )
}

@Module
@InstallIn(SingletonComponent::class)
abstract class LiveTimeshiftArchiveModule {
    @Binds
    abstract fun bindLiveTimeshiftArchive(implementation: SmbLiveTimeshiftArchive): LiveTimeshiftArchive
}
