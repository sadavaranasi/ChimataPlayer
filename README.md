# Chimata Player

A personal Android media player for browsing/playing the song catalog from
chimatamusic.us, built for use in a car via Bluetooth (AVRCP next/previous/play/pause).

## What's inside
- **Kotlin + Media3/ExoPlayer** app with a `MediaSessionService`, which is what makes your
  car stereo's Bluetooth "next track" / "previous track" / play / pause buttons work — Android
  routes those automatically to whichever app has an active `MediaSession`. No car-specific
  pairing or extra setup needed beyond normal Bluetooth audio pairing.
- **`app/src/main/assets/catalog.json`** — the full song catalog (1,559 movies / 7,566 songs,
  titles + movie + year + music director) parsed from the page you provided. This ships inside
  the app, so browsing/searching works instantly offline; only actual playback needs network.
- **`ChimataDataSource.kt`** — resolves each song's real streaming URL on demand, the same way
  the site's own mobile player does (a GET to `playeriphone.php?plist=<id>`), then streams the
  returned `.mp3`. This means the app never hardcodes stream URLs — it always asks the site for
  the current one right before playing.
- **Shuffle / Sequential toggle** — flips ExoPlayer's built-in shuffle mode on the queue.
- **Search box** — filters by song title or movie name across the whole catalog.

## Option A: Build in Android Studio (recommended, easiest to also sign/install)
1. Install [Android Studio](https://developer.android.com/studio) if you don't have it.
2. Open this folder (`ChimataPlayer/`) as a project — **File → Open**.
3. Let it sync (Android Studio will auto-generate the Gradle wrapper jar on first sync; you
   don't need to do anything extra).
4. **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
5. Click the notification link to locate `app-debug.apk`, or find it at
   `app/build/outputs/apk/debug/app-debug.apk`.
6. Copy that APK to your phone (email, USB, Drive, etc.) and tap it to install. You'll need to
   allow "install unknown apps" for whichever app you use to open it — that's a one-time Android
   setting since this isn't from the Play Store.

To run it straight from Android Studio onto your phone: enable Developer Options + USB
debugging on the phone, plug it in via USB, and hit the green ▶ Run button instead.

## Option B: Build in the cloud with GitHub Actions (no Android Studio needed)
1. Create a free GitHub account if you don't have one, and create a new empty repository.
2. Upload this whole `ChimataPlayer/` folder's contents into that repository (drag-and-drop
   works fine on github.com, or `git push` if you're comfortable with git).
3. Go to the repo's **Actions** tab → you should see the "Build APK" workflow → click
   **Run workflow**.
4. Once it finishes (a few minutes), open the completed run → under **Artifacts**, download
   `ChimataPlayer-debug-apk` — that's a zip containing `app-debug.apk`.
5. Transfer that APK to your phone and install it as described above.

## Using it in the car
1. Install the APK, open the app once, grant the notification permission when asked (needed for
   the persistent playback controls / foreground service).
2. Pair your phone to the car stereo via Bluetooth as usual (this app doesn't need to be open
   for pairing).
3. Tap any song in the list to start playback — this becomes the active queue (the whole
   catalog, in catalog order by default, or shuffled if you've toggled Shuffle).
4. Drive off — your car stereo's next/previous/play/pause buttons control this app as long as
   it's the last app that was playing audio over the Bluetooth connection.

## Lyricist data
Parsed from a second view of the site's catalog (`?page=lyricist`), which groups songs by
lyricist instead of by movie. 7,538 of 7,566 songs matched directly to a lyricist; the rest
(a handful of unusual title formats, or the site itself leaving that field blank for a group)
are tagged `"NA"`.

## Error-handling behavior
- **Tap a song directly** → that becomes a deliberate, explicit pick. If it fails to play, the
  app stops there and shows the error — it won't guess at something else for you.
- **Everything else** (a song finishing and auto-advancing, pressing Next/Previous — from the
  app or the car's Bluetooth buttons — or shuffle moving to its next pick) → if that song fails,
  the app automatically tries the next one in the current queue, and keeps going until something
  plays (with a safety limit so it can't loop forever if, say, you've lost signal entirely).
- **Searching** narrows the on-screen list; tapping a song from a filtered list makes *that
  filtered list* the active queue, so any of the auto-skip behavior above stays within your
  search results instead of jumping back out to the full catalog.

## If playback stops when you minimize the app or the screen turns off
This is a very common Android issue for any sideloaded app that plays audio in the background -
it's not specific to this app's code. Big streaming apps (YouTube Music, Spotify) are
pre-approved by phone manufacturers to keep running unrestricted; a personal app you installed
yourself usually isn't, until you tell your phone to leave it alone.

The app now prompts you once, on first launch, to exclude it from battery optimization - tap
**Allow** on that system dialog when it appears. If you missed it or dismissed it, you can
trigger it again, or do it manually:

1. **Settings → Apps → Chimata Player → Battery** → set to **Unrestricted** (wording varies:
   "Don't optimize", "No restrictions", etc.).
2. **On Xiaomi (MIUI):** also open **Settings → Apps → Manage apps → Chimata Player → Autostart**
   and enable it, plus **Battery saver → No restrictions**.
3. **On Samsung:** **Settings → Apps → Chimata Player → Battery → Unrestricted**, and make sure
   it's not listed under **Settings → Battery → Background usage limits → Sleeping/Deep sleeping
   apps**.
4. **On OnePlus/Oppo/Vivo:** look for **Battery → App battery management** (or **Autostart
   manager**) and allow background activity for the app.
5. It also helps to swipe-lock the app's card in the recent-apps view on many of these phones
   (a small padlock icon appears when you long-press the card), which further signals the OS not
   to kill it.

None of this is optional bloat - it's the actual mechanism that lets any background audio app
survive screen-off on most non-Pixel/stock-Android phones.

## Notes / limitations
- This app is for **your personal use only** — it streams directly from chimatamusic.us using
  the same lookup mechanism their own mobile player page uses. It does not host, cache, or
  redistribute any audio itself.
- The site's `robots.txt` disallows automated crawling of their pages. This app doesn't crawl —
  the song catalog was captured once from a page you saved and provided directly — but each
  playback still calls their `playeriphone.php` lookup endpoint per song, the same as visiting
  their site in a mobile browser would. Worth being mindful of if you use this heavily.
- If a particular song's mp3 file has moved or been removed from their server, that song will
  fail to load — there's no local fallback, since nothing is cached beyond the title/movie
  metadata baked into the app at build time.
- The catalog is a point-in-time snapshot. If chimatamusic.us adds new songs later, you'd need
  to redo the "save page → re-parse → rebuild" step to refresh `catalog.json`, or I can add an
  in-app "refresh catalog" feature that re-parses the page HTML you send it — let me know if
  you'd like that.
