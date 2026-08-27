package com.chimata.player

import android.app.PendingIntent
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
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

    companion object {
        private const val TAG = "ChimataPlayer"
        const val ACTION_LOCAL_BIND = "com.chimata.player.LOCAL_BIND"

        // Safety cap so a broken queue (e.g. no internet, or a long run of missing files)
        // can't retry forever in a tight loop.
        private const val MAX_CONSECUTIVE_AUTO_SKIPS = 25
    }

    inner class LocalBinder : Binder() {
        fun getService(): PlaybackService = this@PlaybackService
    }
    private val localBinder = LocalBinder()

    lateinit var player: ExoPlayer
        private set
    private lateinit var mediaSession: MediaSession
    private lateinit var okHttpClient: OkHttpClient
    private var songById: Map<Int, Song> = emptyMap()

    // True only for the exact playback attempt that resulted directly from the user tapping a
    // song in the list. Any other transition (auto-advance, next/prev, our own auto-skip) clears
    // it. Governs whether a playback error is allowed to auto-skip to the next song or not.
    private var expectingManualSeek = false
    private var lastPlayWasManual = false
    private var consecutiveAutoSkips = 0

    override fun onCreate() {
        super.onCreate()

        okHttpClient = LenientSsl.applyTo(
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
        ).build()

        val dataSourceFactory = ChimataDataSource.Factory(okHttpClient)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()

        // Repeat the whole queue so "next" at the end just loops back to the start.
        player.repeatMode = Player.REPEAT_MODE_ALL

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // Consume the manual flag: it only ever applies to the single item it was set
                // for. Every other transition (auto-advance, next/prev, our own skip-on-error)
                // moves us into "auto" context.
                lastPlayWasManual = expectingManualSeek
                expectingManualSeek = false
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    consecutiveAutoSkips = 0
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                var cause: Throwable? = error.cause
                val chain = StringBuilder()
                while (cause != null) {
                    chain.append(cause.javaClass.simpleName).append(": ").append(cause.message).append(" | ")
                    cause = cause.cause
                }
                Log.e(TAG, "Playback error [${error.errorCodeName}]: ${chain.ifBlank { error.message ?: "unknown" }}")

                if (lastPlayWasManual) {
                    Log.d(TAG, "Error was on a manually-selected song - stopping, not auto-skipping.")
                    return
                }
                attemptAutoSkip()
            }
        })

        val sessionActivityIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivityIntent)
            .build()

        // Default queue = full catalog. MainActivity can replace this with a filtered subset
        // (e.g. search results) via playManualSelection().
        val songs = Catalog.loadFlatSongs(this)
        songById = songs.associateBy { it.id }
        player.setMediaItems(songs.map { toMediaItem(it) })
        player.prepare()
    }

    private fun toMediaItem(song: Song): MediaItem =
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

    private fun attemptAutoSkip() {
        consecutiveAutoSkips++
        val queueSize = player.mediaItemCount
        if (queueSize == 0 || consecutiveAutoSkips > minOf(queueSize, MAX_CONSECUTIVE_AUTO_SKIPS)) {
            Log.e(TAG, "Stopping auto-skip after $consecutiveAutoSkips failures in a row.")
            consecutiveAutoSkips = 0
            return
        }
        Log.d(TAG, "Auto-skipping to next song (attempt $consecutiveAutoSkips).")
        if (player.hasNextMediaItem()) {
            player.seekToNextMediaItem()
        } else {
            player.seekTo(0, C.TIME_UNSET)
        }
        player.playWhenReady = true
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = mediaSession

    override fun onBind(intent: Intent?): IBinder? {
        return if (intent?.action == ACTION_LOCAL_BIND) localBinder else super.onBind(intent)
    }

    /**
     * Rebuilds the play queue to exactly [orderedIds] (e.g. the full catalog, or the currently
     * shown search results) and jumps straight to [startId], marking that one song as a manual,
     * explicit selection - if it fails to play, playback stops instead of auto-skipping.
     */
    fun playManualSelection(orderedIds: List<Int>, startId: Int) {
        val items = orderedIds.mapNotNull { id -> songById[id]?.let { toMediaItem(it) } }
        val startIndex = orderedIds.indexOf(startId).coerceAtLeast(0)
        expectingManualSeek = true
        consecutiveAutoSkips = 0
        player.setMediaItems(items, startIndex, 0L)
        player.prepare()
        player.playWhenReady = true
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
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }
}
