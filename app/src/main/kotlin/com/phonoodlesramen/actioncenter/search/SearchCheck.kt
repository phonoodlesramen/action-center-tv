package com.phonoodlesramen.actioncenter.search

import com.phonoodlesramen.actioncenter.plex.CatalogOffer
import com.phonoodlesramen.actioncenter.plex.CatalogTitle
import com.phonoodlesramen.actioncenter.plex.PlexCatalogApi
import com.phonoodlesramen.actioncenter.plex.PlexException
import com.phonoodlesramen.actioncenter.plex.PlexServerApi
import com.phonoodlesramen.actioncenter.plex.ServerAccess
import kotlin.coroutines.cancellation.CancellationException

/** One line of Diagnostics › Search check (Phase 15): what was asked, and what came back. Never a token. */
data class CheckLine(val label: String, val result: String, val ok: Boolean)

/** What the check found: its lines, and the catalog title and its offers, for the buttons that try them on this TV. */
data class SearchCheckResult(
    val lines: List<CheckLine> = emptyList(),
    val title: CatalogTitle? = null,
    val offers: List<CatalogOffer> = emptyList(),
    val done: Boolean = false,
)

/**
 * Diagnostics › Search check (Phase 15, plan step 4): what this TV gets from each piece Search
 * leans on, before the streaming row is built on it. Plex's search on your server; the
 * catalog's search and a title's details (no token); "where to watch" for that title with your
 * plex.tv token (the one call that needs it); the search providers other apps declare. Counts,
 * names and statuses only: never a token.
 */
class SearchCheck(
    private val server: PlexServerApi,
    private val access: suspend () -> ServerAccess?,
    private val catalog: PlexCatalogApi,
    private val userToken: suspend () -> String?,
    /** Packages of the apps that offer Android's global search a provider (`SearchManager.getSearchablesInGlobalSearch`). */
    private val searchables: () -> List<String>,
) {
    suspend fun run(libraryWords: String = LIBRARY_WORDS, catalogWords: String = CATALOG_WORDS, report: (SearchCheckResult) -> Unit) {
        var result = SearchCheckResult()
        fun add(line: CheckLine) {
            result = result.copy(lines = result.lines + line)
            report(result)
        }

        add(librarySearch(libraryWords))
        val search = step("Plex's catalog: “$catalogWords”") { catalog.search(catalogWords, CATALOG_LIMIT) }
        val title = search.value?.titles?.firstOrNull()
        add(
            search.line { found ->
                if (found.titles.isEmpty()) {
                    "No titles"
                } else {
                    "${found.titles.size} titles, first ${label(found.titles.first())}; ${found.suggestedTerms.size} suggested terms"
                }
            },
        )
        if (title != null) {
            add(
                step("Its details: ${title.title}") { catalog.details(title.ratingKey) }.line { details ->
                    if (details == null) "Not found" else listOfNotNull(details.contentRating?.let { "Rated $it" } ?: "No rating", details.commonSenseAge?.let { "Common Sense $it+" }).joinToString(" · ")
                },
            )
            val token = userToken()
            if (token == null) {
                add(CheckLine("Where to watch: ${title.title}", "No Plex account on this TV", ok = false))
            } else {
                val offers = step("Where to watch: ${title.title}") { catalog.offers(title.ratingKey, token) }
                result = result.copy(title = title, offers = offers.value.orEmpty())
                add(
                    offers.line { found ->
                        if (found.isEmpty()) {
                            "Answered, with no offers"
                        } else {
                            "${found.size} offers: " + found.joinToString(", ") { "${it.title} [${it.platform}] (${it.offerType})${if (it.url == null) ", no link" else ""}" }
                        }
                    },
                )
            }
        }
        val providers = try {
            searchables()
        } catch (e: RuntimeException) {
            null
        }
        add(
            CheckLine(
                "Search providers other apps declare",
                providers?.let { if (it.isEmpty()) "None" else "${it.size}: ${it.joinToString(", ")}" } ?: "Couldn't ask",
                ok = providers != null,
            ),
        )
        result = result.copy(done = true)
        report(result)
    }

    private suspend fun librarySearch(words: String): CheckLine {
        val label = "Plex's search on your server: “$words”"
        val access = access() ?: return CheckLine(label, "No server linked", ok = false)
        return step(label) { server.search(access, words, LIBRARY_LIMIT) }.line { hubs ->
            if (hubs.isEmpty()) {
                "No hubs"
            } else {
                val withReason = hubs.sumOf { hub -> hub.hits.count { it.reason != null } }
                "${hubs.size} hubs: " + hubs.joinToString(", ") { "${it.type.ifEmpty { "?" }} ${it.hits.size}" } +
                    "; $withReason found through something else (an actor, a genre)"
            }
        }
    }

    private class Step<T>(val label: String, val value: T?, val failure: String?) {
        fun line(describe: (T) -> String): CheckLine =
            if (failure != null || value == null) CheckLine(label, failure ?: "Nothing", ok = false) else CheckLine(label, describe(value), ok = true)
    }

    private suspend fun <T> step(label: String, block: suspend () -> T): Step<T> = try {
        Step(label, block(), null)
    } catch (e: CancellationException) {
        throw e
    } catch (e: PlexException.Unauthorized) {
        Step(label, null, "Plex said no (401)")
    } catch (e: PlexException) {
        Step(label, null, e.message ?: e.javaClass.simpleName)
    }

    private fun label(title: CatalogTitle) = "${title.title}${title.year?.let { " ($it)" }.orEmpty()}"

    companion object {
        /** Words a library almost surely holds, and a film the catalog surely does. */
        const val LIBRARY_WORDS = "the"
        const val CATALOG_WORDS = "heat"
        private const val LIBRARY_LIMIT = 5
        private const val CATALOG_LIMIT = 10
    }
}
