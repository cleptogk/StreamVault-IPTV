package com.streamvault.data.storage

import jcifs.util.Crypto
import org.bouncycastle.crypto.digests.MD4Digest
import java.security.MessageDigestSpi
import java.security.Provider

/**
 * Android ships a reduced provider named BC that omits MD4. jCIFS asks its provider for MD4
 * to calculate the NTLM password hash, so give jCIFS a private provider instead of changing
 * Android's process-wide provider list.
 */
internal object SmbCryptoProvider {
    @Volatile
    private var initialized = false

    fun ensureInitialized() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            Crypto.initProvider(StreamVaultSmbProvider())
            Crypto.getMD4()
            initialized = true
        }
    }
}

private class StreamVaultSmbProvider : Provider(
    "StreamVaultSMB",
    1.0,
    "StreamVault private cryptography provider for SMB authentication"
) {
    init {
        put("MessageDigest.MD4", SmbMd4MessageDigest::class.java.name)
    }
}

/** MessageDigest adapter around Bouncy Castle's lightweight MD4 implementation. */
class SmbMd4MessageDigest public constructor() : MessageDigestSpi() {
    private val delegate = MD4Digest()

    override fun engineUpdate(input: Byte) = delegate.update(input)

    override fun engineUpdate(input: ByteArray, offset: Int, len: Int) =
        delegate.update(input, offset, len)

    override fun engineDigest(): ByteArray = ByteArray(delegate.digestSize).also { output ->
        delegate.doFinal(output, 0)
    }

    override fun engineReset() = delegate.reset()

    override fun engineGetDigestLength(): Int = delegate.digestSize
}
