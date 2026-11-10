//! The playback engine: a librespot session, player and Spotify Connect
//! device, set up the way Spotifast's desktop engine (src/player.rs) does,
//! with the phone's AudioTrack as the output.

use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use anyhow::{Context, Result, anyhow, bail};
use librespot_connect::{
    ConnectConfig, LoadContextOptions, LoadRequest, LoadRequestOptions, Options, PlayingTrack, Spirc,
};
use librespot_core::authentication::Credentials;
use librespot_core::cache::Cache;
use librespot_core::config::{DeviceType, SessionConfig};
use librespot_core::{Session, SpotifyUri};
use librespot_metadata::audio::{AudioItem, UniqueFields};
use librespot_playback::config::{Bitrate, PlayerConfig, VolumeCtrl};
use librespot_playback::mixer::{self, Mixer, MixerConfig};
use librespot_playback::player::{Player, PlayerEvent, PlayerEventChannel};
use serde_json::{Value, json};

use crate::audio::AndroidSink;
use crate::auth;
use crate::core::Core;

/// Played audio kept on the phone, so a replayed song starts at once.
const AUDIO_CACHE_LIMIT: u64 = 512 * 1024 * 1024;

pub struct Engine {
    core: &'static Core,
    session: Session,
    spirc: Spirc,
    player: Arc<Player>,
    device_id: String,
    /// Whether this phone is the active Spotify Connect device.
    active: Arc<AtomicBool>,
    shutting_down: Arc<AtomicBool>,
}

impl Engine {
    pub async fn connect(core: &'static Core, credentials: Credentials) -> Result<Self> {
        let settings = core.settings();
        let session_config = SessionConfig {
            // The identity the playback grant was issued to, as on desktop.
            client_id: auth::PLAYBACK_CLIENT_ID.to_string(),
            device_id: settings.device_id.clone(),
            tmp_dir: core.cache_dir.join("tmp"),
            autoplay: Some(true),
            ..SessionConfig::default()
        };
        let cache = Cache::new(
            Some(core.librespot_dir()),
            None,
            Some(core.cache_dir.join("audio")),
            Some(AUDIO_CACHE_LIMIT),
        )
        .map_err(|error| anyhow!("cannot open the playback cache: {error}"))?;
        let session = Session::new(session_config, Some(cache));

        let player_config = PlayerConfig {
            bitrate: Bitrate::Bitrate320,
            gapless: true,
            position_update_interval: Some(Duration::from_secs(1)),
            ..PlayerConfig::default()
        };
        let mixer_builder =
            mixer::find(Some("softvol")).ok_or_else(|| anyhow!("soft volume mixer missing"))?;
        let mixer: Arc<dyn Mixer> = mixer_builder(MixerConfig {
            volume_ctrl: VolumeCtrl::Cubic(VolumeCtrl::DEFAULT_DB_RANGE),
            ..MixerConfig::default()
        })
        .map_err(|error| anyhow!("cannot create the mixer: {error}"))?;
        // The phone's own volume keys do the work; Connect volume from
        // other devices still goes through this mixer.
        mixer.set_volume(u16::MAX);

        core.pcm.reopen();
        let pcm = Arc::clone(&core.pcm);
        let player = Player::new(
            player_config,
            session.clone(),
            mixer.get_soft_volume(),
            move || Box::new(AndroidSink::new(pcm)),
        );
        let active = Arc::new(AtomicBool::new(false));
        tokio::spawn(run_events(
            player.get_player_event_channel(),
            core,
            Arc::clone(&active),
        ));

        let connect_config = ConnectConfig {
            name: core.device_name.clone(),
            device_type: DeviceType::Smartphone,
            initial_volume: u16::MAX,
            disable_volume: false,
            volume_steps: 64,
            ..ConnectConfig::default()
        };
        let (spirc, spirc_task) = Spirc::new(
            connect_config,
            session.clone(),
            credentials,
            Arc::clone(&player),
            Arc::clone(&mixer),
        )
        .await
        .map_err(|error| anyhow!("{error}"))
        .context("unable to connect to Spotify")?;

        let device_id = session.device_id().to_string();
        let shutting_down = Arc::new(AtomicBool::new(false));
        {
            let shutting_down = Arc::clone(&shutting_down);
            let device_id = device_id.clone();
            tokio::spawn(async move {
                spirc_task.await;
                if !shutting_down.load(Ordering::SeqCst) {
                    core.engine_ended(device_id);
                }
            });
        }
        core.events.update_state(json!({
            "connected": true,
            "username": session.username(),
            "deviceId": device_id,
        }));
        Ok(Self {
            core,
            session,
            spirc,
            player,
            device_id,
            active,
            shutting_down,
        })
    }

    pub fn device_id(&self) -> &str {
        &self.device_id
    }

    pub fn shutdown(&self) {
        self.shutting_down.store(true, Ordering::SeqCst);
        let _ = self.spirc.shutdown();
        self.player.stop();
        self.session.shutdown();
        self.core.pcm.flush();
        self.core.events.update_state(json!({ "connected": false, "playing": false, "active": false }));
    }

    /// One command from the interface. The interface has already shown its
    /// result (Spotifast is optimistic); this makes it true.
    pub fn command(&self, command: &Value) -> Result<()> {
        let pcm = &self.core.pcm;
        let spirc = &self.spirc;
        let active = self.active.load(Ordering::SeqCst);
        let result = match command["cmd"].as_str().unwrap_or_default() {
            "play" => {
                pcm.set_paused(false);
                self.core.events.update_state(json!({ "playing": true }));
                if active { spirc.play() } else { spirc.transfer(None) }
            }
            "pause" => {
                pcm.set_paused(true);
                self.core.events.update_state(json!({ "playing": false, "position": command["position"].clone(), "at": now_ms() }));
                spirc.pause()
            }
            "next" => spirc.next(),
            "prev" => spirc.prev(),
            "seek" => {
                let position = command["position"].as_u64().unwrap_or(0) as u32;
                self.core.events.update_state(json!({ "position": position, "at": now_ms() }));
                spirc.set_position_ms(position)
            }
            "shuffle" => spirc.shuffle(command["on"].as_bool().unwrap_or(false)),
            "repeat" => match command["mode"].as_str().unwrap_or("off") {
                "context" => spirc.repeat_track(false).and_then(|_| spirc.repeat(true)),
                "track" => spirc.repeat_track(true),
                _ => spirc.repeat_track(false).and_then(|_| spirc.repeat(false)),
            },
            "transfer" => spirc.transfer(None),
            "load" => {
                let request = load_request(command)?;
                pcm.flush();
                pcm.set_paused(false);
                self.core.events.update_state(json!({ "playing": true, "loading": true, "position": 0, "at": now_ms() }));
                // Spirc ignores a load until this phone is the active device.
                spirc.activate().and_then(|_| spirc.load(request))
            }
            other => bail!("unknown command {other:?}"),
        };
        result.map_err(|error| anyhow!("{error}"))
    }
}

/// `{"context": uri, "track": uri | "index": n, "shuffle": bool}` or
/// `{"tracks": [uri, ...], "index": n}`.
fn load_request(command: &Value) -> Result<LoadRequest> {
    let playing_track = command["track"]
        .as_str()
        .map(|uri| PlayingTrack::Uri(uri.to_string()))
        .or_else(|| command["index"].as_u64().map(|index| PlayingTrack::Index(index as u32)));
    let options = LoadRequestOptions {
        start_playing: true,
        seek_to: command["position"].as_u64().unwrap_or(0) as u32,
        context_options: command["shuffle"].as_bool().map(|shuffle| {
            LoadContextOptions::Options(Options {
                shuffle,
                ..Options::default()
            })
        }),
        playing_track,
    };
    if let Some(context) = command["context"].as_str() {
        return Ok(LoadRequest::from_context_uri(context.to_string(), options));
    }
    let tracks: Vec<String> = command["tracks"]
        .as_array()
        .map(|tracks| tracks.iter().filter_map(|uri| uri.as_str().map(str::to_string)).collect())
        .unwrap_or_default();
    if tracks.is_empty() {
        bail!("nothing to play");
    }
    Ok(LoadRequest::from_tracks(tracks, options))
}

fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|elapsed| elapsed.as_millis() as u64)
        .unwrap_or(0)
}

fn uri(id: &SpotifyUri) -> String {
    id.to_uri().unwrap_or_default()
}

fn describe(item: &AudioItem) -> Value {
    let cover = item
        .covers
        .iter()
        .max_by_key(|cover| cover.width)
        .map(|cover| cover.url.clone())
        .unwrap_or_default();
    let (artists, album) = match &item.unique_fields {
        UniqueFields::Track { artists, album, .. } => (
            artists
                .iter()
                .map(|artist| json!({ "name": artist.name, "uri": uri(&artist.id) }))
                .collect::<Vec<_>>(),
            album.clone(),
        ),
        UniqueFields::Episode { show_name, .. } => {
            (vec![json!({ "name": show_name, "uri": "" })], String::new())
        }
        UniqueFields::Local { artists, album, .. } => (
            artists
                .iter()
                .map(|name| json!({ "name": name, "uri": "" }))
                .collect(),
            album.clone().unwrap_or_default(),
        ),
    };
    json!({
        "uri": item.uri,
        "name": item.name,
        "artists": artists,
        "album": album,
        "cover": cover,
        "duration": item.duration_ms,
    })
}

async fn run_events(mut events: PlayerEventChannel, core: &'static Core, active: Arc<AtomicBool>) {
    let state = &core.events;
    while let Some(event) = events.recv().await {
        match event {
            PlayerEvent::Playing { track_id, position_ms, .. } => {
                core.pcm.set_paused(false);
                state.update_state(json!({
                    "playing": true, "loading": false, "uri": uri(&track_id),
                    "position": position_ms, "at": now_ms(),
                }));
            }
            PlayerEvent::Paused { position_ms, .. } => {
                core.pcm.set_paused(true);
                state.update_state(json!({
                    "playing": false, "loading": false, "position": position_ms, "at": now_ms(),
                }));
            }
            PlayerEvent::Loading { position_ms, .. } => {
                state.update_state(json!({ "loading": true, "position": position_ms, "at": now_ms() }));
            }
            PlayerEvent::Stopped { .. } => {
                core.pcm.flush();
                state.update_state(json!({ "playing": false, "loading": false }));
            }
            PlayerEvent::Seeked { position_ms, .. } => {
                core.pcm.flush();
                state.update_state(json!({ "position": position_ms, "at": now_ms() }));
            }
            PlayerEvent::PositionCorrection { position_ms, .. }
            | PlayerEvent::PositionChanged { position_ms, .. } => {
                state.update_state(json!({ "position": position_ms, "at": now_ms() }));
            }
            PlayerEvent::TrackChanged { audio_item } => {
                state.update_state(json!({ "track": describe(&audio_item) }));
            }
            PlayerEvent::ShuffleChanged { shuffle } => {
                state.update_state(json!({ "shuffle": shuffle }));
            }
            PlayerEvent::RepeatChanged { context, track } => {
                let mode = if track { "track" } else if context { "context" } else { "off" };
                state.update_state(json!({ "repeat": mode }));
            }
            PlayerEvent::SessionConnected { .. } => {
                active.store(true, Ordering::SeqCst);
                state.update_state(json!({ "active": true }));
            }
            PlayerEvent::SessionDisconnected { .. } => {
                active.store(false, Ordering::SeqCst);
                core.pcm.set_paused(true);
                state.update_state(json!({ "active": false, "playing": false }));
            }
            PlayerEvent::Unavailable { .. } => {
                state.error("player", "This song isn't available. Skipping it.");
            }
            _ => {}
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_context_load_starts_at_the_chosen_track() {
        let request = load_request(&json!({
            "context": "spotify:playlist:37i9dQZF1DXcBWIGoYBM5M",
            "track": "spotify:track:4uLU6hMCjMI75M1A2tKUQC",
            "shuffle": false,
        }))
        .unwrap();
        assert!(request.start_playing);
        assert!(matches!(request.playing_track, Some(PlayingTrack::Uri(ref uri)) if uri.ends_with("KUQC")));
        assert!(matches!(request.context_options, Some(LoadContextOptions::Options(Options { shuffle: false, .. }))));
    }

    #[test]
    fn a_track_list_load_needs_tracks() {
        assert!(load_request(&json!({ "tracks": [] })).is_err());
        let request = load_request(&json!({ "tracks": ["spotify:track:a"], "index": 0 })).unwrap();
        assert!(matches!(request.playing_track, Some(PlayingTrack::Index(0))));
        assert!(request.context_options.is_none());
    }
}
