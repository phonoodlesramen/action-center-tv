package com.phonoodlesramen.actioncenter.ai

import com.phonoodlesramen.actioncenter.guide.editor.EditorApi
import com.phonoodlesramen.actioncenter.guide.editor.EditorJson
import com.phonoodlesramen.actioncenter.guide.editor.HttpResponse
import com.phonoodlesramen.actioncenter.guide.editor.PhoneApi
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.Serializable

/**
 * The key page's API (M10), behind the phone editor's sign-in ([com.phonoodlesramen.actioncenter.guide.editor.EditorSite]
 * has already checked the session). Paths are after `/api`.
 *
 * | Route | Does |
 * |---|---|
 * | `GET key` | `{"ending"}`: the stored key's last 4 characters, or null |
 * | `POST key` with `{"key"}` | checks it ([AiKeys.problem]), stores it, calls [onSaved]; `{"ending"}` |
 *
 * - **The key** is never logged, never in an error, and never sent back: only its last 4
 *   characters are.
 * - **Errors:** a key that can't be one is 400 with its sentence; anything else is 500, and
 *   only the exception's class is logged.
 */
class AiKeyApi(
    private val keys: AiKeyDrop,
    private val onSaved: (ending: String) -> Unit,
    private val log: (String) -> Unit,
) : PhoneApi {
    override suspend fun handle(method: String, path: List<String>, body: ByteArray): HttpResponse = try {
        when {
            path != listOf("key") -> HttpResponse.error(404, "Not found")
            method == "GET" -> ending(keys.keyEnding())
            method == "POST" -> save(body)
            else -> HttpResponse.error(405, "Not allowed", listOf("Allow" to "GET, POST"))
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("key page: ${e.javaClass.simpleName}")
        HttpResponse.error(500, EditorApi.GENERAL_ERROR)
    }

    private suspend fun save(body: ByteArray): HttpResponse {
        val key = try {
            EditorJson.json.decodeFromString(KeyBody.serializer(), body.decodeToString()).key.trim()
        } catch (_: IllegalArgumentException) {
            // SerializationException is one too.
            return HttpResponse.error(400, EditorApi.UNREADABLE)
        }
        AiKeys.problem(key)?.let { return HttpResponse.error(400, it) }
        keys.saveKey(key)
        val ending = AiKeys.ending(key)
        onSaved(ending)
        return ending(ending)
    }

    private fun ending(ending: String?) = HttpResponse.json(200, EditorJson.json.encodeToString(EndingBody.serializer(), EndingBody(ending)))
}

@Serializable
private class KeyBody(val key: String)

@Serializable
private class EndingBody(val ending: String?)
