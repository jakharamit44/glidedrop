package io.github.jakharamit44.glidedrop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.zip.GZIPOutputStream

class AuditSecurityRemediationTest {

    private fun gzipBytes(raw: ByteArray): ByteArray {
        val baos = ByteArrayOutputStream()
        GZIPOutputStream(baos).use { it.write(raw) }
        return baos.toByteArray()
    }

    private fun odcEntry(name: String, data: ByteArray = ByteArray(0), mode: Int = 33188): ByteArray {
        val nameBytes = name.toByteArray(StandardCharsets.UTF_8) + byteArrayOf(0)
        val hdr = String.format(
            "070707%06o%06o%06o%06o%06o%06o%06o%011o%06o%011o",
            0, 1, mode, 0, 0, 1, 0, 0, nameBytes.size, data.size
        ).toByteArray(StandardCharsets.US_ASCII)
        return hdr + nameBytes + data
    }

    @Test
    fun uriSecurityAllowsOnlyHttpAndHttpsWithHost() {
        assertTrue(UriSecurity.isSafeWebUrl("https://example.com/file"))
        assertTrue(UriSecurity.isSafeWebUrl("http://192.168.1.5:8080/test"))
        assertFalse(UriSecurity.isSafeWebUrl("intent://scan/#Intent;scheme=zxing;end"))
        assertFalse(UriSecurity.isSafeWebUrl("file:///etc/passwd"))
        assertFalse(UriSecurity.isSafeWebUrl("tel:+15551234567"))
        assertFalse(UriSecurity.isSafeWebUrl("javascript:alert(1)"))
        assertFalse(UriSecurity.isSafeWebUrl("https://"))
        assertFalse(UriSecurity.isSafeWebUrl("   "))
    }

    @Test
    fun safeFileNameStripsBiDiReservedCharsLeadingDashAndPreservesExtension() {
        // BiDi right-to-left override spoofing: "invoice\u202Efdp.exe"
        val bidi = GlideDropCodec.safeFileName("invoice\u202Efdp.exe")
        assertFalse(bidi.contains('\u202E'))
        assertTrue(bidi.endsWith(".exe"))

        // Reserved FAT32 chars and leading dash
        val reserved = GlideDropCodec.safeFileName("--bad:name*with?\"<>|.txt")
        assertFalse(reserved.startsWith("-"))
        assertFalse(reserved.any { it in ":*?\"<>|" })
        assertTrue(reserved.endsWith(".txt"))

        // Trailing slash should not collapse to fallback name
        assertEquals("folder", GlideDropCodec.safeFileName("path/to/folder/"))

        // Long UTF-8 filename preserves extension within 239-byte budget
        val longName = "a".repeat(300) + ".mp4"
        val safeLong = GlideDropCodec.safeFileName(longName)
        assertTrue(safeLong.toByteArray(StandardCharsets.UTF_8).size <= 239)
        assertTrue(safeLong.endsWith(".mp4"))
    }

    @Test
    fun malformedNewcHexHeaderThrowsTypedIOException() {
        val badHdr = ("070701" + "Z".repeat(104)).toByteArray(StandardCharsets.US_ASCII)
        val wire = gzipBytes(badHdr)
        val dir = Files.createTempDirectory("codec-bad-hex").toFile()
        try {
            var threwIO = false
            try {
                GlideDropCodec.extractDvzipCpio(wire.inputStream(), dir)
            } catch (_: IOException) {
                threwIO = true
            }
            assertTrue("Malformed hex CPIO header must throw IOException", threwIO)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun nonRegularEntryRejectedEvenWithoutApprovedManifest() {
        val raw = odcEntry("evil_link", "target".toByteArray(), mode = 41471) +
            odcEntry("TRAILER!!!", mode = 0)
        val wire = gzipBytes(raw)
        val dir = Files.createTempDirectory("codec-unapproved-symlink").toFile()
        try {
            var threwIO = false
            try {
                GlideDropCodec.extractDvzipCpio(wire.inputStream(), dir, approvedFiles = null)
            } catch (_: IOException) {
                threwIO = true
            }
            assertTrue("Symlink must be rejected even when approvedFiles is null", threwIO)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun cpioEntryCountCapRejectsFlooding() {
        val baos = ByteArrayOutputStream()
        for (i in 1..6) {
            baos.write(odcEntry("f$i.txt", "x".toByteArray()))
        }
        baos.write(odcEntry("TRAILER!!!", mode = 0))
        val wire = gzipBytes(baos.toByteArray())
        val dir = Files.createTempDirectory("codec-max-entries").toFile()
        try {
            var threwIO = false
            try {
                GlideDropCodec.extractDvzipCpio(wire.inputStream(), dir, maxEntries = 5)
            } catch (_: IOException) {
                threwIO = true
            }
            assertTrue("Archive exceeding maxEntries must throw IOException", threwIO)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun viewModelRetainsStagedTextAndModalDrafts() {
        val vm = GlideDropViewModel()
        vm.openTextModal()
        vm.updateTextSnippetDraft("https://example.com")
        assertTrue(vm.showTextModal.value)
        assertEquals("https://example.com", vm.textSnippetDraft.value)

        vm.stageTextOrUrl("https://example.com")
        assertEquals(1, vm.stagedItems.value.size)
        assertEquals("Link", vm.stagedItems.value.first().category)

        vm.clearStagedItems()
        assertTrue(vm.stagedItems.value.isEmpty())
    }
}
