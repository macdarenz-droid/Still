package app.still.gallery.inputs

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Real host filesystem probes; these do not execute Android Os/PFD or prove OEM behavior. */
class SelectedInputFilesystemTest {
    @Test fun genuineOrphansAreRemovedAfterReopenWithoutTouchingSibling() = runBlocking {
        val parent = Files.createTempDirectory("still-selected-fs")
        val root = Files.createDirectory(parent.resolve("selected"))
        val neighbor = parent.resolve("neighbor"); Files.write(neighbor, byteArrayOf(7))
        val old = NioSelectedInputFileSystem(root)
        old.initialize()
        old.create(name()).use { it.stream.write(byteArrayOf(1, 2)); it.sync() }
        old.syncDirectory(); old.close()
        val store = SelectedInputStore(root, SelectedInputOwnership())
        assertEquals(SelectedInputCleanupResult.CLEAN, store.initialize())
        assertEquals(0L, Files.list(root).use { it.count() })
        assertArrayEquals(byteArrayOf(7), Files.readAllBytes(neighbor))
    }

    @Test fun unknownEntryBlocksAllCleanupBeforeDeletingAnyKnownChild() = runBlocking {
        val root = Files.createTempDirectory("still-selected-fs")
        val known = root.resolve(name()); Files.write(known, byteArrayOf(1))
        val unknown = root.resolve("do-not-touch"); Files.write(unknown, byteArrayOf(2))
        val store = SelectedInputStore(root, SelectedInputOwnership())
        assertEquals(SelectedInputCleanupResult.FAILED, store.initialize())
        assertArrayEquals(byteArrayOf(1), Files.readAllBytes(known))
        assertArrayEquals(byteArrayOf(2), Files.readAllBytes(unknown))
    }

    @Test fun rootSymlinkLeafSymlinkDirectoryAndHardlinkFailWithoutTouchingVictim() = runBlocking {
        for (mode in listOf("root", "leaf", "directory", "hardlink")) {
            val parent = Files.createTempDirectory("still-selected-fs")
            val victim = parent.resolve("victim"); Files.write(victim, byteArrayOf(9))
            val root = parent.resolve("selected")
            if (mode == "root") Files.createSymbolicLink(root, parent) else {
                Files.createDirectory(root)
                val child = root.resolve(name())
                when (mode) {
                    "leaf" -> Files.createSymbolicLink(child, victim)
                    "directory" -> Files.createDirectory(child)
                    else -> Files.createLink(child, victim)
                }
            }
            assertEquals(mode, SelectedInputCleanupResult.FAILED, SelectedInputStore(root, SelectedInputOwnership()).initialize())
            assertArrayEquals(byteArrayOf(9), Files.readAllBytes(victim))
        }
    }

    @Test fun exclusiveNamesNeverOverwriteAndTraversalIsRejected() {
        val root = Files.createTempDirectory("still-selected-fs")
        val fs = NioSelectedInputFileSystem(root); fs.initialize()
        val name = name()
        fs.create(name).use { it.stream.write(1); it.sync() }
        assertTrue(runCatching { fs.create(name) }.exceptionOrNull() is IOException)
        assertTrue(runCatching { fs.create("../outside") }.exceptionOrNull() is IOException)
        assertTrue(runCatching { fs.remove("../outside") }.exceptionOrNull() is IOException)
        assertArrayEquals(byteArrayOf(1), Files.readAllBytes(root.resolve(name)))
        fs.close()
    }

    @Test fun replacingRootPathCannotRedirectAnchoredCreateOrDeleteOutsideVerifiedDirectory() {
        val parent = Files.createTempDirectory("still-selected-fs")
        val root = Files.createDirectory(parent.resolve("selected")); val detached = parent.resolve("detached")
        val outside = parent.resolve("outside"); Files.createDirectory(outside)
        val fs = NioSelectedInputFileSystem(root); fs.initialize()
        val existing = name(); fs.create(existing).use { it.stream.write(1); it.sync() }
        Files.move(root, detached); Files.createSymbolicLink(root, outside)
        val created = name()
        fs.create(created).use { it.stream.write(2); it.sync() }
        assertTrue(Files.exists(detached.resolve(created)))
        assertFalse(Files.exists(outside.resolve(created)))
        // Path mismatch may deny host metadata probes; it must never delete the victim.
        Files.write(outside.resolve(existing), byteArrayOf(8))
        runCatching { fs.remove(existing) }
        assertArrayEquals(byteArrayOf(8), Files.readAllBytes(outside.resolve(existing)))
        assertTrue(runCatching { fs.syncDirectory() }.exceptionOrNull() is IOException)
        fs.close()
    }

    @Test fun changedEntryIdentityCannotBeBorrowedAsPreviouslyVerifiedBytes() {
        val root = Files.createTempDirectory("still-selected-fs")
        val fs = NioSelectedInputFileSystem(root); fs.initialize()
        val name = name(); fs.create(name).use { it.stream.write(1); it.sync() }
        val expected = fs.info(name)
        Files.move(root.resolve(name), root.resolve("held-old"))
        Files.write(root.resolve(name), byteArrayOf(2))
        assertTrue(runCatching { fs.open(name, expected) }.exceptionOrNull() is IOException)
        assertArrayEquals(byteArrayOf(2), Files.readAllBytes(root.resolve(name)))
        fs.close()
    }
    private fun name() = "rep-${UUID.randomUUID()}.bin"
}
