# Spotifast for Android

A native Android app built on Spotifast: Jetpack Compose for the interface,
and a Rust core with the same librespot engine and sign-in code as the
desktop app. Music plays on the phone itself, keeps playing in the
background, and the phone shows up as a Spotify Connect device.

Spotify Premium is required for playback, as on the desktop.

## Install

1. On your phone, open this repository's **Releases** page and the
   **Android latest** release.
2. Download `Spotifast-android.apk` and open it. Android asks once to allow
   your browser to install apps.

Every push to `main` that touches the app builds a new APK into that same
release, and it installs over the previous one.

## First run

Spotifast signs in through your own free Spotify app registration (the same
as the desktop, see `docs/_guide/getting-started.md`):

1. In the [Spotify developer dashboard](https://developer.spotify.com/dashboard),
   create an app.
2. Add the redirect URI `http://127.0.0.1:8989/login` exactly.
3. Tick **Web API**, accept the terms, save, and copy the Client ID.
4. Paste it into the app and tap **Sign in with Spotify**.
5. Tap **Turn on playback**: Spotify asks once more to allow streaming.

The sign-in pages open in your browser and come back to the app through a
listener on `127.0.0.1` inside the app, as on the desktop.

## Two players

Spotify refuses audio keys to librespot for many accounts, mostly newer
ones (see `docs/_guide/troubleshooting.md`). So by default the app plays
through **Spotify's own web player** (the Web Playback SDK), hidden inside
the app: Spotify's player plays, as Soloist does on the desktop, and
Spotifast tells it what to play through the Web API. It needs one more
approval with the `streaming` and `user-read-email` permissions.

The ⋮ menu switches to the **built-in player** (librespot, the desktop's
built-in player) for accounts it works with. **Debug log** in the same menu
shows what either player reports.

## What's inside

| Part | Where | What it does |
|---|---|---|
| Rust core | `android/core` | librespot session, player and Spotify Connect; Web API with token refresh; sign-in (Spotifast's own `src/auth.rs`, shared as is); 16-bit PCM handed to the app |
| App | `android/app` | Compose UI (library, search, playlists, albums, artists, player), AudioTrack output, media session, notification, audio focus |
| Build | `.github/workflows/android.yml` | Tests the core, cross-compiles it with `cargo-ndk`, builds and signs the APK, publishes **Android latest** |

The interface is optimistic like the desktop app: a tap shows its result at
once, and Spotify's state catches up behind it.

## Build it yourself

You need the Android SDK and NDK, JDK 17, Rust with the Android targets, and
`cargo-ndk`.

```sh
cd android/core
rustup target add aarch64-linux-android x86_64-linux-android
cargo install cargo-ndk
cargo ndk -t arm64-v8a -t x86_64 -o ../app/src/main/jniLibs build --release
cd ..
gradle assembleRelease   # Gradle 8.11
```

The APK is in `android/app/build/outputs/apk/release/`.

## Signing

So each build installs as an update, APKs are signed with one fixed key,
`app/spotifast-sideload.jks`. It is in the repository, so it identifies the
build but proves nothing about who made it. To use a private key instead,
add these repository secrets and the workflow signs with it:
`SPOTIFAST_KEYSTORE_BASE64` (the keystore, base64), `SPOTIFAST_KEYSTORE_PASSWORD`,
`SPOTIFAST_KEY_ALIAS`, `SPOTIFAST_KEY_PASSWORD`. Switching keys means
uninstalling the app once.

## Not there yet

Compared with the desktop app: no queue view, lyrics, visualisers, skins,
equalizer, Soloist, Cast, or local files. Playlists that Spotify keeps from
personal apps (some Spotify-made ones) don't open, since the desktop's
session fallback for them isn't wired up here yet.
