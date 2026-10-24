package com.shapeshed.aerial.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RegistryStationListImportTest {
    private lateinit var context: Context
    private lateinit var live: RegistryDatabase
    private val createdDatabases = mutableListOf<String>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
        live = Room.inMemoryDatabaseBuilder(context, RegistryDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        live.close()
        createdDatabases.forEach(context::deleteDatabase)
    }

    @Test
    fun publishedListReplacesTheBundledRows() = runBlocking {
        live.registryDao().insertAll(listOf(station(1, "Bundled")))
        val published = publishedDatabase(station(7, "Published One"), station(8, "Published Two"))

        assertEquals(2, importStationList(live, published))

        val stations = live.registryDao().all()
        val search = live.registryDao().searchFts("published*").map { it.name }
        assertEquals(listOf(7L, 8L), stations.map { it.id })
        assertEquals(listOf("Published One", "Published Two"), stations.map { it.name })
        assertEquals(listOf("Published One", "Published Two"), search)
    }

    @Test
    fun emptyPublishedListLeavesTheBundledRows() = runBlocking {
        live.registryDao().insertAll(listOf(station(1, "Bundled")))

        assertNull(importStationList(live, publishedDatabase()))

        assertEquals(listOf("Bundled"), live.registryDao().all().map { it.name })
    }

    @Test
    fun databaseFromAnotherSchemaLeavesTheBundledRows() = runBlocking {
        live.registryDao().insertAll(listOf(station(1, "Bundled")))

        assertNull(importStationList(live, foreignDatabase()))

        assertEquals(listOf("Bundled"), live.registryDao().all().map { it.name })
    }

    @Test
    fun unreadableFileLeavesTheBundledRows() = runBlocking {
        live.registryDao().insertAll(listOf(station(1, "Bundled")))
        val garbage = File(context.cacheDir, "registry-garbage.db").apply { writeText("not a database") }

        assertNull(importStationList(live, garbage))

        assertEquals(listOf("Bundled"), live.registryDao().all().map { it.name })
        garbage.delete()
    }

    /** A registry database built the way the release workflow builds one, then handed over as a file. */
    private suspend fun publishedDatabase(vararg stations: RegistryStation): File {
        val name = "published-registry.db"
        val database = createDatabase(name)
        try {
            database.registryDao().insertAll(stations.toList())
        } finally {
            database.close()
        }
        return context.getDatabasePath(name)
    }

    private fun foreignDatabase(): File {
        val name = "foreign-registry.db"
        context.deleteDatabase(name)
        createdDatabases += name
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { database ->
            database.execSQL("CREATE TABLE registry_stations (id INTEGER PRIMARY KEY, name TEXT)")
        }
        return file
    }

    private fun createDatabase(name: String): RegistryDatabase {
        context.deleteDatabase(name)
        createdDatabases += name
        return Room.databaseBuilder(context, RegistryDatabase::class.java, name)
            // Keep everything in the main file so it can be read back as a plain SQLite database.
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .build()
    }

    private fun station(id: Long, name: String) = RegistryStation(
        id = id,
        name = name,
        streamUrl = "https://example.test/$id",
        tags = "test",
        provider = "test",
        providerId = id.toString(),
        searchText = name,
    )
}
