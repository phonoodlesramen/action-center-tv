package com.phonoodlesramen.actioncenter.guide

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GuideDatabaseMigrationTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `a 2_6 guide moves to version 2, its channels on and filled with trailers, its schedule kept`() = runTest {
        // Version 1 as Phase 2.6 wrote it (guide-core/schemas/…GuideDatabase/1.json).
        val path = temp.root.resolve("guide-migration-test.db").absolutePath
        BundledSQLiteDriver().open(path).let { db ->
            try {
                db.execSQL(
                    "CREATE TABLE channels (`id` TEXT NOT NULL, `number` INTEGER NOT NULL, `name` TEXT NOT NULL, `rule` TEXT NOT NULL, " +
                        "`seed` INTEGER NOT NULL, `alignmentMinutes` INTEGER NOT NULL, `cursor` TEXT NOT NULL, `scheduledUntil` INTEGER, " +
                        "`extendedAt` INTEGER, PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "CREATE TABLE slots (`channelId` TEXT NOT NULL, `startMillis` INTEGER NOT NULL, `endMillis` INTEGER NOT NULL, " +
                        "`kind` TEXT NOT NULL, `itemKey` TEXT, `title` TEXT NOT NULL, `episode` TEXT, `showKey` TEXT, `art` TEXT, " +
                        "`contentRating` TEXT, `summary` TEXT, PRIMARY KEY(`channelId`, `startMillis`))",
                )
                db.execSQL("CREATE INDEX `index_slots_endMillis` ON `slots` (`endMillis`)")
                db.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
                db.execSQL("INSERT INTO room_master_table (id,identity_hash) VALUES(42, 'e73c4a92b31f2121a01463c856e255bc')")
                db.execSQL("INSERT INTO channels VALUES ('movies', 1, 'Movies', '{}', 7, 30, '{}', 1793134800000, 1793047260000)")
                db.execSQL("INSERT INTO slots VALUES ('movies', 1793046600000, 1793053800000, 'program', '1', 'Big Atlas', NULL, NULL, NULL, 'PG-13', NULL)")
                db.execSQL("PRAGMA user_version = 1")
            } finally {
                db.close()
            }
        }

        // Room's own journal mode (WAL), which Robolectric's SQLite couldn't do before Phase 8.
        val guide = Room.databaseBuilder<GuideDatabase>(name = path)
            .setDriver(BundledSQLiteDriver())
            .build()
        try {
            val channel = guide.dao().channels().single()
            assertEquals("Movies", channel.name)
            assertEquals(1_793_134_800_000L, channel.scheduledUntil)
            assertTrue(channel.enabled)
            assertEquals(ChannelEntity.FILLER_TRAILERS, channel.filler)
            assertEquals(listOf("Big Atlas"), guide.dao().slots("movies").map { it.title })
        } finally {
            guide.close()
        }
    }
}
