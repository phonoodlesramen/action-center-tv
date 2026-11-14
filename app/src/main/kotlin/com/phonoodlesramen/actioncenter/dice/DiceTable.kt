package com.phonoodlesramen.actioncenter.dice

import com.phonoodlesramen.actioncenter.profiles.ActiveProfile
import java.time.LocalDate
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The die's side of the app (spec §4, the die), held as long as the app runs: the table for
 * whoever's watching, the dials each profile left, what's come up today, and the face the
 * top bar's die last landed on. The tray asks it; tests give it their own [read] and [random].
 *
 * The table is read from the Plex cache once per profile, sync ([deck]'s `version`) and day,
 * and only the last one is kept: a 2 GB TV holds one library's worth of small rows, not one
 * per kid. The dials and what's come up are in memory: they last the evening, not a restart.
 */
class DiceTable(
    /** [ActiveProfile]'s table, read from the Plex cache off the main thread. */
    private val read: suspend (ActiveProfile) -> DiceDeck = { DiceDeck.Empty },
    private val random: Random = Random.Default,
    private val today: () -> LocalDate = LocalDate::now,
    private val log: (String) -> Unit = {},
) {
    private data class Key(val profile: ActiveProfile, val version: Long?, val day: LocalDate)

    private val reading = Mutex()
    private var cached: Pair<Key, DiceDeck>? = null
    private val dials = HashMap<String, DiceFilters>()
    private val cameUp = HashMap<String, MutableSet<String>>()
    private var cameUpOn: LocalDate? = null

    /** The face the top bar's die shows: the last it landed on, five until then (the symbol's). */
    var face: Int = DEFAULT_FACE

    /** [profile]'s table: read again after a sync ([version], the last sync's time) or on a new day. Never throws: a failed read is an empty table. */
    suspend fun deck(profile: ActiveProfile, version: Long?): DiceDeck = reading.withLock {
        val key = Key(profile, version, today())
        cached?.takeIf { it.first == key }?.let { return@withLock it.second }
        val deck = try {
            read(profile)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("dice: reading the table failed: ${e.javaClass.simpleName}: ${e.message?.take(160)}")
            return@withLock DiceDeck.Empty
        }
        cached = key to deck
        log("dice: ${deck.titles.size} titles on the table")
        deck
    }

    /** Where [profileId] left the dials; all Any the first time. */
    fun filters(profileId: String): DiceFilters = dials[profileId] ?: DiceFilters()

    fun keep(profileId: String, filters: DiceFilters) {
        dials[profileId] = filters
    }

    /**
     * A throw for [profileId] at what fits [filters] ([Dice.roll]), the [throws]th with these
     * dials. What comes up stays off the table for them until everything that fits has, or
     * until tomorrow. Null when nothing fits.
     */
    fun roll(profileId: String, deck: DiceDeck, filters: DiceFilters, throws: Int): DieRoll? {
        val day = today()
        if (cameUpOn != day) {
            cameUp.clear()
            cameUpOn = day
        }
        val theirs = cameUp.getOrPut(profileId) { HashSet() }
        val roll = Dice.roll(deck, filters, theirs, throws, random) ?: return null
        if (roll.startedOver) deck.matching(filters).forEach { theirs -= it.ratingKey }
        theirs += roll.title.ratingKey
        return roll
    }

    companion object {
        const val DEFAULT_FACE = 5

        /** No library: an empty table (previews and screenshots that don't roll). */
        val None = DiceTable()
    }
}
