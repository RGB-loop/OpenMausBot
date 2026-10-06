package com.openmausbot.companion.storage

import com.openmausbot.companion.core.CompanionState
import com.openmausbot.companion.core.SnapshotStore
import com.openmausbot.companion.core.StateSnapshot
import java.io.File
import java.nio.file.Files
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.w3c.dom.Element

/**
 * The sealed snapshot files (MOCA-296). Robolectric has no AndroidKeyStore,
 * so the key is a software AES-256 key handed in through the same seam the
 * Keystore key uses; everything else — IV, AAD, file layout, the failure
 * paths — is the production code.
 */
class EncryptedSnapshotStorageTest {
    private val directory: File = Files.createTempDirectory("snapshots").toFile()
    private val key = aesKey()

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private fun aesKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private fun storage(key: SecretKey = this.key) = EncryptedSnapshotStorage(directory, key = { key })

    private val plain = """{"schemaVersion":1,"connectionId":"c1","savedAt":5,"bots":[{"name":"Scout"}]}""".encodeToByteArray()

    @Test
    fun `a copy round trips and is not plain text on disk`() {
        storage().write("c1", plain)

        assertContentEquals(plain, storage().read("c1"))
        val onDisk = File(directory, "c1.json").readBytes()
        assertFalse("Scout" in String(onDisk, Charsets.ISO_8859_1))
        // 12-byte IV, the ciphertext, and a 16-byte tag.
        assertEquals(12 + plain.size + 16, onDisk.size)
    }

    @Test
    fun `every write takes a fresh IV`() {
        storage().write("c1", plain)
        val first = File(directory, "c1.json").readBytes()
        storage().write("c1", plain)
        val second = File(directory, "c1.json").readBytes()

        assertFalse(first.copyOfRange(0, 12).contentEquals(second.copyOfRange(0, 12)))
    }

    @Test
    fun `a copy renamed into another computer's place does not open and is removed`() {
        storage().write("c1", plain)
        File(directory, "c1.json").copyTo(File(directory, "c2.json"))

        assertNull(storage().read("c2"))
        assertFalse(File(directory, "c2.json").exists())
        assertContentEquals(plain, storage().read("c1"))
    }

    @Test
    fun `a copy sealed under a lost key reads as none and is removed`() {
        storage().write("c1", plain)

        assertNull(storage(aesKey()).read("c1"))
        assertFalse(File(directory, "c1.json").exists())
    }

    @Test
    fun `a key that cannot be had yet leaves the copy alone`() {
        storage().write("c1", plain)
        val locked = EncryptedSnapshotStorage(directory, key = { throw java.security.KeyStoreException("locked") })

        val error = runCatching { locked.read("c1") }.exceptionOrNull()

        assertTrue(error is java.security.KeyStoreException)
        assertTrue(File(directory, "c1.json").exists())
    }

    @Test
    fun `wiping removes one copy or all of them`() {
        storage().write("c1", plain)
        storage().write("c2", plain)

        storage().delete("c1")
        assertNull(storage().read("c1"))
        assertContentEquals(plain, storage().read("c2"))

        storage().deleteAll()
        assertNull(storage().read("c2"))
    }

    @Test
    fun `the store reads back what it saved through the sealed files`() {
        val snapshot = StateSnapshot(
            schemaVersion = StateSnapshot.SCHEMA_VERSION,
            connectionId = "c1",
            savedAt = 7,
        )
        val name = SnapshotStore.fileName("c1")
        storage().write(name, snapshot.encoded())

        assertEquals(snapshot, StateSnapshot.decode(requireNotNull(storage().read(name))))
        assertTrue(CompanionState(snapshot).isCached)
    }

    @Test
    fun `the snapshot directory is the one backup leaves out`() {
        assertEquals("snapshots", EncryptedSnapshotStorage.DIRECTORY)
        assertEquals("openmaus.offline-snapshot.v1", EncryptedSnapshotStorage.KEY_ALIAS)
    }

    // MARK: - Backup exclusions

    @Test
    fun `auto backup leaves the snapshots out`() {
        val backup = document("backup_rules.xml").documentElement
        assertTrue(excludesSnapshots(backup), "backup_rules.xml must exclude file:snapshots/")
    }

    @Test
    fun `cloud backup and device transfer leave the snapshots out`() {
        val rules = document("data_extraction_rules.xml").documentElement
        for (section in listOf("cloud-backup", "device-transfer")) {
            val block = rules.getElementsByTagName(section).item(0) as Element
            assertTrue(excludesSnapshots(block), "$section must exclude file:snapshots/")
        }
    }

    private fun excludesSnapshots(parent: Element): Boolean {
        val excludes = parent.getElementsByTagName("exclude")
        return (0 until excludes.length).map { excludes.item(it) as Element }.any {
            it.getAttribute("domain") == "file" && it.getAttribute("path") == "${EncryptedSnapshotStorage.DIRECTORY}/"
        }
    }

    private fun document(name: String) = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(
        listOf(File("src/main/res/xml", name), File("android/app/src/main/res/xml", name), File("app/src/main/res/xml", name))
            .firstOrNull(File::isFile) ?: error("Could not find res/xml/$name"),
    )
}
