//! The log, kept in memory as well, so the app can show it and a user can
//! copy it into a bug report. On Android every line also goes to logcat.
//!
//! librespot-core stays at info level: its debug lines are about tokens
//! and sign-in, and nothing secret belongs in a log someone pastes.

use std::collections::VecDeque;
use std::sync::Mutex;
use std::time::{SystemTime, UNIX_EPOCH};

use log::{Level, LevelFilter, Log, Metadata, Record};

const LIMIT: usize = 800;

static LINES: Mutex<VecDeque<String>> = Mutex::new(VecDeque::new());

fn level_for(target: &str) -> Level {
    if target.starts_with("spotifast_android")
        || target.starts_with("librespot_connect")
        || target.starts_with("librespot_playback")
    {
        Level::Debug
    } else if target.starts_with("librespot") {
        Level::Info
    } else {
        Level::Warn
    }
}

struct Tee {
    #[cfg(target_os = "android")]
    android: android_logger::AndroidLogger,
}

impl Log for Tee {
    fn enabled(&self, metadata: &Metadata) -> bool {
        metadata.level() <= level_for(metadata.target())
    }

    fn log(&self, record: &Record) {
        if !self.enabled(record.metadata()) {
            return;
        }
        #[cfg(target_os = "android")]
        self.android.log(record);
        push(format!(
            "{} {:<5} {}: {}",
            clock(),
            record.level(),
            record.target(),
            record.args()
        ));
    }

    fn flush(&self) {}
}

fn push(line: String) {
    let mut lines = LINES.lock().unwrap_or_else(|p| p.into_inner());
    if lines.len() >= LIMIT {
        lines.pop_front();
    }
    lines.push_back(line);
}

/// UTC time of day, enough to line events up.
fn clock() -> String {
    let millis = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|elapsed| elapsed.as_millis())
        .unwrap_or(0);
    let seconds = (millis / 1000) % 86_400;
    format!(
        "{:02}:{:02}:{:02}.{:03}",
        seconds / 3600,
        (seconds / 60) % 60,
        seconds % 60,
        millis % 1000
    )
}

pub fn init() {
    let tee = Tee {
        #[cfg(target_os = "android")]
        android: android_logger::AndroidLogger::new(
            android_logger::Config::default()
                .with_max_level(LevelFilter::Debug)
                .with_tag("Spotifast"),
        ),
    };
    // Lives for the whole process, as a logger must.
    if log::set_logger(Box::leak(Box::new(tee))).is_ok() {
        log::set_max_level(LevelFilter::Debug);
    }
}

/// Everything kept, oldest first.
pub fn text() -> String {
    let lines = LINES.lock().unwrap_or_else(|p| p.into_inner());
    let mut text = String::with_capacity(lines.len() * 80);
    for line in lines.iter() {
        text.push_str(line);
        text.push('\n');
    }
    text
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn levels_keep_secrets_out() {
        assert_eq!(level_for("librespot_core::login5"), Level::Info);
        assert_eq!(level_for("librespot_playback::player"), Level::Debug);
        assert_eq!(level_for("rustls::client"), Level::Warn);
    }

    #[test]
    fn the_buffer_is_bounded() {
        for index in 0..(LIMIT + 5) {
            push(format!("line {index}"));
        }
        let text = text();
        assert!(!text.contains("line 4\n"));
        assert!(text.contains(&format!("line {}\n", LIMIT + 4)));
    }
}
