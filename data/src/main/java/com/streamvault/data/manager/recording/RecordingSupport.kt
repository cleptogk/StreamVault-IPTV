package com.streamvault.data.manager.recording

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import androidx.documentfile.provider.DocumentFile
import com.streamvault.data.local.entity.RecordingRunEntity
import com.streamvault.data.local.entity.RecordingRunWithSchedule
import com.streamvault.data.local.entity.RecordingStorageEntity
import com.streamvault.data.storage.SmbStorageClient
import com.streamvault.data.storage.SmbStorageProfileStore
import com.streamvault.domain.model.RecordingFailureCategory
import com.streamvault.domain.model.RecordingItem
import com.streamvault.domain.model.RecordingStorageConfig
import com.streamvault.domain.model.RecordingStorageState
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject

private const val DEFAULT_RECORDINGS_DIR_NAME = "recordings"

internal fun RecordingRunWithSchedule.toDomain(): RecordingItem = RecordingItem(
    id = id,
    scheduleId = scheduleId,
    providerId = providerId,
    channelId = channelId,
    channelName = channelName,
    streamUrl = streamUrl,
    scheduledStartMs = scheduledStartMs,
    scheduledEndMs = scheduledEndMs,
    programTitle = programTitle,
    outputPath = outputDisplayPath,
    outputUri = outputUri,
    outputDisplayPath = outputDisplayPath,
    recurrence = recurrence,
    recurringRuleId = recurringRuleId,
    status = status,
    sourceType = sourceType,
    bytesWritten = bytesWritten,
    averageThroughputBytesPerSecond = averageThroughputBytesPerSecond,
    retryCount = retryCount,
    lastProgressAtMs = lastProgressAtMs,
    failureCategory = failureCategory,
    scheduleEnabled = scheduleEnabled,
    exactAlarmArmed = exactAlarmArmed,
    priority = priority,
    failureReason = failureReason,
    terminalAtMs = terminalAtMs
)

internal fun RecordingStorageEntity.toDomain(): RecordingStorageState = RecordingStorageState(
    treeUri = treeUri,
    displayName = displayName,
    // When no SAF tree is selected, outputDirectory holds the resolved local path (default
    // internal dir or a chosen USB folder), so it doubles as the active localDirectory.
    localDirectory = if (treeUri.isNullOrBlank()) outputDirectory else null,
    outputDirectory = outputDirectory,
    availableBytes = availableBytes,
    isWritable = isWritable,
    fileNamePattern = fileNamePattern,
    retentionDays = retentionDays,
    maxSimultaneousRecordings = maxSimultaneousRecordings
)

internal fun RecordingStorageConfig.toEntity(existing: RecordingStorageEntity?, outputDirectory: String?, availableBytes: Long?, isWritable: Boolean): RecordingStorageEntity =
    RecordingStorageEntity(
        id = existing?.id ?: 1L,
        treeUri = treeUri,
        displayName = displayName,
        outputDirectory = outputDirectory,
        availableBytes = availableBytes,
        isWritable = isWritable,
        fileNamePattern = fileNamePattern,
        retentionDays = retentionDays,
        maxSimultaneousRecordings = maxSimultaneousRecordings,
        updatedAt = System.currentTimeMillis()
    )

internal fun headersToJson(headers: Map<String, String>): String {
    val obj = JSONObject()
    headers.forEach { (k, v) -> obj.put(k, v) }
    return obj.toString() ?: "{}"
}

internal fun headersFromJson(raw: String?): Map<String, String> {
    if (raw.isNullOrBlank()) return emptyMap()
    return runCatching {
        val obj = JSONObject(raw)
        buildMap { obj.keys().forEach { key -> put(key, obj.getString(key)) } }
    }.getOrDefault(emptyMap())
}

internal fun inferFailureCategory(error: Throwable?, fallback: RecordingFailureCategory = RecordingFailureCategory.UNKNOWN): RecordingFailureCategory {
    if (error is UnsupportedRecordingException) return error.category
    val rootCause = error?.cause ?: error
    when (rootCause) {
        is java.net.SocketTimeoutException,
        is java.net.SocketException,
        is java.net.UnknownHostException,
        is java.net.ConnectException -> return RecordingFailureCategory.NETWORK
        is javax.net.ssl.SSLException -> return RecordingFailureCategory.NETWORK
    }
    val normalized = error?.message.orEmpty().lowercase(Locale.ROOT)
    return when {
        normalized.isBlank() -> fallback
        "drm" in normalized -> RecordingFailureCategory.DRM_UNSUPPORTED
        "storage" in normalized || "space" in normalized || "writable" in normalized -> RecordingFailureCategory.STORAGE
        "conflict" in normalized -> RecordingFailureCategory.SCHEDULE_CONFLICT
        "connection" in normalized || "http 401" in normalized || "http 403" in normalized || "forbidden" in normalized -> RecordingFailureCategory.AUTH
        "expired" in normalized || "token" in normalized -> RecordingFailureCategory.TOKEN_EXPIRED
        "unsupported" in normalized || "dash" in normalized -> RecordingFailureCategory.FORMAT_UNSUPPORTED
        "network" in normalized || "timeout" in normalized || "http" in normalized -> RecordingFailureCategory.NETWORK
        else -> fallback
    }
}

internal fun sanitizeRecordingFileName(
    channelName: String,
    programTitle: String?,
    startMs: Long,
    pattern: String
): String {
    val formatter = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US)
    val startLabel = formatter.format(Date(startMs))
    val safeChannel = channelName.replace(Regex("[^a-zA-Z0-9_ -]"), "_").trim().ifBlank { "Channel" }
    val safeProgram = programTitle
        ?.replace(Regex("[^a-zA-Z0-9_ -]"), "_")
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: "Program"
    val rendered = pattern
        .replace("ChannelName", safeChannel)
        .replace("ProgramTitle", safeProgram)
        .replace("yyyy-MM-dd_HH-mm", startLabel)
    return if (rendered.endsWith(".ts", ignoreCase = true)) rendered else "$rendered.ts"
}

internal fun resolveStorageDetails(
    context: Context,
    treeUriString: String?,
    localDirectory: String? = null,
    smbProfileStore: SmbStorageProfileStore? = null
): Triple<String?, Long?, Boolean> {
    if (treeUriString.isNullOrBlank()) {
        val recordingsDir = localDirectory
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.also { runCatching { it.mkdirs() } }
            ?: defaultRecordingDirectory(context)
        val available = runCatching { StatFs(recordingsDir.absolutePath).availableBytes }.getOrNull()
            ?.takeIf { it > 0L }
            ?: recordingsDir.usableSpace.takeIf { it > 0L }
        return Triple(recordingsDir.absolutePath, available, recordingsDir.canWrite())
    }

    val treeUri = runCatching { Uri.parse(treeUriString) }.getOrNull() ?: return Triple(null, null, false)
    if (treeUri.scheme == SMB_PROFILE_SCHEME) {
        val profile = treeUri.host?.let { smbProfileStore?.profile(it) }
        return Triple(profile?.maskedLocation?.plus(profile.recordingsFolder), null, profile != null)
    }
    val documentTree = DocumentFile.fromTreeUri(context, treeUri)
    val isWritable = documentTree?.canWrite() == true
    return Triple(documentTree?.name ?: treeUriString, null, isWritable)
}

sealed interface RecordingOutputTarget {
    data class FileTarget(val file: File) : RecordingOutputTarget
    data class DocumentTarget(val uri: Uri, val displayPath: String?) : RecordingOutputTarget
    class SmbTarget(
        val profileId: String,
        val folder: String,
        val fileName: String,
        val displayPath: String,
        val outputOpener: (Boolean) -> java.io.OutputStream
    ) : RecordingOutputTarget
}

internal fun createOutputTarget(
    context: Context,
    storage: RecordingStorageEntity,
    fileName: String,
    smbProfileStore: SmbStorageProfileStore,
    smbClient: SmbStorageClient
): RecordingOutputTarget {
    val treeUri = storage.treeUri
    if (treeUri.isNullOrBlank()) {
        val baseDirectory = storage.outputDirectory
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?: defaultRecordingDirectory(context)
        val outputFile = File(baseDirectory, fileName)
        outputFile.parentFile?.mkdirs()
        return RecordingOutputTarget.FileTarget(outputFile)
    }

    val uri = Uri.parse(treeUri)
    if (uri.scheme == SMB_PROFILE_SCHEME) {
        val profileId = requireNotNull(uri.host) { "SMB recording profile is invalid." }
        val profile = requireNotNull(smbProfileStore.profile(profileId)) { "SMB recording profile is unavailable." }
        val baseName = fileName.removeSuffix(".ts")
        var candidateName = fileName
        var counter = 1
        while (smbClient.exists(profile, profile.recordingsFolder, candidateName)) {
            candidateName = "${baseName}_$counter.ts"
            counter++
        }
        return smbOutputTarget(profileId, profile.recordingsFolder, candidateName, smbProfileStore, smbClient)
    }
    val documentTree = DocumentFile.fromTreeUri(context, uri)
        ?: throw IllegalStateException("Recording folder is unavailable.")
    val baseName = fileName.removeSuffix(".ts")
    var candidateName = fileName
    var counter = 1
    while (documentTree.findFile(candidateName) != null) {
        candidateName = "${baseName}_$counter.ts"
        counter++
    }
    val document = documentTree.createFile("video/mp2t", candidateName.removeSuffix(".ts"))
    requireNotNull(document?.uri) { "Failed to create recording file." }
    return RecordingOutputTarget.DocumentTarget(document.uri, "${documentTree.name ?: "Recordings"}/$candidateName")
}

internal fun deleteOutputTarget(
    context: Context,
    outputUri: String?,
    outputPath: String?,
    smbProfileStore: SmbStorageProfileStore,
    smbClient: SmbStorageClient
) {
    outputUri?.let { rawUri ->
        val uri = runCatching { Uri.parse(rawUri) }.getOrNull()
        if (uri?.scheme == SMB_FILE_SCHEME) {
            parseSmbFileUri(uri)?.let { (profileId, folder, fileName) ->
                smbProfileStore.profile(profileId)?.let { profile ->
                    runCatching { smbClient.delete(profile, folder, fileName) }
                }
            }
            return@let
        }
        runCatching {
            val document = DocumentFile.fromSingleUri(context, uri ?: Uri.parse(rawUri))
            if (document?.exists() == true) {
                document.delete()
            }
        }
    }
    outputPath?.takeIf { it.isNotBlank() }?.let { path ->
        runCatching {
            val file = File(path)
            if (file.exists()) {
                file.delete()
            }
        }
    }
}

internal fun RecordingOutputTarget.asPersistenceValues(): Pair<String?, String?> = when (this) {
    is RecordingOutputTarget.FileTarget -> null to file.absolutePath
    is RecordingOutputTarget.DocumentTarget -> uri.toString() to displayPath
    is RecordingOutputTarget.SmbTarget -> Uri.Builder()
        .scheme(SMB_FILE_SCHEME)
        .authority(profileId)
        .appendPath(folder)
        .appendPath(fileName)
        .build()
        .toString() to displayPath
}

internal fun RecordingOutputTarget.openOutputStream(contentResolver: ContentResolver, append: Boolean = false) = when (this) {
    is RecordingOutputTarget.FileTarget -> FileOutputStream(file, append).buffered()
    is RecordingOutputTarget.DocumentTarget -> contentResolver.openOutputStream(uri, if (append) "wa" else "w")
    is RecordingOutputTarget.SmbTarget -> outputOpener(append)
}

internal fun existingSmbOutputTarget(
    rawUri: String,
    displayPath: String?,
    smbProfileStore: SmbStorageProfileStore,
    smbClient: SmbStorageClient
): RecordingOutputTarget.SmbTarget? {
    val uri = runCatching { Uri.parse(rawUri) }.getOrNull()?.takeIf { it.scheme == SMB_FILE_SCHEME } ?: return null
    val (profileId, folder, fileName) = parseSmbFileUri(uri) ?: return null
    return smbOutputTarget(profileId, folder, fileName, smbProfileStore, smbClient, displayPath)
}

private fun smbOutputTarget(
    profileId: String,
    folder: String,
    fileName: String,
    smbProfileStore: SmbStorageProfileStore,
    smbClient: SmbStorageClient,
    persistedDisplayPath: String? = null
): RecordingOutputTarget.SmbTarget {
    val profile = requireNotNull(smbProfileStore.profile(profileId)) { "SMB recording profile is unavailable." }
    val displayPath = persistedDisplayPath ?: "${profile.maskedLocation}${folder.trim('/')}/$fileName"
    return RecordingOutputTarget.SmbTarget(profileId, folder, fileName, displayPath) { append ->
        val currentProfile = requireNotNull(smbProfileStore.profile(profileId)) { "SMB recording profile is unavailable." }
        smbClient.openOutput(currentProfile, folder, fileName, append)
    }
}

private fun parseSmbFileUri(uri: Uri): Triple<String, String, String>? {
    val profileId = uri.host?.takeIf(String::isNotBlank) ?: return null
    val segments = uri.pathSegments
    if (segments.size != 2 || segments.any(String::isBlank)) return null
    return Triple(profileId, segments[0], segments[1])
}

fun smbRecordingStorageUri(profileId: String): String = Uri.Builder()
    .scheme(SMB_PROFILE_SCHEME)
    .authority(profileId)
    .build()
    .toString()

private const val SMB_PROFILE_SCHEME = "smb-profile"
private const val SMB_FILE_SCHEME = "smb-file"

private fun defaultRecordingDirectory(context: Context): File {
    val externalAppMoviesDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
        ?.let { File(it, DEFAULT_RECORDINGS_DIR_NAME) }
    val targetDir = externalAppMoviesDir ?: File(context.filesDir, DEFAULT_RECORDINGS_DIR_NAME)
    if (!targetDir.exists()) {
        targetDir.mkdirs()
    }
    return targetDir
}
