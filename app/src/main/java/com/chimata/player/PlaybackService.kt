package com.chimata.player

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

@UnstableApi
class PlaybackService : MediaSessionService() {

    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaSession
    private lateinit var okHttpClient: OkHttpClient

    override fun onCreate() {
        super.onCreate()

        okHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        val dataSourceFactory = ChimataDataSource.Factory(okHttpClient)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()

        // Default: repeat the whole queue so "next" at the end just loops back to the start.
        player.repeatMode = Player.REPEAT_MODE_ALL

        val sessionActivityIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivityIntent)
            .build()

        // Build the full catalog as the ExoPlayer queue up front. Each item's URI is a
        // lightweight virtual "chimata://plist/<id>" reference; ChimataDataSource resolves the
        // real streaming URL lazily, only when ExoPlayer actually needs to load that item.
        val songs = Catalog.loadFlatSongs(this)
        val items = songs.map { song ->
            MediaItem.Builder()
                .setMediaId(song.id.toString())
                .setUri(ChimataDataSource.uriForSong(song.id))
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(song.title)
                        .setArtist(song.movie)
                        .build()
                )
                .build()
        }
        player.setMediaItems(items)
        player.prepare()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession {
        return mediaSession
    }

    /** Called from MainActivity to jump the queue to a specific song id. */
    fun playSongId(id: Int) {
        val items = player.let { p -> (0 until p.mediaItemCount).map { p.getMediaItemAt(it) } }
        val index = items.indexOfFirst { it.mediaId == id.toString() }
        if (index >= 0) {
            player.seekTo(index, C.TIME_UNSET)
            player.playWhenReady = true
        }
    }

    fun setShuffle(enabled: Boolean) {
        player.shuffleModeEnabled = enabled
    }

    override fun onDestroy() {
        mediaSession.release()
        player.release()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep playing in the background / over Bluetooth even if the app UI is swiped away,
        // unless the player is actually paused.
        val p = player
        if (!p.playWhenReady || p.mediaItemCount == 0) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }
}
