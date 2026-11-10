# OmniMirror wire protocol (version 1)

One TCP connection per receiver. Everything is a typed message; the same message bodies
travel over two framings.

| Transport | Used by | Framing |
|---|---|---|
| Raw TCP | iPad app | Connector first sends the 4 bytes `OMM1`. Then each message is `[u32 length][u8 type][payload]`, big-endian, `length` = 1 + payload size. |
| WebSocket (`/ws`) | Browser receiver | Each binary WebSocket message is `[u8 type][payload]`. |

Default port **47800** (plain: raw TCP, HTTP and WebSocket share it, told apart by the first
four bytes). Port **47801** serves the same HTTP/WebSocket content over TLS.

Discovery: DNS-SD service type `_omnimirror._tcp`, TXT `role=sender|receiver`, `v=1`.

## Roles and direction

*Sender* (phone) and *receiver* (iPad) are roles. *Connector* and *listener* describe who
opened the TCP connection. Either role can be either side:

- iPad app or browser connects to the phone (the normal case), or
- the phone connects to an iPad app that is listening.

The **listener owns the pairing code**; the connector must prove it knows it.

## Handshake

Control messages are UTF-8 JSON.

| # | Direction | Type | Body |
|---|---|---|---|
| 1 | connector → listener | `0x01 HELLO` | `{"v":1,"role":"receiver","name":"iPad","nonce":"<hex>"}` |
| 2 | listener → connector | `0x02 CHALLENGE` | `{"v":1,"role":"sender","name":"Galaxy A15","nonce":"<hex>"}` |
| 3 | connector → listener | `0x03 AUTH` | `{"proof":"<hex>"}` |
| 4 | listener → connector | `0x04 AUTH_RESULT` | `{"ok":true}` or `{"ok":false,"reason":"wrong code"}` |

`proof = hex(HMAC-SHA256(key = code, message = connectorNonce + ":" + listenerNonce))`

Test vector: code `123456`, connector nonce `aa`, listener nonce `bb` →
`d3b5227b6f459b1cfeb36c4ccfb75be4aaf0edbaabbf25a7667d3a7c5c0fb2c8`.

The code never crosses the network. This keeps other people on the same Wi-Fi out; it is not
strong cryptography (a recorded handshake allows an offline search of the 10⁶ codes) and the
video itself is unencrypted on the raw-TCP and `ws://` transports. Use it on networks you trust.

## Streaming

| Type | Direction | Body |
|---|---|---|
| `0x10 STREAM_INFO` | sender → receiver | JSON `{"codec":"h264","width":1080,"height":2336,"fps":60,"bitrate":8000000,"sps":"<base64>","pps":"<base64>"}`. Sent after pairing and again whenever the encoder restarts (rotation, settings change). The receiver must rebuild its decoder. The next video frame is a keyframe. |
| `0x11 VIDEO` | sender → receiver | `[u8 flags][i64 captureTimeUs][Annex-B access unit]`. `flags & 1` = keyframe (IDR). `captureTimeUs` is on the sender's monotonic clock. |
| `0x20 PING` | receiver → sender | `[i64 receiverTimeUs]` |
| `0x21 PONG` | sender → receiver | `[i64 receiverTimeUs echoed][i64 senderTimeUs]` |
| `0x30 STATS` | receiver → sender | JSON `{"fps":60,"latencyMs":41.5,"decoder":"VideoToolbox"}`, once per second |
| `0x31 REQUEST_KEY` | receiver → sender | empty; the receiver cannot decode until it gets a keyframe |
| `0x40 BYE` | either | empty; deliberate disconnect |

Unknown message types are ignored.

## Latency measurement

The receiver estimates the offset between the two monotonic clocks NTP-style from
PING/PONG: `offset = senderTime − (t_sent + t_received) / 2`, keeping the sample with the
smallest round trip out of the last 30. When a frame is handed to the display:

`latency = receiverNow + offset − captureTimeUs`

This covers encode, network, decode and the wait for presentation. It does not include the
phone's own compositing before capture or the iPad panel's scan-out (roughly one refresh more).

## Congestion

Each receiver has its own send queue on the sender. If the oldest queued frame is more than
200 ms old, the sender drops that receiver's backlog, requests a keyframe from the encoder and
resumes from it. Receivers ping at least every second; a sender drops a receiver after 8 s of
silence, a receiver drops a sender after 6 s.
