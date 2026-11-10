# Architecture

## What was researched

**Android (sender)**

- `MediaProjection` is the only supported way to capture the screen. On Android 14 it must run
  inside a foreground service of type `mediaProjection`, started after the user accepts the
  system dialog; each grant allows exactly one `createVirtualDisplay` call, and a callback must
  be registered first. The user can end capture from the status bar at any time.
- `MediaCodec` with **Surface input** gives a zero-copy path: the compositor renders the
  mirrored screen straight into the hardware encoder's input surface. Bitrate can be changed
  and keyframes requested while it runs.
- The screen only produces frames when something changes, so a static screen costs almost
  nothing. `KEY_MAX_FPS_TO_ENCODER` caps a 90 Hz panel to the chosen stream rate.
- `NsdManager` provides mDNS/DNS-SD (the same thing Apple calls Bonjour) in both directions.

**iPadOS (receiver)**

- `VideoToolbox` (`VTDecompressionSession`) decodes H.264 in hardware and returns frames in
  decode order, so there is no reordering delay. `AVSampleBufferDisplayLayer` shows a decoded
  pixel buffer at the next refresh with the "display immediately" attachment.
- `Network.framework` gives TCP with Bonjour browsing and advertising.
- Safari (16.4+) exposes the same hardware decoder to web pages through **WebCodecs**, but only
  on secure (HTTPS) origins. On plain HTTP, **Media Source Extensions** work instead.

## Transport: the comparison

| | WebRTC | Custom UDP (RTP-like) | TCP |
|---|---|---|---|
| Latency on a clean LAN | Good; jitter buffer adds some | Best | Within a few ms of UDP |
| Behaviour under loss | NACK/FEC built in | Must build retransmit/FEC or request keyframes | Retransmits; a stall delays everything behind it |
| Congestion control | Built in | Must build | Built in; we add frame dropping on top |
| Works in Safari | Yes | No | Yes (WebSocket) |
| Native iPad code | Large framework | Small | Small |
| Android cost | ~10 MB native library, its own capture/encode wrappers | Small | Small, no dependencies |
| Setup | Signalling, ICE, SDP | None | None |

**Chosen: TCP**, one connection per receiver, with the same messages carried over WebSocket
for the browser.

Why not WebRTC: its strengths (NAT traversal, loss recovery over the internet, echo
cancellation, simulcast) solve problems this project does not have. It would add a large
dependency on both sides and hide the encoder behind its own abstractions.

Why not UDP: head-of-line blocking is the real argument against TCP, and it matters on lossy
paths. On one Wi-Fi hop, lost frames are retransmitted by the radio layer in a few
milliseconds before TCP ever sees a loss, so TCP rarely stalls. In exchange, TCP needs no
packetisation, reordering or loss handling code, and (decisive here) a browser can speak it.
A UDP transport could not serve the Safari receiver, which is the only receiver installable
without Apple tooling. scrcpy makes the same choice and reaches 35–70 ms.

What is done to keep TCP low-latency:

- `TCP_NODELAY`, and a small send buffer (128 KB) so backlog appears in the app's own queue
  instead of hiding in the kernel.
- A per-receiver queue. If its oldest frame is over 200 ms old, the backlog is dropped and the
  stream resumes from a fresh keyframe. Stale frames are never delivered late.
- The time frames wait in that queue drives the bitrate controller (fast back-off, slow recovery).
- A low-latency Wi-Fi lock on the phone while streaming.

## Pipeline

```
Android                                                    iPad
screen ─► VirtualDisplay ─► encoder Surface                app:     NWConnection ─► VTDecompressionSession ─► AVSampleBufferDisplayLayer
          (MediaProjection)   │ hardware H.264             Safari:  WebSocket ─► WebCodecs VideoDecoder ─► canvas   (HTTPS)
                              ▼                                     WebSocket ─► fMP4 muxer ─► Media Source ─► <video> (HTTP)
                        SenderHub ── per-receiver queue ── TCP / WebSocket ──►
```

Code layout:

| Path | What |
|---|---|
| `android/core` | Pure Kotlin, no Android APIs: protocol, pairing, HTTP/WebSocket server, fan-out with frame dropping, bitrate controller, and the browser receiver's files. Unit-tested on the JVM. |
| `android/app` | The sender app: capture service, encoder, discovery, UI. No third-party dependencies. |
| `android/simulator` | Desktop stand-in for the phone: feeds a live ffmpeg H.264 stream through the real `core` server. |
| `ios/OmniMirror` | The iPad app (SwiftUI, Network, VideoToolbox). |
| `tools/e2e.js` | End-to-end test: simulator → headless Chromium running the browser receiver. |

## Pairing and discovery

- The phone advertises `_omnimirror._tcp` (role `sender`); the iPad app lists it. The iPad
  app advertises the same type (role `receiver`); the phone app lists it.
- Whoever accepts the connection shows a 6-digit code; the other side proves knowledge of it
  (HMAC challenge–response). A code that worked is remembered.
- Manual: type the other device's IP address and the code.
- By code only: the connecting device tries every address on its /24 that has the port open
  until one accepts the code. This works where the router blocks mDNS.

## Known limitations

- **Protected content is black.** Apps that set `FLAG_SECURE` (banking, Netflix, some password
  fields) cannot be captured. Android enforces this.
- **No audio.** Video only.
- **View only.** Touching the iPad does not control the phone.
- **Encoder ceiling.** 1080p at 60 fps needs an encoder rated for it. The Galaxy A15's chipset
  is mid-range and may not be. The app asks the codec, steps down frame rate and then
  resolution when the request is unsupported, and says what it chose. It never claims 60 fps
  it is not producing: the sending and displayed frame rates are both shown.
- **Safari over plain HTTP** cannot use WebCodecs, so it falls back to Media Source, which
  buffers more. The HTTPS page uses a certificate generated on the phone, which Safari will
  warn about once.
- **The pairing code is not strong security** and video is not encrypted on the app transport.
- **iOS local network permission.** The iPad app needs it; if it was denied, discovery and
  connections fail until it is enabled in Settings.

## Roadmap

1. Done: transport core, browser receiver, simulator, automated end-to-end test.
2. Done: Android sender app; builds in CI.
3. Done: iPad app source; compiles in CI as an unsigned IPA.
4. **Next: first run on the real devices** (see `docs/TESTING.md`). Expect fixes here: the
   Android capture path, Safari's decoders and the iPad app have never executed on hardware.
5. Tune from measurements: encoder settings for the A15, Safari Media Source behaviour, bitrate
   controller thresholds on real Wi-Fi.
6. Later: QR code for one-scan pairing, audio (Android 10+ playback capture), HEVC, TLS on the
   app transport, optional UDP transport if real-world Wi-Fi shows TCP stalls.
