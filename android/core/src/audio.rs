//! The audio path to Android: librespot's player writes 16-bit stereo PCM
//! at 44.1 kHz into a small ring, and the app's AudioTrack thread pulls it
//! out with [`Pcm::read`].
//!
//! Pausing is optimistic, as in Spotifast: a pause from the interface
//! stops the reader at once, while librespot catches up behind. Audio
//! already decoded stays in the ring, so resuming picks up exactly where
//! the sound stopped. A seek or a new track flushes it instead.

use std::collections::VecDeque;
use std::sync::{Arc, Condvar, Mutex};
use std::time::Duration;

use librespot_playback::audio_backend::{Sink, SinkResult};
use librespot_playback::convert::Converter;
use librespot_playback::decoder::AudioPacket;

pub const SAMPLE_RATE: u32 = librespot_playback::SAMPLE_RATE;
pub const CHANNELS: u32 = librespot_playback::NUM_CHANNELS as u32;

/// Half a second of stereo audio: enough to ride out a busy moment, short
/// enough that a flush throws away little.
const CAPACITY: usize = (SAMPLE_RATE as usize) * (CHANNELS as usize) / 2;
/// While paused, writes never block (librespot may still be finishing a
/// packet before it handles the pause), but the ring stops growing here.
const PAUSED_LIMIT: usize = CAPACITY * 4;
/// How long a blocked writer waits before checking again.
const WRITE_POLL: Duration = Duration::from_millis(100);

/// What [`Pcm::read`] reports besides a sample count.
pub const READ_FLUSHED: i32 = -1;
pub const READ_PAUSED: i32 = -2;

#[derive(Default)]
struct Ring {
    samples: VecDeque<i16>,
    paused: bool,
    flushed: bool,
    closed: bool,
}

#[derive(Default)]
pub struct Pcm {
    ring: Mutex<Ring>,
    readable: Condvar,
    writable: Condvar,
}

impl Pcm {
    fn lock(&self) -> std::sync::MutexGuard<'_, Ring> {
        self.ring.lock().unwrap_or_else(|p| p.into_inner())
    }

    /// Appends samples, waiting for room while playing.
    pub fn push(&self, mut samples: &[i16]) {
        let mut ring = self.lock();
        while !samples.is_empty() {
            if ring.closed {
                return;
            }
            let limit = if ring.paused { PAUSED_LIMIT } else { CAPACITY };
            let room = limit.saturating_sub(ring.samples.len());
            if room == 0 {
                if ring.paused {
                    // Dropping beats blocking the player thread while paused.
                    return;
                }
                ring = self
                    .writable
                    .wait_timeout(ring, WRITE_POLL)
                    .unwrap_or_else(|p| p.into_inner())
                    .0;
                continue;
            }
            let take = room.min(samples.len());
            ring.samples.extend(&samples[..take]);
            samples = &samples[take..];
            self.readable.notify_all();
        }
    }

    /// Fills `out` with what is ready. Returns the number of samples,
    /// [`READ_FLUSHED`] once after a flush (the reader drops what its own
    /// device still holds), [`READ_PAUSED`] while paused, or 0 when
    /// nothing arrived within `timeout`.
    pub fn read(&self, out: &mut [i16], timeout: Duration) -> i32 {
        let ring = self.lock();
        let (mut ring, _) = self
            .readable
            .wait_timeout_while(ring, timeout, |ring| {
                !ring.flushed && !ring.closed && (ring.paused || ring.samples.is_empty())
            })
            .unwrap_or_else(|p| p.into_inner());
        if ring.flushed {
            ring.flushed = false;
            return READ_FLUSHED;
        }
        if ring.paused {
            return READ_PAUSED;
        }
        let count = out.len().min(ring.samples.len());
        for (slot, sample) in out.iter_mut().zip(ring.samples.drain(..count)) {
            *slot = sample;
        }
        if count > 0 {
            self.writable.notify_all();
        }
        count as i32
    }

    /// Drops buffered audio: a seek, a new track, a stop.
    pub fn flush(&self) {
        let mut ring = self.lock();
        ring.samples.clear();
        ring.flushed = true;
        self.readable.notify_all();
        self.writable.notify_all();
    }

    pub fn set_paused(&self, paused: bool) {
        let mut ring = self.lock();
        if ring.paused != paused {
            ring.paused = paused;
            self.readable.notify_all();
            self.writable.notify_all();
        }
    }

    pub fn paused(&self) -> bool {
        self.lock().paused
    }

    /// Wakes every waiter for good; used when the engine shuts down.
    pub fn close(&self) {
        let mut ring = self.lock();
        ring.closed = true;
        ring.samples.clear();
        self.readable.notify_all();
        self.writable.notify_all();
    }

    pub fn reopen(&self) {
        let mut ring = self.lock();
        ring.closed = false;
        ring.flushed = true;
        self.readable.notify_all();
    }
}

/// librespot's sink, writing into the shared ring.
pub struct AndroidSink {
    pcm: Arc<Pcm>,
}

impl AndroidSink {
    pub fn new(pcm: Arc<Pcm>) -> Self {
        Self { pcm }
    }
}

impl Sink for AndroidSink {
    fn write(&mut self, packet: AudioPacket, converter: &mut Converter) -> SinkResult<()> {
        if let AudioPacket::Samples(samples) = packet {
            let samples = converter.f64_to_s16(&samples);
            self.pcm.push(&samples);
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::thread;

    #[test]
    fn samples_come_out_in_order() {
        let pcm = Pcm::default();
        pcm.push(&[1, 2, 3, 4]);
        let mut out = [0i16; 3];
        assert_eq!(pcm.read(&mut out, Duration::ZERO), 3);
        assert_eq!(out, [1, 2, 3]);
        assert_eq!(pcm.read(&mut out, Duration::ZERO), 1);
        assert_eq!(out[0], 4);
        assert_eq!(pcm.read(&mut out, Duration::from_millis(5)), 0);
    }

    #[test]
    fn a_flush_is_reported_once_and_drops_audio() {
        let pcm = Pcm::default();
        pcm.push(&[1, 2]);
        pcm.flush();
        let mut out = [0i16; 4];
        assert_eq!(pcm.read(&mut out, Duration::ZERO), READ_FLUSHED);
        assert_eq!(pcm.read(&mut out, Duration::from_millis(5)), 0);
    }

    #[test]
    fn pausing_keeps_audio_for_the_resume() {
        let pcm = Pcm::default();
        pcm.push(&[7, 8]);
        pcm.set_paused(true);
        let mut out = [0i16; 4];
        assert_eq!(pcm.read(&mut out, Duration::from_millis(5)), READ_PAUSED);
        pcm.set_paused(false);
        assert_eq!(pcm.read(&mut out, Duration::ZERO), 2);
        assert_eq!(&out[..2], &[7, 8]);
    }

    #[test]
    fn a_full_ring_blocks_the_writer_until_read() {
        let pcm = Arc::new(Pcm::default());
        pcm.push(&vec![0; CAPACITY]);
        let writer = {
            let pcm = Arc::clone(&pcm);
            thread::spawn(move || pcm.push(&[5; 10]))
        };
        thread::sleep(Duration::from_millis(30));
        assert!(!writer.is_finished());
        let mut out = vec![0i16; 64];
        assert_eq!(pcm.read(&mut out, Duration::ZERO), 64);
        writer.join().unwrap();
    }

    #[test]
    fn a_paused_ring_never_blocks_the_writer() {
        let pcm = Pcm::default();
        pcm.set_paused(true);
        pcm.push(&vec![0; PAUSED_LIMIT + 100]);
        assert_eq!(pcm.lock().samples.len(), PAUSED_LIMIT);
    }
}
