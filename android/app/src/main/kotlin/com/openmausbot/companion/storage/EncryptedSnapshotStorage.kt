package com.openmausbot.companion.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.openmausbot.companion.core.PlainFileSnapshotStorage
import com.openmausbot.companion.core.SnapshotStorage
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The phone's offline copies (MOCA-296): `filesDir/snapshots/<name>.json`,
 * each sealed with AES-256-GCM under a key that lives in the Android
 * Keystore and never leaves it.
 *
 * Each write takes a fresh random 12-byte IV and stores `IV ‖ ciphertext+tag`.
 * The file's name is the associated data, so one computer's copy cannot be
 * renamed into another's place and still open. A copy that does not open —
 * the key went with a reinstall or a restore to another phone, or the bytes
 * were tampered with — is not this phone's to show: it is deleted and read as
 * no copy at all, and the next hydrate writes a fresh one.
 *
 * The key needs no user authentication, so the copy can be shown on a cold
 * launch as readily as the device token can be read. The directory is kept
 * out of Auto Backup and device transfer (`backup_rules.xml`,
 * `data_extraction_rules.xml`), and the key is device-bound in any case.
 *
 * Blocking; [com.openmausbot.companion.core.SnapshotStore] only calls it from
 * its own IO writer.
 */
class EncryptedSnapshotStorage(
    private val directory: File,
    /** The AES key. Injectable because Robolectric has no AndroidKeyStore; production uses [keystoreKey]. */
    private val key: () -> SecretKey,
    private val random: SecureRandom = SecureRandom(),
) : SnapshotStorage {
    constructor(context: Context) : this(
        directory = File(context.applicationContext.filesDir, DIRECTORY),
        key = ::keystoreKey,
    )

    private val files = PlainFileSnapshotStorage(directory)

    override fun read(name: String): ByteArray? {
        val sealed = files.read(name) ?: return null
        // A Keystore that cannot hand over the key right now throws from here,
        // which the store reads as "not readable yet" and leaves the file alone.
        val secret = key()
        return try {
            open(name, sealed, secret)
        } catch (error: Exception) {
            // Anything that does not open as this phone's own copy is gone.
            runCatching { files.delete(name) }
            null
        }
    }

    override fun write(name: String, bytes: ByteArray) {
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(associatedData(name))
        files.write(name, iv + cipher.doFinal(bytes))
    }

    override fun delete(name: String) = files.delete(name)

    override fun deleteAll() = files.deleteAll()

    private fun open(name: String, sealed: ByteArray, secret: SecretKey): ByteArray {
        require(sealed.size > IV_BYTES) { "Too short to be a sealed snapshot." }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
        cipher.updateAAD(associatedData(name))
        return cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
    }

    private fun associatedData(name: String): ByteArray = "$DIRECTORY/$name".encodeToByteArray()

    companion object {
        /** Under `filesDir`; named in the backup exclusions. */
        const val DIRECTORY = "snapshots"
        const val KEY_ALIAS = "openmaus.offline-snapshot.v1"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128

        /** The Keystore key, made on first use. Never exported; usable without unlocking. */
        @Synchronized
        fun keystoreKey(): SecretKey {
            val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            generator.init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    // The IV is ours, random per write; the Keystore's own would do too,
                    // but this keeps one code path for the injected test key.
                    .setRandomizedEncryptionRequired(false)
                    .setUserAuthenticationRequired(false)
                    .build(),
            )
            return generator.generateKey()
        }
    }
}
