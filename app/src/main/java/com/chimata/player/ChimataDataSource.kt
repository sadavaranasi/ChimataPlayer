package com.chimata.player

import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.common.util.UnstableApi
import android.net.Uri
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
        const val SCHEME = "chimata"
        const val BASE = "https://chimatamusic.us"
        const val RESOLVE_ENDPOINT = "$BASE/telugu_songs/playeriphone.php"

        fun uriForSong(id: Int): Uri = Uri.parse("$SCHEME://plist/$id")

        /** Cache of plist id -> resolved absolute mp3 URL, avoids re-resolving on repeat play. */
        private val resolvedCache = HashMap<Int, String>()

        @Throws(IOException::class)
        fun resolveSongUrl(client: OkHttpClient, id: Int): Pair<String, String> {
            resolvedCache[id]?.let { return Pair(readableTitleCache[id] ?: "", it) }
            val request = Request.Builder()
                .url("$RESOLVE_ENDPOINT?plist=$id")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Failed to resolve song $id: HTTP ${response.code}")
                }
                val body = response.body?.string().orEmpty()
                // Format: "Title text|/telugu_songs/teluguSongs/.../File.mp3"
                val sepIndex = body.indexOf('|')
                if (sepIndex < 0) throw IOException("Unexpected resolve response for song $id")
                val title = body.substring(0, sepIndex).trim()
                val path = body.substring(sepIndex + 1).trim()
                val fullUrl = if (path.startsWith("http")) path else BASE + path
                resolvedCache[id] = fullUrl
                readableTitleCache[id] = title
                return Pair(title, fullUrl)
            }
        }

        private val readableTitleCache = HashMap<Int, String>()
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
        private val httpFactory = OkHttpDataSource.Factory(client)
        override fun createDataSource(): DataSource = ChimataDataSource(httpFactory, client)
    }
}
