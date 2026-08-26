package com.chimata.player

import android.Manifest
import android.content.ComponentName
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.LinearLayoutManager
import com.chimata.player.databinding.ActivityMainBinding
import com.google.common.util.concurrent.MoreExecutors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var controller: MediaController? = null
    private lateinit var adapter: SongListAdapter
    private var allSongs: List<Song> = emptyList()

    /** Non-null only while an explicitly tapped non-search song is the current item. */
    private var directTapSongId: String? = null

    /** Mirrors the service's guard so the UI only shows a message when the whole queue is exhausted. */
    private var consecutiveAutoErrors = 0

    private val playDirectCommand =
        SessionCommand(PlaybackService.ACTION_PLAY_DIRECT, Bundle.EMPTY)

    private val playSearchCommand =
        SessionCommand(PlaybackService.ACTION_PLAY_SEARCH, Bundle.EMPTY)

    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (Build.VERSION.SDK_INT >= 33) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        allSongs = Catalog.loadFlatSongs(this)

        adapter = SongListAdapter(allSongs) { song -> playSong(song) }
        binding.songList.layoutManager = LinearLayoutManager(this)
        binding.songList.adapter = adapter

        binding.searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adapter.filter(s?.toString().orEmpty())
            }

            override fun afterTextChanged(s: Editable?) {}
        })

        binding.shuffleToggle.setOnCheckedChangeListener { _, isChecked ->
            controller?.shuffleModeEnabled = isChecked
            binding.shuffleToggle.text =
                if (isChecked) getString(R.string.shuffle_on)
                else getString(R.string.sequential_on)
        }

        binding.btnPlayPause.setOnClickListener {
            controller?.let { c ->
                if (c.isPlaying) {
                    c.pause()
                } else {
                    // After a playback error ExoPlayer is IDLE and needs prepare() to retry.
                    if (c.playbackState == Player.STATE_IDLE) {
                        c.prepare()
                    }
                    c.play()
                }
            }
        }

        binding.btnNext.setOnClickListener {
            directTapSongId = null
            controller?.let { c ->
                val wasIdle = c.playbackState == Player.STATE_IDLE
                c.seekToNextMediaItem()
                if (wasIdle) c.prepare()
            }
        }

        binding.btnPrev.setOnClickListener {
            directTapSongId = null
            controller?.let { c ->
                val wasIdle = c.playbackState == Player.STATE_IDLE
                c.seekToPreviousMediaItem()
                if (wasIdle) c.prepare()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val sessionToken = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()

        controllerFuture.addListener({
            controller = controllerFuture.get()
            controller?.addListener(playerListener)
            updateNowPlaying()
        }, MoreExecutors.directExecutor())
    }

    override fun onStop() {
        super.onStop()
        controller?.removeListener(playerListener)
        controller = null

        MediaController.releaseFuture(
            MediaController.Builder(
                this,
                SessionToken(this, ComponentName(this, PlaybackService::class.java))
            ).buildAsync()
        )
    }

    private fun playSong(song: Song) {
        val c = controller
        if (c == null) {
            Toast.makeText(this, "Player not ready yet, try again", Toast.LENGTH_SHORT).show()
            return
        }

        val query = binding.searchBox.text?.toString()?.trim().orEmpty()

        if (query.isBlank()) {
            // Explicit selection outside search:
            // attempt this exact song; if it errors, do not silently jump away.
            directTapSongId = song.id.toString()
            consecutiveAutoErrors = 0

            val args = Bundle().apply {
                putInt(PlaybackService.ARG_SONG_ID, song.id)
            }
            c.sendCustomCommand(playDirectCommand, args)
        } else {
            // Search playback:
            // the exact visible results become the queue, starting from the tapped result.
            directTapSongId = null
            consecutiveAutoErrors = 0

            val resultIds = ArrayList(adapter.currentSongs().map { it.id })
            val args = Bundle().apply {
                putIntegerArrayList(PlaybackService.ARG_SONG_IDS, resultIds)
                putInt(PlaybackService.ARG_SONG_ID, song.id)
            }
            c.sendCustomCommand(playSearchCommand, args)
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            updateNowPlaying()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val directId = directTapSongId
            if (directId != null && mediaItem?.mediaId != directId) {
                directTapSongId = null
            }
            updateNowPlaying()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                consecutiveAutoErrors = 0
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            binding.btnPlayPause.text =
                if (isPlaying) getString(R.string.pause) else getString(R.string.play)
        }

        override fun onPlayerError(error: PlaybackException) {
            var cause: Throwable? = error.cause
            val causeChain = StringBuilder()

            while (cause != null) {
                causeChain
                    .append(cause.javaClass.simpleName)
                    .append(": ")
                    .append(cause.message)
                    .append(" | ")
                cause = cause.cause
            }

            val detail = causeChain.toString().ifBlank { error.message ?: "unknown" }

            Log.e(
                "ChimataPlayer",
                "Playback error [${error.errorCodeName}]: $detail",
                error
            )

            if (directTapSongId != null) {
                // User explicitly asked for this exact song, so tell them it failed.
                Toast.makeText(
                    this@MainActivity,
                    "Couldn't play the selected song: $detail",
                    Toast.LENGTH_LONG
                ).show()
                return
            }

            // Automatic sequence/shuffle/search failures are skipped silently.
            // Only notify if the entire active queue has failed consecutively.
            consecutiveAutoErrors += 1
            val queueSize = controller?.mediaItemCount ?: 0
            if (queueSize > 0 && consecutiveAutoErrors >= queueSize) {
                Toast.makeText(
                    this@MainActivity,
                    "Couldn't find a playable song in this queue.",
                    Toast.LENGTH_LONG
                ).show()
                consecutiveAutoErrors = 0
            }
        }
    }

    private fun updateNowPlaying() {
        val md = controller?.mediaMetadata ?: return
        binding.nowPlayingTitle.text = md.title ?: getString(R.string.nothing_playing)
        binding.nowPlayingMovie.text = md.artist ?: ""
    }
}
