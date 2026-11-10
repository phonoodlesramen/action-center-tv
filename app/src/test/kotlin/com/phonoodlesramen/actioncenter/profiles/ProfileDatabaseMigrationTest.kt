package com.phonoodlesramen.actioncenter.profiles

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Every name here is made up. */
@RunWith(RobolectricTestRunner::class)
class ProfileDatabaseMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "profiles-migration-test.db"

    @After
    fun tearDown() {
        context.deleteDatabase(name)
    }

    @Test
    fun `the seam's kids, brackets and current profile move to version 2, with no Plex data yet`() = runTest {
        // Version 1 as the seam wrote it (app/schemas/…ProfileDatabase/1.json).
        val file = context.getDatabasePath(name).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL(
                "CREATE TABLE kids (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `color` INTEGER NOT NULL, `birthday` INTEGER NOT NULL, " +
                    "`plexUserId` INTEGER, `plexUserTitle` TEXT, `ceilingOverride` TEXT, `apps` TEXT NOT NULL, `sort` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`id`))",
            )
            db.execSQL("CREATE TABLE brackets (`fromAge` INTEGER NOT NULL, `toAge` INTEGER NOT NULL, `ratings` TEXT NOT NULL, PRIMARY KEY(`fromAge`))")
            db.execSQL("CREATE TABLE current (`id` INTEGER NOT NULL, `profileId` TEXT NOT NULL, PRIMARY KEY(`id`))")
            db.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            db.execSQL("INSERT INTO room_master_table (id,identity_hash) VALUES(42, 'bd549111cad1e12f75df12e085a2e6f2')")
            db.execSQL("INSERT INTO kids VALUES ('maya', 'Maya', 4294937189, 17227, 31415926, 'Maya', NULL, 'org.pbskids.video', 0)")
            db.execSQL("INSERT INTO brackets VALUES (7, 12, 'G|PG')")
            db.execSQL("INSERT INTO current VALUES (0, 'maya')")
            db.version = 1
        }

        // Robolectric's SQLite can't put a file in WAL mode; the migration is the same either way.
        val profiles = Room.databaseBuilder(context, ProfileDatabase::class.java, name)
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = profiles.dao()
            val maya = dao.kids().first().single()
            assertEquals("Maya" to 31_415_926L, maya.name to maya.plexUserId)
            assertEquals(listOf(BracketEntity(7, 12, "G|PG")), dao.brackets().first())
            assertEquals("maya", dao.current().first())

            val plex = profiles.kidPlex()
            assertEquals(emptyList<KidContinueEntity>(), plex.continueWatchingNow("maya"))
            assertEquals(emptyList<KidPlayEntity>(), plex.plays("maya"))
            val row = KidContinueEntity(
                "maya", 0, "8801", "episode", "The Big Race", "Puddle Jumpers", 1, 3, null, 1_320_000, 300_000, "TV-Y",
                "/thumb/8801", null, "/art/8000", 1_793_050_000, "8000",
            )
            plex.save("maya", listOf(row), KidPlays(31_415_926, listOf(KidPlayEntity("maya", "8802", "episode", 1_793_040_000, "8000", 31_415_926)), 0))
            assertEquals(listOf(row), plex.continueWatchingNow("maya"))
            assertEquals(listOf("8802"), plex.plays("maya").map { it.ratingKey })
        } finally {
            profiles.close()
        }
    }
}
