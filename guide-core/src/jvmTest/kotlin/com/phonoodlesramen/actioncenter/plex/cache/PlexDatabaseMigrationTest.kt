package com.phonoodlesramen.actioncenter.plex.cache

import androidx.room.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.phonoodlesramen.actioncenter.SchemaFiles
import com.phonoodlesramen.actioncenter.TvDatabasesOpenTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Phase 9: the TVs and the station had version 5 caches. Version 6 adds `items.studio` by
 * an AutoMigration, so they keep their rows; its spec makes the next sync a full one, which
 * fills the studios. Phase 12's version 7 adds `items.logo` the same way, from 6 or, through
 * 6, from 5. Each file is opened as the station opens it (`Station.kt`), with the destructive
 * fallback that `AppContainer` uses too: had the migration not run, the rows would be gone.
 * `:app`'s `PlexCacheMigrationOnAndroidTest` does the same on Room's Android path.
 */
class PlexDatabaseMigrationTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val path by lazy { temp.root.resolve(PlexDatabase.NAME).absolutePath }

    private fun open(): PlexDatabase = Room.databaseBuilder<PlexDatabase>(name = path)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .fallbackToDestructiveMigration(dropAllTables = true)
        .build()

    @Test
    fun `a version 5 cache moves through 6 to 7 with every row, an empty studio and no logo each, and its next sync a full one`() = runTest {
        SchemaFiles.create(path, V5, TvDatabasesOpenTest.PLEX_V5) { db ->
            db.item("m1", "movie", "Big Atlas", extra = ", partKey, detailsAt", values = ", '/library/parts/m1/1/file.mkv', $FILLED_AT")
            db.item("ns", "show", "Night Shift")
            db.item("ns-1", "episode", "Pilot", extra = ", showKey", values = ", 'ns'")
            db.execSQL("INSERT INTO plays VALUES ('h1', 'm1', 'movie', 'Big Atlas', 1792900000, NULL)")
            db.execSQL("INSERT INTO continue_watching (position, ratingKey, type, title) VALUES (0, 'ns-1', 'episode', 'Pilot')")
            db.execSQL("INSERT INTO sync_state VALUES (0, $SYNCED, $SYNCED, $SYNCED, ${SYNCED / 1_000}, NULL)")
        }

        val plex = open()
        try {
            val dao = plex.dao()
            assertEquals("every row kept: migrated, not emptied", 3, dao.itemCount())
            val film = dao.item("m1")!!
            assertEquals("", film.studio)
            assertNull(film.logo)
            assertEquals(
                "its tags and file as they were",
                listOf("Action|Drama", "Atlas Saga", "Favourites", "Dena Varga", "Mara Quint", "/library/parts/m1/1/file.mkv"),
                listOf(film.genres, film.collections, film.labels, film.directors, film.actors, film.partKey),
            )
            assertEquals(FILLED_AT, film.detailsAt)
            assertEquals("ns", dao.item("ns-1")!!.showKey)
            assertEquals(1_792_900_000L, dao.latestPlay())
            assertEquals(listOf("Pilot"), dao.continueWatching().map { it.title })
            assertEquals(
                "the next sync is a full one; the rest of the state stays",
                SyncStateEntity(lastAttemptAt = SYNCED, lastSuccessAt = SYNCED, lastFullAt = null, itemsUpdatedSince = SYNCED / 1_000),
                dao.syncState(),
            )
            dao.upsertItems(listOf(film.copy(studio = "Northlight")))
            assertEquals("Northlight", dao.item("m1")!!.studio)
        } finally {
            plex.close()
        }

        val file = BundledSQLiteDriver().open(path)
        try {
            assertEquals(7L, file.long("PRAGMA user_version"))
            assertEquals(SchemaFiles.hash(V7), file.text("SELECT identity_hash FROM room_master_table WHERE id = 42"))
        } finally {
            file.close()
        }
    }

    @Test
    fun `a version 6 cache moves to 7 with every row and its studios, no logo each, and its next sync a full one`() = runTest {
        SchemaFiles.create(path, V6, SchemaFiles.hash(V6)) { db ->
            db.item("m1", "movie", "Big Atlas", extra = ", partKey, detailsAt, studio", values = ", '/library/parts/m1/1/file.mkv', $FILLED_AT, 'Northlight'")
            db.item("ns", "show", "Night Shift", extra = ", studio", values = ", 'Tidewater'")
            db.item("ns-1", "episode", "Pilot", extra = ", showKey", values = ", 'ns'")
            db.execSQL("INSERT INTO plays VALUES ('h1', 'm1', 'movie', 'Big Atlas', 1792900000, NULL)")
            db.execSQL("INSERT INTO sync_state VALUES (0, $SYNCED, $SYNCED, $SYNCED, ${SYNCED / 1_000}, NULL)")
        }

        val plex = open()
        try {
            val dao = plex.dao()
            assertEquals("every row kept: migrated, not emptied", 3, dao.itemCount())
            val film = dao.item("m1")!!
            assertNull(film.logo)
            assertEquals("Northlight" to "Tidewater", film.studio to dao.item("ns")!!.studio)
            assertEquals(listOf("Action|Drama", "/library/parts/m1/1/file.mkv"), listOf(film.genres, film.partKey))
            assertEquals(FILLED_AT, film.detailsAt)
            assertEquals(1_792_900_000L, dao.latestPlay())
            assertEquals(
                "the next sync is a full one; the rest of the state stays",
                SyncStateEntity(lastAttemptAt = SYNCED, lastSuccessAt = SYNCED, lastFullAt = null, itemsUpdatedSince = SYNCED / 1_000),
                dao.syncState(),
            )
            dao.upsertItems(listOf(film.copy(logo = "/library/metadata/m1/clearLogo/1")))
            assertEquals("/library/metadata/m1/clearLogo/1", dao.item("m1")!!.logo)
        } finally {
            plex.close()
        }

        val file = BundledSQLiteDriver().open(path)
        try {
            assertEquals(7L, file.long("PRAGMA user_version"))
            assertEquals(SchemaFiles.hash(V7), file.text("SELECT identity_hash FROM room_master_table WHERE id = 42"))
        } finally {
            file.close()
        }
    }

    @Test
    fun `the same builder empties a version 4 cache, which has no migration`() = runTest {
        SchemaFiles.create(path, V4, SchemaFiles.hash(V4)) { db ->
            db.item("m1", "movie", "Big Atlas")
            db.execSQL("INSERT INTO sync_state VALUES (0, $SYNCED, $SYNCED, $SYNCED, ${SYNCED / 1_000}, NULL)")
        }

        val plex = open()
        try {
            assertEquals(0, plex.dao().itemCount())
            assertNull(plex.dao().syncState())
        } finally {
            plex.close()
        }
    }

    /** A title with the columns versions 4 and 5 both require, and [extra] columns with their [values]. */
    private fun SQLiteConnection.item(key: String, type: String, title: String, extra: String = "", values: String = "") = execSQL(
        "INSERT INTO items (ratingKey, sectionKey, type, title, durationMs, viewCount, genres, syncedAt, collections, labels, directors, actors$extra) " +
            "VALUES ('$key', '1', '$type', '$title', 7200000, 0, 'Action|Drama', 1, 'Atlas Saga', 'Favourites', 'Dena Varga', 'Mara Quint'$values)",
    )

    private fun SQLiteConnection.long(sql: String): Long = prepare(sql).use { it.step(); it.getLong(0) }

    private fun SQLiteConnection.text(sql: String): String = prepare(sql).use { it.step(); it.getText(0) }

    private companion object {
        const val SCHEMAS = "com.phonoodlesramen.actioncenter.plex.cache.PlexDatabase"
        const val V4 = "$SCHEMAS/4.json"
        const val V5 = "$SCHEMAS/5.json"
        const val V6 = "$SCHEMAS/6.json"
        const val V7 = "$SCHEMAS/7.json"

        /** The last sync and when Big Atlas's tags were filled (millis). */
        const val SYNCED = 1_793_000_000_000L
        const val FILLED_AT = 1_792_000_000_000L
    }
}
