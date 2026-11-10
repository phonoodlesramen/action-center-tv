package com.phonoodlesramen.actioncenter.player

import androidx.media3.common.PlaybackException
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Media3's error codes, sorted into the off-air cards a viewer can follow. */
class VideoErrorTest {
    private fun reason(code: Int) = VideoError.reasonFor(code)

    @Test
    fun `a connection that fails or times out is Plex offline`() {
        assertEquals(OffAirReason.OFFLINE, reason(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
        assertEquals(OffAirReason.OFFLINE, reason(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT))
    }

    @Test
    fun `Plex answering without the file is unavailable, as when the NAS drive is restarting`() {
        listOf(
            // The operator's case, 28 Oct 2026: Plex up, the drive not.
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
        ).forEach { assertEquals("code $it", OffAirReason.UNAVAILABLE, reason(it)) }
    }

    @Test
    fun `a format the Shield can't play, and anything unknown, is can't play`() {
        listOf(
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
            PlaybackException.ERROR_CODE_DRM_UNSPECIFIED,
            PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED,
            // A setting on the Shield: trying again wouldn't change it.
            PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
            PlaybackException.ERROR_CODE_UNSPECIFIED,
            PlaybackException.ERROR_CODE_TIMEOUT,
            // A code this Media3 doesn't have.
            999_999,
        ).forEach { assertEquals("code $it", OffAirReason.CANT_PLAY, reason(it)) }
    }

    /** Every code this Media3 has, by name, so a new one in an upgrade lands somewhere sensible or fails here. */
    private val codes: Map<String, Int> = PlaybackException::class.java.fields
        .filter { Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType && it.name.startsWith("ERROR_CODE_") }
        .associate { it.name to it.getInt(null) }

    @Test
    fun `every IO code is offline or unavailable, except cleartext, and every format code is can't play`() {
        assertTrue(codes.size > 40)
        codes.forEach { (name, code) ->
            val expected = when {
                name == "ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED" -> OffAirReason.CANT_PLAY
                name.startsWith("ERROR_CODE_IO_NETWORK_") -> OffAirReason.OFFLINE
                name.startsWith("ERROR_CODE_IO_") -> OffAirReason.UNAVAILABLE
                else -> OffAirReason.CANT_PLAY
            }
            assertEquals(name, expected, reason(code))
        }
    }
}
