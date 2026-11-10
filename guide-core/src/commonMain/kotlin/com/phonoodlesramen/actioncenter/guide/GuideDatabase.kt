package com.phonoodlesramen.actioncenter.guide

import androidx.room.AutoMigration
import androidx.room.ColumnInfo
import androidx.room.ConstructedBy
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A guide channel: what it plays, its seed and where its scheduler left off. Times are
 * millis. The rule and the cursor are JSON, so a new rule type needs no schema change.
 */
@Entity(tableName = "channels")
data class ChannelEntity(
    @PrimaryKey val id: String,
    val number: Int,
    val name: String,
    /** A [ChannelRule] as JSON. */
    val rule: String,
    /** The same library and seed give the same schedule. */
    val seed: Long,
    /** Programs start on multiples of this many minutes of the local clock: 30, or 15 for short episodes. */
    val alignmentMinutes: Int,
    /** A [ChannelCursor] as JSON: the shuffle bag, each show's last episode, the next program. */
    val cursor: String,
    /** Where the schedule ends: the next program starts here. Null until it's first scheduled. */
    val scheduledUntil: Long? = null,
    /** When the schedule was last extended. */
    val extendedAt: Long? = null,
    /**
     * Off channels leave the Live row, the Guide card and the full guide, but keep their
     * schedule, so turning one back on is instant (2.7).
     */
    @ColumnInfo(defaultValue = "1") val enabled: Boolean = true,
    /** What fills the gap before a program: [FILLER_TRAILERS] or [FILLER_UP_NEXT] (G3, G18). */
    @ColumnInfo(defaultValue = FILLER_TRAILERS) val filler: String = FILLER_TRAILERS,
) {
    companion object {
        /** A trailer when the gap allows, else the Up next card (G3). */
        const val FILLER_TRAILERS = "trailers"

        /** Always the Up next card. */
        const val FILLER_UP_NEXT = "up_next"
    }
}

/**
 * One scheduled block, keyed by Plex IDs (G0). What it shows is copied in when it's
 * scheduled, so a slot never changes: a title that leaves the library still shows, off air.
 */
@Entity(tableName = "slots", primaryKeys = ["channelId", "startMillis"], indices = [Index("endMillis")])
data class SlotEntity(
    val channelId: String,
    val startMillis: Long,
    val endMillis: Long,
    /** [PROGRAM], [TRAILER] or [UP_NEXT]. */
    val kind: String,
    /** The program's ratingKey, or the trailer extra's; null for an Up next card. */
    val itemKey: String?,
    val title: String,
    /** "S2 E5 · Episode title" for an episode. */
    val episode: String?,
    val showKey: String?,
    val art: String?,
    val contentRating: String?,
    val summary: String?,
) {
    companion object {
        const val PROGRAM = "program"
        const val TRAILER = "trailer"
        const val UP_NEXT = "up_next"
    }
}

@Dao
interface GuideDao {
    @Query("SELECT * FROM channels ORDER BY number")
    suspend fun channels(): List<ChannelEntity>

    @Query("SELECT COUNT(*) FROM channels")
    suspend fun channelCount(): Int

    /** The schedule that ends soonest; null without channels. */
    @Query("SELECT MIN(COALESCE(scheduledUntil, 0)) FROM channels")
    suspend fun earliestEnd(): Long?

    @Insert
    suspend fun insertChannels(channels: List<ChannelEntity>)

    @Update
    suspend fun updateChannel(channel: ChannelEntity)

    /** Never replaces: what's scheduled never changes. */
    @Insert
    suspend fun insertSlots(slots: List<SlotEntity>)

    /** New slots and the channel's cursor, saved together. */
    @Transaction
    suspend fun saveExtension(channel: ChannelEntity, slots: List<SlotEntity>) {
        insertSlots(slots)
        updateChannel(channel)
    }

    @Query("SELECT MAX(endMillis) FROM slots WHERE channelId = :channelId")
    suspend fun lastEnd(channelId: String): Long?

    /** Every channel's slots that end after [from], in order. */
    @Query("SELECT * FROM slots WHERE endMillis > :from ORDER BY channelId, startMillis")
    suspend fun slotsFrom(from: Long): List<SlotEntity>

    @Query("SELECT * FROM slots WHERE channelId = :channelId ORDER BY startMillis")
    suspend fun slots(channelId: String): List<SlotEntity>

    @Query("DELETE FROM slots WHERE endMillis < :before")
    suspend fun prune(before: Long): Int

    // Phase 2.7: the editor's reads and writes (GuideEditor).

    @Query("SELECT * FROM channels WHERE id = :id")
    suspend fun channel(id: String): ChannelEntity?

    /** Where [channelId]'s slots that start before [before] end. */
    @Query("SELECT MAX(endMillis) FROM slots WHERE channelId = :channelId AND startMillis < :before")
    suspend fun lastEndBefore(channelId: String, before: Long): Long?

    @Update
    suspend fun updateChannels(channels: List<ChannelEntity>)

    /** What's on [channelId] at [millis]: null in a gap. */
    @Query("SELECT * FROM slots WHERE channelId = :channelId AND startMillis <= :millis AND endMillis > :millis LIMIT 1")
    suspend fun slotAt(channelId: String, millis: Long): SlotEntity?

    /** Every channel's slots that overlap [from] to [to], in order: what's on now and what follows it. */
    @Query("SELECT * FROM slots WHERE endMillis > :from AND startMillis <= :to ORDER BY channelId, startMillis")
    suspend fun slotsBetween(from: Long, to: Long): List<SlotEntity>

    @Query("DELETE FROM slots WHERE channelId = :channelId AND startMillis >= :from")
    suspend fun deleteSlotsFrom(channelId: String, from: Long)

    @Query("DELETE FROM slots WHERE channelId = :channelId")
    suspend fun deleteSlots(channelId: String)

    @Query("DELETE FROM channels WHERE id = :id")
    suspend fun deleteChannel(id: String)

    @Query("DELETE FROM slots")
    suspend fun deleteAllSlots()

    @Query("DELETE FROM channels")
    suspend fun deleteAllChannels()

    /** A new channel and its first schedule, saved together. */
    @Transaction
    suspend fun addChannel(channel: ChannelEntity, slots: List<SlotEntity>) {
        insertChannels(listOf(channel))
        insertSlots(slots)
    }

    /** A channel and its slots, gone together. */
    @Transaction
    suspend fun removeChannel(id: String) {
        deleteSlots(id)
        deleteChannel(id)
    }

    /**
     * The one place a schedule is rewritten (G16): [channel]'s slots from [from] on go, and
     * its new ones and its fresh cursor are saved, together.
     */
    @Transaction
    suspend fun rewriteFrom(channel: ChannelEntity, from: Long, slots: List<SlotEntity>) {
        deleteSlotsFrom(channel.id, from)
        updateChannel(channel)
        insertSlots(slots)
    }

    /** Every channel and slot replaced by [channels] (a starter lineup), together (G18, G19). */
    @Transaction
    suspend fun replaceLineup(channels: List<ChannelEntity>) {
        deleteAllSlots()
        deleteAllChannels()
        insertChannels(channels)
    }

    // Phase 8: a paired TV keeps a copy of the guide station's guide here (StationGuideSource).

    @Upsert
    suspend fun upsertChannels(channels: List<ChannelEntity>)

    @Query("DELETE FROM channels WHERE id NOT IN (:ids)")
    suspend fun deleteChannelsNotIn(ids: List<String>)

    @Query("DELETE FROM slots WHERE channelId NOT IN (:ids)")
    suspend fun deleteSlotsNotIn(ids: List<String>)

    /** [channelId]'s slots still running at [from], or later. */
    @Query("DELETE FROM slots WHERE channelId = :channelId AND endMillis > :from")
    suspend fun deleteSlotsEndingAfter(channelId: String, from: Long)

    /**
     * The station's lineup copied in, together: its [channels] replace this TV's, and each
     * channel's slots from its cut ([slots]' key: the earliest the station sent, or its
     * window's start) are the station's. Older slots stay, for the full guide's look back
     * and the saved spot, until they're [pruned][prune] at [pruneBefore].
     */
    @Transaction
    suspend fun copyLineup(channels: List<ChannelEntity>, slots: Map<String, Pair<Long, List<SlotEntity>>>, pruneBefore: Long) {
        val ids = channels.map { it.id }
        deleteSlotsNotIn(ids)
        deleteChannelsNotIn(ids)
        upsertChannels(channels)
        for ((channelId, cut) in slots) {
            deleteSlotsEndingAfter(channelId, cut.first)
            insertSlots(cut.second)
        }
        prune(pruneBefore)
    }
}

/**
 * The Plex Guide's own file (Phase 2.6, G0), apart from the Plex cache: a sign-out or a
 * cache rebuild never wipes the channels. Its schema is exported to `guide-core/schemas`, so
 * a later version migrates instead of starting over: version 2 (2.7) adds each channel's
 * on/off and filler.
 */
@Database(
    entities = [ChannelEntity::class, SlotEntity::class],
    version = 2,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
@ConstructedBy(GuideDatabaseConstructor::class)
abstract class GuideDatabase : RoomDatabase() {
    abstract fun dao(): GuideDao

    companion object {
        const val NAME = "guide.db"
        val TABLES = arrayOf("channels", "slots")
    }
}

/** Room's KSP writes the `actual` for each target, so neither needs reflection (Phase 8). */
@Suppress("KotlinNoActualForExpect")
expect object GuideDatabaseConstructor : RoomDatabaseConstructor<GuideDatabase> {
    override fun initialize(): GuideDatabase
}

/**
 * Where a channel's scheduler left off. [played] and [last] are titles for a shuffle and
 * shows for rotating shows; [positions] is each show's last episode; [pending] is the next
 * program, already drawn (its trailer filled the gap before it), placed first next time.
 */
@Serializable
internal data class ChannelCursor(
    val round: Int = 0,
    val played: List<String> = emptyList(),
    val last: String? = null,
    val positions: Map<String, EpisodePosition> = emptyMap(),
    val pending: String? = null,
) {
    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }

        fun fromJson(text: String): ChannelCursor = json.decodeFromString(serializer(), text)
    }
}

/** An episode's place in its show, so the next one is found even if this one is deleted. */
@Serializable
internal data class EpisodePosition(val season: Int?, val episode: Int?, val key: String)
