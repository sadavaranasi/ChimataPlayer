package com.chimata.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.media3.common.AudioAttributes
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
    private val mainHandler = Handler(Looper.getMainLooper())

    // True only for the exact playback attempt that resulted directly from the user tapping a
    // song in the list. Any other transition (auto-advance, next/prev, our own auto-skip) clears
    // it. Governs whether a playback error is allowed to auto-skip to the next song or not.
    private var expectingManualSeek = false
    private var lastPlayWasManual = false
    private var networkWaitCallback: ConnectivityManager.NetworkCallback? = null
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
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .build()

        // Keeps the CPU/network alive to finish loading audio even if the screen turns off or
        // the device would otherwise start dozing - the classic cause of background streams
        // going silent mid-playback with no error at all. Very relevant for a "phone mounted
        // in the car, screen off" use case.
        player.setWakeMode(C.WAKE_MODE_NETWORK)

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
                val name = when (playbackState) {
                    Player.STATE_IDLE -> "IDLE"
                    Player.STATE_BUFFERING -> "BUFFERING"
                    Player.STATE_READY -> "READY"
                    Player.STATE_ENDED -> "ENDED"
                    else -> "UNKNOWN($playbackState)"
                }
                Log.d(TAG, "Playback state -> $name")
                if (playbackState == Player.STATE_READY) {
                    consecutiveAutoSkips = 0
                    clearNetworkWait()
                    // The manual flag should only protect a song's very first attempt to start
                    // (i.e. "this exact tap failed outright, stop"). Once it has actually reached
                    // READY - meaning the direct pick already succeeded once - any later hiccup
                    // mid-stream (a stall, a dropped connection, etc.) is treated as an ordinary
                    // playback error and is allowed to auto-skip onward like everything else.
                    lastPlayWasManual = false
                }
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                Log.d(TAG, "playWhenReady=$playWhenReady reason=$reason")
                // Some car head units cause a brief Bluetooth handshake blip (reconnect, a nav
                // prompt, a phone-call tone) that Android sometimes reports to apps as a
                // *permanent* audio focus loss even though it's momentary. ExoPlayer correctly
                // refuses to auto-resume after a real permanent loss (e.g. an actual phone call
                // taking over) - but that also means these spurious blips can silently and
                // permanently pause music with no error at all. Retrying once, shortly after,
                // recovers from the false case while a genuine ongoing loss (e.g. a real call
                // still in progress) will simply fail to resume again and stay paused, which is
                // still the correct outcome for that case.
                if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS) {
                    Log.w(TAG, "Paused by audio focus loss - will retry resuming once in 2s.")
                    mainHandler.postDelayed({
                        if (!player.playWhenReady) {
                            Log.d(TAG, "Retrying playback after focus-loss pause.")
                            player.playWhenReady = true
                        }
                    }, 2000)
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                Log.d(TAG, "isPlaying=$isPlaying")
            }

            override fun onPlayerError(error: PlaybackException) {
                var cause: Throwable? = error.cause
                val chain = StringBuilder()
                while (cause != null) {
                    chain.append(cause.javaClass.simpleName).append(": ").append(cause.message).append(" | ")
                    cause = cause.cause
                }
                Log.e(TAG, "Playback error [${error.errorCodeName}]: ${chain.ifBlank { error.message ?: "unknown" }}")

                if (!isNetworkConnected()) {
                    // Every song would fail the same way right now - skipping through the queue
                    // is pointless. Wait for connectivity to actually come back, then retry this
                    // exact song, whether it was a manual pick or mid-sequence.
                    waitForNetworkThenRetryCurrentSong()
                    return
                }

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

        // Default queue = full catalog, starting on a random song each time the app/service
        // starts up (instead of always song #1). MainActivity can replace this with a filtered
        // subset (e.g. search results) via playManualSelection() once the user taps something.
        val songs = Catalog.loadFlatSongs(this)
        songById = songs.associateBy { it.id }
        val startIndex = if (songs.isNotEmpty()) songs.indices.random() else 0
        player.setMediaItems(songs.map { toMediaItem(it) }, startIndex, 0L)
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

    private fun isNetworkConnected(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun waitForNetworkThenRetryCurrentSong() {
        if (networkWaitCallback != null) {
            // Already waiting on a previous failure - don't stack multiple callbacks.
            return
        }
        Log.w(TAG, "No signal - waiting for connectivity to return before retrying.")
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                mainHandler.post {
                    Log.d(TAG, "Signal restored - retrying current song.")
                    cm.unregisterNetworkCallback(this)
                    networkWaitCallback = null
                    player.prepare()
                    player.playWhenReady = true
                }
            }
        }
        networkWaitCallback = callback
        cm.registerNetworkCallback(request, callback)
    }

    private fun clearNetworkWait() {
        networkWaitCallback?.let { callback ->
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            try {
                cm.unregisterNetworkCallback(callback)
            } catch (_: Exception) {
                // Already unregistered - fine.
            }
        }
        networkWaitCallback = null
    }

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
        clearNetworkWait()
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
        clearNetworkWait()
        mainHandler.removeCallbacksAndMessages(null)
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
