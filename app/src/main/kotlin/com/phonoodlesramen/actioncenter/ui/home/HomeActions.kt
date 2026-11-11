package com.phonoodlesramen.actioncenter.ui.home

import com.phonoodlesramen.actioncenter.apps.LaunchableApp
import com.phonoodlesramen.actioncenter.content.LensPick
import com.phonoodlesramen.actioncenter.content.Pick
import com.phonoodlesramen.actioncenter.content.QuickItem
import com.phonoodlesramen.actioncenter.content.UpNextItem
import com.phonoodlesramen.actioncenter.guide.GuideChannel
import com.phonoodlesramen.actioncenter.home.ChannelButton
import com.phonoodlesramen.actioncenter.lastapp.AppVisit
import com.phonoodlesramen.actioncenter.lastapp.TunedChannel
import com.phonoodlesramen.actioncenter.nfl.WatchWay
import com.phonoodlesramen.actioncenter.player.SavedSpot
import com.phonoodlesramen.actioncenter.search.StreamingApp
import com.phonoodlesramen.actioncenter.ui.nfl.NflOpen

/** Everything the home screen and All apps can ask for. Defaults do nothing (previews, screenshots). */
interface HomeActions {
    /** Plays [id] (a Plex ratingKey) by handing off to Plezy or Plex, by Settings › Plex › Play in (D11, Phase 11). Sample content can't play. */
    fun play(id: String, title: String) {}

    /**
     * The Plex Guide's Open in Plezy (or Plex): [id] is always a real title from the library,
     * so it hands off even while the home shows sample content (2.7 follow-up). Nothing is
     * written to Plex: nothing's playing.
     */
    fun openIn(id: String, title: String) = play(id, title)

    /** Plays [pick]'s Plex trailer extra through Plezy, or searches YouTube for its trailer. Sample content can't. */
    fun trailer(pick: Pick) {}

    /**
     * Opens an intent URI another app published (Channels, Continue in your apps: L8, L9),
     * or a plain link. [what] names it in the toast if nothing can open it.
     */
    fun openIntentUri(uri: String, what: String) {}

    /** Tunes in to a Plex Guide channel (G5): the launcher's own player, joining the program midway. */
    fun tuneIn(channelId: String) {}

    /** Tunes in to [channelId] with the program on now from its start (G11). */
    fun startOver(channelId: String) {}

    /** The Back-to card's OK and Resume (P4): the player on [spot]'s channel, paused on its frame, behind live. */
    fun resume(spot: SavedSpot) {}

    /** The Back-to card's Back to live (P4): [channelId] live, and the saved spot forgotten. */
    fun backToLive(channelId: String) {}

    /** The Back-to card's Forget this (P4): the spot is forgotten, and the card goes back to what it showed. */
    fun forgetSpot() {}

    /**
     * A network in the Live row (L8). A YouTube TV channel is remembered: it comes
     * after the favorites in the row, and in Back to YouTube TV's quick actions.
     */
    fun watchNetwork(button: ChannelButton) = openIntentUri(button.intentUri, button.name)

    /** Back to YouTube TV's Watch {channel}: [channel] in YouTube TV, remembered again. */
    fun watchTvChannel(channel: TunedChannel) {}

    /** Back to YouTube TV's Forget this: [visit]'s card goes, and the Guide card is back. */
    fun forgetVisit(visit: AppVisit) {}

    /** The full-screen details of the program on [channelId] that starts at [startMillis] (2.7). */
    fun openDetails(channelId: String, startMillis: Long) {}
    fun notTonight() {}
    fun undo() {}
    /** The lens card's next title, running on into the next lens: Next, and the card moving on by itself (L1). */
    fun nextLensPick() {}

    /** The lens card's Back (L3): the title it showed before. */
    fun previousLensPick() {}

    /** The lens card showed [pick] for a few seconds with the home on screen (L5): a day on screen. */
    fun lensShown(pick: LensPick) {}

    /** Quick actions › Another lens. */
    fun nextLens() {}

    /** Up Next › Remove from Up Next (S10): until you play it again. Plex is unchanged. */
    fun removeFromUpNext(item: UpNextItem) {}

    /** Quick Watch › Hide from Quick Watch (S10): for 30 days. */
    fun hideFromQuickWatch(item: QuickItem) {}

    /** The lens card › Don't show this (S10): off that lens for 6 months. */
    fun dontShowLensPick() {}

    /** Tonight's Pick › More like this (S10): the lens card shows titles like the pick. */
    fun moreLikeThis() {}
    fun launch(app: LaunchableApp) {}
    fun setHidden(app: LaunchableApp, hidden: Boolean) {}
    fun appInfo(app: LaunchableApp) {}
    fun openAllApps() {}
    fun openSettings() {}

    /** Search (Phase 15), from the top bar's magnifier or the remote's Search key. */
    fun openSearch() {}

    /** Search's last row (Q4, grown-ups only): Google's TV search, with [text] if it takes it. */
    fun searchWithGoogle(text: String) {}

    /** Search opened something for [text]: it leads the profile on now's last searches. */
    fun rememberSearch(text: String) {}

    /**
     * Search's streaming row (Q3): [title] in [app], by the title's link when the app takes it,
     * else the app's home with "Search for [title] in it".
     */
    fun watchOn(app: StreamingApp, title: String) {}

    /** Settings › Diagnostics, from the "Stock launcher is back" chip. */
    fun openDiagnostics() {}

    /** Settings › Setup, from the "Finish setup" or "Home button is off" chip. */
    fun openSetup() {}

    /** The "Update available" or "Update ready" chip: Settings › About, or the install itself once it's downloaded. */
    fun openUpdate() {}

    /** Settings › Plex: account, server, sign out. */
    fun openPlexSettings() {}

    /** "Who's watching?" (K1), from the avatar. */
    fun openProfiles() {}

    /** [profileId] is on from now (K1, K11): the kids' avatars on a weekend morning switch in one press. */
    fun switchProfile(profileId: String) {}

    /** The full guide, at now, on [channelId] (a Live card's Full guide) or the first channel (the Guide card). */
    fun openGuide(channelId: String? = null) {}

    /**
     * Add to favorites or Remove from favorites (Phase 13, C2), for the profile on now. A toast
     * says what changed, or why it couldn't (the station can't be reached, N5).
     */
    fun setFavorite(channel: GuideChannel, favorite: Boolean) {}

    /** Move put down on the Live row (C3): [order] is the profile's favorites as they now stand; [done] hears whether they were kept. */
    fun saveFavorites(order: List<String>, done: (kept: Boolean) -> Unit) = done(true)

    /** The NFL screen (Phase 13, F3), on [open]'s page: a game in Games, a tab, a game's details or a team. */
    fun openNfl(open: NflOpen) {}

    /** Watch a game in [way]'s app (F6, F7), through the hand-off code; [what] names the game in a toast if nothing opens. */
    fun watch(way: WatchWay, what: String) {}

    /** Hide scores › Show score (F10, C): [gameId]'s score, on this TV, until tomorrow. */
    fun showScore(gameId: String) {}

    /** The game-day card's Not today (F1): Quick Watch until tomorrow, on this TV. The NFL row stays. */
    fun notToday() {}

    /** Throws the current plex.tv/link code away and shows a new one. */
    fun newPlexCode() {}

    /** A short message in a toast: why a pick was chosen, or what a later phase adds. */
    fun say(message: String) {}

    companion object None : HomeActions
}
