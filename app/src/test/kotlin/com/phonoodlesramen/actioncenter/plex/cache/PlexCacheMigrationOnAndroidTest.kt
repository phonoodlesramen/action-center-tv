package com.phonoodlesramen.actioncenter.plex.cache

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Phases 9 and 12 on the TVs' own path: a version 5 or 6 `plex-cache.db`, made from its
 * exported schema, opened as `AppContainer` opens it (Room on the platform's SQLite, with
 * the destructive fallback). The AutoMigrations to 6 and 7 keep the rows and make the next
 * sync a full one; `:guide-core`'s `PlexDatabaseMigrationTest` does the same on the
 * station's driver.
 */
@RunWith(RobolectricTestRunner::class)
class PlexCacheMigrationOnAndroidTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val file = context.getDatabasePath(PlexDatabase.NAME)

    @After
    fun tearDown() {
        context.deleteDatabase(PlexDatabase.NAME)
    }

    @Test
    fun `a TV's version 5 cache moves through 6 to 7 with its rows, and its next sync is a full one`() = runBlocking {
        asOnTheTv(V5, PLEX_V5, version = 5) { db ->
            db.execSQL(
                "INSERT INTO items (ratingKey, sectionKey, type, title, durationMs, viewCount, genres, syncedAt, collections, labels, " +
                    "directors, actors, partKey, detailsAt) VALUES ('m1', '1', 'movie', 'Big Atlas', 7200000, 0, 'Action', 1, '', " +
                    "'Favourites', '', '', '/library/parts/m1/1/file.mkv', 1792000000000)",
            )
            db.execSQL("INSERT INTO sync_state VALUES (0, 1793000000000, 1793000000000, 1793000000000, 1793000000, NULL)")
        }

        val plex = open()
        try {
            val film = plex.dao().item("m1")!!
            assertEquals("kept, not emptied", 1, plex.dao().itemCount())
            assertEquals("Favourites" to "", film.labels to film.studio)
            assertNull(film.logo)
            assertEquals(1_792_000_000_000L, film.detailsAt)
            val state = plex.dao().syncState()!!
            assertNull("the next sync is a full one", state.lastFullAt)
            assertEquals(1_793_000_000L, state.itemsUpdatedSince)
        } finally {
            plex.close()
        }
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { assertEquals(7, it.version) }
    }

    @Test
    fun `a TV's version 6 cache moves to 7 with its rows and studios, and its next sync is a full one`() = runBlocking {
        asOnTheTv(V6, schema(V6).getValue("identityHash").jsonPrimitive.content, version = 6) { db ->
            db.execSQL(
                "INSERT INTO items (ratingKey, sectionKey, type, title, durationMs, viewCount, genres, syncedAt, collections, labels, " +
                    "directors, actors, partKey, detailsAt, studio) VALUES ('m1', '1', 'movie', 'Big Atlas', 7200000, 0, 'Action', 1, '', " +
                    "'Favourites', '', '', '/library/parts/m1/1/file.mkv', 1792000000000, 'Northlight')",
            )
            db.execSQL("INSERT INTO sync_state VALUES (0, 1793000000000, 1793000000000, 1793000000000, 1793000000, NULL)")
        }

        val plex = open()
        try {
            val film = plex.dao().item("m1")!!
            assertEquals("kept, not emptied", 1, plex.dao().itemCount())
            assertEquals("Favourites" to "Northlight", film.labels to film.studio)
            assertNull(film.logo)
            assertEquals(1_792_000_000_000L, film.detailsAt)
            val state = plex.dao().syncState()!!
            assertNull("the next sync is a full one", state.lastFullAt)
            assertEquals(1_793_000_000L, state.itemsUpdatedSince)
            plex.dao().upsertItems(listOf(film.copy(logo = "/library/metadata/m1/clearLogo/1")))
            assertEquals("/library/metadata/m1/clearLogo/1", plex.dao().item("m1")!!.logo)
        } finally {
            plex.close()
        }
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { assertEquals(7, it.version) }
    }

    /**
     * Makes the TV's cache from [path]'s schema, whose identity hash must be [hash], at
     * [version]; [rows] are written as that version would have written them.
     */
    private fun asOnTheTv(path: String, hash: String, version: Int, rows: (SQLiteDatabase) -> Unit) {
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val schema = schema(path)
            assertEquals("the TVs' version $version identity hash", hash, schema.getValue("identityHash").jsonPrimitive.content)
            for (entity in schema.getValue("entities").jsonArray.map { it.jsonObject }) {
                val table = entity.getValue("tableName").jsonPrimitive.content
                db.execSQL(entity.getValue("createSql").jsonPrimitive.content.replace(TABLE_NAME, table))
                entity["indices"]?.jsonArray?.forEach {
                    db.execSQL(it.jsonObject.getValue("createSql").jsonPrimitive.content.replace(TABLE_NAME, table))
                }
            }
            schema.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
            rows(db)
            db.version = version
        }
    }

    /** AppContainer's builder. */
    private fun open(): PlexDatabase = Room.databaseBuilder(context, PlexDatabase::class.java, PlexDatabase.NAME)
        .fallbackToDestructiveMigration(dropAllTables = true)
        .build()

    private fun schema(path: String): JsonObject = Json.parseToJsonElement(File(path).readText()).jsonObject.getValue("database").jsonObject

    private companion object {
        /** `:guide-core`'s exported schemas (unit tests run in `app/`). */
        const val SCHEMAS = "../guide-core/schemas/com.phonoodlesramen.actioncenter.plex.cache.PlexDatabase"
        const val V5 = "$SCHEMAS/5.json"
        const val V6 = "$SCHEMAS/6.json"
        const val PLEX_V5 = "97efd70a67b23b914036702138f4e3f5"
        const val TABLE_NAME = "\${TABLE_NAME}"
    }
}
