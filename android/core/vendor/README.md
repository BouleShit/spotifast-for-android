# Vendored crates

`librespot-core` is librespot-core 0.8.0 from crates.io (MIT, by the
librespot contributors, https://github.com/librespot-org/librespot), with
one change in `src/config.rs`: on Android, `OS` is `"linux"`, so the
session signs in as the desktop client that Spotifast's playback grant
belongs to. Without it Spotify answers the login with `BAD_REQUEST`.

To move to a new librespot release, copy the new crate here and reapply
that change, keeping every librespot crate on the same version.
