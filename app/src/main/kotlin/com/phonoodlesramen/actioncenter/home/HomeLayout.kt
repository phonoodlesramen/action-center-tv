package com.phonoodlesramen.actioncenter.home

/**
 * Settings › Look › Layout (spec §4, Home layouts): how your home is laid out. A kid's home
 * keeps its own layout in every one (K7). Stored by [name], so a layout that's gone reads as
 * [DASHBOARD].
 */
enum class HomeLayout(val title: String, val detail: String) {
    /** v1: the top bar, Tonight's Pick, the lens card, Quick Watch, Up Next and the Plex Guide card. */
    DASHBOARD("Dashboard", "Tonight's pick, the lens card, Quick Watch, Up Next and the guide, all on the first screen."),

    /** The title you rest on fills the screen, over a strip of what's on for tonight. */
    MARQUEE("Marquee", "The title you rest on fills the screen, above a strip of tonight's picks. The rows below stay as they are."),
    ;

    companion object {
        val DEFAULT = DASHBOARD

        fun of(name: String?): HomeLayout = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
