import { abilityAccessCtrl, common, Permissions } from '@kit.AbilityKit';
import { asset } from '@kit.AssetStoreKit';
import { audio } from '@kit.AudioKit';
import { util } from '@kit.ArkTS';
import { rcp } from '@kit.RemoteCommunicationKit';

const KEY_ALIAS: string = 'leaf.elevenlabs';
const KEY_PATTERN: RegExp = new RegExp('^[A-Za-z0-9_-]{20,200}$');
const MIC: Permissions = 'ohos.permission.MICROPHONE';
const SCRIBE_URL: string = 'https://api.elevenlabs.io/v1/speech-to-text';
const SCRIBE_MODEL: string = 'scribe_v2';
/** 16 kHz mono s16le: two bytes a sample. */
const BYTES_PER_SECOND: number = 16000 * 2;
/** Five minutes: a take longer than that is a slip, and the upload stays under 10 MB. */
const MAX_BYTES: number = BYTES_PER_SECOND * 300;
/** 0.3 s. Under 100 ms ElevenLabs refuses the file; a take that short heard nothing anyway. */
const MIN_BYTES: number = BYTES_PER_SECOND * 3 / 10;
const MIC_BUSY: string = '无法打开麦克风。请稍后重试。';

function utf8(s: string): Uint8Array {
  return new util.TextEncoder().encodeInto(s);
}

/**
 * The ElevenLabs API key voice input calls Scribe with, straight from the device. It is pasted in 设置 and kept
 * in the Asset Store under its own alias, like the Notion token in Vault; it never leaves the device except in
 * the xi-api-key header to api.elevenlabs.io. Port of Android's ElevenLabsKey and VaultSpeechKeyStore.
 */
export class SpeechKey {
  /** The key as pasted, trimmed; null when it cannot be one (spaces inside, a URL, too short). */
  static parse(input: string): string | null {
    const key = input.trim();
    return KEY_PATTERN.test(key) ? key : null;
  }

  /** What settings shows instead of the key: its first three characters and last four. */
  static masked(key: string): string {
    return key.length <= 10 ? '…' : `${key.substring(0, 3)}…${key.substring(key.length - 4)}`;
  }

  /** Saves [key] (what parse returned), replacing any key saved before. */
  static async save(key: string): Promise<void> {
    await SpeechKey.clear();
    const attrs: asset.AssetMap = new Map();
    attrs.set(asset.Tag.ALIAS, utf8(KEY_ALIAS));
    attrs.set(asset.Tag.SECRET, utf8(key));
    attrs.set(asset.Tag.ACCESSIBILITY, asset.Accessibility.DEVICE_FIRST_UNLOCKED);
    await asset.add(attrs);
  }

  /** The saved key, or '' when none is saved. */
  static async read(): Promise<string> {
    const query: asset.AssetMap = new Map();
    query.set(asset.Tag.ALIAS, utf8(KEY_ALIAS));
    query.set(asset.Tag.RETURN_TYPE, asset.ReturnType.ALL);
    try {
      const found: asset.AssetMap[] = await asset.query(query);
      if (found.length === 0) {
        return '';
      }
      const secret = found[0].get(asset.Tag.SECRET) as Uint8Array;
      return util.TextDecoder.create('utf-8').decodeToString(secret);
    } catch (e) {
      // NOT_FOUND is the normal "no key yet"; anything else also means no usable key.
      return '';
    }
  }

  /** Forgets the saved key; fine when none is saved. */
  static async clear(): Promise<void> {
    const query: asset.AssetMap = new Map();
    query.set(asset.Tag.ALIAS, utf8(KEY_ALIAS));
    try {
      await asset.remove(query);
    } catch (e) {
      // Nothing stored: already clear.
    }
  }

  /** The saved key masked for settings, or '' when none is saved. Reads the secret, so call it once per visit. */
  static async maskedSaved(): Promise<string> {
    const key = await SpeechKey.read();
    return key.length > 0 ? SpeechKey.masked(key) : '';
  }
}

/** Why a take ended without words. */
export enum VoiceErrorKind {
  /** No ElevenLabs key saved in 设置. */
  NoKey = 'no_key',
  /** The microphone permission was refused. */
  NoPermission = 'no_permission',
  /** Scribe is rate limited or its gateway timed out (429, 502, 503, 504), or the microphone would not open. */
  Busy = 'busy',
  /** ElevenLabs refused the saved key (401, 403): wrong, revoked or out of quota. */
  Unauthorized = 'unauthorized',
  /** The request never got an answer: offline, DNS, TLS or a timeout. */
  Network = 'network',
  /** Anything else: another status, an unreadable answer, or a take Scribe heard nothing in. */
  Failed = 'failed',
}

function messageOf(kind: VoiceErrorKind): string {
  switch (kind) {
    case VoiceErrorKind.NoKey:
      return '未设置 ElevenLabs 密钥。请在设置中添加。';
    case VoiceErrorKind.NoPermission:
      return '无法使用麦克风。请在系统设置中允许 Leaf 使用麦克风。';
    case VoiceErrorKind.Busy:
      return '语音服务繁忙。请稍后重试。';
    case VoiceErrorKind.Unauthorized:
      return 'ElevenLabs 密钥无效。请在设置中检查。';
    case VoiceErrorKind.Network:
      return '无法连接语音服务。请检查网络连接。';
    default:
      return '无法识别这段语音。请重试。';
  }
}

/** A failed take. [message] is the line to show 51; it never includes the key or the audio. */
export class VoiceError extends Error {
  readonly kind: VoiceErrorKind;

  /** [message] replaces the kind's usual line only where the cause is narrower (the microphone, not the service). */
  constructor(kind: VoiceErrorKind, message?: string) {
    super(message ?? messageOf(kind));
    this.kind = kind;
  }
}

/** Root mean square of one s16le chunk, 0..1 of full scale. Speech sits around 0.02 to 0.3. */
function rms(chunk: ArrayBuffer): number {
  const samples = new Int16Array(chunk, 0, Math.floor(chunk.byteLength / 2));
  if (samples.length === 0) {
    return 0;
  }
  let sum = 0;
  for (let i = 0; i < samples.length; i++) {
    const s = samples[i] / 32768;
    sum += s * s;
  }
  return Math.min(1, Math.sqrt(sum / samples.length));
}

/**
 * Records one take as raw 16 kHz mono 16-bit little-endian PCM, the format Scribe reads without decoding.
 * At five minutes it releases the microphone by itself and calls [onLimit]; stop() still returns the take.
 */
export class Recorder {
  /** Called once when a take reaches five minutes; the microphone is already being released. */
  onLimit: () => void = () => {};
  private capturer: audio.AudioCapturer | null = null;
  private halting: Promise<void> = Promise.resolve();
  private chunks: ArrayBuffer[] = [];
  private bytes: number = 0;
  private lastLevel: number = 0;

  /**
   * Opens the microphone, asking for the permission first when needed (through the system's settings sheet
   * once the dialog was refused). Throws VoiceError NoPermission, or Busy when the microphone will not open.
   * Does nothing while already recording.
   */
  async start(context: common.UIAbilityContext): Promise<void> {
    if (this.capturer !== null) {
      return;
    }
    if (!(await Recorder.allowed(context))) {
      throw new VoiceError(VoiceErrorKind.NoPermission);
    }
    await this.halting;
    this.chunks = [];
    this.bytes = 0;
    this.lastLevel = 0;
    const streamInfo: audio.AudioStreamInfo = {
      samplingRate: audio.AudioSamplingRate.SAMPLE_RATE_16000,
      channels: audio.AudioChannel.CHANNEL_1,
      sampleFormat: audio.AudioSampleFormat.SAMPLE_FORMAT_S16LE,
      encodingType: audio.AudioEncodingType.ENCODING_TYPE_RAW,
    };
    const capturerInfo: audio.AudioCapturerInfo = { source: audio.SourceType.SOURCE_TYPE_MIC, capturerFlags: 0 };
    const options: audio.AudioCapturerOptions = { streamInfo: streamInfo, capturerInfo: capturerInfo };
    let c: audio.AudioCapturer;
    try {
      c = await audio.createAudioCapturer(options);
    } catch (e) {
      throw new VoiceError(VoiceErrorKind.Busy, MIC_BUSY);
    }
    this.capturer = c;
    c.on('readData', (buf: ArrayBuffer) => {
      if (this.capturer === c) {
        this.keep(buf);
      }
    });
    try {
      await c.start();
    } catch (e) {
      if (this.capturer === c) {
        await this.halt();
      }
      throw new VoiceError(VoiceErrorKind.Busy, MIC_BUSY);
    }
  }

  /** Releases the microphone and returns the whole take as one s16le buffer (empty if nothing was recorded). */
  async stop(): Promise<ArrayBuffer> {
    await this.halt();
    const out = new Uint8Array(this.bytes);
    let at = 0;
    for (const b of this.chunks) {
      out.set(new Uint8Array(b), at);
      at += b.byteLength;
    }
    this.chunks = [];
    this.bytes = 0;
    return out.buffer;
  }

  /** Releases the microphone and drops the take. */
  async cancel(): Promise<void> {
    await this.halt();
    this.chunks = [];
    this.bytes = 0;
  }

  /** Loudness of the last chunk, 0..1 (RMS of full scale), for a level meter; 0 when not recording. */
  level(): number {
    return this.capturer === null ? 0 : this.lastLevel;
  }

  /** Length of the take so far, in seconds. */
  seconds(): number {
    return this.bytes / BYTES_PER_SECOND;
  }

  private keep(buf: ArrayBuffer): void {
    const room = MAX_BYTES - this.bytes;
    if (room <= 0) {
      return;
    }
    // The buffer is the capturer's own and is reused, so it is copied.
    const chunk = buf.slice(0, Math.min(buf.byteLength, room));
    this.chunks.push(chunk);
    this.bytes += chunk.byteLength;
    this.lastLevel = rms(chunk);
    if (this.bytes >= MAX_BYTES) {
      this.halt();
      this.onLimit();
    }
  }

  private halt(): Promise<void> {
    const c = this.capturer;
    this.capturer = null;
    this.lastLevel = 0;
    if (c !== null) {
      this.halting = Recorder.close(c);
    }
    return this.halting;
  }

  private static async close(c: audio.AudioCapturer): Promise<void> {
    try {
      if (c.state === audio.AudioState.STATE_RUNNING) {
        await c.stop();
      }
      c.off('readData');
    } catch (e) {
      // Already stopped by the system (a call took the microphone): release it all the same.
    }
    try {
      await c.release();
    } catch (e) {
      // Already released.
    }
  }

  private static async allowed(context: common.UIAbilityContext): Promise<boolean> {
    const at = abilityAccessCtrl.createAtManager();
    try {
      const status = at.checkAccessTokenSync(context.applicationInfo.accessTokenId, MIC);
      if (status === abilityAccessCtrl.GrantStatus.PERMISSION_GRANTED) {
        return true;
      }
      const result = await at.requestPermissionsFromUser(context, [MIC]);
      if (result.authResults[0] === 0) {
        return true;
      }
      // Refused before: the dialog no longer shows, so ask through the system's settings sheet instead.
      const shown = result.dialogShownResults;
      if (shown !== undefined && shown[0] === false) {
        const granted = await at.requestPermissionOnSetting(context, [MIC]);
        return granted[0] === abilityAccessCtrl.GrantStatus.PERMISSION_GRANTED;
      }
      return false;
    } catch (e) {
      return false;
    }
  }
}

interface ScribeAnswer {
  text?: string;
}

/**
 * ElevenLabs Scribe v2, called from the device with the key saved in 设置, as Android's ScribeTranscriber.
 * No language is sent: Scribe detects it, mixed Chinese and English included. Raw PCM skips decoding on
 * their side, which ElevenLabs says answers sooner than a file. One upload at a time per instance.
 */
export class Scribe {
  private session: rcp.Session | null = null;

  /** A rate limit or a gateway timeout is a wait; a refused key needs 设置; anything else the next take would meet too. */
  static kindOf(code: number): VoiceErrorKind {
    if (code === 429 || code === 502 || code === 503 || code === 504) {
      return VoiceErrorKind.Busy;
    }
    return code === 401 || code === 403 ? VoiceErrorKind.Unauthorized : VoiceErrorKind.Failed;
  }

  /** The take's words, trimmed. A take under 0.3 s returns '' without being sent. Throws VoiceError. */
  async transcribe(pcm: ArrayBuffer, key: string): Promise<string> {
    if (pcm.byteLength < MIN_BYTES) {
      return '';
    }
    const clean = key.trim();
    if (clean.length === 0) {
      throw new VoiceError(VoiceErrorKind.NoKey);
    }
    // FileContent, not a string: a string there is read as a file path.
    const audioBytes: rcp.FileContent = { content: pcm };
    const file: rcp.FormFieldFileValue = { contentType: 'application/octet-stream', remoteFileName: 'take.pcm', contentOrPath: audioBytes };
    const fields: rcp.MultipartFormFields = {
      'model_id': SCRIBE_MODEL,
      'file_format': 'pcm_s16le_16',
      'tag_audio_events': 'false',
      'file': file,
    };
    // No content-type here: rcp writes multipart/form-data with its own boundary.
    const headers: rcp.RequestHeaders = { 'xi-api-key': clean, 'accept': 'application/json' };
    // Like Android's 60 s read and write timeouts: the line may idle 60 s at most. The whole exchange gets
    // five minutes, since a five-minute take is about 10 MB to send.
    const session = rcp.createSession({
      requestConfiguration: {
        transfer: { autoRedirect: false, timeout: { connectMs: 20000, inactivityMs: 60000, transferMs: 300000 } },
      },
    });
    this.session = session;
    try {
      let response: rcp.Response;
      try {
        response = await session.fetch(new rcp.Request(SCRIBE_URL, 'POST', headers, new rcp.MultipartForm(fields)));
      } catch (e) {
        throw new VoiceError(VoiceErrorKind.Network);
      }
      const code = response.statusCode;
      if (code < 200 || code >= 300) {
        throw new VoiceError(Scribe.kindOf(code));
      }
      try {
        const answer = JSON.parse(response.toString() ?? '') as ScribeAnswer;
        return (answer.text ?? '').trim();
      } catch (e) {
        throw new VoiceError(VoiceErrorKind.Failed);
      }
    } finally {
      if (this.session === session) {
        this.session = null;
      }
      session.close();
    }
  }

  /** Stops the upload under way, if any; its transcribe() then rejects with Network. */
  cancel(): void {
    const s = this.session;
    this.session = null;
    if (s !== null) {
      s.cancel();
    }
  }
}

/** Where voice input is: nothing, the microphone open, or the take on its way to Scribe. */
export enum VoiceState {
  Idle = 'idle',
  Recording = 'recording',
  Transcribing = 'transcribing',
}

/** Told the new state each time VoiceInput's state changes. */
export type VoiceListener = (state: VoiceState) => void;

/**
 * Voice input for the essay editor: one tap starts a take, the next sends it to Scribe and hands the words to
 * [onText]. Failures go to [onError] and leave it Idle; toggle() and cancel() never reject. The key is read from
 * SpeechKey at each take, so a missing key is reported before the microphone opens. Call cancel() when the
 * editor goes away or to the background.
 */
export class VoiceInput {
  /** The take's words, trimmed and never empty. */
  onText: (text: string) => void = (text: string) => {};
  /** Why a take ended without words; show e.message. */
  onError: (e: VoiceError) => void = (e: VoiceError) => {};
  private current: VoiceState = VoiceState.Idle;
  private listeners: VoiceListener[] = [];
  private recorder: Recorder = new Recorder();
  private scribe: Scribe = new Scribe();
  private key: string = '';
  /** Counts takes, so a cancelled one's late result is dropped. */
  private take: number = 0;
  private starting: boolean = false;

  constructor() {
    this.recorder.onLimit = () => {
      this.finish();
    };
  }

  /** Idle, Recording or Transcribing; subscribe() to follow it. */
  get state(): VoiceState {
    return this.current;
  }

  /** Calls [listener] on every state change; returns the call that stops it. */
  subscribe(listener: VoiceListener): () => void {
    this.listeners.push(listener);
    return () => {
      this.listeners = this.listeners.filter((l: VoiceListener) => l !== listener);
    };
  }

  /** Loudness of the microphone now, 0..1, for a level meter; 0 unless Recording. */
  level(): number {
    return this.current === VoiceState.Recording ? this.recorder.level() : 0;
  }

  /** Length of the take while Recording, in seconds. */
  seconds(): number {
    return this.recorder.seconds();
  }

  /** Idle: starts a take. Recording: ends it and transcribes. Ignored while the take starts or transcribes. */
  async toggle(context: common.UIAbilityContext): Promise<void> {
    if (this.current === VoiceState.Recording) {
      await this.finish();
    } else if (this.current === VoiceState.Idle && !this.starting) {
      await this.begin(context);
    }
  }

  /** Drops the take, recording or transcribing: the microphone and the upload stop, nothing reaches onText. */
  async cancel(): Promise<void> {
    this.take++;
    this.key = '';
    this.scribe.cancel();
    this.enter(VoiceState.Idle);
    await this.recorder.cancel();
  }

  private async begin(context: common.UIAbilityContext): Promise<void> {
    const take = ++this.take;
    this.starting = true;
    try {
      const key = await SpeechKey.read();
      if (key.length === 0) {
        throw new VoiceError(VoiceErrorKind.NoKey);
      }
      if (take !== this.take) {
        return;
      }
      await this.recorder.start(context);
      if (take !== this.take) {
        // Cancelled while the permission sheet was up or the microphone was opening.
        await this.recorder.cancel();
        return;
      }
      this.key = key;
      this.enter(VoiceState.Recording);
    } catch (e) {
      if (take === this.take) {
        this.onError(e instanceof VoiceError ? e : new VoiceError(VoiceErrorKind.Failed));
      }
    } finally {
      this.starting = false;
    }
  }

  private async finish(): Promise<void> {
    if (this.current !== VoiceState.Recording) {
      return;
    }
    const take = this.take;
    const key = this.key;
    this.key = '';
    this.enter(VoiceState.Transcribing);
    let pcm: ArrayBuffer = new ArrayBuffer(0);
    let text = '';
    try {
      pcm = await this.recorder.stop();
      if (take !== this.take) {
        return;
      }
      text = await this.scribe.transcribe(pcm, key);
    } catch (e) {
      if (take === this.take) {
        this.enter(VoiceState.Idle);
        this.onError(e instanceof VoiceError ? e : new VoiceError(VoiceErrorKind.Failed));
      }
      return;
    }
    if (take !== this.take) {
      return;
    }
    this.enter(VoiceState.Idle);
    if (text.length > 0) {
      this.onText(text);
    } else if (pcm.byteLength >= MIN_BYTES) {
      // Scribe heard nothing it could write down. A take under 0.3 s was a slip and says nothing.
      this.onError(new VoiceError(VoiceErrorKind.Failed));
    }
  }

  private enter(state: VoiceState): void {
    if (this.current === state) {
      return;
    }
    this.current = state;
    this.listeners.forEach((l: VoiceListener) => l(state));
  }
}
