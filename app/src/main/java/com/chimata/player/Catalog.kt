package com.chimata.player

import android.content.Context
import org.json.JSONArray

data class Song(
    val id: Int,
    val title: String,
    val movie: String,
    val musicDirector: String
)

data class Movie(
    val name: String,
    val year: String,
    val musicDirector: String,
    val songs: List<Song>
)

object Catalog {

    private var moviesCache: List<Movie>? = null
    private var flatCache: List<Song>? = null

    /** All movies, each with their own songs (grouped, for the browsing UI). */
    fun loadMovies(context: Context): List<Movie> {
        moviesCache?.let { return it }
        val json = context.assets.open("catalog.json").bufferedReader().use { it.readText() }
        val arr = JSONArray(json)
        val movies = ArrayList<Movie>(arr.length())
        for (i in 0 until arr.length()) {
            val m = arr.getJSONObject(i)
            val movieName = m.getString("movie")
            val songsArr = m.getJSONArray("songs")
            val songs = ArrayList<Song>(songsArr.length())
            for (j in 0 until songsArr.length()) {
                val s = songsArr.getJSONObject(j)
                songs.add(
                    Song(
                        id = s.getInt("id"),
                        title = s.getString("title"),
                        movie = movieName,
                        musicDirector = m.optString("musicDirector", "")
                    )
                )
            }
            movies.add(
                Movie(
                    name = movieName,
                    year = m.optString("year", ""),
                    musicDirector = m.optString("musicDirector", ""),
                    songs = songs
                )
            )
        }
        moviesCache = movies
        return movies
    }

    /** Every song, flattened into a single ordered list (this is the default play queue). */
    fun loadFlatSongs(context: Context): List<Song> {
        flatCache?.let { return it }
        val flat = loadMovies(context).flatMap { it.songs }
        flatCache = flat
        return flat
    }
}
