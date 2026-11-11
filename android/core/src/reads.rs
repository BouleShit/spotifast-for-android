//! Reads only Spotify's own clients make, over a librespot session: lyrics
//! and the personalized Home shelves (Made for you, Your top mixes).
//!
//! With the built-in player, its session serves them. With Spotify's web
//! player, a session that plays nothing is kept for them: Spotify refusing
//! audio keys to an account does not stop it signing in or reading these.
//! Ported from desktop Spotifast (src/player.rs lyrics, src/lyrics.rs,
//! src/made_for_you.rs).

use std::time::Duration;

use anyhow::{Context, Result, anyhow, bail};
use librespot_core::error::ErrorKind;
use librespot_core::{Session, SpotifyId, SpotifyUri};
use librespot_metadata::{Metadata, Track};
use serde_json::{Value, json};

use crate::core::Core;

const PATHFINDER: &str = "https://api-partner.spotify.com/pathfinder/v2/query";
const MADE_FOR_YOU: &str = "spotify:section:0JQ5DAUnp4wcj0bCb3wh3S";
const TOP_MIXES: &str = "spotify:section:0JQ5DAnM3wGh0gz1MXnu89";
const HOME_QUERY_HASH: &str = "eb3fba2d388cf4fc4d696b1757a58584e9538a3b515ea742e9cc9465807340be";
/// Spotify DJ is a special context that cannot be started as a playlist.
const DJ_URI: &str = "spotify:playlist:37i9dQZF1EYkqdzj48dyYq";
const SHELF_SIZE: usize = 20;
const CONNECT_TIMEOUT: Duration = Duration::from_secs(30);

impl Core {
    /// A connected session for these reads: the built-in player's when it
    /// runs, otherwise one kept for reading only.
    pub async fn session_for_reads(&'static self) -> Result<Session> {
        if let Some(engine) = self.engine() {
            return Ok(engine.session());
        }
        let mut slot = self.reader.lock().await;
        if let Some(session) = slot.as_ref() {
            if !session.is_invalid() {
                return Ok(session.clone());
            }
        }
        let credentials = self
            .saved_playback_credentials()
            .ok_or_else(|| anyhow!("Allow Spotify's session in Settings first."))?;
        let session = crate::engine::new_session(self)?;
        tokio::time::timeout(CONNECT_TIMEOUT, session.connect(credentials, true))
            .await
            .map_err(|_| anyhow!("Spotify didn't answer the session sign-in"))?
            .map_err(|error| anyhow!("Spotify session sign-in failed: {error}"))?;
        *slot = Some(session.clone());
        Ok(session)
    }

    /// Signs a reading-only session in with a fresh streaming grant and keeps
    /// its reusable credential, without starting a player.
    pub async fn connect_reader(
        &'static self,
        credentials: librespot_core::authentication::Credentials,
    ) -> Result<()> {
        let session = crate::engine::new_session(self)?;
        tokio::time::timeout(CONNECT_TIMEOUT, session.connect(credentials, true))
            .await
            .map_err(|_| anyhow!("Spotify didn't answer the session sign-in"))?
            .map_err(|error| anyhow!("Spotify session sign-in failed: {error}"))?;
        *self.reader.lock().await = Some(session);
        Ok(())
    }

    pub async fn drop_reader(&self) {
        if let Some(session) = self.reader.lock().await.take() {
            session.shutdown();
        }
    }

    /// A track's lyrics as `{"synced": bool, "lines": [{"ms": n|null,
    /// "text": ".."}]}`, or `None` when Spotify has none.
    pub async fn lyrics(&'static self, track_uri: &str) -> Result<Option<Value>> {
        let Some(id) = track_uri
            .strip_prefix("spotify:track:")
            .and_then(|id| SpotifyId::from_base62(id).ok())
        else {
            return Ok(None);
        };
        let session = self.session_for_reads().await?;
        // Spotify's own request names the album cover; without it Spotify
        // answers 404 for most tracks.
        let cover = match Track::get(&session, &SpotifyUri::Track { id }).await {
            Ok(track) => track.album.covers.first().map(|cover| cover.id),
            Err(error) => {
                log::debug!("no cover to ask for lyrics with: {error}");
                None
            }
        };
        let track = id.to_base62();
        let query = "format=json&vocalRemoval=false&market=from_token";
        let endpoint = match cover {
            Some(cover) => format!("/color-lyrics/v2/track/{track}/image/spotify:image:{cover}?{query}"),
            None => format!("/color-lyrics/v2/track/{track}?{query}"),
        };
        match session
            .spclient()
            .request_as_json(&reqwest::Method::GET, &endpoint, None, None)
            .await
        {
            Ok(bytes) => {
                let json: Value = serde_json::from_slice(&bytes).context("unreadable lyrics")?;
                let lyrics = simplify_lyrics(&json);
                // The shape only: lyrics never go to the log.
                log::info!(
                    "lyrics {track_uri}: {}",
                    lyrics.as_ref().map_or("none".to_string(), |found| format!(
                        "{} lines",
                        found["lines"].as_array().map_or(0, Vec::len)
                    ))
                );
                Ok(lyrics)
            }
            Err(error) if error.kind == ErrorKind::NotFound => Ok(None),
            Err(error) => bail!("Spotify couldn't load the lyrics: {error}"),
        }
    }

    /// `{"madeForYou": [playlist..], "topMixes": [playlist..]}`, each playlist
    /// shaped like a Web API simplified playlist so the app reads both alike.
    pub async fn home_shelves(&'static self, timezone: &str) -> Result<Value> {
        let session = self.session_for_reads().await?;
        let token = session
            .login5()
            .auth_token()
            .await
            .map_err(|error| anyhow!("Spotify session token: {error}"))?;
        let client_token = session
            .spclient()
            .client_token()
            .await
            .map_err(|error| anyhow!("Spotify client token: {error}"))?;
        // Spotify's Home request takes the Web API token as its context.
        let context_token = self.web_token().await?;
        let country = session.country();
        let read = |section: &'static str| {
            let body = home_query(section, &country, timezone, &context_token);
            let request = self
                .http
                .post(PATHFINDER)
                .bearer_auth(&token.access_token)
                .header("client-token", &client_token)
                .json(&body);
            async move {
                let response = request.send().await.context("Spotify could not be reached")?;
                let status = response.status();
                if status == reqwest::StatusCode::TOO_MANY_REQUESTS {
                    bail!("Spotify asks to slow down. Try again in a minute.");
                }
                if !status.is_success() {
                    bail!("Spotify answered {status} for Home");
                }
                let body: Value = response.json().await.context("unreadable Home answer")?;
                Ok(parse_home_section(&body))
            }
        };
        let (made_for_you, top_mixes) = tokio::join!(read(MADE_FOR_YOU), read(TOP_MIXES));
        let made_for_you = made_for_you?;
        let top_mixes = top_mixes.unwrap_or_else(|error| {
            log::info!("top mixes unavailable: {error:#}");
            Vec::new()
        });
        Ok(json!({ "madeForYou": made_for_you, "topMixes": top_mixes }))
    }
}

fn home_query(section: &str, country: &str, timezone: &str, context_token: &str) -> Value {
    json!({
        "operationName": "homeSection",
        "variables": {
            "uri": section,
            "country": country,
            "timeZone": timezone,
            "sp_t": context_token,
            "sectionItemsLimit": SHELF_SIZE,
            "sectionItemsOffset": 0
        },
        "extensions": {"persistedQuery": {"version": 1, "sha256Hash": HOME_QUERY_HASH}}
    })
}

/// Playlist cards of one Home section, in Spotify's order.
fn parse_home_section(body: &Value) -> Vec<Value> {
    let mut playlists = Vec::new();
    let sections = body
        .pointer("/data/homeSections/sections")
        .and_then(Value::as_array)
        .cloned()
        .unwrap_or_default();
    for section in sections {
        let items = section
            .pointer("/sectionItems/items")
            .and_then(Value::as_array)
            .cloned()
            .unwrap_or_default();
        for item in items {
            let Some(data) = item.pointer("/content/data") else {
                continue;
            };
            if data.get("__typename").and_then(Value::as_str) != Some("Playlist") {
                continue;
            }
            let Some(uri) = data.get("uri").and_then(Value::as_str) else {
                continue;
            };
            let Some(id) = uri.strip_prefix("spotify:playlist:") else {
                continue;
            };
            if uri == DJ_URI || id.len() != 22 {
                continue;
            }
            let images: Vec<Value> = data
                .pointer("/images/items")
                .and_then(Value::as_array)
                .into_iter()
                .flatten()
                .filter_map(|image| image.get("sources").and_then(Value::as_array))
                .flatten()
                .filter(|source| source.get("url").and_then(Value::as_str).is_some_and(|url| url.starts_with("https://")))
                .map(|source| {
                    json!({
                        "url": source["url"],
                        "width": source.get("width").cloned().unwrap_or(Value::Null),
                        "height": source.get("height").cloned().unwrap_or(Value::Null),
                    })
                })
                .collect();
            let owner = data
                .pointer("/ownerV2/data/name")
                .and_then(Value::as_str)
                .unwrap_or("Spotify");
            playlists.push(json!({
                "type": "playlist",
                "id": id,
                "uri": uri,
                "name": data.get("name").and_then(Value::as_str).unwrap_or_default(),
                "description": data.get("description").and_then(Value::as_str).unwrap_or_default(),
                "images": images,
                "owner": { "display_name": owner },
            }));
        }
    }
    playlists
}

/// Spotify's `color-lyrics` answer, reduced to lines and their start times.
fn simplify_lyrics(json: &Value) -> Option<Value> {
    let lyrics = json.get("lyrics")?;
    let synced = lyrics.get("syncType").and_then(Value::as_str) == Some("LINE_SYNCED");
    let lines: Vec<(Option<u64>, String)> = lyrics
        .get("lines")?
        .as_array()?
        .iter()
        .filter_map(|line| {
            let text = line.get("words")?.as_str()?.trim();
            if text.is_empty() || text == "\u{266a}" {
                return None;
            }
            let at = line.get("startTimeMs").and_then(|value| {
                value
                    .as_str()
                    .and_then(|text| text.parse().ok())
                    .or_else(|| value.as_u64())
            });
            Some((at.filter(|_| synced), text.to_string()))
        })
        .collect();
    if lines.is_empty() {
        return None;
    }
    let synced = synced && lines.iter().all(|(at, _)| at.is_some());
    Some(json!({
        "synced": synced,
        "lines": lines
            .into_iter()
            .map(|(at, text)| json!({ "ms": if synced { at } else { None }, "text": text }))
            .collect::<Vec<_>>(),
    }))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn synced_lyrics_keep_their_times_and_drop_empty_lines() {
        let answer = json!({ "lyrics": { "syncType": "LINE_SYNCED", "lines": [
            { "startTimeMs": "1000", "words": "first placeholder line" },
            { "startTimeMs": "2500", "words": "\u{266a}" },
            { "startTimeMs": "4000", "words": "second placeholder line" },
            { "startTimeMs": "5000", "words": "  " }
        ]}});
        let simple = simplify_lyrics(&answer).unwrap();
        assert_eq!(simple["synced"], true);
        assert_eq!(simple["lines"].as_array().unwrap().len(), 2);
        assert_eq!(simple["lines"][1]["ms"], 4000);
        assert_eq!(simple["lines"][1]["text"], "second placeholder line");
    }

    #[test]
    fn unsynced_lyrics_have_no_times() {
        let answer = json!({ "lyrics": { "syncType": "UNSYNCED", "lines": [
            { "startTimeMs": "0", "words": "a placeholder line" }
        ]}});
        let simple = simplify_lyrics(&answer).unwrap();
        assert_eq!(simple["synced"], false);
        assert!(simple["lines"][0]["ms"].is_null());
        assert!(simplify_lyrics(&json!({ "lyrics": { "lines": [] } })).is_none());
    }

    #[test]
    fn home_cards_become_playlists_and_skip_dj_and_other_types() {
        let id = "37i9dQZEVXcJZyENOWUFo7";
        let body = json!({ "data": { "homeSections": { "sections": [ { "sectionItems": { "items": [
            { "content": { "data": {
                "__typename": "Playlist", "uri": format!("spotify:playlist:{id}"), "name": "Discover Weekly",
                "images": { "items": [ { "sources": [ { "url": "https://i.scdn.co/image/a", "width": 300 } ] } ] },
                "ownerV2": { "data": { "name": "Spotify" } }
            } } },
            { "content": { "data": { "__typename": "Playlist", "uri": DJ_URI, "name": "DJ" } } },
            { "content": { "data": { "__typename": "Album", "uri": "spotify:album:x", "name": "An album" } } }
        ] } } ] } } });
        let cards = parse_home_section(&body);
        assert_eq!(cards.len(), 1);
        assert_eq!(cards[0]["id"], id);
        assert_eq!(cards[0]["type"], "playlist");
        assert_eq!(cards[0]["images"][0]["width"], 300);
        assert_eq!(cards[0]["owner"]["display_name"], "Spotify");
    }
}
