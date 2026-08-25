package com.streamvault.data.storage

import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import jcifs.smb.SmbFileOutputStream
import jcifs.smb.SmbFileInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.util.Properties
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SmbStorageClient @Inject constructor() {
    init {
        SmbCryptoProvider.ensureInitialized()
    }

    suspend fun test(profile: SmbStorageProfile): SmbStorageTestResult = withContext(Dispatchers.IO) {
        val valid = profile.normalizedAndValidated()
        val context = createContext(valid)
        val folders = listOf(valid.timeshiftFolder, valid.recordingsFolder).distinct()
        try {
            folders.forEach { folder -> testWritableFolder(valid, context, folder) }
            SmbStorageTestResult(true, "Connected and verified write access to ${folders.size} folder(s).")
        } catch (error: Exception) {
            SmbStorageTestResult(false, sanitizeError(error))
        } finally {
            runCatching { context.close() }
        }
    }

    fun openOutput(profile: SmbStorageProfile, folder: String, fileName: String, append: Boolean): OutputStream {
        val valid = profile.normalizedAndValidated()
        val context = createContext(valid)
        return try {
            val directory = SmbFile("${valid.maskedLocation}${folder.trim('/')}/", context)
            if (!directory.exists()) directory.mkdirs()
            val remote = SmbFile(directory, fileName)
            val delegate = SmbFileOutputStream(remote, append)
            object : FilterOutputStream(delegate) {
                override fun close() {
                    try { super.close() } finally {
                        runCatching { remote.close() }
                        runCatching { directory.close() }
                        runCatching { context.close() }
                    }
                }
            }.buffered(256 * 1024)
        } catch (error: Throwable) {
            runCatching { context.close() }
            throw error
        }
    }

    fun delete(profile: SmbStorageProfile, folder: String, fileName: String) {
        val context = createContext(profile)
        try {
            SmbFile("${profile.maskedLocation}${folder.trim('/')}/${fileName.trim('/')}", context).use {
                if (it.exists()) it.delete()
            }
        } finally {
            runCatching { context.close() }
        }
    }

    fun exists(profile: SmbStorageProfile, folder: String, fileName: String): Boolean {
        val valid = profile.normalizedAndValidated()
        val context = createContext(valid)
        return try {
            SmbFile("${valid.maskedLocation}${folder.trim('/')}/${fileName.trim('/')}", context).use {
                it.exists()
            }
        } finally {
            runCatching { context.close() }
        }
    }

    suspend fun uploadFile(profile: SmbStorageProfile, folder: String, relativePath: String, source: File) =
        withContext(Dispatchers.IO) {
            require(source.isFile) { "SMB upload source is not a file" }
            val safe = relativePath.replace('\\', '/').split('/').filter(String::isNotBlank)
            require(safe.isNotEmpty() && safe.none { it == "." || it == ".." }) { "Invalid SMB relative path" }
            val parent = safe.dropLast(1).joinToString("/")
            val targetFolder = listOf(folder.trim('/'), parent).filter(String::isNotBlank).joinToString("/")
            openOutput(profile, targetFolder, safe.last(), append = false).use { output ->
                source.inputStream().buffered().use { input -> input.copyTo(output, 256 * 1024) }
            }
        }

    suspend fun listFiles(profile: SmbStorageProfile, folder: String): List<SmbRemoteFile> =
        withContext(Dispatchers.IO) {
            val valid = profile.normalizedAndValidated()
            val context = createContext(valid)
            try {
                SmbFile("${valid.maskedLocation}${folder.trim('/')}/", context).use { directory ->
                    if (!directory.exists()) return@withContext emptyList()
                    directory.listFiles().orEmpty()
                        .filterNot { it.isDirectory }
                        .map { remote ->
                            remote.use {
                                SmbRemoteFile(
                                    name = it.name.trimEnd('/'),
                                    size = it.length(),
                                    modifiedAtMs = it.lastModified()
                                )
                            }
                        }
                }
            } finally {
                runCatching { context.close() }
            }
        }

    suspend fun downloadFile(
        profile: SmbStorageProfile,
        folder: String,
        fileName: String,
        target: File
    ) = withContext(Dispatchers.IO) {
        val valid = profile.normalizedAndValidated()
        require(fileName.isNotBlank() && '/' !in fileName && fileName != "." && fileName != "..") {
            "Invalid SMB file name"
        }
        val context = createContext(valid)
        try {
            target.parentFile?.mkdirs()
            SmbFile("${valid.maskedLocation}${folder.trim('/')}/$fileName", context).use { remote ->
                require(remote.exists() && !remote.isDirectory) { "SMB timeshift segment is unavailable" }
                SmbFileInputStream(remote).use { input ->
                    target.outputStream().buffered(256 * 1024).use { output ->
                        input.copyTo(output, 256 * 1024)
                    }
                }
            }
        } finally {
            runCatching { context.close() }
        }
    }

    private fun createContext(profile: SmbStorageProfile): CIFSContext {
        val properties = Properties().apply {
            setProperty("jcifs.smb.client.minVersion", "SMB202")
            // jCIFS 2.1.x only has partial SMB3 support. SMB 2.1 avoids its Android
            // session-finalization failures while retaining signing and large I/O.
            setProperty("jcifs.smb.client.maxVersion", "SMB210")
            setProperty("jcifs.smb.client.responseTimeout", "10000")
            setProperty("jcifs.smb.client.soTimeout", "15000")
        }
        val base = BaseContext(PropertyConfiguration(properties))
        return base.withCredentials(NtlmPasswordAuthenticator(profile.domain, profile.username, profile.password))
    }

    private fun testWritableFolder(profile: SmbStorageProfile, context: CIFSContext, folder: String) {
        SmbFile("${profile.maskedLocation}${folder.trim('/')}/", context).use { directory ->
            if (!directory.exists()) directory.mkdirs()
            require(directory.isDirectory) { "The configured $folder path is not a folder" }
            SmbFile(directory, ".streamvault-test-${UUID.randomUUID()}").use { file ->
                try {
                    SmbFileOutputStream(file).use { it.write(TEST_BYTES) }
                    require(file.length() == TEST_BYTES.size.toLong()) { "SMB write verification failed" }
                } finally { runCatching { if (file.exists()) file.delete() } }
            }
        }
    }

    private fun sanitizeError(error: Exception): String = generateSequence<Throwable>(error) { it.cause }
        .mapNotNull { cause -> cause.message }
        .filter(String::isNotBlank)
        .distinct()
        .take(4)
        .joinToString(": ")
        .replace(Regex("(?i)(password|passwd|pwd)=[^&\\s]+"), "$1=<redacted>")
        .replace(Regex("smb://[^@/\\s]+@"), "smb://<redacted>@")
        .take(320)
        .takeIf(String::isNotBlank) ?: "SMB connection failed"

    companion object { private val TEST_BYTES = "StreamVault SMB test".encodeToByteArray() }
}

data class SmbStorageTestResult(val success: Boolean, val message: String)

data class SmbRemoteFile(val name: String, val size: Long, val modifiedAtMs: Long)
