//! Events for the Kotlin side, which pulls them with a blocking call on a
//! background thread (no JNI callbacks, no attached native threads).
//!
//! The latest playback state is also kept whole, so a recreated activity
//! can draw the player at once instead of waiting for the next event.

use std::collections::VecDeque;
use std::sync::{Condvar, Mutex};
use std::time::Duration;

use serde_json::{Value, json};

/// Older events are dropped past this, so a UI that stopped listening
/// cannot grow the queue without bound.
const LIMIT: usize = 512;

pub struct Events {
    queue: Mutex<VecDeque<String>>,
    ready: Condvar,
    state: Mutex<Value>,
}

impl Default for Events {
    fn default() -> Self {
        Self {
            queue: Mutex::new(VecDeque::new()),
            ready: Condvar::new(),
            state: Mutex::new(json!({})),
        }
    }
}

impl Events {
    pub fn emit(&self, event: Value) {
        let text = event.to_string();
        let mut queue = self.queue.lock().unwrap_or_else(|p| p.into_inner());
        if queue.len() >= LIMIT {
            queue.pop_front();
        }
        queue.push_back(text);
        self.ready.notify_all();
    }

    /// Merges `fields` into the kept playback state and emits the whole
    /// state as a `state` event.
    pub fn update_state(&self, fields: Value) {
        let snapshot = {
            let mut state = self.state.lock().unwrap_or_else(|p| p.into_inner());
            if let (Value::Object(state), Value::Object(fields)) = (&mut *state, fields) {
                for (key, value) in fields {
                    state.insert(key, value);
                }
            }
            state.clone()
        };
        self.emit(json!({ "type": "state", "state": snapshot }));
    }

    pub fn reset_state(&self) {
        *self.state.lock().unwrap_or_else(|p| p.into_inner()) = json!({});
        self.emit(json!({ "type": "state", "state": {} }));
    }

    pub fn state(&self) -> Value {
        self.state.lock().unwrap_or_else(|p| p.into_inner()).clone()
    }

    pub fn error(&self, place: &str, message: impl std::fmt::Display) {
        log::warn!("{place}: {message}");
        self.emit(json!({ "type": "error", "where": place, "message": message.to_string() }));
    }

    /// The next event, or `None` after `timeout` without one.
    pub fn next(&self, timeout: Duration) -> Option<String> {
        let queue = self.queue.lock().unwrap_or_else(|p| p.into_inner());
        let (mut queue, _) = self
            .ready
            .wait_timeout_while(queue, timeout, |queue| queue.is_empty())
            .unwrap_or_else(|p| p.into_inner());
        queue.pop_front()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn state_merges_and_is_emitted_whole() {
        let events = Events::default();
        events.update_state(json!({ "playing": true }));
        events.update_state(json!({ "position": 10 }));
        assert_eq!(events.state(), json!({ "playing": true, "position": 10 }));
        let _ = events.next(Duration::ZERO);
        let last: Value = serde_json::from_str(&events.next(Duration::ZERO).unwrap()).unwrap();
        assert_eq!(last["state"]["playing"], true);
        assert_eq!(last["state"]["position"], 10);
        assert!(events.next(Duration::from_millis(5)).is_none());
    }

    #[test]
    fn the_queue_is_bounded() {
        let events = Events::default();
        for index in 0..(LIMIT + 10) {
            events.emit(json!({ "n": index }));
        }
        let first: Value = serde_json::from_str(&events.next(Duration::ZERO).unwrap()).unwrap();
        assert_eq!(first["n"], 10);
    }
}
