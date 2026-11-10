package com.phonoodlesramen.actioncenter.ui.art

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.phonoodlesramen.actioncenter.plex.ServerAccess
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlexArtTest {
    private val server = MockWebServer()

    @After
    fun tearDown() = server.close()

    @Test
    fun `art goes through the photo transcoder at the tile's pixel size, with the token inside and out`() {
        val urls = PlexArtUrls(ServerAccess("https://192-168-1-20.aaaa.plex.direct:32400/", "server-token"))

        val url = urls.url("/library/metadata/7303/art/1793000000", 310, 174)!!.toHttpUrl()

        assertEquals("/photo/:/transcode", url.encodedPath)
        assertEquals("310", url.queryParameter("width"))
        assertEquals("174", url.queryParameter("height"))
        assertEquals("1", url.queryParameter("minSize"))
        assertEquals("/library/metadata/7303/art/1793000000?X-Plex-Token=server-token", url.queryParameter("url"))
        assertEquals("server-token", url.queryParameter("X-Plex-Token"))
    }

    @Test
    fun `no server, or no size yet, means placeholder art`() {
        assertNull(PlexArtUrls(null).url("/art", 100, 100))
        assertNull(PlexArtUrls(ServerAccess("https://x", "t")).url("/art", 0, 100))
    }

    @Test
    fun `art is a JPEG covering the tile, and a logo a PNG fitted inside its box, its transparency kept`() {
        val urls = PlexArtUrls(ServerAccess("https://192-168-1-20.aaaa.plex.direct:32400/", "server-token"))

        val art = urls.url("/library/metadata/7303/art/1793000000", 310, 174)!!.toHttpUrl()
        val logo = urls.logoUrl("/library/metadata/7303/clearLogo/1793000000", 712, 120)!!.toHttpUrl()

        assertEquals("jpg" to "1", art.queryParameter("format") to art.queryParameter("minSize"))
        assertEquals("/photo/:/transcode", logo.encodedPath)
        assertEquals(
            listOf("712", "120", "0", "1", "png"),
            listOf("width", "height", "minSize", "upscale", "format").map { logo.queryParameter(it) },
        )
        assertNull("the transcoder's default background is none", logo.queryParameter("background"))
        assertEquals("/library/metadata/7303/clearLogo/1793000000?X-Plex-Token=server-token", logo.queryParameter("url"))
        assertEquals("server-token", logo.queryParameter("X-Plex-Token"))
    }

    @Test
    fun `no server, no size, or art URLs that don't know logos, means the title`() {
        assertNull(PlexArtUrls(null).logoUrl("/logo", 100, 20))
        assertNull(PlexArtUrls(ServerAccess("https://x", "t")).logoUrl("/logo", 100, 0))
        assertNull(ArtUrls { path, _, _ -> path }.logoUrl("/logo", 100, 20))
    }

    @Test
    fun `Coil loads an image through OkHttp 5 (Coil's network module was built for OkHttp 4)`() = runTest {
        val png = ByteArrayOutputStream().also {
            Bitmap.createBitmap(32, 18, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }.compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        server.start()
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "image/png").body(Buffer().write(png)).build())
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val loader = ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { OkHttpClient() })) }
            .build()

        val result = loader.execute(ImageRequest.Builder(context).data(server.url("/photo/:/transcode?width=32").toString()).build())

        assertTrue("Got $result", result is SuccessResult)
        assertEquals(32, (result as SuccessResult).image.width)
    }
}
