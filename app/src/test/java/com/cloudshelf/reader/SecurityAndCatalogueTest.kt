package com.cloudshelf.reader

import com.cloudshelf.reader.core.security.ChunkedPdfCryptoEngine
import com.cloudshelf.reader.core.security.MasterKeyWrapper
import com.cloudshelf.reader.core.security.WrappedKey
import com.cloudshelf.reader.data.remote.CatalogueValidator
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class SecurityAndCatalogueTest {

    private class InMemoryMasterKeyWrapper(rawKey: ByteArray) : MasterKeyWrapper {
        private val masterKey = SecretKeySpec(rawKey, "AES")
        override fun wrapDek(dekBytes: ByteArray): WrappedKey {
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, masterKey, GCMParameterSpec(128, iv))
            return WrappedKey(iv, cipher.doFinal(dekBytes))
        }

        override fun unwrapDek(wrapped: WrappedKey): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, masterKey, GCMParameterSpec(128, wrapped.iv))
            return cipher.doFinal(wrapped.ciphertext)
        }
    }

    @Test
    fun chunkedEncryptionRoundTripAndTamperRejection() {
        val wrapper = InMemoryMasterKeyWrapper(ByteArray(32) { 0x2A })
        val engine = ChunkedPdfCryptoEngine(wrapper, chunkSize = 1024)
        val plaintext = ("%PDF-1.7\n" + "Simulated PDF content block. ".repeat(200)).toByteArray()

        val encryptedOut = ByteArrayOutputStream()
        engine.encryptStream("book-001", plaintext.size.toLong(), ByteArrayInputStream(plaintext), encryptedOut)
        val cipherBytes = encryptedOut.toByteArray()

        // 1. Verify ciphertext is not plaintext PDF
        assertFalse(cipherBytes.decodeToString().contains("%PDF-1.7"))

        // 2. Verify valid decryption
        val decryptedOut = ByteArrayOutputStream()
        engine.decryptStream("book-001", ByteArrayInputStream(cipherBytes), decryptedOut)
        assertArrayEquals(plaintext, decryptedOut.toByteArray())

        // 3. Wrong bookId (AAD mismatch) must fail authentication
        assertThrows(GeneralSecurityException::class.java) {
            engine.decryptStream("book-002", ByteArrayInputStream(cipherBytes), ByteArrayOutputStream())
        }

        // 4. Bit-flip tampering in ciphertext must fail authentication
        val tampered = cipherBytes.copyOf()
        tampered[tampered.size - 10] = (tampered[tampered.size - 10].toInt() xor 0xFF).toByte()
        assertThrows(GeneralSecurityException::class.java) {
            engine.decryptStream("book-001", ByteArrayInputStream(tampered), ByteArrayOutputStream())
        }

        // 5. Truncated file must fail authentication
        val truncated = cipherBytes.copyOf(cipherBytes.size - 25)
        assertThrows(GeneralSecurityException::class.java) {
            engine.decryptStream("book-001", ByteArrayInputStream(truncated), ByteArrayOutputStream())
        }
    }

    @Test
    fun catalogueValidationRejectsUnsafeUrlsAndPreservesValidEntries() {
        val sampleJson = """
            {
              "schemaVersion": 1,
              "catalogueVersion": "2026.11.19.1",
              "categories": [
                { "id": "science", "name": "Science" }
              ],
              "books": [
                {
                  "id": "valid-book",
                  "title": "Clean Architecture",
                  "authors": ["Author A"],
                  "categoryIds": ["science"],
                  "releaseTag": "1.1.2",
                  "assetName": "clean-arch.pdf",
                  "downloadUrl": "https://github.com",
                  "fileSize": 1048576,
                  "sha256": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
                },
                {
                  "id": "evil-book",
                  "title": "Untrusted Host",
                  "releaseTag": "1.1.2",
                  "assetName": "../evil.pdf",
                  "downloadUrl": "http://example.com",
                  "fileSize": 1024,
                  "sha256": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
                }
              ]
            }
        """.trimIndent()

        val result = CatalogueValidator().parseAndValidate(sampleJson).getOrThrow()
        assertEquals(1, result.validBooks.size)
        assertEquals("valid-book", result.validBooks.first().id)
        assertTrue(result.rejectedReasons.any { it.contains("evil-book") })
    }
}
