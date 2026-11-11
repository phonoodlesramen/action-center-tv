package com.phonoodlesramen.actioncenter.ui.theme

import com.phonoodlesramen.actioncenter.guide.ChannelGlyphs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every glyph `ChannelGlyphs` can pick is one the TV can draw (I1). */
class ChannelGlyphIconsTest {
    @Test
    fun `every glyph the guide can pick has its path, and no others`() {
        assertEquals(ChannelGlyphs.ALL.toSet(), ChannelGlyphIcons.PATHS.keys)
        for (name in ChannelGlyphs.ALL) assertNotNull(name, ChannelGlyphIcons.of(name))
        assertNull("a newer station's glyph falls back to the monogram", ChannelGlyphIcons.of("not_a_glyph"))
        assertNull(ChannelGlyphIcons.of(null))
    }

    @Test
    fun `every path is a 960-unit symbol, which symbol() draws, not an older 24-unit one, which it would leave blank`() {
        for ((name, path) in ChannelGlyphIcons.PATHS) {
            val numbers = Regex("-?\\d+(\\.\\d+)?").findAll(path).map { it.value.toDouble() }.toList()
            assertTrue("$name reaches into the 960-unit box", numbers.any { it <= -100 })
        }
    }
}
