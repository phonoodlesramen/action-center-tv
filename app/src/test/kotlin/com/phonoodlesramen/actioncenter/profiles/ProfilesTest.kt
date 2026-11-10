package com.phonoodlesramen.actioncenter.profiles

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.phonoodlesramen.actioncenter.suggest.Profiles
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Ages, brackets and ceilings (K3, K4), and the store. Every name here is made up. */
@RunWith(RobolectricTestRunner::class)
class ProfilesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, ProfileDatabase::class.java).allowMainThreadQueries().build()
    private val store = RoomProfileStore(db.dao())
    private val day = LocalDate.of(2026, 10, 27)
    private val pg = AgeBrackets.DEFAULT[0].ratings
    private val pg13 = AgeBrackets.DEFAULT[1].ratings

    @After
    fun tearDown() = db.close()

    private fun kid(id: String, name: String, born: LocalDate, sort: Int = 0) =
        KidEntity(id, name, color = 0xFFFF8A65, birthday = born.toEpochDay(), sort = sort)

    @Test
    fun `age turns over on the birthday, and a 29 February one on 1 March`() {
        val born = LocalDate.of(2017, 10, 28)
        assertEquals(8, AgeBrackets.age(born, day))
        assertEquals(9, AgeBrackets.age(born, day.plusDays(1)))
        val leap = LocalDate.of(2016, 2, 29)
        assertEquals(9, AgeBrackets.age(leap, LocalDate.of(2026, 2, 28)))
        assertEquals(10, AgeBrackets.age(leap, LocalDate.of(2026, 3, 1)))
    }

    @Test
    fun `the ceiling follows the bracket, moves up on a birthday, and an override wins`() {
        val born = LocalDate.of(2016, 10, 28)
        assertEquals("9: the 7-9 bracket", pg, AgeBrackets.ceiling(born, null, AgeBrackets.DEFAULT, day))
        assertEquals("10 tomorrow: PG-13 joins", pg13, AgeBrackets.ceiling(born, null, AgeBrackets.DEFAULT, day.plusDays(1)))
        assertEquals(setOf("G"), AgeBrackets.ceiling(born, setOf("G"), AgeBrackets.DEFAULT, day))
        assertEquals("under 7: the first bracket", pg, AgeBrackets.ceiling(LocalDate.of(2021, 1, 1), null, AgeBrackets.DEFAULT, day))
        assertEquals("over 12: still the last, never everything", pg13, AgeBrackets.ceiling(LocalDate.of(2010, 1, 1), null, AgeBrackets.DEFAULT, day))
    }

    @Test
    fun `a title is allowed by its rating, an episode's by its show's, and an unrated one is hidden from kids`() {
        assertTrue(AgeBrackets.allows(pg, "PG"))
        assertFalse(AgeBrackets.allows(pg, "PG-13"))
        assertTrue("an episode with no rating takes its show's", AgeBrackets.allows(pg, null, "TV-Y7"))
        assertFalse("no rating at all: hidden (K4)", AgeBrackets.allows(pg, null, null))
        assertFalse("Not Rated isn't a rating a kid may see", AgeBrackets.allows(pg, "NR"))
        assertTrue("an adult sees everything", AgeBrackets.allows(null, null))
    }

    @Test
    fun `you come first, then the kids, and the current profile falls back to you`() = runTest {
        val name = flowOf("Alex")
        val today = MutableStateFlow(day)
        assertEquals(listOf(Profiles.OWNER), store.profiles(name, today).first().map { it.id })

        store.saveKid(kid("leo", "Leo", LocalDate.of(2019, 5, 1), sort = 1))
        store.saveKid(kid("maya", "Maya", LocalDate.of(2017, 3, 2)))
        val all = store.profiles(name, today).first()
        assertEquals(listOf("Alex", "Maya", "Leo"), all.map { it.name })
        val maya = all[1]
        assertTrue(maya.kid)
        assertEquals(9, maya.age)
        assertEquals(pg, maya.ceiling)
        assertEquals(emptySet<String>(), maya.apps)
        assertNull("you have no ceiling", all[0].ceiling)

        assertEquals(Profiles.OWNER, store.currentId.first())
        store.switchTo("maya")
        assertEquals("maya", store.currentId.first())
        assertEquals("Maya", store.current(name, today).first().name)

        store.removeKid("maya")
        assertEquals("a removed kid's profile falls back to you", Profiles.OWNER, store.currentId.first())
        assertEquals("Alex", store.current(name, today).first().name)
    }

    @Test
    fun `an edited bracket table is kept, and an empty one falls back to the default`() = runTest {
        assertEquals(AgeBrackets.DEFAULT, store.brackets.first())
        val edited = listOf(AgeBracket(7, 12, setOf("G", "PG")))
        store.saveBrackets(edited)
        assertEquals(edited, store.brackets.first())
    }
}
