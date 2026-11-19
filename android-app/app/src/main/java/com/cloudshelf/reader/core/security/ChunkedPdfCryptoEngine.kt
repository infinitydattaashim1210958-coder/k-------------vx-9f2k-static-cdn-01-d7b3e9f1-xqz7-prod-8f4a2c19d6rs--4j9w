package com.cloudshelf.reader.core.security

import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Streaming AES-256-GCM chunked file encryption ("CSPDF1" format).
 * Decouples KeyWrapper (AndroidKeyStore in production, software key in JVM unit tests)
 * so cryptographic invariants can be rigorously tested on JVM without an emulator.
 */
interface MasterKeyWrapper {
    fun wrapDek(dekBytes: ByteArray): WrappedKey
    fun unwrapDek(wrapped: WrappedKey): ByteArray
}

data class WrappedKey(val iv: ByteArray, val ciphertext: ByteArray)

class ChunkedPdfCryptoEngine(
    private val keyWrapper: MasterKeyWrapper,
    private val chunkSize: Int = DEFAULT_CHUNK_SIZE
) {
    companion object {
        val MAGIC = "CSPDF1".toByteArray(StandardCharsets.US_ASCII)
        const val VERSION: Byte = 1
        const val DEFAULT_CHUNK_SIZE = 64 * 1024 // 64 KB
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
        const val GCM_TAG_BYTES = 16
        const val DEK_BYTES = 32
    }

    private val secureRandom = SecureRandom()

    fun encryptStream(
        bookId: String,
        plaintextLength: Long,
        input: InputStream,
        output: OutputStream
    ) {
        val dek = generateDek()
        try {
            val wrappedDek = keyWrapper.wrapDek(dek.encoded)
            writeHeader(output, plaintextLength, wrappedDek)

            val buffer = ByteArray(chunkSize)
            var chunkIndex = 0L
            var totalRead = 0L

            while (true) {
                val read = readFully(input, buffer)
                if (read <= 0) break

                val iv = ByteArray(GCM_IV_BYTES).also { secureRandom.nextBytes(it) }
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, dek, GCMParameterSpec(GCM_TAG_BITS, iv))
                cipher.updateAAD(buildAad(bookId, chunkIndex))

                val encryptedChunk = cipher.doFinal(buffer, 0, read)
                val frameHeader = ByteBuffer.allocate(4 + GCM_IV_BYTES)
                    .putInt(encryptedChunk.size)
                    .put(iv)
                    .array()

                output.write(frameHeader)
                output.write(encryptedChunk)

                totalRead += read
                chunkIndex++
            }

            if (totalRead != plaintextLength) {
                throw GeneralSecurityException("Plaintext length mismatch: expected $plaintextLength, got $totalRead")
            }
            output.flush()
        } finally {
            dek.encoded?.fill(0)
        }
    }

    fun decryptStream(
        bookId: String,
        input: InputStream,
        output: OutputStream
    ): Long {
        val header = readHeader(input)
        val dekBytes = keyWrapper.unwrapDek(header.wrappedKey)
        val dek = SecretKeySpec(dekBytes, "AES")

        try {
            var chunkIndex = 0L
            var totalDecrypted = 0L
            val framePrefix = ByteArray(4 + GCM_IV_BYTES)

            while (true) {
                val prefixRead = readFully(input, framePrefix)
                if (prefixRead == 0) break
                if (prefixRead < framePrefix.size) {
                    throw GeneralSecurityException("Truncated chunk header at index $chunkIndex")
                }

                val buf = ByteBuffer.wrap(framePrefix)
                val cipherLen = buf.int
                if (cipherLen <= GCM_TAG_BYTES || cipherLen > chunkSize + GCM_TAG_BYTES) {
                    throw GeneralSecurityException("Invalid chunk length $cipherLen at index $chunkIndex")
                }
                val iv = ByteArray(GCM_IV_BYTES).also { buf.get(it) }

                val cipherBytes = ByteArray(cipherLen)
                if (readFully(input, cipherBytes) != cipherLen) {
                    throw GeneralSecurityException("Truncated chunk payload at index $chunkIndex")
                }

                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, dek, GCMParameterSpec(GCM_TAG_BITS, iv))
                cipher.updateAAD(buildAad(bookId, chunkIndex))

                val plainChunk = cipher.doFinal(cipherBytes)
                output.write(plainChunk)

                totalDecrypted += plainChunk.size
                chunkIndex++
            }

            if (totalDecrypted != header.plaintextLength) {
                throw GeneralSecurityException(
                    "Truncated or altered file: expected ${header.plaintextLength} bytes, verified $totalDecrypted"
                )
            }
            output.flush()
            return totalDecrypted
        } finally {
            dekBytes.fill(0)
        }
    }

    private data class ParsedHeader(val plaintextLength: Long, val wrappedKey: WrappedKey)

    private fun writeHeader(out: OutputStream, plaintextLength: Long, wrapped: WrappedKey) {
        val fixed = ByteBuffer.allocate(MAGIC.size + 1 + 4 + 8 + 4 + wrapped.iv.size + 4 + wrapped.ciphertext.size)
        fixed.put(MAGIC)
        fixed.put(VERSION)
        fixed.putInt(chunkSize)
        fixed.putLong(plaintextLength)
        fixed.putInt(wrapped.iv.size)
        fixed.put(wrapped.iv)
        fixed.putInt(wrapped.ciphertext.size)
        fixed.put(wrapped.ciphertext)
        out.write(fixed.array())
    }

    private fun readHeader(input: InputStream): ParsedHeader {
        val magic = ByteArray(MAGIC.size)
        if (readFully(input, magic) != MAGIC.size || !magic.contentEquals(MAGIC)) {
            throw GeneralSecurityException("Invalid CSPDF1 file magic")
        }
        val meta = ByteArray(1 + 4 + 8 + 4)
        if (readFully(input, meta) != meta.size) {
            throw GeneralSecurityException("Truncated CSPDF1 header")
        }
        val buf = ByteBuffer.wrap(meta)
        val version = buf.get()
        if (version != VERSION) {
            throw GeneralSecurityException("Unsupported CSPDF1 version: $version")
        }
        buf.int // chunkSize
        val plaintextLength = buf.long
        val ivLen = buf.int
        if (ivLen !in 12..32) throw GeneralSecurityException("Invalid wrapped key IV length")

        val iv = ByteArray(ivLen)
        if (readFully(input, iv) != ivLen) throw GeneralSecurityException("Truncated wrapped key IV")

        val ctLenBuf = ByteArray(4)
        if (readFully(input, ctLenBuf) != 4) throw GeneralSecurityException("Truncated wrapped key length")
        val ctLen = ByteBuffer.wrap(ctLenBuf).int
        if (ctLen !in 32..128) throw GeneralSecurityException("Invalid wrapped key length")

        val ct = ByteArray(ctLen)
        if (readFully(input, ct) != ctLen) throw GeneralSecurityException("Truncated wrapped key")

        return ParsedHeader(plaintextLength, WrappedKey(iv, ct))
    }

    private fun buildAad(bookId: String, chunkIndex: Long): ByteArray {
        val idBytes = bookId.toByteArray(StandardCharsets.UTF_8)
        return ByteBuffer.allocate(idBytes.size + 8)
            .put(idBytes)
            .putLong(chunkIndex)
            .array()
    }

    private fun generateDek(): SecretKey {
        val gen = KeyGenerator.getInstance("AES")
        gen.init(DEK_BYTES * 8, secureRandom)
        return gen.generateKey()
    }

    private fun readFully(input: InputStream, dest: ByteArray): Int {
        var offset = 0
        while (offset < dest.size) {
            val read = input.read(dest, offset, dest.size - offset)
            if (read == -1) break
            offset += read
        }
        return offset
    }
}
