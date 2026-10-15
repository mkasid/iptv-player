# Kasid TV

A Fire TV IPTV player. Browses movies and series from a Stalker portal (MAC
authentication), with global search, watch history with progress, OMDb metadata
(poster, plot, IMDb rating, Rotten Tomatoes, age rating), and a remote-friendly
rows UI. Playback is handed off to
[AutoSub Player](https://github.com/mkasid/autosub-player).

- Package `com.kasidwala.kasidtv`, minSdk 21, targetSdk 34
- No Leanback dependency — custom D-pad UI
- Portal URL, MAC and OMDb key are entered in the app's Settings (first-run setup wizard)

## Layout

- `source/app/src/main/java/com/kasidwala/kasidtv/`
  - `stalker/` — Stalker portal client (handshake with endpoint auto-discovery,
    VOD/series categories + paged lists, seasons/episodes, create_link at play time)
  - `data/` — SharedPreferences, SQLite history + OMDb cache, image loader, OMDb client
    (permanent cache, 800/day background budget, portal IMDb rating used when present)
  - `player/` — handoff to AutoSub Player via ACTION_VIEW
  - `ui/` — Setup, Main (Movies / Series / Continue Watching tabs), Details,
    Search (grouped Movies + Series), Settings
- `kasid-tv-v1.apk` — signed release build

## Releases

| Version | Download |
| ------- | -------- |
| v1 | https://go.kasidwala.com/kasidtv1 |
