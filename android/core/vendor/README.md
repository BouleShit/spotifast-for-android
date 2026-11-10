# Vendored crates

`librespot/` holds the librespot crates the Android core uses, from
Spotifast's own librespot fork, github.com/crmne/librespot at revision
`3ba977f1f918582edce566cfc16617f8eccdfb1e`: the revision desktop Spotifast
pins in the repository's root `Cargo.toml`. librespot is MIT-licensed
(`librespot/LICENSE`).

The fork carries fixes stock librespot 0.8.0 lacks that playback needs:
stopping on refused audio keys instead of decoding with a wrong key, pacing
audio key requests, and falling back to another CDN URL on a bad answer.

One change is made here, in `librespot/core/src/config.rs`: on Android, `OS`
is `"linux"`, so the session signs in as the desktop client that Spotifast's
playback grant belongs to. Without it Spotify answers the login with
`BAD_REQUEST`.

To follow desktop Spotifast to a new fork revision, copy the same crates
from that revision here, keep `librespot/Cargo.toml`, and reapply that
change.
