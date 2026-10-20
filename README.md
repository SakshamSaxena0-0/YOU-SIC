# YouSic

A music player for Windows (Electron).

## Run

- **Packaged app:** `dist\win-unpacked\YouSic.exe` (or the `YouSic.lnk` shortcut in this folder)
- **From source:** `npm start`
- **Rebuild the exe:** `npm run dist` (folder build) or `npm run installer` (NSIS setup .exe)

On first start the app scans your Windows *Music* folder. Add more folders from Library → *Add folder*, or from Settings.

## Features

| Area | What it does |
| --- | --- |
| Window layout | Wide windows (980 px and up) have three columns: the library (or the equalizer) on the left, the player in the middle (the album art fills the whole column, edge to edge, with the title and controls over its lower part on a dark fade; tap it for the song info). The button at the end of the title row (or A) hides the header and the controls to show only the art; the same button, A, Esc or a tap on the cover brings them back, and lyrics or the visualization on the right. The right-hand panel switches with its Lyrics / Visualizer tabs, the dock buttons or Y / V, and hides with its arrow button (the player then takes the room; LYRICS or WAVE brings it back). Narrow windows show one screen at a time. |
| Player | Blurred, slowly drifting album-art background; accent color extracted from the cover; 3D album-art carousel |
| Gestures | Album art: swipe ←/→ to change track, swipe ↓ (or click) for the track's category, swipe ↑ for lyrics. Mini-player: click to open the player, drag ←/→ to change track, long-press to open the playing category. Library: swipe → from the left edge (or Alt+drag, mouse Back, Backspace) for the parent category. Visualization: swipe ←/→ to change presets |
| Seekbar | Real waveform, decoded from the file and morphed in, with a drag bubble; mouse wheel seeks |
| Library | Folders, Folders Hierarchy, All Songs, Albums, Artists, Album Artists, Genres, Years, Composers, Playlists, Queue, Recently Added/Played, Most Played, Top Rated; search; sort options; Ctrl+wheel zoom; virtualized lists |
| Playback | Gapless (next track preloaded) or crossfade (1–12 s); shuffle; repeat off/category/song; Play Next / Queue; resumes the last session |
| Equalizer | 10-band graphic EQ with a live frequency-response curve, 21 presets plus your own; Preamp, Bass, Treble, Balance and Tempo knobs; Limiter; Reverb tab with 7 knobs and 8 presets |
| Visualization | MilkDrop presets (butterchurn) in the right-hand panel (over the album art in narrow windows) or fullscreen; shuffle / locked / sequential preset modes |
| Lyrics | Synced `.lrc` files next to the song, plus embedded (synced or plain) lyrics tags; click a line to seek |
| Windows | Custom title bar, media keys and the Windows media flyout (SMTC), taskbar thumbnail prev/play/next buttons, sleep timer with fade-out, star ratings, track info |

Supported formats: MP3, FLAC, M4A/AAC, OGG/Opus, WAV, WebM.

## Shortcuts

Space play/pause · Ctrl+←/→ prev/next · ←/→ seek 5 s (Shift: 30 s) · ↑/↓ volume · M mute · E equalizer ·
V visualization (F fullscreen) · Y lyrics · A only the album art (wide windows) · S shuffle · R repeat · Ctrl+F search · Backspace back · Alt+0…5 rate

## Layout

```
main.js          main process: app:// protocol (range-streamed audio, covers), library scan, lyrics, storage
preload.js       IPC bridge
renderer/
  index.html     app shell
  css/app.css    theme, layout, animations
  js/audio.js    Web Audio engine (2 decks, EQ / tone / balance / reverb / limiter)
  js/player.js   queue, shuffle/repeat, gapless & crossfade
  js/*View.js    player, library, equalizer, visualization, lyrics screens
```

User data (library cache, covers, settings, playlists, stats) lives in `%APPDATA%\YouSic`.
