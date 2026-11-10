//! The process-wide core: one tokio runtime, the Web API grant, the
//! playback engine, settings, and the event queue the app listens to.
//!
//! Sign-in follows Spotifast's desktop flow (docs/_reference/how-it-connects.md):
//! the listener's own Spotify app (personal Client ID) carries the Web API,
//! and a separate browser approval with Spotify's desktop identity carries
//! librespot playback. Both redirects come back to a loopback listener in
//! this process, which works the same on a phone: the browser on the device
//! opens `http://127.0.0.1:<port>/login`, which is this app.

use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::Duration;

use anyhow::{Context, Result, anyhow, bail};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use tokio::sync::watch;

use crate::audio::Pcm;
use crate::auth::{self, Grant, StoredToken, TokenEndpointError};
use crate::engine::Engine;
use crate::events::Events;

const API_ROOT: &str = "https://api.spotify.com";
/// A `Retry-After` up to this long is waited out once; longer ones are
/// reported so the interface can say so instead of hanging.
const RETRY_AFTER_LIMIT: Duration = Duration::from_secs(8);
/// What the Android app asks the personal app for: Spotifast's Web API
/// scopes (auth::WEB_SCOPES) plus the two Spotify's web player needs to
/// play on this phone (Web Playback SDK: streaming, user-read-email).
pub const ANDROID_WEB_SCOPES: &[&str] = &[
    "ugc-image-upload",
    "playlist-modify-private",
    "playlist-modify-public",
    "playlist-read-collaborative",
    "playlist-read-private",
    "user-follow-modify",
    "user-follow-read",
    "user-library-modify",
    "user-library-read",
    "user-modify-playback-state",
    "user-read-playback-position",
    "user-read-playback-state",
    "user-read-private",
    "user-read-recently-played",
    "user-top-read",
    "streaming",
    "user-read-email",
];

/// The scopes Spotify's web player needs on the access token.
pub const WEB_PLAYER_SCOPES: &[&str] = &["streaming", "user-read-email", "user-read-private"];

/// A playback connection that takes longer than this has stalled.
const CONNECT_TIMEOUT: Duration = Duration::from_secs(60);

#[derive(Clone, Default, Serialize, Deserialize)]
pub struct Settings {
    #[serde(default)]
    pub client_id: String,
    /// The Spotify account the Web API grant verified; playback binds to it.
    #[serde(default)]
    pub account_id: String,
    #[serde(default)]
    pub display_name: String,
    #[serde(default)]
    pub product: String,
    /// Stable per install, so Spotify Connect sees one device.
    #[serde(default)]
    pub device_id: String,
}

pub struct Core {
    pub runtime: tokio::runtime::Runtime,
    pub http: reqwest::Client,
    pub events: Events,
    pub pcm: Arc<Pcm>,
    pub data_dir: PathBuf,
    pub cache_dir: PathBuf,
    pub device_name: String,
    settings: Mutex<Settings>,
    web: tokio::sync::Mutex<Option<StoredToken>>,
    engine: Mutex<Option<Arc<Engine>>>,
    /// Serialises engine starts so two connects never race.
    connecting: tokio::sync::Mutex<()>,
    /// What the playback connection is doing, for the interface and for
    /// the error when it stalls.
    step: Mutex<String>,
    web_login: Mutex<Option<watch::Sender<bool>>>,
    playback_login: Mutex<Option<watch::Sender<bool>>>,
}

static CORE: OnceLock<Core> = OnceLock::new();

pub fn get() -> Result<&'static Core> {
    CORE.get().ok_or_else(|| anyhow!("Spotifast core is not initialised"))
}

/// Initialises the core once per process; later calls return the same one.
pub fn init(data_dir: &str, cache_dir: &str, device_name: &str) -> Result<&'static Core> {
    if let Some(core) = CORE.get() {
        return Ok(core);
    }
    // A panic on a runtime task would otherwise vanish without a trace and
    // leave the interface waiting: report it like any other error.
    let previous = std::panic::take_hook();
    std::panic::set_hook(Box::new(move |info| {
        if let Some(core) = CORE.get() {
            core.events.error("panic", format!("Internal error: {info}"));
        }
        previous(info);
    }));
    let data_dir = PathBuf::from(data_dir);
    let cache_dir = PathBuf::from(cache_dir);
    std::fs::create_dir_all(&data_dir).context("cannot create the data folder")?;
    std::fs::create_dir_all(cache_dir.join("tmp")).context("cannot create the cache folder")?;
    let runtime = tokio::runtime::Builder::new_multi_thread()
        .worker_threads(4)
        .thread_name("spotifast")
        .enable_all()
        .build()
        .context("cannot start the runtime")?;
    let http = reqwest::Client::builder()
        .user_agent(concat!("Spotifast-Android/", env!("CARGO_PKG_VERSION")))
        .connect_timeout(Duration::from_secs(10))
        .timeout(Duration::from_secs(30))
        .build()
        .context("cannot build the HTTP client")?;
    let mut settings: Settings = read_json(&data_dir.join("settings.json")).unwrap_or_default();
    if settings.device_id.is_empty() {
        settings.device_id = random_hex(20);
        let _ = write_private_json(&data_dir.join("settings.json"), &settings);
    }
    let web = StoredToken::load(&data_dir.join("web_token.json"));
    let core = Core {
        runtime,
        http,
        events: Events::default(),
        pcm: Arc::new(Pcm::default()),
        device_name: device_name.to_string(),
        settings: Mutex::new(settings),
        web: tokio::sync::Mutex::new(web),
        engine: Mutex::new(None),
        connecting: tokio::sync::Mutex::new(()),
        step: Mutex::new(String::new()),
        web_login: Mutex::new(None),
        playback_login: Mutex::new(None),
        data_dir,
        cache_dir,
    };
    Ok(CORE.get_or_init(|| core))
}

impl Core {
    pub fn settings(&self) -> Settings {
        self.settings.lock().unwrap_or_else(|p| p.into_inner()).clone()
    }

    fn update_settings(&self, change: impl FnOnce(&mut Settings)) {
        let snapshot = {
            let mut settings = self.settings.lock().unwrap_or_else(|p| p.into_inner());
            change(&mut settings);
            settings.clone()
        };
        if let Err(error) = write_private_json(&self.data_dir.join("settings.json"), &snapshot) {
            log::warn!("cannot save settings: {error}");
        }
    }

    pub fn engine(&self) -> Option<Arc<Engine>> {
        self.engine.lock().unwrap_or_else(|p| p.into_inner()).clone()
    }

    fn web_token_path(&self) -> PathBuf {
        self.data_dir.join("web_token.json")
    }

    pub fn librespot_dir(&self) -> PathBuf {
        self.data_dir.join("librespot")
    }

    /// What the app needs to decide which screen to show.
    pub async fn status(&self) -> Value {
        let settings = self.settings();
        let (web, web_player) = {
            let token = self.web.lock().await;
            (
                token.is_some(),
                token.as_ref().is_some_and(|token| token.has_scopes(WEB_PLAYER_SCOPES)),
            )
        };
        let engine = self.engine();
        json!({
            "clientId": settings.client_id,
            "redirectUri": auth::personal_redirect_uri(),
            "web": web,
            "accountId": settings.account_id,
            "displayName": settings.display_name,
            "product": settings.product,
            "playbackSaved": self.saved_playback_credentials().is_some(),
            "playback": engine.is_some(),
            "webPlayer": web_player,
            "deviceId": engine.map(|engine| engine.device_id().to_string()).unwrap_or_default(),
        })
    }

    /// Called once the app is up: reconnects playback from the saved
    /// credential, if there is one.
    pub fn start(&'static self, connect_built_in: bool) {
        self.runtime.spawn(async move {
            if self.web.lock().await.is_none() {
                return;
            }
            if self.settings().account_id.is_empty() {
                match self.verify_account().await {
                    Ok(_) => self.events.emit(json!({ "type": "web_signed_in" })),
                    Err(error) => log::warn!("account check failed: {error:#}"),
                }
            }
            if !connect_built_in {
                return;
            }
            if let Some(credentials) = self.saved_playback_credentials() {
                if let Err(error) = self.connect_engine(credentials).await {
                    self.events.error("playback", format!("{error:#}"));
                }
            }
        });
    }

    /// Stops the built-in player and leaves Spotify Connect, keeping its
    /// saved credential for later.
    pub fn stop_engine(&self) {
        if let Some(engine) = self.engine.lock().unwrap_or_else(|p| p.into_inner()).take() {
            engine.shutdown();
        }
        self.events.reset_state();
    }

    fn saved_playback_credentials(&self) -> Option<librespot_core::authentication::Credentials> {
        librespot_core::cache::Cache::new(Some(self.librespot_dir()), None, None, None)
            .ok()?
            .credentials()
    }

    // ---------------------------------------------------------------- Web API

    pub fn set_client_id(&self, client_id: &str) -> Result<()> {
        let client_id = client_id.trim();
        if !auth::looks_like_client_id(client_id) {
            bail!("A Spotify Client ID is 32 letters and digits. Copy it from your app's page on developer.spotify.com.");
        }
        self.update_settings(|settings| settings.client_id = client_id.to_string());
        Ok(())
    }

    /// Starts the Web API sign-in and returns the URL to open.
    pub fn begin_web_login(&'static self) -> Result<String> {
        let client_id = self.settings().client_id;
        let grant = Grant {
            scopes: ANDROID_WEB_SCOPES,
            ..Grant::personal_web_api(&client_id)?
        };
        let (listener, cancel) = self.reserve_port(&self.web_login, grant.redirect_port)?;
        let flow = auth::begin(grant.clone());
        let url = flow.url.clone();
        self.runtime.spawn(async move {
            let outcome: Result<Value> = async {
                if auth::client_known(&self.http, &grant.client_id).await == Some(false) {
                    bail!("Spotify doesn't know this Client ID. Check it on developer.spotify.com.");
                }
                let code = auth::wait_for_code_on_listener(listener, &flow.state, cancel).await?;
                let response =
                    auth::exchange_code(&self.http, &grant, &code, &flow.verifier).await?;
                let token = StoredToken::from_response(&grant.client_id, response, None)?;
                self.save_web_token(&token);
                *self.web.lock().await = Some(token);
                self.verify_account().await
            }
            .await;
            match outcome {
                Ok(account) => self.events.emit(json!({ "type": "web_signed_in", "account": account })),
                Err(error) => self.events.error("web_login", format!("{error:#}")),
            }
        });
        Ok(url)
    }

    /// Reads the account behind the Web API grant and remembers who it is.
    async fn verify_account(&self) -> Result<Value> {
        let (status, body) = self.api("GET", "/v1/me", None).await?;
        if status != 200 {
            let reason = serde_json::from_str::<Value>(&body)
                .ok()
                .and_then(|value| value["error"]["message"].as_str().map(str::to_string))
                .unwrap_or_default();
            bail!("Spotify answered {status} when reading the account {reason}");
        }
        let me: Value = serde_json::from_str(&body).context("unreadable account")?;
        let id = me["id"].as_str().unwrap_or_default().to_string();
        if id.is_empty() {
            bail!("Spotify did not say which account this is");
        }
        let name = me["display_name"].as_str().unwrap_or(&id).to_string();
        let product = me["product"].as_str().unwrap_or_default().to_string();
        let previous = self.settings().account_id;
        if !previous.is_empty() && previous != id {
            // A different account: the old playback credential is not theirs.
            self.forget_playback().await;
        }
        self.update_settings(|settings| {
            settings.account_id = id.clone();
            settings.display_name = name.clone();
            settings.product = product.clone();
        });
        Ok(json!({ "id": id, "name": name, "product": product }))
    }

    fn save_web_token(&self, token: &StoredToken) {
        if let Err(error) = write_private_json(&self.web_token_path(), token) {
            log::warn!("cannot save the Web API grant: {error}");
        }
    }

    /// A current access token for the Web API, refreshed when it is close
    /// to expiring.
    pub async fn web_token(&self) -> Result<String> {
        let mut slot = self.web.lock().await;
        let token = slot.as_ref().ok_or_else(|| anyhow!("not signed in"))?;
        if !token.needs_refresh() {
            return Ok(token.access_token.clone());
        }
        let client_id = token.client_id.clone();
        let refresh_token = token.refresh_token.clone();
        match auth::refresh(&self.http, &client_id, &refresh_token).await {
            Ok(response) => {
                let fresh = StoredToken::from_response(&client_id, response, Some(&refresh_token))?;
                self.save_web_token(&fresh);
                let access = fresh.access_token.clone();
                *slot = Some(fresh);
                Ok(access)
            }
            Err(TokenEndpointError::Rejected { detail, .. }) => {
                *slot = None;
                let _ = std::fs::remove_file(self.web_token_path());
                self.events.emit(json!({ "type": "signed_out", "reason": detail }));
                bail!("{detail}")
            }
            Err(error @ TokenEndpointError::Unreachable(_)) => {
                // Still usable until it actually expires.
                if !token.expired() {
                    return Ok(token.access_token.clone());
                }
                Err(error.into())
            }
        }
    }

    /// One Web API request. `path` is `/v1/...` or a full `api.spotify.com`
    /// URL (the `next` links of paged answers). Returns the status and body.
    pub async fn api(&self, method: &str, path: &str, body: Option<String>) -> Result<(u16, String)> {
        let url = if path.starts_with("https://") {
            if !path.starts_with(API_ROOT) {
                bail!("only Spotify Web API URLs are allowed");
            }
            path.to_string()
        } else {
            format!("{API_ROOT}{path}")
        };
        let method = reqwest::Method::from_bytes(method.as_bytes()).context("bad method")?;
        let mut waited = false;
        loop {
            let token = self.web_token().await?;
            let mut request = self.http.request(method.clone(), &url).bearer_auth(token);
            if let Some(body) = &body {
                request = request
                    .header(reqwest::header::CONTENT_TYPE, "application/json")
                    .body(body.clone());
            } else if method != reqwest::Method::GET {
                request = request.header(reqwest::header::CONTENT_LENGTH, "0");
            }
            let response = request.send().await.context("Spotify could not be reached")?;
            let status = response.status().as_u16();
            if status == 429 && !waited {
                let wait = response
                    .headers()
                    .get(reqwest::header::RETRY_AFTER)
                    .and_then(|value| value.to_str().ok())
                    .and_then(|value| value.trim().parse::<u64>().ok())
                    .map(Duration::from_secs)
                    .unwrap_or(Duration::from_secs(2));
                if wait <= RETRY_AFTER_LIMIT {
                    waited = true;
                    tokio::time::sleep(wait).await;
                    continue;
                }
                bail!("Spotify asks to slow down. Try again in {} s.", wait.as_secs());
            }
            if status == 401 && !waited {
                // Revoked or expired early: force a refresh once.
                waited = true;
                if let Some(token) = self.web.lock().await.as_mut() {
                    token.expires_at = 0;
                }
                continue;
            }
            let text = response.text().await.unwrap_or_default();
            return Ok((status, text));
        }
    }

    // --------------------------------------------------------------- Playback

    /// Starts the playback approval and returns the URL to open.
    pub fn begin_playback_login(&'static self) -> Result<String> {
        if self.runtime.block_on(async { self.web.lock().await.is_none() }) {
            bail!("Sign in with your Spotify app first.");
        }
        if self.settings().account_id.is_empty() {
            // The account check right after sign-in can fail (the app was
            // frozen in the background, a slow network): try it again now.
            if let Err(error) = self.runtime.block_on(self.verify_account()) {
                log::warn!("account check failed again: {error:#}");
            }
        }
        let grant = Grant::playback();
        let (listener, cancel) = self.reserve_port(&self.playback_login, grant.redirect_port)?;
        let flow = auth::begin(grant.clone());
        let url = flow.url.clone();
        self.runtime.spawn(async move {
            let outcome: Result<()> = async {
                let code = auth::wait_for_code_on_listener(listener, &flow.state, cancel).await?;
                let response =
                    auth::exchange_code(&self.http, &grant, &code, &flow.verifier).await?;
                if self.web.lock().await.is_none() {
                    bail!("signed out while approving playback");
                }
                // Bound to the verified account when known; otherwise
                // Spotify takes the account from the streaming token itself.
                let account = self.settings().account_id;
                let credentials = librespot_core::authentication::Credentials {
                    username: (!account.is_empty()).then_some(account),
                    auth_type: librespot_protocol::authentication::AuthenticationType::AUTHENTICATION_SPOTIFY_TOKEN,
                    auth_data: response.access_token.into_bytes(),
                };
                self.connect_engine(credentials).await
            }
            .await;
            if let Err(error) = outcome {
                self.events.error("playback_login", format!("{error:#}"));
            }
        });
        Ok(url)
    }

    /// Connects the librespot session and announces this phone on Spotify
    /// Connect. Replaces a running engine.
    pub async fn connect_engine(
        &'static self,
        credentials: librespot_core::authentication::Credentials,
    ) -> Result<()> {
        let _guard = self.connecting.lock().await;
        if let Some(old) = self.engine.lock().unwrap_or_else(|p| p.into_inner()).take() {
            old.shutdown();
        }
        self.set_step("starting");
        // On its own task, so a panic comes back as an error, and with a
        // deadline, so a stalled request cannot hold every later attempt.
        let mut attempt = tokio::spawn(Engine::connect(self, credentials));
        let engine = match tokio::time::timeout(CONNECT_TIMEOUT, &mut attempt).await {
            Ok(Ok(result)) => result.with_context(|| format!("Playback failed while {}", self.step()))?,
            Ok(Err(join)) if join.is_panic() => {
                bail!("Playback crashed while {}: {}", self.step(), panic_text(join.into_panic()))
            }
            Ok(Err(join)) => bail!("Playback stopped while {}: {join}", self.step()),
            Err(_) => {
                attempt.abort();
                bail!(
                    "Spotify didn't answer while {} (waited {} s).",
                    self.step(),
                    CONNECT_TIMEOUT.as_secs()
                )
            }
        };
        let device_id = engine.device_id().to_string();
        *self.engine.lock().unwrap_or_else(|p| p.into_inner()) = Some(Arc::new(engine));
        self.events.emit(json!({ "type": "playback_ready", "deviceId": device_id }));
        Ok(())
    }

    /// Records and shows what the playback connection is doing.
    pub fn set_step(&self, step: &str) {
        *self.step.lock().unwrap_or_else(|p| p.into_inner()) = step.to_string();
        self.events.emit(json!({ "type": "playback_connecting", "step": step }));
    }

    fn step(&self) -> String {
        self.step.lock().unwrap_or_else(|p| p.into_inner()).clone()
    }

    /// Called by the engine when its session ends on its own (network loss,
    /// another login): reconnect with the saved credential after a pause.
    pub fn engine_ended(&'static self, device_id: String) {
        let current = self.engine();
        if current.as_ref().map(|engine| engine.device_id()) != Some(device_id.as_str()) {
            return;
        }
        if let Some(engine) = self.engine.lock().unwrap_or_else(|p| p.into_inner()).take() {
            engine.shutdown();
        }
        self.events.emit(json!({ "type": "playback_lost" }));
        self.runtime.spawn(async move {
            for attempt in 0..5u32 {
                tokio::time::sleep(Duration::from_secs(2u64 << attempt)).await;
                if self.engine().is_some() {
                    return;
                }
                let Some(credentials) = self.saved_playback_credentials() else {
                    return;
                };
                match self.connect_engine(credentials).await {
                    Ok(()) => return,
                    Err(error) => log::warn!("reconnect attempt {attempt} failed: {error:#}"),
                }
            }
            self.events.error("playback", "Lost the connection to Spotify. Tap to reconnect.");
        });
    }

    pub fn reconnect(&'static self) -> Result<()> {
        let credentials = self
            .saved_playback_credentials()
            .ok_or_else(|| anyhow!("Playback isn't set up yet."))?;
        self.runtime.spawn(async move {
            if let Err(error) = self.connect_engine(credentials).await {
                self.events.error("playback", format!("{error:#}"));
            }
        });
        Ok(())
    }

    async fn forget_playback(&self) {
        if let Some(engine) = self.engine.lock().unwrap_or_else(|p| p.into_inner()).take() {
            engine.shutdown();
        }
        let _ = std::fs::remove_dir_all(self.librespot_dir());
        self.events.reset_state();
    }

    pub async fn sign_out(&self) {
        self.forget_playback().await;
        *self.web.lock().await = None;
        let _ = std::fs::remove_file(self.web_token_path());
        self.update_settings(|settings| {
            settings.account_id.clear();
            settings.display_name.clear();
            settings.product.clear();
        });
        self.events.emit(json!({ "type": "signed_out", "reason": "" }));
    }

    /// Binds a redirect port, cancelling an earlier sign-in that still holds it.
    fn reserve_port(
        &self,
        slot: &Mutex<Option<watch::Sender<bool>>>,
        port: u16,
    ) -> Result<(tokio::net::TcpListener, watch::Receiver<bool>)> {
        if let Some(previous) = slot.lock().unwrap_or_else(|p| p.into_inner()).take() {
            let _ = previous.send(true);
        }
        let _enter = self.runtime.enter();
        let mut last_error = None;
        for _ in 0..20 {
            match auth::listen_for_redirect(port) {
                Ok(listener) => {
                    let (sender, receiver) = watch::channel(false);
                    *slot.lock().unwrap_or_else(|p| p.into_inner()) = Some(sender);
                    return Ok((listener, receiver));
                }
                Err(error) => {
                    last_error = Some(error);
                    std::thread::sleep(Duration::from_millis(50));
                }
            }
        }
        Err(last_error.unwrap_or_else(|| anyhow!("port {port} is unavailable")))
    }
}

fn panic_text(panic: Box<dyn std::any::Any + Send>) -> String {
    panic
        .downcast_ref::<&str>()
        .map(|text| text.to_string())
        .or_else(|| panic.downcast_ref::<String>().cloned())
        .unwrap_or_else(|| "unknown panic".to_string())
}

fn read_json<T: for<'de> Deserialize<'de>>(path: &Path) -> Option<T> {
    serde_json::from_str(&std::fs::read_to_string(path).ok()?).ok()
}

/// Written whole through a temporary file, readable only by this app.
fn write_private_json<T: Serialize>(path: &Path, value: &T) -> Result<()> {
    use std::io::Write;
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)?;
    }
    let temporary = path.with_extension("json.tmp");
    let mut options = std::fs::OpenOptions::new();
    options.write(true).create(true).truncate(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    let mut file = options.open(&temporary)?;
    file.write_all(serde_json::to_string(value)?.as_bytes())?;
    file.sync_all()?;
    std::fs::rename(&temporary, path)?;
    Ok(())
}

fn random_hex(bytes: usize) -> String {
    use rand::RngCore;
    let mut buffer = vec![0u8; bytes];
    rand::rng().fill_bytes(&mut buffer);
    buffer.iter().map(|byte| format!("{byte:02x}")).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn private_json_round_trips() {
        let dir = std::env::temp_dir().join(format!("spotifast-android-{}", random_hex(4)));
        let path = dir.join("settings.json");
        let settings = Settings {
            client_id: "abc".into(),
            device_id: "dev".into(),
            ..Settings::default()
        };
        write_private_json(&path, &settings).unwrap();
        let back: Settings = read_json(&path).unwrap();
        assert_eq!(back.client_id, "abc");
        assert_eq!(back.device_id, "dev");
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn device_ids_are_hex() {
        let id = random_hex(20);
        assert_eq!(id.len(), 40);
        assert!(id.bytes().all(|byte| byte.is_ascii_hexdigit()));
    }
}
