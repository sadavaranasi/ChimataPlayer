package com.chimata.player

import android.Manifest
import android.content.ComponentName
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.widget.Toast
import androidx.media3.common.PlaybackException
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.common.util.concurrent.MoreExecutors
import com.chimata.player.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var controller: MediaController? = null
    private lateinit var adapter: SongListAdapter
    private var allSongs: List<Song> = emptyList()

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

        adapter = SongListAdapter(allSongs) { song -> controller?.let { playSong(song) } }
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
            binding.shuffleToggle.text = if (isChecked) getString(R.string.shuffle_on) else getString(R.string.sequential_on)
        }

        binding.btnPlayPause.setOnClickListener {
            controller?.let { c -> if (c.isPlaying) c.pause() else c.play() }
        }
        binding.btnNext.setOnClickListener { controller?.seekToNextMediaItem() }
        binding.btnPrev.setOnClickListener { controller?.seekToPreviousMediaItem() }
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
        // The service preloads the entire catalog as the queue, so playing a tapped song is
        // just a seek to its index in that same queue (mediaId == song.id as a string).
        val c = controller
        if (c == null) {
            Toast.makeText(this, "Player not ready yet, try again", Toast.LENGTH_SHORT).show()
            return
        }
        var index = -1
        for (i in 0 until c.mediaItemCount) {
            if (c.getMediaItemAt(i).mediaId == song.id.toString()) {
                index = i
                break
            }
        }
        if (index >= 0) {
            c.seekTo(index, 0L)
            c.play()
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            updateNowPlaying()
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            binding.btnPlayPause.text = if (isPlaying) getString(R.string.pause) else getString(R.string.play)
        }
        override fun onPlayerError(error: PlaybackException) {
            Log.e("ChimataPlayer", "Playback error: ${error.errorCodeName} - ${error.message}", error)
            Toast.makeText(
                this@MainActivity,
                "Couldn't play this song: ${error.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun updateNowPlaying() {
        val md = controller?.mediaMetadata ?: return
        binding.nowPlayingTitle.text = md.title ?: getString(R.string.nothing_playing)
        binding.nowPlayingMovie.text = md.artist ?: ""
    }
}
