package com.chimata.player

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
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
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

@UnstableApi
class PlaybackService : MediaSessionService() {

    companion object {
        const val ACTION_PLAY_DIRECT = "com.chimata.player.action.PLAY_DIRECT"
        const val ACTION_PLAY_SEARCH = "com.chimata.player.action.PLAY_SEARCH"

        const val ARG_SONG_ID = "song_id"
        const val ARG_SONG_IDS = "song_ids"
    }

    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaSession
    private lateinit var okHttpClient: OkHttpClient

    private lateinit var catalogSongs: List<Song>
    private lateinit var catalogById: Map<Int, Song>
    private lateinit var fullCatalogItems: List<MediaItem>

    /**
     * Non-null only while the current item is an explicit direct selection made outside search.
     * If that exact item errors, we stop instead of silently jumping to another song.
     * Once playback transitions away from that item, normal auto-skip behavior resumes.
     */
    private var directRequestedMediaId: String? = null

    /**
     * Protects repeat-all queues from looping forever when every candidate is broken.
     * Reset as soon as any item reaches READY.
     */
    private var consecutiveErrors = 0

    private val playDirectCommand by lazy {
        SessionCommand(ACTION_PLAY_DIRECT, Bundle.EMPTY)
    }

    private val playSearchCommand by lazy {
        SessionCommand(ACTION_PLAY_SEARCH, Bundle.EMPTY)
    }

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

        // Repeat the active queue. For a search, the active queue is only the search results.
        player.repeatMode = Player.REPEAT_MODE_ALL

        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                handlePlaybackError()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                // A real playable item was reached, so a previous chain of failures is over.
                if (playbackState == Player.STATE_READY) {
                    consecutiveErrors = 0
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // The one-shot "direct selection" protection only belongs to that exact item.
                val directId = directRequestedMediaId
                if (directId != null && mediaItem?.mediaId != directId) {
                    directRequestedMediaId = null
                }
            }
        })

        val sessionActivityIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivityIntent)
            .setCallback(PlaybackSessionCallback())
            .build()

        catalogSongs = Catalog.loadFlatSongs(this)
        catalogById = catalogSongs.associateBy { it.id }
        fullCatalogItems = catalogSongs.map(::toMediaItem)

        // Default queue is the full catalog. Nothing starts until the user presses play/selects.
        player.setMediaItems(fullCatalogItems)
        player.prepare()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession {
        return mediaSession
    }

    /**
     * Failure policy:
     * 1) Direct selection outside search -> stop on that selected item's error.
     * 2) Sequence/shuffle/search queue -> advance automatically.
     * 3) If an entire active queue fails consecutively -> stop instead of looping forever.
     */
    private fun handlePlaybackError() {
        val currentId = player.currentMediaItem?.mediaId

        if (currentId != null && currentId == directRequestedMediaId) {
            // Keep directRequestedMediaId so pressing Play retries the same requested song
            // with the same "do not auto-skip" rule.
            consecutiveErrors = 0
            player.playWhenReady = false
            return
        }

        val queueSize = player.mediaItemCount
        consecutiveErrors += 1

        if (queueSize <= 1 || consecutiveErrors >= queueSize || !player.hasNextMediaItem()) {
            consecutiveErrors = 0
            player.playWhenReady = false
            return
        }

        // A source error leaves ExoPlayer in IDLE, so after seeking we must prepare again.
        player.seekToNextMediaItem()
        player.prepare()
        player.playWhenReady = true
    }

    private fun toMediaItem(song: Song): MediaItem {
        return MediaItem.Builder()
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

    /** Explicit tap outside search: full catalog queue, but selected item itself does not auto-skip. */
    private fun playDirectSong(id: Int): Boolean {
        val index = catalogSongs.indexOfFirst { it.id == id }
        if (index < 0) return false

        directRequestedMediaId = id.toString()
        consecutiveErrors = 0

        // Restore full catalog in case the previous playback came from a search-result queue.
        player.setMediaItems(fullCatalogItems, index, 0L)
        player.prepare()
        player.playWhenReady = true
        return true
    }

    /**
     * Search tap: the filtered results themselves become the active queue.
     * Sequential/shuffle therefore remains inside the search results only.
     */
    private fun playSearchResults(ids: List<Int>, selectedId: Int): Boolean {
        val songs = ids.mapNotNull { catalogById[it] }
        if (songs.isEmpty()) return false

        val startIndex = songs.indexOfFirst { it.id == selectedId }
        if (startIndex < 0) return false

        directRequestedMediaId = null
        consecutiveErrors = 0

        player.setMediaItems(songs.map(::toMediaItem), startIndex, 0L)
        player.prepare()
        player.playWhenReady = true
        return true
    }

    private inner class PlaybackSessionCallback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                .buildUpon()
                .add(playDirectCommand)
                .add(playSearchCommand)
                .build()

            // This constructor is the Media3 1.4.x pattern and preserves normal player commands.
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            val resultCode = when (customCommand.customAction) {
                ACTION_PLAY_DIRECT -> {
                    val id = args.getInt(ARG_SONG_ID, -1)
                    if (id >= 0 && playDirectSong(id)) {
                        SessionResult.RESULT_SUCCESS
                    } else {
                        SessionResult.RESULT_ERROR_BAD_VALUE
                    }
                }

                ACTION_PLAY_SEARCH -> {
                    val ids = args.getIntegerArrayList(ARG_SONG_IDS)?.toList().orEmpty()
                    val selectedId = args.getInt(ARG_SONG_ID, -1)
                    if (selectedId >= 0 && playSearchResults(ids, selectedId)) {
                        SessionResult.RESULT_SUCCESS
                    } else {
                        SessionResult.RESULT_ERROR_BAD_VALUE
                    }
                }

                else -> SessionResult.RESULT_ERROR_NOT_SUPPORTED
            }

            return Futures.immediateFuture(SessionResult(resultCode))
        }
    }

    /** Kept for compatibility with any existing internal callers. */
    fun playSongId(id: Int) {
        playDirectSong(id)
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
        val p = player
        if (!p.playWhenReady || p.mediaItemCount == 0) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }
}
