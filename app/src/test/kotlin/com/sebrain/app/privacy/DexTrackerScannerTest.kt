package com.sebrain.app.privacy

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DexTrackerScannerTest {
    private val appsFlyer = Tracker("AppsFlyer", Tracker.Category.ATTRIBUTION, listOf("com.appsflyer"))
    private val sentry = Tracker("Sentry", Tracker.Category.CRASH_REPORTING, listOf("io.sentry"))
    private val scanner = DexTrackerScanner(listOf(appsFlyer, sentry))

    private fun scan(bytes: ByteArray) = scanner.scan(ByteArrayInputStream(bytes))

    @Test
    fun findsClassDescriptors() {
        val dex = "dex\n035\u0000...Lcom/appsflyer/AppsFlyerLib;...Lio/sentry/Sentry;".toByteArray()
        assertEquals(setOf(appsFlyer, sentry), scan(dex))
    }

    @Test
    fun ignoresSimilarNamesThatAreNotThePackage() {
        val dex = "Lcom/appsflyerclone/X; com/appsflyer/ Lio/sentryx/Y;".toByteArray()
        assertTrue(scan(dex).isEmpty())
    }

    @Test
    fun findsADescriptorThatCrossesABlockBoundary() {
        val descriptor = "Lcom/appsflyer/AppsFlyerLib;".toByteArray()
        // Put the descriptor across the 1 MiB block boundary, and another at the exact end.
        val size = (1 shl 20) * 2
        val bytes = ByteArray(size)
        descriptor.copyInto(bytes, (1 shl 20) - 5)
        "Lio/sentry/".toByteArray().let { it.copyInto(bytes, size - it.size) }
        assertEquals(setOf(appsFlyer, sentry), scan(bytes))
    }

    @Test
    fun scansEveryDexInAnApkAndSkipsOtherEntries() {
        val apk = Files.createTempFile("app", ".apk").toFile()
        try {
            ZipOutputStream(apk.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("assets/readme.txt"))
                zip.write("Lcom/appsflyer/".toByteArray())
                zip.putNextEntry(ZipEntry("classes.dex"))
                zip.write("nothing here".toByteArray())
                zip.putNextEntry(ZipEntry("classes2.dex"))
                zip.write("Lio/sentry/Sentry;".toByteArray())
            }
            assertEquals(setOf(sentry), scanner.scanApks(listOf(apk.path, File(apk.parent, "missing.apk").path)))
        } finally {
            apk.delete()
        }
    }

    @Test
    fun bundledSignaturesAreWellFormed() {
        val all = TrackerSignatures.all
        assertEquals(all.size, all.map { it.name }.toSet().size)
        all.flatMap { it.packages }.forEach { assertTrue(it, Regex("[a-z0-9_]+(\\.[a-z0-9_]+)+").matches(it)) }
        DexTrackerScanner()
    }
}
