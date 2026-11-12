// The browser side of the web build's audio, called from wasmJsMain
// (audio/WebAudio.kt). Kept in plain JS because it is mostly promises, events
// and typed arrays, which read better here than through Kotlin interop.

// iOS Safari only sounds a media element first started inside a tap, and the
// app starts clips after a network wait. So the first tap or key press starts
// a few silent elements (which unlocks them for good) and the audio context the
// recorder needs; clips then borrow those elements.
const SILENT_WAV = 'data:audio/wav;base64,UklGRiQAAABXQVZFZm10IBAAAAABAAEAQB8AAEAfAAABAAgAZGF0YQAAAAA=';
const POOL_SIZE = 8;
const unlocked = [];
let context = null;

function unlock() {
  if (!context) context = new (window.AudioContext || window.webkitAudioContext)();
  if (context.state !== 'running') context.resume().catch(() => {});
  while (unlocked.length < POOL_SIZE) {
    const element = new Audio();
    element.src = SILENT_WAV;
    element.play().then(() => element.pause(), () => {});
    unlocked.push(element);
  }
  if (context.state === 'running') {
    for (const type of GESTURES) document.removeEventListener(type, unlock, true);
  }
}
const GESTURES = ['touchend', 'click', 'keydown'];
for (const type of GESTURES) document.addEventListener(type, unlock, true);

function borrowElement() {
  const element = unlocked.pop() || new Audio();
  element.preload = 'auto';
  return element;
}

function returnElement(element) {
  element.pause();
  element.onended = element.onerror = element.oncanplay = null;
  element.src = SILENT_WAV;
  if (unlocked.length < POOL_SIZE) unlocked.push(element);
}

// Recently prepared clips, by URL: a replay is not fetched or decoded again.
const CACHE_SIZE = 32;
const cache = new Map();

function cached(url) {
  const hit = cache.get(url);
  if (hit) { cache.delete(url); cache.set(url, hit); }
  return hit;
}

function remember(url, objectUrl) {
  cache.set(url, objectUrl);
  while (cache.size > CACHE_SIZE) {
    const [oldest, old] = cache.entries().next().value;
    cache.delete(oldest);
    URL.revokeObjectURL(old);
  }
}

const isOgg = (bytes) => bytes.length > 4 && bytes[0] === 0x4f && bytes[1] === 0x67 && bytes[2] === 0x67 && bytes[3] === 0x53;

// stb_vorbis (shared/src/nativeInterop/stb_vorbis, MIT) built to WebAssembly by
// its build-wasm.sh and served next to the page. It imports nothing.
let vorbis = null;
function loadVorbis() {
  if (!vorbis) {
    vorbis = WebAssembly.instantiateStreaming(fetch('stb_vorbis.wasm'), {}).then(({ instance }) => {
      instance.exports._initialize();
      return instance.exports;
    });
  }
  return vorbis;
}

async function decodeOgg(bytes) {
  const e = await loadVorbis();
  const src = e.malloc(bytes.length);
  const out = e.malloc(12);
  try {
    new Uint8Array(e.memory.buffer, src, bytes.length).set(bytes);
    const frames = e.stb_vorbis_decode_memory(src, bytes.length, out, out + 4, out + 8);
    if (frames <= 0) throw new Error('cannot decode Ogg Vorbis');
    const view = new DataView(e.memory.buffer);
    const channels = view.getInt32(out, true);
    const sampleRate = view.getInt32(out + 4, true);
    const pcm = view.getUint32(out + 8, true);
    try {
      return wav(new Int16Array(e.memory.buffer, pcm, frames * channels), channels, sampleRate);
    } finally {
      e.free(pcm);
    }
  } finally {
    e.free(src);
    e.free(out);
  }
}

/** A 16-bit PCM WAV around interleaved samples (copied out of the decoder's memory). */
function wav(samples, channels, sampleRate) {
  const bytes = samples.length * 2;
  const header = new DataView(new ArrayBuffer(44));
  const ascii = (at, text) => { for (let i = 0; i < text.length; i++) header.setUint8(at + i, text.charCodeAt(i)); };
  ascii(0, 'RIFF'); header.setUint32(4, 36 + bytes, true); ascii(8, 'WAVE');
  ascii(12, 'fmt '); header.setUint32(16, 16, true); header.setUint16(20, 1, true); header.setUint16(22, channels, true);
  header.setUint32(24, sampleRate, true); header.setUint32(28, sampleRate * channels * 2, true);
  header.setUint16(32, channels * 2, true); header.setUint16(34, 16, true);
  ascii(36, 'data'); header.setUint32(40, bytes, true);
  return new Blob([header.buffer, samples.slice()], { type: 'audio/wav' });
}

async function playable(bytes) {
  return isOgg(bytes) ? decodeOgg(bytes) : new Blob([bytes]);
}

/**
 * One clip: prepared from a URL or from bytes, then played on a borrowed
 * element. Ogg Vorbis is always decoded here, because Safari reports it as
 * playable and then fails to decode it.
 */
export class Clip {
  constructor(onReady, onEnd, onError) {
    this.element = null;
    this.ownUrl = null;
    this.released = false;
    this.rate = 1;
    this.onReady = onReady;
    this.onEnd = onEnd;
    this.onError = onError;
  }

  prepareUrl(url) {
    const hit = cached(url);
    if (hit) return this.open(hit, false);
    const abort = new AbortController();
    const timer = setTimeout(() => abort.abort(), 20000);
    fetch(url, { signal: abort.signal })
      .then((response) => {
        if (!response.ok) throw new Error('HTTP ' + response.status);
        return response.arrayBuffer();
      })
      .then((buffer) => playable(new Uint8Array(buffer)))
      .then((blob) => {
        const objectUrl = URL.createObjectURL(blob);
        remember(url, objectUrl);
        this.open(objectUrl, false);
      })
      .catch((error) => this.fail(error))
      .finally(() => clearTimeout(timer));
  }

  prepareBytes(bytes) {
    playable(bytes)
      .then((blob) => this.open(URL.createObjectURL(blob), true))
      .catch((error) => this.fail(error));
  }

  open(objectUrl, owned) {
    if (this.released) {
      if (owned) URL.revokeObjectURL(objectUrl);
      return;
    }
    if (owned) this.ownUrl = objectUrl;
    const element = borrowElement();
    this.element = element;
    element.oncanplay = () => {
      element.oncanplay = null;
      if (!this.released) this.onReady();
    };
    element.onended = () => { if (!this.released) this.onEnd(); };
    element.onerror = () => this.fail(element.error);
    element.src = objectUrl;
    element.load();
  }

  fail(error) {
    if (this.released) return;
    console.warn('W/Clip: ' + error);
    this.onError();
  }

  play() {
    if (!this.element) return;
    this.applyRate();
    this.element.play().catch((error) => this.fail(error));
  }

  pause() { this.element?.pause(); }

  stop() {
    if (!this.element) return;
    this.element.pause();
    this.element.currentTime = 0;
  }

  setRate(rate) {
    this.rate = rate;
    this.applyRate();
  }

  applyRate() {
    if (!this.element) return;
    // Slower, same pitch, as Android's PlaybackParams.setSpeed.
    this.element.preservesPitch = true;
    this.element.webkitPreservesPitch = true;
    this.element.playbackRate = this.rate;
  }

  isPlaying() {
    return !!this.element && !this.element.paused && !this.element.ended;
  }

  release() {
    this.released = true;
    if (this.element) returnElement(this.element);
    this.element = null;
    if (this.ownUrl) URL.revokeObjectURL(this.ownUrl);
    this.ownUrl = null;
  }
}

// The microphone. The stream is opened per take and closed after it, so the
// browser's recording indicator is only on while the learner records.
const CAPTURE_WORKLET = `
class GlossoCapture extends AudioWorkletProcessor {
  process(inputs) {
    const channel = inputs[0] && inputs[0][0];
    if (channel) this.port.postMessage(channel.slice(0));
    return true;
  }
}
registerProcessor('glosso-capture', GlossoCapture);`;
let workletReady = null;
let microphoneGranted = false;
let capture = null;

const MIC_CONSTRAINTS = {
  audio: { channelCount: 1, echoCancellation: false, noiseSuppression: false, autoGainControl: false },
};

if (navigator.permissions?.query) {
  navigator.permissions.query({ name: 'microphone' }).then((status) => {
    microphoneGranted = status.state === 'granted';
    status.onchange = () => { microphoneGranted = status.state === 'granted'; };
  }, () => {});
}

export function microphoneAllowed() { return microphoneGranted; }

/** The browser's prompt; resolves true when the learner allows the microphone. */
export async function requestMicrophone() {
  try {
    const stream = await navigator.mediaDevices.getUserMedia(MIC_CONSTRAINTS);
    stream.getTracks().forEach((track) => track.stop());
    microphoneGranted = true;
  } catch (error) {
    console.warn('W/Microphone: ' + error);
    microphoneGranted = false;
  }
  return microphoneGranted;
}

/** Starts collecting the microphone; what arrives before stopCapture is the take. */
export function startCapture() {
  stopCapture();
  unlock();
  const take = { chunks: [], peak: 0, stopped: false, stream: null, node: null, source: null, sink: null };
  capture = take;
  if (!workletReady) {
    const url = URL.createObjectURL(new Blob([CAPTURE_WORKLET], { type: 'text/javascript' }));
    workletReady = context.audioWorklet.addModule(url);
  }
  Promise.all([navigator.mediaDevices.getUserMedia(MIC_CONSTRAINTS), workletReady]).then(([stream]) => {
    if (take.stopped) {
      stream.getTracks().forEach((track) => track.stop());
      return;
    }
    microphoneGranted = true;
    take.stream = stream;
    take.source = context.createMediaStreamSource(stream);
    take.node = new AudioWorkletNode(context, 'glosso-capture');
    take.node.port.onmessage = (event) => {
      const samples = event.data;
      let peak = 0;
      for (let i = 0; i < samples.length; i++) peak = Math.max(peak, Math.abs(samples[i]));
      take.peak = peak;
      take.chunks.push(samples);
    };
    take.source.connect(take.node);
    // Muted, but in the graph, so every browser keeps pulling the worklet.
    take.sink = context.createGain();
    take.sink.gain.value = 0;
    take.node.connect(take.sink).connect(context.destination);
  }, (error) => {
    console.warn('W/Microphone: ' + error);
    take.stopped = true;
  });
}

export function capturePeak() { return capture ? capture.peak : 0; }

export function captureRate() { return context ? context.sampleRate : 48000; }

/** Stops the take and returns its samples as one Float32Array at captureRate(). */
export function stopCapture() {
  const take = capture;
  capture = null;
  if (!take) return new Float32Array(0);
  take.stopped = true;
  take.source?.disconnect();
  take.node?.port.close();
  take.sink?.disconnect();
  take.stream?.getTracks().forEach((track) => track.stop());
  const total = take.chunks.reduce((sum, chunk) => sum + chunk.length, 0);
  const out = new Float32Array(total);
  let at = 0;
  for (const chunk of take.chunks) { out.set(chunk, at); at += chunk.length; }
  return out;
}

/** A short buzz where the device has one (not on iOS). */
export function vibrate(millis) { navigator.vibrate?.(millis); }
