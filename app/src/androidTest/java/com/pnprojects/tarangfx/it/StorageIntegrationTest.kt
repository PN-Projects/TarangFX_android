package com.pnprojects.tarangfx.it

import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pnprojects.tarangfx.data.db.AppDatabase
import com.pnprojects.tarangfx.data.media.MediaStoreRepository
import com.pnprojects.tarangfx.data.media.OutputRepository
import com.pnprojects.tarangfx.data.media.SourceRegistry
import com.pnprojects.tarangfx.data.media.WaveformRepository
import com.pnprojects.tarangfx.domain.model.OutputCategory
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Scoped-storage publishing (MediaStore), staging hygiene and the waveform cache. */
@RunWith(AndroidJUnit4::class)
class StorageIntegrationTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: AppDatabase
    private lateinit var repo: OutputRepository
    private lateinit var sources: SourceRegistry

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        sources = SourceRegistry(ctx)
        repo = OutputRepository(ctx, db.outputDao(), MediaStoreRepository(ctx, sources))
    }

    @After
    fun tearDown() = db.close()

    private fun stagedWav(name: String, seconds: Double = 1.0) =
        File(repo.stagingDir(), name).also { TestAudio.writeSineWav(it, seconds) }

    private fun query(uri: Uri, vararg cols: String): Map<String, String?> =
        ctx.contentResolver.query(uri, cols, null, null, null)!!.use { c ->
            assertTrue("row exists", c.moveToFirst())
            cols.associateWith { c.getString(c.getColumnIndexOrThrow(it)) }
        }

    @Test
    fun publishGoesToMusicFolderAndCleansStaging() = runTest {
        val staged = stagedWav("pub-${System.nanoTime()}.wav")
        val size = staged.length()
        val e = repo.publish(staged, "IT Publish ${System.nanoTime()}", OutputCategory.TRIM, 44_100, 0)
        try {
            assertFalse("staged copy deleted", staged.exists())
            val uri = Uri.parse(e.contentUri)
            val row = query(uri, MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.IS_PENDING,
                MediaStore.MediaColumns.SIZE, MediaStore.Audio.Media.IS_MUSIC)
            assertEquals("Music/TarangFX/", row[MediaStore.MediaColumns.RELATIVE_PATH])
            assertEquals("0", row[MediaStore.MediaColumns.IS_PENDING])
            assertEquals("1", row[MediaStore.Audio.Media.IS_MUSIC])
            assertEquals(size.toString(), row[MediaStore.MediaColumns.SIZE])
            assertEquals(1_000.0, e.durationMs.toDouble(), 20.0)
            assertEquals(e.id, db.outputDao().findByUri(e.contentUri)!!.id)
        } finally {
            assertTrue("no consent needed for own file", repo.delete(listOf(e)).isEmpty())
        }
        assertTrue(db.outputDao().getAll().isEmpty())
    }

    @Test
    fun ringtonesAreNotFiledAsMusic() = runTest {
        val e = repo.publish(stagedWav("rt-${System.nanoTime()}.wav"), "IT Ringtone ${System.nanoTime()}",
            OutputCategory.TRIM, 44_100, 0, baseDirectory = Environment.DIRECTORY_RINGTONES)
        try {
            val row = query(Uri.parse(e.contentUri), MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.Audio.Media.IS_MUSIC)
            assertEquals("Ringtones/TarangFX/", row[MediaStore.MediaColumns.RELATIVE_PATH])
            assertEquals("0", row[MediaStore.Audio.Media.IS_MUSIC])
        } finally {
            repo.delete(listOf(e))
        }
    }

    @Test
    fun renameUpdatesMediaStoreAndRoom() = runTest {
        val e = repo.publish(stagedWav("rn-${System.nanoTime()}.wav"), "IT Rename ${System.nanoTime()}", OutputCategory.MIX, 44_100, 0)
        val newTitle = "IT Renamed ${System.nanoTime()}"
        try {
            assertTrue(repo.rename(e, newTitle))
            val row = query(Uri.parse(e.contentUri), MediaStore.MediaColumns.DISPLAY_NAME)
            assertEquals("$newTitle.wav", row[MediaStore.MediaColumns.DISPLAY_NAME])
            assertEquals(newTitle, db.outputDao().findByUri(e.contentUri)!!.title)
        } finally {
            repo.delete(listOf(db.outputDao().findByUri(e.contentUri) ?: e))
        }
    }

    @Test
    fun sanitizeAndStagingLocation() {
        assertEquals("a_b_c_d", repo.sanitize("a/b:c*d"))
        assertEquals("audio", repo.sanitize("   "))
        assertEquals(120, repo.sanitize("x".repeat(500)).length)
        // Staging must not live in cacheDir (Android purges it under storage pressure).
        assertFalse(repo.stagingDir().absolutePath.startsWith(ctx.cacheDir.absolutePath))
        val leftover = File(repo.stagingDir(), "stale.tmp").apply { writeText("x") }
        repo.purgeStaleStaging()
        assertFalse(leftover.exists())
    }

    @Test
    fun waveformRepositoryBuildsCachesAndIndexesPeaks() = runTest {
        val f = File(ctx.cacheDir, "it-wf/${System.nanoTime()}.wav").also { TestAudio.writeSineWav(it, seconds = 4.0, amp = 0.5) }
        try {
            val wfRepo = WaveformRepository(ctx, sources)
            val wf = wfRepo.load(f.absolutePath, f.length())
            assertNotNull(wf)
            wf!!
            assertEquals(400.0, wf.peaks.size.toDouble(), 1.0)
            assertEquals(4_000.0, wf.durationMs.toDouble(), 20.0)
            // FFmpeg's stereo->mono downmix sums L+R at -3 dB each (x0.707), so two 0.5-amplitude
            // sines of different pitch peak somewhere below 0.5 * 2 * 0.707 = 0.707.
            val whole = wf.maxPeak(0, wf.peaks.size)
            assertTrue("peak $whole", whole in 0.40f..0.71f)
            assertEquals(wf.peaks.max(), whole, 0f)
            assertSame("second load is served from memory", wf, wfRepo.load(f.absolutePath, f.length()))
        } finally {
            f.parentFile?.deleteRecursively()
        }
    }
}
