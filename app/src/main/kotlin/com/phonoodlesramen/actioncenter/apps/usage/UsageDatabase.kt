package com.phonoodlesramen.actioncenter.apps.usage

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import com.phonoodlesramen.actioncenter.apps.UsageRow
import kotlinx.coroutines.flow.Flow

/** [opens] of [packageName] in one hour of one week: a [UsageRow] (M5). */
@Entity(tableName = "app_usage", primaryKeys = ["packageName", "weekStart", "hourOfWeek"])
data class UsageEntity(
    val packageName: String,
    /** The week's Monday, as an epoch day. */
    val weekStart: Long,
    /** 0 is Monday 0:00-1:00. */
    val hourOfWeek: Int,
    val opens: Int,
)

/** Where the last read of Android's events ended (millis), and the app in front then. One row. */
@Entity(tableName = "reads")
data class UsageReadEntity(
    @PrimaryKey val id: Int = 0,
    val readUntil: Long,
    val front: String? = null,
)

@Dao
interface UsageDao {
    @Query("SELECT packageName, weekStart, hourOfWeek, opens FROM app_usage")
    fun rows(): Flow<List<UsageRow>>

    @Query("SELECT * FROM reads WHERE id = 0")
    suspend fun lastRead(): UsageReadEntity?

    /** How many rows it changed: none when the bucket is new. */
    @Query("UPDATE app_usage SET opens = opens + :opens WHERE packageName = :packageName AND weekStart = :weekStart AND hourOfWeek = :hourOfWeek")
    suspend fun addOpens(packageName: String, weekStart: Long, hourOfWeek: Int, opens: Int): Int

    @Insert
    suspend fun insert(row: UsageEntity)

    @Upsert
    suspend fun saveRead(read: UsageReadEntity)

    /** Weeks that began before [weekStart] (an epoch day). */
    @Query("DELETE FROM app_usage WHERE weekStart < :weekStart")
    suspend fun pruneBefore(weekStart: Long): Int

    /**
     * A read's opens added to their buckets, where it ended, and the weeks past 8 pruned,
     * together: a read is never counted twice, nor lost.
     */
    @Transaction
    suspend fun record(opens: List<UsageRow>, read: UsageReadEntity, keepFromWeek: Long) {
        opens.forEach {
            if (addOpens(it.packageName, it.weekStart, it.hourOfWeek, it.opens) == 0) {
                insert(UsageEntity(it.packageName, it.weekStart, it.hourOfWeek, it.opens))
            }
        }
        saveRead(read)
        pruneBefore(keepFromWeek)
    }
}

/**
 * The Apps row's counts (M5), in their own small file like `guide.db`: Android keeps only a
 * few days of usage events, so the launcher keeps 8 weeks of its own. The schema is
 * exported, so a later version migrates instead of starting over.
 */
@Database(entities = [UsageEntity::class, UsageReadEntity::class], version = 1)
abstract class UsageDatabase : RoomDatabase() {
    abstract fun dao(): UsageDao

    companion object {
        const val NAME = "usage.db"
    }
}
