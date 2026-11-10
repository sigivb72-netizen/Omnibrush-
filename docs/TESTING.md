# Testing and measurements

## What has been verified, and how

| Part | Status |
|---|---|
| Protocol, pairing, HTTP/WebSocket/TLS server, frame dropping, bitrate controller | Unit tests (`./gradlew :core:test`, 12 tests) and the end-to-end test below |
| Browser receiver, WebCodecs and Media Source decoders | End-to-end in headless Chromium against a live H.264 stream |
| Orientation change, sender restart and reconnection | End-to-end, same setup |
| Android app | **Compiles, passes lint, builds an APK in CI. Never run on a device.** |
| iPad app | **Compiles in CI (see the iOS workflow). Never run on a device.** |
| Safari on iPadOS | **Not tested.** Chromium implements the same web APIs, but Safari's media stack differs |
| Galaxy A15 → iPad over Wi-Fi | **Not tested.** No numbers exist yet |

## Measured here

Setup: `android/simulator` (the real server code fed by ffmpeg/libx264, zero-latency, baseline
profile) → loopback → the browser receiver in headless Chromium, software decoding, 4-core
cloud VM. Latency is the receiver's own figure: frame leaves the encoder → frame painted.

| Stream | Decoder | Transport | Displayed fps | Median latency |
|---|---|---|---|---|
| 1280×720 @ 30, 8 Mbps | WebCodecs | ws | 29.8 | 14 ms |
| 1920×1080 @ 60, 8 Mbps | WebCodecs | wss (TLS) | 60.0 | 19 ms |
| 1280×720 @ 30, 8 Mbps | Media Source | ws | 30.0 | 59 ms |
| 1280×720 @ 60, 8 Mbps, rotating every 5 s | Media Source | ws | 43 (see note) | 25 ms |

- Orientation: with the stream swapping between 1280×720 and 720×1280 every 5 s, both
  decoders followed every switch.
- Reconnection: with the sender killed and restarted 2 s later, the receiver was showing video
  again about 1 s after the sender came back, without user action.
- Note: Media Source frame counts come from `requestVideoFrameCallback`, which under-reports
  in headless Chromium at 60 fps; it is not evidence of dropped frames.

**What these numbers are not.** They contain no screen capture, no hardware encoder, no Wi-Fi
and no display. They show the transport and receiver add little delay; they say nothing about
the A15 or the iPad. A realistic expectation on good 5 GHz Wi-Fi, from the stages involved
(capture ~1 frame, encode 5–15 ms, network 2–10 ms, decode 3–8 ms, wait for refresh 0–16 ms),
is roughly 50–90 ms glass to glass at 60 fps. That is an estimate to be checked, not a result.

## Reproducing

```
cd android && ./gradlew :core:test :simulator:installDist && cd ..
node tools/e2e.js --mode webcodecs --size 1920x1080 --fps 60 --https
node tools/e2e.js --mode mse --size 1280x720 --fps 60 --rotate 5
node tools/e2e.js --mode webcodecs --size 1280x720 --fps 60 --restart 5 --seconds 14
```

Needs Java 17+, ffmpeg with libx264, Node with Playwright and Chromium.
The simulator alone (`simulator --size 1920x1080 --fps 60 --pin 123456`) is also a convenient
sender for trying the iPad app or Safari before the phone app is installed: run it on any
computer on the same network.

## First run on the real devices

Please report the numbers the apps show; they are the first real measurements.

1. **Install.** Download `OmniMirror-android` from the latest *Android* run in the Actions tab,
   unzip, install the APK (allow installs from your browser/file manager).
2. **Connect.** Tap *Start mirroring*, accept the system dialog (choose *entire screen*). On the
   iPad open the `http://…:47800` address in Safari and enter the code.
3. **Read the numbers.** The phone shows the encoder settings in use, frames sent, and what
   each receiver reports (fps shown, latency, decoder). The iPad overlay shows the same.
   Note them at 1080p/60, 1080p/30 and 720p/60.
4. **Try HTTPS** (`https://…:47801`) and compare latency with the HTTP page.
5. **Rotate** the phone a few times. The picture should follow within about half a second.
6. **Interrupt.** Turn the iPad's Wi-Fi off for 5 s and on again; lock and unlock the iPad;
   walk to the edge of Wi-Fi range. It should reconnect by itself, and under weak signal the
   bitrate limit on the phone should fall and later recover.
7. **Stop.** Use the notification's *Stop mirroring* action; the iPad should return to the
   connect screen.

True glass-to-glass latency needs a camera: show a millisecond stopwatch on the phone, film
both screens together (slow motion if available), and subtract the two readings in one frame.

If something fails on the phone, `adb logcat -s OmniMirror` (or any logcat app) shows the
encoder choice and connection events.
