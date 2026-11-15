package com.snapgpt

import com.snapgpt.backup.BackupAccount
import com.snapgpt.backup.ConfigPlanner
import com.snapgpt.backup.InternalBackupStore
import com.snapgpt.backup.SecretBox
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class ConfigPlannerTest {
    private fun acct(id: String, key: String?, priority: Int, provider: String = "openrouter", endpoint: String = "https://openrouter.ai/api/v1") =
        BackupAccount(id, "N-$id", provider, endpoint, "model", true, priority, -1, key)

    @Test fun mergeKeepsEveryExistingAccountAndAddsOnlyNewOnes() {
        val existing = listOf(acct("e1", "k1", 0), acct("e2", "k2", 1))
        val incoming = listOf(acct("e1", "different", 0), acct("n1", "k3", 1), acct("n2", "k4", 2))
        val r = ConfigPlanner.merge(existing, incoming)
        assertEquals(listOf("e1", "e2", "n1", "n2"), r.accounts.map { it.id })
        assertEquals(listOf(0, 1, 2, 3), r.accounts.map { it.priority })
        assertEquals("k1", r.accounts[0].apiKey) // the existing account is never overwritten
        assertEquals(2, r.added); assertEquals(1, r.skipped)
    }

    @Test fun mergeDetectsTheSameCredentialEnteredUnderAnotherId() {
        val existing = listOf(acct("e1", "samekey", 0))
        val incoming = listOf(acct("other-id", "samekey", 0, endpoint = "https://OPENROUTER.ai/api/v1/"))
        val r = ConfigPlanner.merge(existing, incoming)
        assertEquals(1, r.accounts.size); assertEquals(0, r.added); assertEquals(1, r.skipped)
    }

    @Test fun mergeFillsAMissingKeyButNeverReplacesOne() {
        val r = ConfigPlanner.merge(listOf(acct("e1", null, 0), acct("e2", "own", 1)), listOf(acct("e1", "fromBackup", 0), acct("e2", "fromBackup2", 1)))
        assertEquals("fromBackup", r.accounts[0].apiKey)
        assertEquals("own", r.accounts[1].apiKey)
        assertEquals(1, r.keysFilled)
    }

    @Test fun replaceUsesTheBackupOrderAndNumbersPrioritiesFromZero() {
        val r = ConfigPlanner.replace(listOf(acct("b", "k", 7), acct("a", "k", 3)))
        assertEquals(listOf("a", "b"), r.map { it.id })
        assertEquals(listOf(0, 1), r.map { it.priority })
    }

    @Test fun previewReportsWhatReplaceWouldRemove() {
        val existing = listOf(acct("e1", "k1", 0), acct("e2", "k2", 1), acct("e3", "k3", 2))
        val incoming = listOf(acct("e1", "k1", 0), acct("n1", "k9", 1))
        val p = ConfigPlanner.preview(existing, incoming)
        assertEquals(2, p.total); assertEquals(1, p.alreadyPresent); assertEquals(1, p.brandNew); assertEquals(2, p.wouldBeRemovedByReplace)
    }
}

class InternalBackupStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    /** Stand-in for the Keystore-backed box: real AES-GCM with a fixed key, so corruption is really detected. */
    private class TestBox(val key: ByteArray = ByteArray(32) { it.toByte() }, var failSeal: Boolean = false) : SecretBox {
        override fun seal(plain: ByteArray): ByteArray {
            if (failSeal) throw IllegalStateException("simulated Keystore failure")
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            return iv + c.doFinal(plain)
        }
        override fun open(sealed: ByteArray): ByteArray {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, sealed.copyOfRange(0, 12)))
            return c.doFinal(sealed, 12, sealed.size - 12)
        }
    }

    private fun store(box: TestBox = TestBox()) = InternalBackupStore(File(tmp.root, "backup"), box)
    private fun bytes(s: String) = s.toByteArray()

    @Test fun missingBackupReadsAsNull() {
        val s = store()
        assertNull(s.readLatest()); assertFalse(s.hasBackup())
    }

    @Test fun writeThenReadReturnsTheSameBytes() {
        val s = store()
        assertTrue(s.write(bytes("v1")))
        assertArrayEquals(bytes("v1"), s.readLatest())
        assertTrue(s.hasBackup())
    }

    @Test fun theNewestWriteWinsAndThePreviousOneIsKept() {
        val s = store()
        s.write(bytes("v1")); s.write(bytes("v2"))
        assertArrayEquals(bytes("v2"), s.readLatest())
        assertTrue(File(tmp.root, "backup/api_config.prev.bak").exists())
    }

    @Test fun corruptedPrimaryFallsBackToThePreviousBackup() {
        val s = store()
        s.write(bytes("v1")); s.write(bytes("v2"))
        File(tmp.root, "backup/api_config.bak").writeBytes(ByteArray(64) { 7 }) // damage the current file
        assertArrayEquals(bytes("v1"), s.readLatest())
    }

    @Test fun failedWriteLeavesTheExistingBackupUntouched() {
        val box = TestBox()
        val s = store(box)
        s.write(bytes("good"))
        box.failSeal = true
        assertFalse(s.write(bytes("never stored")))
        box.failSeal = false
        assertArrayEquals(bytes("good"), s.readLatest())
    }

    @Test fun interruptedWriteLeavesATruncatedTempFileThatIsIgnored() {
        val s = store()
        s.write(bytes("good"))
        File(tmp.root, "backup/api_config.tmp").writeBytes(bytes("half-writt")) // a crash mid-write
        assertArrayEquals(bytes("good"), s.readLatest())
        // The next successful write cleans up and carries on.
        assertTrue(s.write(bytes("next")))
        assertArrayEquals(bytes("next"), s.readLatest())
    }

    @Test fun aCompletedTempFileFromACrashBeforeTheRenameIsRecovered() {
        val s = store()
        s.write(bytes("old"))
        val sealed = TestBox().seal(bytes("newer"))
        File(tmp.root, "backup/api_config.tmp").writeBytes(sealed) // fully written and valid, but never renamed
        assertArrayEquals(bytes("newer"), s.readLatest())
    }

    @Test fun aBackupSealedWithAnotherKeyIsTreatedAsMissing() {
        store(TestBox(key = ByteArray(32) { 1 })).write(bytes("other device"))
        assertNull(store(TestBox(key = ByteArray(32) { 2 })).readLatest())
    }

    @Test fun deleteAllRemovesEverything() {
        val s = store()
        s.write(bytes("v1")); s.write(bytes("v2"))
        s.deleteAll()
        assertNull(s.readLatest())
    }
}
