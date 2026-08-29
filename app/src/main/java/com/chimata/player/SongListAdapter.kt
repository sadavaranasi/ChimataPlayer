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

    /**
     * Supports "and"/"or" as query connectives, e.g. "ntr and veturi and ilaiyaraja" or
     * "chiranjeevi or balakrishna". "and" narrows (every term must match, on any field);
     * "or" widens (any group matching is enough). A plain query with neither keyword behaves
     * exactly as before - one literal substring checked against every field.
     */
    fun filter(query: String) {
        val trimmed = query.trim()
        shown = if (trimmed.isBlank()) {
            allSongs
        } else {
            val orGroups = trimmed.split(Regex("(?i)\\s+or\\s+"))
            allSongs.filter { song ->
                orGroups.any { group ->
                    val terms = group.split(Regex("(?i)\\s+and\\s+"))
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                    terms.isNotEmpty() && terms.all { term -> songMatchesTerm(song, term) }
                }
            }
        }
        notifyDataSetChanged()
    }

    private fun songMatchesTerm(song: Song, term: String): Boolean {
        val t = term.lowercase()
        return song.title.lowercase().contains(t) ||
            song.movie.lowercase().contains(t) ||
            song.musicDirector.lowercase().contains(t) ||
            song.lyricist.lowercase().contains(t) ||
            song.heroes.lowercase().contains(t)
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
        holder.binding.tagHero.text = "Hero: ${song.heroes}"
        holder.binding.tagMusic.text = "Music: ${song.musicDirector}"
        holder.binding.tagLyricist.text = "Lyrics: ${song.lyricist}"
        holder.binding.root.setOnClickListener { onClick(song) }
    }

    override fun getItemCount(): Int = shown.size
}
