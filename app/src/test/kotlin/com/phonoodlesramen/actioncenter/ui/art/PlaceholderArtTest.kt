package com.phonoodlesramen.actioncenter.ui.art

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceholderArtTest {
    @Test
    fun `the same title always gets the same art`() {
        assertEquals(PlaceholderArt.specFor("severance"), PlaceholderArt.specFor("severance"))
    }

    @Test
    fun `different titles spread across palettes and motifs`() {
        val specs = (1..40).map { PlaceholderArt.specFor("title-$it") }

        assertTrue(specs.map { it.from }.distinct().size >= 6)
        assertTrue(specs.map { it.motif }.distinct().size >= 4)
    }
}
