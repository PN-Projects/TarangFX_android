package com.pnprojects.tarangfx.it

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pnprojects.tarangfx.data.db.AppDatabase
import com.pnprojects.tarangfx.data.db.EqPresetRepository
import com.pnprojects.tarangfx.data.db.OutputAudioEntity
import com.pnprojects.tarangfx.data.db.ProjectDraftEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Room schema + DAOs + repository on the real SQLite of the device (in-memory DB). */
@RunWith(AndroidJUnit4::class)
class DatabaseIntegrationTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun eqPresetsSaveListAndDelete() = runTest {
        val repo = EqPresetRepository(db.eqPresetDao())
        repo.save("Zeta", List(10) { it.toFloat() - 5f })
        repo.save("Alpha", List(10) { 1.5f })
        val presets = repo.userPresets.first()
        assertEquals(listOf("Alpha", "Zeta"), presets.map { it.name })  // ordered by name
        assertEquals(List(10) { it.toFloat() - 5f }, presets[1].gains)   // JSON round trip
        repo.delete(presets[0])
        assertEquals(listOf("Zeta"), repo.userPresets.first().map { it.name })
    }

    @Test
    fun outputsInsertFindAndDelete() = runTest {
        val dao = db.outputDao()
        val older = OutputAudioEntity(title = "a", filePath = "Music/TarangFX/a.mp3", contentUri = "content://x/1",
            category = "TRIM", durationMs = 1000, sizeBytes = 10, format = "MP3", sampleRate = 44100, bitrate = 192, createdAt = 1)
        val id1 = dao.insert(older)
        val id2 = dao.insert(older.copy(title = "b", contentUri = "content://x/2", createdAt = 2))
        assertEquals(listOf("b", "a"), dao.getAll().map { it.title })       // newest first
        assertEquals(id2, dao.findByUri("content://x/2")!!.id)
        dao.update(dao.findByUri("content://x/1")!!.copy(title = "renamed"))
        assertEquals("renamed", dao.getAll().last().title)
        dao.delete(listOf(id1, id2))
        assertTrue(dao.getAll().isEmpty())
        assertNull(dao.findByUri("content://x/1"))
    }

    @Test
    fun draftsUpsertAndFilterByTool() = runTest {
        val dao = db.projectDraftDao()
        val id = dao.upsert(ProjectDraftEntity(name = "Mix 1", toolType = "MIX", serializedGraphJson = "{}", updatedAt = 1))
        dao.upsert(ProjectDraftEntity(name = "Merge 1", toolType = "MERGE", serializedGraphJson = "{}", updatedAt = 2))
        dao.upsert(ProjectDraftEntity(id = id, name = "Mix 1", toolType = "MIX", serializedGraphJson = "{\"v\":2}", updatedAt = 3))
        val mixes = dao.observeByTool("MIX").first()
        assertEquals(1, mixes.size)
        assertEquals("{\"v\":2}", mixes.single().serializedGraphJson)  // replaced, not duplicated
        dao.delete(id)
        assertNull(dao.get(id))
    }
}
