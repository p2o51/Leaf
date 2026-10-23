package dev.chenwuyi.leaf.data.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface TokenVault {
    fun read(accountId: String): String?
    fun write(accountId: String, token: String)
}

/**
 * Ported from Sheaf: only the non-exportable AES key lives in Keystore; ciphertext is excluded from backup.
 * Each kind of secret gets its own [folder] and Keystore [alias] (Notion uses the defaults).
 */
class KeystoreTokenVault(
    context: Context,
    folder: String = "notion_credentials",
    private val alias: String = "leaf_notion_credentials_v1",
    private val unavailableMessage: String = "连接凭据不可用，请重新连接 Notion",
) : TokenVault {
    private val directory = File(context.applicationContext.noBackupFilesDir, folder)

    @Synchronized
    override fun write(accountId: String, token: String) {
        require(token.isNotBlank() && token.length <= 4096)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
        cipher.updateAAD(accountId.toByteArray(Charsets.UTF_8))
        val bytes = token.toByteArray(Charsets.UTF_8)
        val encrypted = try { require(bytes.size <= 4096); cipher.doFinal(bytes) } finally { bytes.fill(0) }
        if (!directory.exists() && !directory.mkdirs()) throw IOException("无法保存连接凭据")
        val file = file(accountId)
        val output = file.startWrite()
        try {
            output.write(byteArrayOf(1, cipher.iv.size.toByte()))
            output.write(cipher.iv)
            output.write(encrypted)
            file.finishWrite(output)
        } catch (failure: Exception) {
            file.failWrite(output)
            throw IOException("无法保存连接凭据", failure)
        }
    }

    @Synchronized
    override fun read(accountId: String): String? {
        val file = file(accountId)
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        try {
            val envelope = file.readFully()
            require(envelope.size in 30..8192 && envelope[0] == 1.toByte() && envelope[1] == 12.toByte())
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(128, envelope.copyOfRange(2, 14)))
            cipher.updateAAD(accountId.toByteArray(Charsets.UTF_8))
            val plaintext = cipher.doFinal(envelope.copyOfRange(14, envelope.size))
            return try { plaintext.toString(Charsets.UTF_8) } finally { plaintext.fill(0) }
        } catch (_: Exception) {
            // Do not delete damaged/restored credentials or manufacture a new key while reading.
            throw IOException(unavailableMessage)
        }
    }

    /** Forgets the secret for [accountId]; the Keystore key stays for the others. */
    @Synchronized
    fun delete(accountId: String) {
        file(accountId).delete()
    }

    private fun file(accountId: String): AtomicFile {
        val name = MessageDigest.getInstance("SHA-256").digest(accountId.toByteArray()).joinToString("") { "%02x".format(it) }
        return AtomicFile(File(directory, "$name.bin"))
    }

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        if (!create) throw IOException(unavailableMessage)
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
        }.generateKey()
    }

}
