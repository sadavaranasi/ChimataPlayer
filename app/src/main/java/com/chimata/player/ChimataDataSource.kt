package com.chimata.player

import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.common.util.UnstableApi
import android.net.Uri
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Media URIs in this app look like: chimata://plist/19025
 *
 * On open(), we resolve the real streaming URL the same way the site's own mobile
 * player page does: a GET to playeriphone.php?plist=<id>, which returns
 * "Title|Description|/telugu_songs/teluguSongs/.../File.mp3". We then delegate actual
 * playback to a real HTTP data source pointed at that resolved URL.
 */
@UnstableApi
class ChimataDataSource(
    private val httpFactory: OkHttpDataSource.Factory,
    private val client: OkHttpClient
) : BaseDataSource(true) {

    companion object {
        private const val TAG = "ChimataPlayer"

        const val SCHEME = "chimata"
        const val BASE = "https://chimatamusic.us"
        const val REFERER = "$BASE/telugu_songs/Telugu-Movie-Songs-BigIndex.php"
        const val RESOLVE_ENDPOINT = "$BASE/telugu_songs/playeriphone.php"

        // Mimic a real mobile browser. Many sites (this one included, based on how its player
        // is built) silently reject or misbehave on requests that don't look like they came
        // from an actual browser tab - no User-Agent/Referer is a dead giveaway of a bot/script.
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Mobile Safari/537.36"

        /** Shared headers used for both the plist lookup call and the actual audio file fetch. */
        val browserHeaders: Map<String, String> = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to REFERER
        )

        fun uriForSong(id: Int): Uri = Uri.parse("$SCHEME://plist/$id")

        /** Cache of plist id -> resolved absolute mp3 URL, avoids re-resolving on repeat play. */
        private val resolvedCache = HashMap<Int, String>()
        private val readableTitleCache = HashMap<Int, String>()

        @Throws(IOException::class)
        fun resolveSongUrl(client: OkHttpClient, id: Int): Pair<String, String> {
            resolvedCache[id]?.let { return Pair(readableTitleCache[id] ?: "", it) }

            val requestBuilder = Request.Builder().url("$RESOLVE_ENDPOINT?plist=$id")
            browserHeaders.forEach { (k, v) -> requestBuilder.header(k, v) }

            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    val msg = "Resolve failed for song $id: HTTP ${response.code}"
                    Log.e(TAG, msg)
                    throw IOException(msg)
                }
                val body = response.body?.string().orEmpty()
                Log.d(TAG, "Resolve response for song $id: $body")

                // Expected format: "Title text|/telugu_songs/teluguSongs/.../File.mp3"
                val sepIndex = body.indexOf('|')
                if (sepIndex < 0 || body.isBlank()) {
                    val msg = "Unexpected/empty resolve response for song $id: '$body'"
                    Log.e(TAG, msg)
                    throw IOException(msg)
                }
                val title = body.substring(0, sepIndex).trim()
                val path = body.substring(sepIndex + 1).trim()
                if (path.isBlank()) {
                    val msg = "No file path in resolve response for song $id"
                    Log.e(TAG, msg)
                    throw IOException(msg)
                }
                val fullUrl = if (path.startsWith("http")) path else BASE + path
                Log.d(TAG, "Song $id resolved to: $fullUrl")
                resolvedCache[id] = fullUrl
                readableTitleCache[id] = title
                return Pair(title, fullUrl)
            }
        }
    }

    private var upstream: DataSource? = null

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        val uri = dataSpec.uri
        if (uri.scheme != SCHEME) {
            throw IOException("Unsupported URI scheme: ${uri.scheme}")
        }
        val id = uri.lastPathSegment?.toIntOrNull()
            ?: throw IOException("Missing/invalid song id in $uri")

        val (_, realUrl) = resolveSongUrl(client, id)

        val http = httpFactory.createDataSource()
        upstream = http
        val realSpec = dataSpec.buildUpon().setUri(Uri.parse(realUrl)).build()
        return http.open(realSpec)
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        return upstream?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT
    }

    override fun getUri(): Uri? = upstream?.uri

    override fun close() {
        (upstream as? HttpDataSource)?.close()
        upstream = null
    }

    class Factory(private val client: OkHttpClient) : DataSource.Factory {
        // Same browser-like headers on the actual audio-file fetch, in case the mp3 files
        // themselves are protected against hotlinking/scripted access (common on sites like this).
        private val httpFactory = OkHttpDataSource.Factory(client)
            .setDefaultRequestProperties(browserHeaders)
        override fun createDataSource(): DataSource = ChimataDataSource(httpFactory, client)
    }
}
