package com.chimata.player

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.chimata.player.databinding.ItemSongBinding

class SongListAdapter(
    private val allSongs: List<Song>,
    private val onClick: (Song) -> Unit
) : RecyclerView.Adapter<SongListAdapter.VH>() {

    /** The list currently displayed (full catalog, or the filtered search results). */
    var shown: List<Song> = allSongs
        private set

    fun filter(query: String) {
        shown = if (query.isBlank()) {
            allSongs
        } else {
            val q = query.trim().lowercase()
            allSongs.filter {
                it.title.lowercase().contains(q) || it.movie.lowercase().contains(q)
            }
        }
        notifyDataSetChanged()
    }

    inner class VH(val binding: ItemSongBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemSongBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val song = shown[position]
        holder.binding.songTitle.text = song.title
        holder.binding.songMovie.text = song.movie
        holder.binding.root.setOnClickListener { onClick(song) }
    }

    override fun getItemCount(): Int = shown.size
}
