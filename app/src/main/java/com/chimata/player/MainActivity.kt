package com.chimata.player

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.LinearLayoutManager
import com.chimata.player.databinding.ActivityMainBinding

@UnstableApi
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: SongListAdapter
    private var allSongs: List<Song> = emptyList()

    private var service: PlaybackService? = null
    private var playerListener: Player.Listener? = null

    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val bound = (binder as? PlaybackService.LocalBinder)?.getService() ?: return
            service = bound
            attachPlayerListener(bound)
            updateNowPlaying()
            binding.shuffleToggle.isChecked = bound.player.shuffleModeEnabled
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            detachPlayerListener()
            service = null
        }
    }

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
            service?.setShuffle(isChecked)
            binding.shuffleToggle.text =
                if (isChecked) getString(R.string.shuffle_on) else getString(R.string.sequential_on)
        }

        binding.btnPlayPause.setOnClickListener {
            service?.player?.let { p -> if (p.isPlaying) p.pause() else p.play() }
        }
        // Explicit next/prev presses are queue navigation, not a "direct pick" - if the
        // resulting song fails, the service's own error handling will auto-skip onward.
        binding.btnNext.setOnClickListener { service?.player?.seekToNextMediaItem() }
        binding.btnPrev.setOnClickListener { service?.player?.seekToPreviousMediaItem() }

        // Start the service so it (and its MediaSession, for Bluetooth/AVRCP) stays alive
        // independent of whether this activity is bound.
        startService(Intent(this, PlaybackService::class.java))
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, PlaybackService::class.java).apply {
            action = PlaybackService.ACTION_LOCAL_BIND
        }
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        detachPlayerListener()
        unbindService(connection)
        service = null
    }

    /**
     * A tap on a specific song is an explicit, direct selection: the currently shown list
     * (full catalog, or the active search results if a filter is applied) becomes the queue,
     * and if this exact song fails to play, the service stops there instead of auto-skipping.
     */
    private fun playSong(song: Song) {
        val svc = service
        if (svc == null) {
            Toast.makeText(this, "Player not ready yet, try again", Toast.LENGTH_SHORT).show()
            return
        }
        val orderedIds = adapter.shown.map { it.id }
        svc.playManualSelection(orderedIds, song.id)
    }

    private fun attachPlayerListener(svc: PlaybackService) {
        detachPlayerListener()
        val listener = object : Player.Listener {
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                updateNowPlaying()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                binding.btnPlayPause.text = if (isPlaying) getString(R.string.pause) else getString(R.string.play)
            }
            override fun onPlayerError(error: PlaybackException) {
                var cause: Throwable? = error.cause
                val chain = StringBuilder()
                while (cause != null) {
                    chain.append(cause.javaClass.simpleName).append(": ").append(cause.message).append(" | ")
                    cause = cause.cause
                }
                val detail = chain.toString().ifBlank { error.message ?: "unknown" }
                Log.e("ChimataPlayer", "Playback error [${error.errorCodeName}]: $detail", error)
                Toast.makeText(this@MainActivity, "Couldn't play: $detail", Toast.LENGTH_LONG).show()
            }
        }
        svc.player.addListener(listener)
        playerListener = listener
    }

    private fun detachPlayerListener() {
        playerListener?.let { service?.player?.removeListener(it) }
        playerListener = null
    }

    private fun updateNowPlaying() {
        val md = service?.player?.mediaMetadata ?: return
        binding.nowPlayingTitle.text = md.title ?: getString(R.string.nothing_playing)
        binding.nowPlayingMovie.text = md.artist ?: ""
    }
}
