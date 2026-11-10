//! Spotifast for Android: the native half.
//!
//! The Kotlin app (`android/app`) draws the interface and owns the audio
//! device; this library does what Spotifast's desktop backend does: sign-in
//! (Spotifast's own `src/auth.rs`, shared as is), the Web API, and librespot
//! playback with Spotify Connect.
//!
//! Every JNI entry point lives on `com.spotifast.android.Native`.

#[path = "../../../src/auth.rs"]
#[allow(dead_code)]
mod auth;

pub mod audio;
pub mod core;
pub mod engine;
pub mod events;
pub mod logbuf;

use std::cell::RefCell;
use std::panic::AssertUnwindSafe;
use std::time::Duration;

use anyhow::{Result, anyhow};
use jni::JNIEnv;
use jni::objects::{JClass, JShortArray, JString};
use jni::sys::{jint, jstring};
use serde_json::Value;

/// Runs `body`, turning an error or a panic into a Java exception and the
/// `fallback` return value.
fn guard<'local, T>(
    env: &mut JNIEnv<'local>,
    fallback: T,
    body: impl FnOnce(&mut JNIEnv<'local>) -> Result<T>,
) -> T {
    let outcome = std::panic::catch_unwind(AssertUnwindSafe(|| body(env)));
    let message = match outcome {
        Ok(Ok(value)) => return value,
        Ok(Err(error)) => format!("{error:#}"),
        Err(panic) => panic
            .downcast_ref::<&str>()
            .map(|text| text.to_string())
            .or_else(|| panic.downcast_ref::<String>().cloned())
            .unwrap_or_else(|| "Spotifast core panicked".to_string()),
    };
    if !env.exception_check().unwrap_or(false) {
        let _ = env.throw_new("java/lang/IllegalStateException", message);
    }
    fallback
}

fn string(env: &mut JNIEnv, value: &JString) -> Result<String> {
    Ok(env.get_string(value)?.into())
}

fn optional_string(env: &mut JNIEnv, value: &JString) -> Result<Option<String>> {
    if value.is_null() {
        Ok(None)
    } else {
        string(env, value).map(Some)
    }
}

fn java_string(env: &mut JNIEnv, value: &str) -> Result<jstring> {
    Ok(env.new_string(value)?.into_raw())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_spotifast_android_Native_init<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    data_dir: JString<'local>,
    cache_dir: JString<'local>,
    device_name: JString<'local>,
) {
    guard(&mut env, (), |env| {
        logbuf::init();
        let data_dir = string(env, &data_dir)?;
        let cache_dir = string(env, &cache_dir)?;
        let device_name = string(env, &device_name)?;
        core::init(&data_dir, &cache_dir, &device_name)?;
        Ok(())
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_spotifast_android_Native_start<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
) {
    guard(&mut env, (), |_| {
        core::get()?.start();
        Ok(())
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_spotifast_android_Native_status<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let core = core::get()?;
        let status = core.runtime.block_on(core.status());
        java_string(env, &status.to_string())
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_spotifast_android_Native_setClientId<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    client_id: JString<'local>,
) {
    guard(&mut env, (), |env| {
        let client_id = string(env, &client_id)?;
        core::get()?.set_client_id(&client_id)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_spotifast_android_Native_beginWebLogin<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let url = core::get()?.begin_web_login()?;
        java_string(env, &url)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_spotifast_android_Native_beginPlaybackLogin<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let url = core::get()?.begin_playback_login()?;
        java_string(env, &url)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_spotifast_android_Native_reconnect<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
) {
    guard(&mut env, (), |_| core::get()?.reconnect())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_spotifast_android_Native_signOut<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
) {
    guard(&mut env, (), |_| {
        let core = core::get()?;
        core.runtime.block_on(core.sign_out());
        Ok(())
    })
}

/// A Web API request. Blocks; call it off the main thread. Returns the
/// status code, a newline, then the body.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_spotifast_android_Native_api<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    method: JString<'local>,
    path: JString<'local>,
    body: JString<'local>,
) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let method = string(env, &method)?;
        let path = string(env, &path)?;
        let body = optional_string(env, &body)?;
        let core = core::get()?;
        let (status, text) = core.runtime.block_on(core.api(&method, &path, body))?;
        java_string(env, &format!("{status}\n{text}"))
    })
}

/// A playback command as JSON, such as `{"cmd":"next"}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_spotifast_android_Native_command<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    command: JString<'local>,
) {
    guard(&mut env, (), |env| {
        let command: Value = serde_json::from_str(&string(env, &command)?)?;
        let engine = core::get()?
            .engine()
            .ok_or_else(|| anyhow!("Playback isn't connected yet."))?;
        engine.command(&command)
    })
}

/// The recent log, for the app's debug screen.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_spotifast_android_Native_logs<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| java_string(env, &logbuf::text()))
}

/// The latest playback state as JSON.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_spotifast_android_Native_state<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let state = core::get()?.events.state();
        java_string(env, &state.to_string())
    })
}

/// The next event as JSON, or null after `timeout_ms` without one.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_spotifast_android_Native_nextEvent<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    timeout_ms: jint,
) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let core = core::get()?;
        match core.events.next(Duration::from_millis(timeout_ms.max(0) as u64)) {
            Some(event) => java_string(env, &event),
            None => Ok(std::ptr::null_mut()),
        }
    })
}

thread_local! {
    static PCM_SCRATCH: RefCell<Vec<i16>> = const { RefCell::new(Vec::new()) };
}

/// Fills `buffer` with 16-bit stereo PCM at 44.1 kHz. Returns the number of
/// samples written, -1 after a flush, -2 while paused, 0 on timeout.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_spotifast_android_Native_readPcm<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    buffer: JShortArray<'local>,
    timeout_ms: jint,
) -> jint {
    guard(&mut env, 0, |env| {
        let core = core::get()?;
        let length = env.get_array_length(&buffer)?.max(0) as usize;
        PCM_SCRATCH.with(|scratch| {
            let mut scratch = scratch.borrow_mut();
            scratch.resize(length, 0);
            let count = core
                .pcm
                .read(&mut scratch, Duration::from_millis(timeout_ms.max(0) as u64));
            if count > 0 {
                env.set_short_array_region(&buffer, 0, &scratch[..count as usize])?;
            }
            Ok(count)
        })
    })
}
