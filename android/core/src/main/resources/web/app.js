// OmniMirror browser receiver.
// Served by the phone itself; talks the same message protocol as the native iPad app,
// carried over a WebSocket. See docs/PROTOCOL.md.
'use strict';

// ---------------------------------------------------------------------------
// SHA-256 / HMAC. crypto.subtle only exists on secure origins, and this page is
// usually loaded over plain http://<phone-ip>, so it carries its own.
// ---------------------------------------------------------------------------
const SHA_K = new Uint32Array([
  0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
  0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
  0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
  0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
  0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
  0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
  0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
  0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2]);

function sha256(bytes) {
  const h = new Uint32Array([0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19]);
  const padded = new Uint8Array(((bytes.length + 9 + 63) >> 6) << 6);
  padded.set(bytes);
  padded[bytes.length] = 0x80;
  const view = new DataView(padded.buffer);
  view.setUint32(padded.length - 8, Math.floor(bytes.length / 0x20000000));
  view.setUint32(padded.length - 4, (bytes.length << 3) >>> 0);
  const w = new Uint32Array(64);
  const rotr = (x, n) => (x >>> n) | (x << (32 - n));
  for (let off = 0; off < padded.length; off += 64) {
    for (let i = 0; i < 16; i++) w[i] = view.getUint32(off + i * 4);
    for (let i = 16; i < 64; i++) {
      const s0 = rotr(w[i - 15], 7) ^ rotr(w[i - 15], 18) ^ (w[i - 15] >>> 3);
      const s1 = rotr(w[i - 2], 17) ^ rotr(w[i - 2], 19) ^ (w[i - 2] >>> 10);
      w[i] = (w[i - 16] + s0 + w[i - 7] + s1) | 0;
    }
    let [a, b, c, d, e, f, g, hh] = h;
    for (let i = 0; i < 64; i++) {
      const t1 = (hh + (rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25)) + ((e & f) ^ (~e & g)) + SHA_K[i] + w[i]) | 0;
      const t2 = ((rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22)) + ((a & b) ^ (a & c) ^ (b & c))) | 0;
      hh = g; g = f; f = e; e = (d + t1) | 0; d = c; c = b; b = a; a = (t1 + t2) | 0;
    }
    h[0] += a; h[1] += b; h[2] += c; h[3] += d; h[4] += e; h[5] += f; h[6] += g; h[7] += hh;
  }
  const out = new Uint8Array(32);
  const ov = new DataView(out.buffer);
  for (let i = 0; i < 8; i++) ov.setUint32(i * 4, h[i]);
  return out;
}

function hmacSha256Hex(keyText, messageText) {
  const enc = new TextEncoder();
  let key = enc.encode(keyText);
  if (key.length > 64) key = sha256(key);
  const ipad = new Uint8Array(64).fill(0x36);
  const opad = new Uint8Array(64).fill(0x5c);
  for (let i = 0; i < key.length; i++) { ipad[i] ^= key[i]; opad[i] ^= key[i]; }
  const msg = enc.encode(messageText);
  const inner = new Uint8Array(64 + msg.length);
  inner.set(ipad); inner.set(msg, 64);
  const outer = new Uint8Array(96);
  outer.set(opad); outer.set(sha256(inner), 64);
  return Array.from(sha256(outer), (b) => b.toString(16).padStart(2, '0')).join('');
}

function randomHex(n) {
  const b = new Uint8Array(n);
  crypto.getRandomValues(b);
  return Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('');
}

// ---------------------------------------------------------------------------
// Protocol
// ---------------------------------------------------------------------------
const T = { HELLO: 0x01, CHALLENGE: 0x02, AUTH: 0x03, AUTH_RESULT: 0x04, STREAM_INFO: 0x10, VIDEO: 0x11,
  PING: 0x20, PONG: 0x21, STATS: 0x30, REQUEST_KEY: 0x31, BYE: 0x40 };

const nowUs = () => Math.round(performance.now() * 1000);

function b64ToBytes(s) {
  const bin = atob(s);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

// ---------------------------------------------------------------------------
// H.264 helpers
// ---------------------------------------------------------------------------

/** Converts an Annex-B access unit to length-prefixed (AVCC) form, keeping only picture slices. */
function annexBToAvcc(data) {
  const nals = [];
  let total = 0;
  let start = -1;
  const n = data.length;
  const push = (s, e) => {
    if (e <= s) return;
    const type = data[s] & 0x1f;
    if (type >= 1 && type <= 5) { nals.push([s, e]); total += 4 + e - s; }
  };
  let i = 0;
  while (i + 2 < n) {
    if (data[i] === 0 && data[i + 1] === 0 && data[i + 2] === 1) {
      if (start >= 0) push(start, i > start && data[i - 1] === 0 ? i - 1 : i);
      start = i + 3;
      i += 3;
    } else i++;
  }
  if (start >= 0) push(start, n);
  const out = new Uint8Array(total);
  const view = new DataView(out.buffer);
  let o = 0;
  for (const [s, e] of nals) {
    view.setUint32(o, e - s);
    out.set(data.subarray(s, e), o + 4);
    o += 4 + e - s;
  }
  return out;
}

function avcConfigRecord(sps, pps) {
  const out = new Uint8Array(11 + sps.length + pps.length);
  out.set([1, sps[1], sps[2], sps[3], 0xff, 0xe1, sps.length >> 8, sps.length & 0xff]);
  out.set(sps, 8);
  out.set([1, pps.length >> 8, pps.length & 0xff], 8 + sps.length);
  out.set(pps, 11 + sps.length);
  return out;
}

const codecString = (sps) => 'avc1.' + [sps[1], sps[2], sps[3]].map((b) => b.toString(16).padStart(2, '0')).join('');

// ---------------------------------------------------------------------------
// Renderer 1: WebCodecs. Each frame goes straight into the platform's hardware decoder
// (VideoToolbox on iPad) and is painted on the next display refresh. No buffering.
// ---------------------------------------------------------------------------
class WebCodecsRenderer {
  constructor(canvas, hooks) {
    this.name = 'WebCodecs';
    this.canvas = canvas;
    this.hooks = hooks;
    this.ctx = canvas.getContext('2d', { alpha: false, desynchronized: true });
    this.decoder = null;
    this.pending = null;
    this.raf = 0;
    this.needKey = true;
    canvas.style.display = 'block';
  }

  static supported() { return typeof VideoDecoder === 'function' && typeof EncodedVideoChunk === 'function'; }

  configure(info) {
    this.closeDecoder();
    this.needKey = true;
    this.decoder = new VideoDecoder({
      output: (frame) => this.onFrame(frame),
      error: (e) => { this.hooks.onError('decoder: ' + e.message); this.needKey = true; this.hooks.requestKey(); this.reconfigure(info); },
    });
    this.decoder.configure({
      codec: codecString(info.sps),
      description: avcConfigRecord(info.sps, info.pps),
      codedWidth: info.width,
      codedHeight: info.height,
      optimizeForLatency: true,
    });
  }

  reconfigure(info) {
    // A decoder that reported an error is closed for good; build a new one, but not in a tight loop.
    clearTimeout(this.retry);
    this.retry = setTimeout(() => { try { this.configure(info); } catch (e) { this.hooks.onError(String(e)); } }, 300);
  }

  push(data, key, ptsUs) {
    const d = this.decoder;
    if (!d || d.state !== 'configured') return;
    if (this.needKey) {
      if (!key) return;
      this.needKey = false;
    } else if (d.decodeQueueSize > 4 && !key) {
      // The decoder cannot keep up; skip ahead to the next keyframe instead of falling behind.
      this.needKey = true;
      this.hooks.onDrop();
      this.hooks.requestKey();
      return;
    }
    try {
      d.decode(new EncodedVideoChunk({ type: key ? 'key' : 'delta', timestamp: ptsUs, data: annexBToAvcc(data) }));
    } catch (e) {
      this.needKey = true;
      this.hooks.requestKey();
    }
  }

  onFrame(frame) {
    if (this.pending) { this.pending.close(); this.hooks.onDrop(); }
    this.pending = frame;
    if (!this.raf) this.raf = requestAnimationFrame(() => this.paint());
  }

  paint() {
    this.raf = 0;
    const frame = this.pending;
    if (!frame) return;
    this.pending = null;
    const w = frame.displayWidth, h = frame.displayHeight;
    if (this.canvas.width !== w || this.canvas.height !== h) { this.canvas.width = w; this.canvas.height = h; }
    this.ctx.drawImage(frame, 0, 0, w, h);
    this.hooks.onRendered(frame.timestamp, w, h);
    frame.close();
  }

  closeDecoder() {
    clearTimeout(this.retry);
    if (this.decoder && this.decoder.state !== 'closed') { try { this.decoder.close(); } catch (e) { /* already closed */ } }
    this.decoder = null;
  }

  resync() { this.needKey = true; }

  destroy() {
    this.closeDecoder();
    cancelAnimationFrame(this.raf);
    this.raf = 0;
    if (this.pending) { this.pending.close(); this.pending = null; }
    this.canvas.style.display = 'none';
  }
}

// ---------------------------------------------------------------------------
// Renderer 2: Media Source Extensions. Works on plain-HTTP pages, where WebCodecs is
// not available. Frames are wrapped one-per-fragment in fMP4 and fed to a <video>
// element that is kept pinned to the live edge. The browser adds some buffering of its
// own, so expect more latency than WebCodecs.
// ---------------------------------------------------------------------------
const mp4 = {
  box(type, ...parts) {
    let size = 8;
    for (const p of parts) size += p.length;
    const out = new Uint8Array(size);
    new DataView(out.buffer).setUint32(0, size);
    for (let i = 0; i < 4; i++) out[4 + i] = type.charCodeAt(i);
    let o = 8;
    for (const p of parts) { out.set(p, o); o += p.length; }
    return out;
  },
  u8: (...v) => new Uint8Array(v),
  u16: (v) => new Uint8Array([v >> 8, v & 0xff]),
  u32: (v) => new Uint8Array([(v >>> 24) & 0xff, (v >>> 16) & 0xff, (v >>> 8) & 0xff, v & 0xff]),
  u64(v) { return new Uint8Array([...mp4.u32(Math.floor(v / 4294967296)), ...mp4.u32(v >>> 0)]); },
  str: (s) => new Uint8Array([...s].map((c) => c.charCodeAt(0))),
  zeros: (n) => new Uint8Array(n),
  MATRIX: new Uint8Array([0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x40, 0, 0, 0]),

  init(info, timescale) {
    const { box, u8, u16, u32, str, zeros, MATRIX } = mp4;
    const avc1 = box('avc1', zeros(6), u16(1), zeros(16), u16(info.width), u16(info.height),
      u32(0x00480000), u32(0x00480000), u32(0), u16(1), zeros(32), u16(0x18), u16(0xffff),
      box('avcC', avcConfigRecord(info.sps, info.pps)));
    const stbl = box('stbl', box('stsd', u32(0), u32(1), avc1), box('stts', u32(0), u32(0)),
      box('stsc', u32(0), u32(0)), box('stsz', u32(0), u32(0), u32(0)), box('stco', u32(0), u32(0)));
    const minf = box('minf', box('vmhd', u32(1), zeros(8)),
      box('dinf', box('dref', u32(0), u32(1), box('url ', u32(1)))), stbl);
    const mdia = box('mdia', box('mdhd', u32(0), u32(0), u32(0), u32(timescale), u32(0), u16(0x55c4), u16(0)),
      box('hdlr', u32(0), u32(0), str('vide'), zeros(12), str('OmniMirror'), u8(0)), minf);
    const trak = box('trak', box('tkhd', u32(3), u32(0), u32(0), u32(1), u32(0), u32(0), zeros(8), u16(0), u16(0),
      u16(0), u16(0), MATRIX, u32(info.width << 16), u32(info.height << 16)), mdia);
    const mvhd = box('mvhd', u32(0), u32(0), u32(0), u32(timescale), u32(0), u32(0x00010000), u16(0x0100), zeros(10),
      MATRIX, zeros(24), u32(2));
    const mvex = box('mvex', box('trex', u32(0), u32(1), u32(1), u32(0), u32(0), u32(0)));
    return new Uint8Array([...box('ftyp', str('isom'), u32(0x200), str('isomiso6avc1mp41')), ...box('moov', mvhd, trak, mvex)]);
  },

  fragment(seq, dts, duration, key, sample) {
    const { box, u32, u64 } = mp4;
    const flags = key ? 0x02000000 : 0x01010000;
    // trun: data-offset, sample-duration, sample-size, sample-flags
    const trunBody = [u32(0x000701), u32(1), u32(0), u32(duration), u32(sample.length), u32(flags)];
    const traf = (trun) => box('traf', box('tfhd', u32(0x020000), u32(1)), box('tfdt', u32(0x01000000), u64(dts)), trun);
    let moof = box('moof', box('mfhd', u32(0), u32(seq)), traf(box('trun', ...trunBody)));
    trunBody[2] = u32(moof.length + 8); // sample data starts right after the mdat header
    moof = box('moof', box('mfhd', u32(0), u32(seq)), traf(box('trun', ...trunBody)));
    const out = new Uint8Array(moof.length + 8 + sample.length);
    out.set(moof);
    new DataView(out.buffer).setUint32(moof.length, 8 + sample.length);
    out.set(mp4.str('mdat'), moof.length + 4);
    out.set(sample, moof.length + 8);
    return out;
  },
};

class MseRenderer {
  constructor(video, hooks) {
    this.name = 'MSE';
    this.video = video;
    this.hooks = hooks;
    this.timescale = 90000;
    this.generation = 0;
    this.ptsByIndex = new Float64Array(4096);
    this.timer = setInterval(() => this.chase(), 100);
    video.style.display = 'block';
    this.onVideoFrame = (now, meta) => {
      if (this.frameDuration) {
        const index = Math.round(meta.mediaTime * this.timescale / this.frameDuration);
        const pts = this.ptsByIndex[index & 4095];
        if (pts) this.hooks.onRendered(pts, this.video.videoWidth, this.video.videoHeight);
      }
      this.watchFrames();
    };
  }

  static supported() { return !!(window.ManagedMediaSource || window.MediaSource); }

  watchFrames() {
    if (this.video.requestVideoFrameCallback) this.video.requestVideoFrameCallback(this.onVideoFrame);
  }

  configure(info) {
    this.teardown();
    const generation = ++this.generation;
    const Source = window.ManagedMediaSource || window.MediaSource;
    const mime = `video/mp4; codecs="${codecString(info.sps)}"`;
    if (!Source.isTypeSupported(mime)) { this.hooks.onError('this browser cannot play ' + mime); return; }
    this.frameDuration = Math.round(this.timescale / Math.max(1, info.fps));
    this.frameIndex = 0;
    this.seq = 1;
    this.queue = [mp4.init(info, this.timescale)];
    this.needKey = true;
    this.source = new Source();
    this.source.addEventListener('sourceopen', () => {
      if (generation !== this.generation || this.buffer) return;
      this.buffer = this.source.addSourceBuffer(mime);
      this.buffer.mode = 'segments';
      this.buffer.addEventListener('updateend', () => this.flush());
      this.buffer.addEventListener('error', () => { this.hooks.onError('media buffer error'); this.needKey = true; this.hooks.requestKey(); });
      this.flush();
    });
    this.video.disableRemotePlayback = true;
    this.video.src = URL.createObjectURL(this.source);
    this.watchFrames();
    this.video.play().catch(() => {});
  }

  push(data, key, ptsUs) {
    if (!this.source) return;
    if (this.needKey) {
      if (!key) return;
      this.needKey = false;
    }
    if (this.queue.length > 120) { // appends are stuck; start again from a keyframe
      this.queue.length = 0;
      this.needKey = true;
      this.hooks.onDrop();
      this.hooks.requestKey();
      return;
    }
    this.ptsByIndex[this.frameIndex & 4095] = ptsUs;
    this.queue.push(mp4.fragment(this.seq++, this.frameIndex * this.frameDuration, this.frameDuration, key, annexBToAvcc(data)));
    this.frameIndex++;
    this.flush();
  }

  flush() {
    const b = this.buffer;
    if (!b || b.updating || !this.queue.length || this.source.readyState !== 'open') return;
    let chunk;
    if (this.queue.length === 1) chunk = this.queue[0];
    else {
      let size = 0;
      for (const q of this.queue) size += q.length;
      chunk = new Uint8Array(size);
      let o = 0;
      for (const q of this.queue) { chunk.set(q, o); o += q.length; }
    }
    this.queue.length = 0;
    try { b.appendBuffer(chunk); } catch (e) { this.needKey = true; this.hooks.requestKey(); }
  }

  /** Keeps playback glued to the newest frame and the buffer small. */
  chase() {
    const v = this.video, b = this.buffer;
    if (!b || !v.buffered.length) return;
    const end = v.buffered.end(v.buffered.length - 1);
    const behind = end - v.currentTime;
    if (behind > 0.3 || behind < 0) v.currentTime = Math.max(0, end - 0.04);
    else v.playbackRate = behind > 0.12 ? 1.15 : 1;
    if (v.paused) v.play().catch(() => {});
    const start = v.buffered.start(0);
    if (!b.updating && v.currentTime - start > 8) { try { b.remove(start, v.currentTime - 2); } catch (e) { /* retried next tick */ } }
  }

  resync() {
    const v = this.video;
    if (v.buffered.length) v.currentTime = Math.max(0, v.buffered.end(v.buffered.length - 1) - 0.04);
  }

  teardown() {
    this.generation++;
    this.buffer = null;
    this.queue = [];
    if (this.source) {
      const url = this.video.src;
      this.video.removeAttribute('src');
      this.video.load();
      if (url) URL.revokeObjectURL(url);
      this.source = null;
    }
  }

  destroy() {
    clearInterval(this.timer);
    this.teardown();
    this.video.style.display = 'none';
  }
}

// ---------------------------------------------------------------------------
// Connection and session
// ---------------------------------------------------------------------------
const $ = (id) => document.getElementById(id);
const ui = { panel: $('panel'), hud: $('hud'), dot: $('dot'), infoText: $('infoText'), pin: $('pin'), message: $('message'),
  connectBtn: $('connectBtn'), mode: $('mode'), subtitle: $('subtitle'), secureHint: $('secureHint') };

const store = {
  get(k) { try { return localStorage.getItem(k); } catch (e) { return null; } },
  set(k, v) { try { v == null ? localStorage.removeItem(k) : localStorage.setItem(k, v); } catch (e) { /* private mode */ } },
};

const session = {
  wanted: false, ws: null, state: 'idle', pin: '', senderName: '', info: null, renderer: null,
  retryMs: 500, retryTimer: 0, pingTimer: 0, statsTimer: 0, pings: 0,
  clock: [], offsetUs: null, rttMs: 0,
  frames: 0, bytes: 0, drops: 0, latencies: [],
  stats: { fps: 0, latencyMs: 0, mbps: 0, width: 0, height: 0, drops: 0, rttMs: 0, decoder: '', state: 'idle' },
};
window.__omm = session; // for automated tests and debugging from the console

function chooseRenderer() {
  const want = ui.mode.value;
  const hooks = {
    onRendered(ptsUs, w, h) {
      session.frames++;
      session.stats.width = w; session.stats.height = h;
      if (session.offsetUs != null) session.latencies.push((nowUs() + session.offsetUs - ptsUs) / 1000);
    },
    onDrop() { session.drops++; },
    onError(text) { console.warn(text); },
    requestKey() { send(T.REQUEST_KEY, new Uint8Array(0)); },
  };
  if ((want === 'auto' || want === 'webcodecs') && WebCodecsRenderer.supported()) return new WebCodecsRenderer($('canvas'), hooks);
  if (MseRenderer.supported()) return new MseRenderer($('video'), hooks);
  if (WebCodecsRenderer.supported()) return new WebCodecsRenderer($('canvas'), hooks);
  return null;
}

function send(type, payload) {
  const ws = session.ws;
  if (!ws || ws.readyState !== WebSocket.OPEN) return;
  const out = new Uint8Array(1 + payload.length);
  out[0] = type;
  out.set(payload, 1);
  ws.send(out);
}

const sendJson = (type, obj) => send(type, new TextEncoder().encode(JSON.stringify(obj)));

function setState(state, text) {
  session.state = state;
  session.stats.state = state;
  ui.dot.className = state === 'streaming' ? 'ok' : state === 'error' ? 'bad' : '';
  if (text) ui.infoText.textContent = text;
}

function connect() {
  clearTimeout(session.retryTimer);
  const scheme = location.protocol === 'https:' ? 'wss://' : 'ws://';
  const ws = new WebSocket(scheme + location.host + '/ws');
  ws.binaryType = 'arraybuffer';
  session.ws = ws;
  const nonce = randomHex(16);
  setState('connecting', 'Connecting…');

  ws.onopen = () => sendJson(T.HELLO, { v: 1, role: 'receiver', name: deviceName(), nonce, client: 'web' });

  ws.onmessage = (ev) => {
    const buf = new Uint8Array(ev.data);
    const type = buf[0];
    if (type === T.VIDEO) return onVideo(buf);
    if (type === T.PONG) return onPong(buf);
    const body = JSON.parse(new TextDecoder().decode(buf.subarray(1)));
    if (type === T.CHALLENGE) {
      session.senderName = body.name || 'Android';
      sendJson(T.AUTH, { proof: hmacSha256Hex(session.pin, nonce + ':' + body.nonce) });
    } else if (type === T.AUTH_RESULT) {
      if (!body.ok) {
        session.wanted = false;
        store.set('omm.pin', null);
        ws.close();
        showPanel('Wrong code. Check the code on your phone and try again.', true);
        return;
      }
      store.set('omm.pin', session.pin);
      session.retryMs = 500;
      onPaired();
    } else if (type === T.STREAM_INFO) {
      onStreamInfo(body);
    }
  };

  ws.onclose = () => {
    if (session.ws !== ws) return;
    stopTimers();
    session.ws = null;
    if (!session.wanted) return;
    setState('connecting', 'Reconnecting…');
    if (ui.panel.style.display !== 'none') ui.message.textContent = 'Cannot reach the phone. Retrying…';
    session.retryTimer = setTimeout(connect, session.retryMs);
    session.retryMs = Math.min(3000, session.retryMs * 1.6);
  };
  ws.onerror = () => {};
}

function onPaired() {
  ui.panel.style.display = 'none';
  ui.hud.style.display = 'flex';
  ui.hud.classList.remove('hidden');
  scheduleHudHide();
  setState('waiting', `${session.senderName} · waiting for video…`);
  session.clock = [];
  session.offsetUs = null;
  session.pings = 0;
  ping();
  session.statsTimer = setInterval(reportStats, 1000);
  if (navigator.wakeLock) navigator.wakeLock.request('screen').catch(() => {});
}

function ping() {
  const payload = new Uint8Array(8);
  new DataView(payload.buffer).setBigInt64(0, BigInt(nowUs()));
  send(T.PING, payload);
  // A quick burst to lock the clock offset, then a slow keep-alive that tracks drift.
  session.pingTimer = setTimeout(ping, ++session.pings < 8 ? 150 : 1000);
}

function onPong(buf) {
  const view = new DataView(buf.buffer, buf.byteOffset + 1);
  const t0 = Number(view.getBigInt64(0));
  const senderUs = Number(view.getBigInt64(8));
  const t1 = nowUs();
  const rtt = t1 - t0;
  session.clock.push({ rtt, offset: senderUs - (t0 + t1) / 2 });
  if (session.clock.length > 30) session.clock.shift();
  // The sample with the shortest round trip has the least queueing error in it.
  const best = session.clock.reduce((a, b) => (b.rtt < a.rtt ? b : a));
  session.offsetUs = best.offset;
  session.rttMs = best.rtt / 1000;
}

function onStreamInfo(body) {
  const info = { width: body.width, height: body.height, fps: body.fps, bitrate: body.bitrate,
    sps: b64ToBytes(body.sps), pps: b64ToBytes(body.pps) };
  session.info = info;
  if (!session.renderer) session.renderer = chooseRenderer();
  if (!session.renderer) { setState('error', 'This browser cannot decode H.264 video.'); return; }
  try { session.renderer.configure(info); } catch (e) { setState('error', 'Decoder error: ' + e.message); }
}

function onVideo(buf) {
  if (!session.renderer) return;
  const view = new DataView(buf.buffer, buf.byteOffset + 1);
  const key = (view.getUint8(0) & 1) !== 0;
  const ptsUs = Number(view.getBigInt64(1));
  session.bytes += buf.length;
  session.renderer.push(buf.subarray(10), key, ptsUs);
}

function reportStats() {
  const s = session.stats;
  const lat = session.latencies.sort((a, b) => a - b);
  s.fps = session.frames;
  s.mbps = session.bytes * 8 / 1e6;
  s.latencyMs = lat.length ? lat[lat.length >> 1] : 0;
  s.drops += session.drops;
  s.rttMs = session.rttMs;
  s.decoder = session.renderer ? session.renderer.name : '';
  const streaming = session.info && (session.frames > 0 || session.state === 'streaming');
  session.frames = 0; session.bytes = 0; session.drops = 0; session.latencies = [];
  sendJson(T.STATS, { fps: s.fps, latencyMs: Math.round(s.latencyMs * 10) / 10, decoder: s.decoder, drops: s.drops });
  if (streaming) {
    const latency = s.latencyMs > 0 ? ` · ${Math.round(s.latencyMs)} ms` : '';
    setState('streaming', `${session.senderName} · ${s.width}×${s.height} · ${s.fps} fps${latency} · ${s.mbps.toFixed(1)} Mbps · ${s.decoder}`);
  }
}

function stopTimers() {
  clearTimeout(session.pingTimer);
  clearInterval(session.statsTimer);
}

function disconnect() {
  session.wanted = false;
  clearTimeout(session.retryTimer);
  stopTimers();
  if (session.ws) { send(T.BYE, new Uint8Array(0)); session.ws.close(); session.ws = null; }
  if (session.renderer) { session.renderer.destroy(); session.renderer = null; }
  session.info = null;
  setState('idle');
  showPanel('');
}

function deviceName() {
  const ua = navigator.userAgent;
  if (/iPad/.test(ua) || (/Macintosh/.test(ua) && navigator.maxTouchPoints > 1)) return 'iPad (Safari)';
  if (/iPhone/.test(ua)) return 'iPhone (Safari)';
  return 'Browser';
}

// ---------------------------------------------------------------------------
// UI
// ---------------------------------------------------------------------------
let hudTimer = 0;
function scheduleHudHide() {
  clearTimeout(hudTimer);
  hudTimer = setTimeout(() => ui.hud.classList.add('hidden'), 4000);
}

function showPanel(message, bad) {
  ui.panel.style.display = 'flex';
  ui.hud.style.display = 'none';
  ui.message.textContent = message || '';
  ui.message.className = bad ? 'bad' : '';
  ui.connectBtn.disabled = false;
}

function start(pin) {
  session.pin = pin;
  session.wanted = true;
  session.retryMs = 500;
  ui.connectBtn.disabled = true;
  ui.message.className = '';
  ui.message.textContent = 'Connecting…';
  if (session.renderer) { session.renderer.destroy(); session.renderer = null; }
  connect();
}

$('card').addEventListener('submit', (e) => {
  e.preventDefault();
  const pin = ui.pin.value.replace(/\D/g, '');
  if (pin.length !== 6) { ui.message.textContent = 'The code has 6 digits.'; ui.message.className = 'bad'; return; }
  store.set('omm.mode', ui.mode.value);
  start(pin);
});

$('stage').addEventListener('click', () => {
  if (ui.hud.style.display === 'none') return;
  if (ui.hud.classList.toggle('hidden')) clearTimeout(hudTimer); else scheduleHudHide();
});

$('disconnectBtn').addEventListener('click', disconnect);

$('fullscreenBtn').addEventListener('click', () => {
  const el = document.documentElement;
  const active = document.fullscreenElement || document.webkitFullscreenElement;
  if (active) (document.exitFullscreen || document.webkitExitFullscreen).call(document);
  else {
    const request = el.requestFullscreen || el.webkitRequestFullscreen;
    if (request) Promise.resolve(request.call(el)).catch(() => {});
  }
});

document.addEventListener('visibilitychange', () => {
  if (document.visibilityState !== 'visible' || !session.wanted) return;
  if (!session.ws) { clearTimeout(session.retryTimer); connect(); return; }
  // Frames decoded while hidden are stale; restart from a fresh keyframe.
  if (session.renderer) session.renderer.resync();
  send(T.REQUEST_KEY, new Uint8Array(0));
  if (navigator.wakeLock) navigator.wakeLock.request('screen').catch(() => {});
});

(function init() {
  const params = new URLSearchParams(location.search);
  ui.mode.value = params.get('mode') || store.get('omm.mode') || 'auto';
  if (!ui.mode.value) ui.mode.value = 'auto';

  fetch('info').then((r) => r.json()).then((info) => {
    if (info.name) ui.subtitle.textContent = `Enter the code shown on ${info.name}.`;
    if (!window.isSecureContext && info.tlsPort) {
      const url = `https://${location.hostname}:${info.tlsPort}/`;
      ui.secureHint.innerHTML = 'This page was opened over plain HTTP, so the browser only allows the Media Source decoder. ' +
        `For the lowest latency open <a href="${url}">${url}</a> and accept the certificate warning once ` +
        '(the certificate is generated by your phone and never leaves your network).';
    }
  }).catch(() => {});

  const fromLink = (location.hash.match(/\d{6}/) || [])[0];
  const saved = fromLink || store.get('omm.pin');
  if (saved) { ui.pin.value = saved; start(saved); }
})();
