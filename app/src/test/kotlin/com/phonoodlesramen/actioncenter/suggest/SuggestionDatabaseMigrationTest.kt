package com.phonoodlesramen.actioncenter.suggest

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SuggestionDatabaseMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "suggestions-migration-test.db"

    @After
    fun tearDown() {
        context.deleteDatabase(name)
    }

    @Test
    fun `Phase 3's feedback and picks move to version 2, with no blurbs yet`() = runTest {
        // Version 1 as Phase 3 wrote it (app/schemas/…SuggestionDatabase/1.json).
        val file = context.getDatabasePath(name).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL(
                "CREATE TABLE feedback (`profile` TEXT NOT NULL, `ratingKey` TEXT NOT NULL, `kind` TEXT NOT NULL, `scope` TEXT NOT NULL, " +
                    "`atMillis` INTEGER NOT NULL, `count` INTEGER NOT NULL, `untilMillis` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`profile`, `ratingKey`, `kind`, `scope`))",
            )
            db.execSQL(
                "CREATE TABLE picks (`profile` TEXT NOT NULL, `surface` TEXT NOT NULL, `rank` INTEGER NOT NULL, `ratingKey` TEXT NOT NULL, " +
                    "`reason` TEXT NOT NULL, `evidence` TEXT NOT NULL, `computedAt` INTEGER NOT NULL, PRIMARY KEY(`profile`, `surface`, `rank`))",
            )
            db.execSQL(
                "CREATE TABLE runs (`profile` TEXT NOT NULL, `ranAt` INTEGER NOT NULL, `day` TEXT NOT NULL, `tookMillis` INTEGER NOT NULL, " +
                    "`error` TEXT, PRIMARY KEY(`profile`))",
            )
            db.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            db.execSQL("INSERT INTO room_master_table (id,identity_hash) VALUES(42, '422ef3b288ff49572a4baefd7fe64a2c')")
            db.execSQL("INSERT INTO feedback VALUES ('owner', 'm1', 'NOT_TONIGHT', '', 1000, 1, 2000)")
            db.execSQL("INSERT INTO picks VALUES ('owner', 'hero', 0, 'm1', 'Because you liked Heat', '', 1000)")
            db.version = 1
        }

        // Robolectric's SQLite can't put a file in WAL mode; the migration is the same either way.
        val suggestions = Room.databaseBuilder(context, SuggestionDatabase::class.java, name)
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = suggestions.dao()
            assertEquals(listOf(FeedbackKind.NOT_TONIGHT), dao.feedback(Profiles.OWNER).map { it.kind })
            val pick = dao.picks(Profiles.OWNER).first().single()
            assertEquals("Because you liked Heat", pick.reason)
            assertNull(pick.episodesLeft)
            assertEquals(emptyList<BlurbEntity>(), dao.blurbs(Profiles.OWNER).first())

            dao.saveBlurbs(listOf(BlurbEntity(Profiles.OWNER, "m1", "Because you liked Heat", "Another slow-burn heist", 3000)))
            assertEquals("Another slow-burn heist", dao.blurbs(Profiles.OWNER).first().single().text)
            dao.pruneBlurbs(before = 4000)
            assertEquals(emptyList<BlurbEntity>(), dao.blurbs(Profiles.OWNER).first())
        } finally {
            suggestions.close()
        }
    }

    @Test
    fun `version 2 moves to 3 and keeps everything`() = runTest {
        // Version 2 as Phase 4 wrote it (app/schemas/…SuggestionDatabase/2.json).
        val file = context.getDatabasePath(name).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL(
                "CREATE TABLE feedback (`profile` TEXT NOT NULL, `ratingKey` TEXT NOT NULL, `kind` TEXT NOT NULL, `scope` TEXT NOT NULL, " +
                    "`atMillis` INTEGER NOT NULL, `count` INTEGER NOT NULL, `untilMillis` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`profile`, `ratingKey`, `kind`, `scope`))",
            )
            db.execSQL(
                "CREATE TABLE picks (`profile` TEXT NOT NULL, `surface` TEXT NOT NULL, `rank` INTEGER NOT NULL, `ratingKey` TEXT NOT NULL, " +
                    "`reason` TEXT NOT NULL, `evidence` TEXT NOT NULL, `computedAt` INTEGER NOT NULL, `episodesLeft` INTEGER, " +
                    "PRIMARY KEY(`profile`, `surface`, `rank`))",
            )
            db.execSQL(
                "CREATE TABLE runs (`profile` TEXT NOT NULL, `ranAt` INTEGER NOT NULL, `day` TEXT NOT NULL, `tookMillis` INTEGER NOT NULL, " +
                    "`error` TEXT, PRIMARY KEY(`profile`))",
            )
            db.execSQL(
                "CREATE TABLE blurbs (`profile` TEXT NOT NULL, `ratingKey` TEXT NOT NULL, `reason` TEXT NOT NULL, `text` TEXT NOT NULL, " +
                    "`writtenAt` INTEGER NOT NULL, PRIMARY KEY(`profile`, `ratingKey`))",
            )
            db.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            db.execSQL("INSERT INTO room_master_table (id,identity_hash) VALUES(42, '4999d77dc9e9618d95a053bafeb29147')")
            db.execSQL("INSERT INTO feedback VALUES ('owner', 'm1', 'LENS_HIDDEN', 'CLASSIC', 1000, 1, 2000)")
            db.execSQL("INSERT INTO picks VALUES ('owner', 'lens:CLASSIC', 0, 'm2', '51 years old', '', 1000, NULL)")
            db.version = 2
        }

        val suggestions = Room.databaseBuilder(context, SuggestionDatabase::class.java, name)
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = suggestions.dao()
            assertEquals(listOf(FeedbackKind.LENS_HIDDEN), dao.feedback(Profiles.OWNER).map { it.kind })
            assertEquals("51 years old", dao.picks(Profiles.OWNER).first().single().reason)
            assertEquals(emptyList<LensShownEntity>(), dao.shown(Profiles.OWNER))

            dao.saveShown(LensShownEntity(Profiles.OWNER, "m2", "2026-11-02"))
            dao.saveShown(LensShownEntity(Profiles.OWNER, "m2", "2026-11-02"))
            dao.saveShown(LensShownEntity(Profiles.OWNER, "m2", "2026-11-03"))
            assertEquals("once a day", 2, dao.shown(Profiles.OWNER).size)

            val rest = FeedbackRules.record(null, Profiles.OWNER, "m2", FeedbackKind.LENS_RESTED, "", 5000)
            dao.rest(Profiles.OWNER, listOf(rest))
            assertEquals(emptyList<LensShownEntity>(), dao.shown(Profiles.OWNER))
            assertEquals(5000 + FeedbackRules.REST_MILLIS, dao.feedback(Profiles.OWNER).single { it.kind == FeedbackKind.LENS_RESTED }.untilMillis)

            dao.saveShown(LensShownEntity(Profiles.OWNER, "m3", "2026-08-01"))
            dao.pruneShown(before = "2026-09-01")
            assertEquals(emptyList<LensShownEntity>(), dao.shown(Profiles.OWNER))
        } finally {
            suggestions.close()
        }
    }
}
