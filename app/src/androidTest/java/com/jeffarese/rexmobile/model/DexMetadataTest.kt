package com.jeffarese.rexmobile.model

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipInputStream

/**
 * The profile the updater installs beside an update, made from the installed APK itself, as it would
 * be from a downloaded one: a zip holding primary.prof in the format this device's ART reads.
 */
@RunWith(AndroidJUnit4::class)
class DexMetadataTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun theApksOwnProfileInThisDevicesFormat() {
        assumeTrue(Build.VERSION.SDK_INT >= 28)
        val scratch = File(context.cacheDir, "dm-test").apply { deleteRecursively(); mkdirs() }
        val dm = Updater.dexMetadata(context, File(context.applicationInfo.sourceDir), scratch)
        assertNotNull("no profile made", dm)

        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(dm!!.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entries[it.name] = zip.readBytes() }
        }
        assertEquals(setOf("primary.prof"), entries.keys)
        // "pro\0" then the version: 015 from Android 12, 010 before.
        val version = if (Build.VERSION.SDK_INT >= 31) "015" else "010"
        assertArrayEquals("pro\u0000$version\u0000".toByteArray(), entries.getValue("primary.prof").copyOf(8))
        // Nothing left behind.
        assertEquals(0, scratch.listFiles()!!.size)
    }
}
