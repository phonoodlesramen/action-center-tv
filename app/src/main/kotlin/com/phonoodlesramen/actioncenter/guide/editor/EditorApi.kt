package com.phonoodlesramen.actioncenter.guide.editor

import com.phonoodlesramen.actioncenter.guide.ChannelDraft
import com.phonoodlesramen.actioncenter.guide.EditRejected
import com.phonoodlesramen.actioncenter.guide.EditableChannel
import com.phonoodlesramen.actioncenter.guide.GuideEditor
import com.phonoodlesramen.actioncenter.guide.GuideSlot
import com.phonoodlesramen.actioncenter.guide.RuleChoices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The phone editor's JSON API (plan §4), one route per [GuideEditor] call. [EditorSite]
 * has already checked the session; paths here are after `/api`.
 *
 * | Route | Calls |
 * |---|---|
 * | `GET lineup` | [GuideEditor.lineup]'s first value |
 * | `GET choices` | [GuideEditor.choices] |
 * | `POST channels` | [GuideEditor.create], from a [ChannelDraft]; `{"id"}` |
 * | `PUT channels/{id}`, `DELETE channels/{id}` | [GuideEditor.update], [GuideEditor.delete] |
 * | `POST channels/{id}/duplicate` | [GuideEditor.duplicate]; `{"id"}` |
 * | `PUT channels/{id}/name`, `/number`, `/enabled` | rename, renumber, setEnabled |
 * | `POST channels/{id}/move` with `{"index"}` | [GuideEditor.move] |
 * | `POST preview` | [GuideEditor.preview], as [PreviewSlot]s |
 * | `POST remake` | [GuideEditor.remakeStarterLineup] |
 *
 * - **Errors:** [EditRejected] is 400 with its sentence; a body that can't be read is 400;
 *   anything else is 500 with a general sentence, and only the exception's class is
 *   logged.
 * - **Nothing secret:** what's returned is the editor's own types and [PreviewSlot], none
 *   of which carries the Plex token or the server's address.
 */
class EditorApi(private val editor: GuideEditor, private val log: (String) -> Unit) : PhoneApi {
    override suspend fun handle(method: String, path: List<String>, body: ByteArray): HttpResponse = try {
        route(method, path, body)
    } catch (e: CancellationException) {
        throw e
    } catch (e: EditRejected) {
        HttpResponse.error(400, e.message ?: GENERAL_ERROR)
    } catch (e: HttpError) {
        HttpResponse.error(e.status, e.message ?: GENERAL_ERROR)
    } catch (e: Exception) {
        log("phone editor: ${e.javaClass.simpleName}")
        HttpResponse.error(500, GENERAL_ERROR)
    }

    private suspend fun route(method: String, path: List<String>, body: ByteArray): HttpResponse {
        val channel = path.getOrNull(1)
        return when {
            path == listOf("lineup") -> only(method, "GET") {
                ok(ListSerializer(EditableChannel.serializer()), editor.lineup.first())
            }
            path == listOf("choices") -> only(method, "GET") { ok(RuleChoices.serializer(), editor.choices()) }
            path == listOf("channels") -> only(method, "POST") {
                created(editor.create(read(ChannelDraft.serializer(), body)))
            }
            path.size == 2 && path[0] == "channels" && channel != null -> when (method) {
                "PUT" -> done { editor.update(channel, read(ChannelDraft.serializer(), body)) }
                "DELETE" -> done { editor.delete(channel) }
                else -> notAllowed("PUT, DELETE")
            }
            path.size == 3 && path[0] == "channels" && channel != null -> when (path[2]) {
                "duplicate" -> only(method, "POST") { created(editor.duplicate(channel)) }
                "name" -> only(method, "PUT") { done { editor.rename(channel, read(NameBody.serializer(), body).name) } }
                "number" -> only(method, "PUT") { done { editor.renumber(channel, read(NumberBody.serializer(), body).number) } }
                "enabled" -> only(method, "PUT") { done { editor.setEnabled(channel, read(EnabledBody.serializer(), body).enabled) } }
                "move" -> only(method, "POST") { done { editor.move(channel, read(MoveBody.serializer(), body).index) } }
                else -> notFound()
            }
            path == listOf("preview") -> only(method, "POST") {
                val slots = editor.preview(read(ChannelDraft.serializer(), body)).map(PreviewSlot::of)
                ok(ListSerializer(PreviewSlot.serializer()), slots)
            }
            path == listOf("remake") -> only(method, "POST") { done { editor.remakeStarterLineup() } }
            else -> notFound()
        }
    }

    private inline fun only(method: String, allowed: String, answer: () -> HttpResponse) =
        if (method == allowed) answer() else notAllowed(allowed)

    private inline fun done(change: () -> Unit): HttpResponse {
        change()
        return HttpResponse.noContent()
    }

    private fun <T> ok(serializer: KSerializer<T>, value: T) = HttpResponse.json(200, EditorJson.json.encodeToString(serializer, value))

    private fun created(id: String) = HttpResponse.json(201, EditorJson.json.encodeToString(IdBody.serializer(), IdBody(id)))

    /** [body] as JSON; one that can't be read is 400. The editor's own exceptions stay apart, as 500s. */
    private fun <T> read(serializer: KSerializer<T>, body: ByteArray): T = try {
        EditorJson.json.decodeFromString(serializer, body.decodeToString())
    } catch (_: IllegalArgumentException) {
        // SerializationException is one too.
        throw HttpError(400, UNREADABLE)
    }

    private fun notFound() = HttpResponse.error(404, "Not found")

    private fun notAllowed(allowed: String) = HttpResponse.error(405, "Not allowed", listOf("Allow" to allowed))

    companion object {
        const val GENERAL_ERROR = "Something went wrong on the Shield. Try again."
        const val UNREADABLE = "The Shield couldn't read that. Reload the page and try again."
    }
}

/** A program in a preview: what the page lists, and nothing else (no art, no keys). */
@Serializable
data class PreviewSlot(val start: Long, val end: Long, val title: String, val episode: String?, val filler: Boolean) {
    companion object {
        fun of(slot: GuideSlot) = PreviewSlot(slot.startMillis, slot.endMillis, slot.title, slot.episode, slot.filler)
    }
}

@Serializable
internal data class IdBody(val id: String)

@Serializable
internal data class NameBody(val name: String)

@Serializable
internal data class NumberBody(val number: Int)

@Serializable
internal data class EnabledBody(val enabled: Boolean)

@Serializable
internal data class MoveBody(val index: Int)

@Serializable
internal data class PinBody(val pin: String)

@Serializable
internal data class ErrorBody(val error: String)

/** The editor's JSON: every field written, so the page sees each rule in full; unknown fields from the page ignored. */
internal object EditorJson {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun error(message: String): String = json.encodeToString(ErrorBody.serializer(), ErrorBody(message))
}
