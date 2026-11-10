package com.phonoodlesramen.actioncenter.profiles

import androidx.room.AutoMigration
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import com.phonoodlesramen.actioncenter.suggest.Profiles
import java.time.LocalDate
import java.time.Period
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * A kid's profile (Phase 5, brief §7). You are the owner profile, which isn't stored here:
 * it's [Profiles.OWNER] with the name from Settings. Names and birthdays stay on the Shield;
 * they're never logged or sent anywhere.
 */
@Entity(tableName = "kids")
data class KidEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** Their own colour, as ARGB (U13): it's their accent. */
    val color: Long,
    /** Epoch day. */
    val birthday: Long,
    /** Their Plex Home user (K2): plex.tv's id and title. Null until linked in Settings. */
    val plexUserId: Long? = null,
    val plexUserTitle: String? = null,
    /** "|"-joined ratings that replace their bracket's (an override); null uses the bracket. */
    val ceilingOverride: String? = null,
    /** "|"-joined app keys: My apps (K9). */
    val apps: String = "",
    /** Order in "Who's watching?" and Settings. */
    val sort: Int = 0,
)

/** One row of the age-bracket table (K3): ages [fromAge] to [toAge], and the ratings allowed. */
@Entity(tableName = "brackets")
data class BracketEntity(
    @PrimaryKey val fromAge: Int,
    val toAge: Int,
    /** "|"-joined. */
    val ratings: String,
)

/** Which profile is on: one row. */
@Entity(tableName = "current")
data class CurrentEntity(@PrimaryKey val id: Int = 0, val profileId: String)

@Dao
interface ProfileDao {
    @Query("SELECT * FROM kids ORDER BY sort, name")
    fun kids(): Flow<List<KidEntity>>

    @Upsert
    suspend fun saveKid(kid: KidEntity)

    @Query("DELETE FROM kids WHERE id = :id")
    suspend fun removeKid(id: String)

    @Query("SELECT * FROM brackets ORDER BY fromAge")
    fun brackets(): Flow<List<BracketEntity>>

    @Upsert
    suspend fun saveBrackets(rows: List<BracketEntity>)

    @Query("DELETE FROM brackets")
    suspend fun clearBrackets()

    @Query("SELECT profileId FROM current WHERE id = 0")
    fun current(): Flow<String?>

    @Upsert
    suspend fun setCurrent(row: CurrentEntity)
}

/**
 * Kids, the age-bracket table and the current profile, in their own file: a Plex re-sync
 * never touches them. The schema is exported, so a new version migrates. Version 2 adds
 * each kid's Plex Continue Watching and plays (K2), which [KidSync] fills after each sync.
 */
@Database(
    entities = [KidEntity::class, BracketEntity::class, CurrentEntity::class, KidContinueEntity::class, KidPlayEntity::class],
    version = 2,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class ProfileDatabase : RoomDatabase() {
    abstract fun dao(): ProfileDao

    /** Each kid's Plex data (K2). */
    abstract fun kidPlex(): KidPlexDao

    companion object {
        const val NAME = "profiles.db"
    }
}

/** An age bracket: ages [fromAge] to [toAge], inclusive, and the content ratings allowed (K3). */
data class AgeBracket(val fromAge: Int, val toAge: Int, val ratings: Set<String>)

/** The age-bracket table's rules (K3, K4). Pure. */
object AgeBrackets {
    /** 7-9 and 10-12 (K3): the kids are 7 to 12. */
    val DEFAULT = listOf(
        AgeBracket(7, 9, setOf("G", "PG", "TV-Y", "TV-Y7", "TV-G", "TV-PG")),
        AgeBracket(10, 12, setOf("G", "PG", "PG-13", "TV-Y", "TV-Y7", "TV-G", "TV-PG")),
    )

    /** Whole years on [today]; a 29 February birthday turns a year older on 1 March in other years. */
    fun age(birthday: LocalDate, today: LocalDate): Int = Period.between(birthday, today).years

    /**
     * The ratings a kid may see on [today]: their override, else their age's bracket. Below
     * the first bracket, the first; above the last, the last, so a birthday never opens
     * everything up.
     */
    fun ceiling(birthday: LocalDate, override: Set<String>?, brackets: List<AgeBracket>, today: LocalDate): Set<String> {
        if (override != null) return override
        val table = brackets.ifEmpty { DEFAULT }.sortedBy { it.fromAge }
        val age = age(birthday, today)
        return (table.firstOrNull { age in it.fromAge..it.toAge } ?: if (age < table.first().fromAge) table.first() else table.last()).ratings
    }

    /**
     * Whether a title with [rating] (an episode with none takes [showRating]) is allowed
     * under [ceiling]; a null ceiling is an adult's, which allows everything. A title with
     * no rating at all is hidden from kids (K4).
     */
    fun allows(ceiling: Set<String>?, rating: String?, showRating: String? = null): Boolean {
        if (ceiling == null) return true
        val effective = rating?.takeIf { it.isNotBlank() } ?: showRating?.takeIf { it.isNotBlank() } ?: return false
        return effective in ceiling
    }
}

/** A profile as the home sees it. [ceiling] and [apps] are null for an adult: no limits. */
data class ActiveProfile(
    val id: String,
    val name: String,
    val kid: Boolean,
    /** A kid's colour (ARGB); null for you, whose accent is Settings › Look's. */
    val color: Long? = null,
    val age: Int? = null,
    val ceiling: Set<String>? = null,
    val apps: Set<String>? = null,
    val plexUserId: Long? = null,
) {
    companion object {
        fun owner(name: String) = ActiveProfile(Profiles.OWNER, name, kid = false)
    }
}

/** Kids, the bracket table and which profile is on (Phase 5). */
interface ProfileStore {
    /** You first, then the kids in order. */
    fun profiles(ownerName: Flow<String>, today: Flow<LocalDate>): Flow<List<ActiveProfile>>

    /** The profile on now: you until a kid is picked, and you again if theirs is removed. */
    fun current(ownerName: Flow<String>, today: Flow<LocalDate>): Flow<ActiveProfile>

    /** The current profile's id alone: whose picks the cards show. */
    val currentId: Flow<String>

    val kids: Flow<List<KidEntity>>
    val brackets: Flow<List<AgeBracket>>

    suspend fun switchTo(profileId: String)
    suspend fun saveKid(kid: KidEntity)
    suspend fun removeKid(id: String)
    suspend fun saveBrackets(brackets: List<AgeBracket>)

    /** Previews, tests and before Phase 5's data: you alone. */
    object None : ProfileStore {
        override fun profiles(ownerName: Flow<String>, today: Flow<LocalDate>) = ownerName.map { listOf(ActiveProfile.owner(it)) }
        override fun current(ownerName: Flow<String>, today: Flow<LocalDate>) = ownerName.map { ActiveProfile.owner(it) }
        override val currentId: Flow<String> = flowOf(Profiles.OWNER)
        override val kids: Flow<List<KidEntity>> = flowOf(emptyList())
        override val brackets: Flow<List<AgeBracket>> = flowOf(AgeBrackets.DEFAULT)
        override suspend fun switchTo(profileId: String) {}
        override suspend fun saveKid(kid: KidEntity) {}
        override suspend fun removeKid(id: String) {}
        override suspend fun saveBrackets(brackets: List<AgeBracket>) {}
    }
}

/** [ProfileStore] on `profiles.db`. */
class RoomProfileStore(private val dao: ProfileDao) : ProfileStore {
    override val kids: Flow<List<KidEntity>> = dao.kids()

    override val brackets: Flow<List<AgeBracket>> = dao.brackets().map { rows ->
        rows.map { AgeBracket(it.fromAge, it.toAge, split(it.ratings)) }.ifEmpty { AgeBrackets.DEFAULT }
    }

    override fun profiles(ownerName: Flow<String>, today: Flow<LocalDate>): Flow<List<ActiveProfile>> =
        combine(ownerName, kids, brackets, today) { name, kids, brackets, day ->
            listOf(ActiveProfile.owner(name)) + kids.map { it.active(brackets, day) }
        }

    override fun current(ownerName: Flow<String>, today: Flow<LocalDate>): Flow<ActiveProfile> =
        combine(profiles(ownerName, today), dao.current()) { all, id -> all.firstOrNull { it.id == id } ?: all.first() }

    override val currentId: Flow<String> = combine(dao.current(), kids) { id, kids ->
        if (id != null && kids.any { it.id == id }) id else Profiles.OWNER
    }.distinctUntilChanged()

    override suspend fun switchTo(profileId: String) = dao.setCurrent(CurrentEntity(profileId = profileId))

    override suspend fun saveKid(kid: KidEntity) = dao.saveKid(kid)

    override suspend fun removeKid(id: String) = dao.removeKid(id)

    override suspend fun saveBrackets(brackets: List<AgeBracket>) {
        dao.clearBrackets()
        dao.saveBrackets(brackets.map { BracketEntity(it.fromAge, it.toAge, it.ratings.sorted().joinToString("|")) })
    }

    companion object {
        fun split(joined: String?): Set<String> = joined.orEmpty().split('|').map(String::trim).filter(String::isNotEmpty).toSet()

        fun KidEntity.active(brackets: List<AgeBracket>, today: LocalDate): ActiveProfile {
            val born = LocalDate.ofEpochDay(birthday)
            return ActiveProfile(
                id = id,
                name = name,
                kid = true,
                color = color,
                age = AgeBrackets.age(born, today),
                ceiling = AgeBrackets.ceiling(born, ceilingOverride?.let(::split), brackets, today),
                apps = split(apps),
                plexUserId = plexUserId,
            )
        }
    }
}
