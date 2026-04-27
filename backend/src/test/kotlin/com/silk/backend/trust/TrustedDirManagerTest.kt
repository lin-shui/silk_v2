package com.silk.backend.trust

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrustedDirManagerTest {

    private fun createManager(baseDir: File): TrustedDirManager {
        return TrustedDirManager(baseDir = baseDir.absolutePath)
    }

    @Test
    fun `isTrusted returns false for empty store`() {
        val tempDir = createTempDirectory("trusted-dir-test").toFile()
        val manager = createManager(tempDir)
        assertFalse(manager.isTrusted("user1", "ip:127.0.0.1", "/home/user/project"))
    }

    @Test
    fun `isTrusted returns true for exact match`() {
        val tempDir = createTempDirectory("trusted-dir-test").toFile()
        val manager = createManager(tempDir)
        manager.addTrust("user1", "ip:127.0.0.1", "/home/user/project")
        assertTrue(manager.isTrusted("user1", "ip:127.0.0.1", "/home/user/project"))
    }

    @Test
    fun `isTrusted returns true for subdirectory`() {
        val tempDir = createTempDirectory("trusted-dir-test").toFile()
        val manager = createManager(tempDir)
        manager.addTrust("user1", "ip:127.0.0.1", "/home/user/project")
        assertTrue(manager.isTrusted("user1", "ip:127.0.0.1", "/home/user/project/src/main"))
    }

    @Test
    fun `isTrusted returns false for different bridgeId`() {
        val tempDir = createTempDirectory("trusted-dir-test").toFile()
        val manager = createManager(tempDir)
        manager.addTrust("user1", "ip:127.0.0.1", "/home/user/project")
        assertFalse(manager.isTrusted("user1", "ip:192.168.1.1", "/home/user/project"))
    }

    @Test
    fun `isTrusted returns false for sibling directory`() {
        val tempDir = createTempDirectory("trusted-dir-test").toFile()
        val manager = createManager(tempDir)
        manager.addTrust("user1", "ip:127.0.0.1", "/home/user/proj")
        assertFalse(manager.isTrusted("user1", "ip:127.0.0.1", "/home/user/project"))
    }

    @Test
    fun `isTrusted returns false for parent directory`() {
        val tempDir = createTempDirectory("trusted-dir-test").toFile()
        val manager = createManager(tempDir)
        manager.addTrust("user1", "ip:127.0.0.1", "/home/user/project/src")
        assertFalse(manager.isTrusted("user1", "ip:127.0.0.1", "/home/user/project"))
    }

    @Test
    fun `isTrusted isolates users`() {
        val tempDir = createTempDirectory("trusted-dir-test").toFile()
        val manager = createManager(tempDir)
        manager.addTrust("user1", "ip:127.0.0.1", "/home/user/project")
        assertFalse(manager.isTrusted("user2", "ip:127.0.0.1", "/home/user/project"))
    }

    @Test
    fun `addTrust is idempotent`() {
        val tempDir = createTempDirectory("trusted-dir-test").toFile()
        val manager = createManager(tempDir)
        val first = manager.addTrust("user1", "ip:127.0.0.1", "/home/user/project")
        val second = manager.addTrust("user1", "ip:127.0.0.1", "/home/user/project")
        assertTrue(first)
        assertFalse(second)
    }

    @Test
    fun `removeTrust deletes exact match`() {
        val tempDir = createTempDirectory("trusted-dir-test").toFile()
        val manager = createManager(tempDir)
        manager.addTrust("user1", "ip:127.0.0.1", "/home/user/project")
        val removed = manager.removeTrust("user1", "ip:127.0.0.1", "/home/user/project")
        assertTrue(removed)
        assertFalse(manager.isTrusted("user1", "ip:127.0.0.1", "/home/user/project"))
    }

    @Test
    fun `listTrusts returns user-specific entries`() {
        val tempDir = createTempDirectory("trusted-dir-test").toFile()
        val manager = createManager(tempDir)
        manager.addTrust("user1", "ip:127.0.0.1", "/home/user/a")
        manager.addTrust("user1", "ip:127.0.0.1", "/home/user/b")
        manager.addTrust("user2", "ip:127.0.0.1", "/home/user/c")

        val user1Trusts = manager.listTrusts("user1")
        assertEquals(2, user1Trusts.size)
        assertTrue(user1Trusts.any { it.path == "/home/user/a" })
        assertTrue(user1Trusts.any { it.path == "/home/user/b" })

        val user2Trusts = manager.listTrusts("user2")
        assertEquals(1, user2Trusts.size)
        assertTrue(user2Trusts.any { it.path == "/home/user/c" })
    }

    @Test
    fun `persistence round-trip`() {
        val tempDir = createTempDirectory("trusted-dir-test").toFile()
        val manager1 = createManager(tempDir)
        manager1.addTrust("user1", "ip:127.0.0.1", "/home/user/project")

        val manager2 = createManager(tempDir)
        assertTrue(manager2.isTrusted("user1", "ip:127.0.0.1", "/home/user/project"))
        assertTrue(manager2.isTrusted("user1", "ip:127.0.0.1", "/home/user/project/subdir"))
    }

    @Test
    fun `normalize handles trailing slash`() {
        val tempDir = createTempDirectory("trusted-dir-test").toFile()
        val manager = createManager(tempDir)
        manager.addTrust("user1", "ip:127.0.0.1", "/home/user/project/")
        // The stored path should not have trailing slash, so subdirectory matching works
        assertTrue(manager.isTrusted("user1", "ip:127.0.0.1", "/home/user/project/src"))
    }
}
