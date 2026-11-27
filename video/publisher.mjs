import { Client, generateIdentity, parseIdentity } from '@honeybbq/teamspeak/client';
import { moveClient, updateClient } from '@honeybbq/teamspeak';
import { Streams } from '@honeybbq/teamspeak/stream';
import { RTCPeerConnection, RTCRtpCodecParameters, MediaStreamTrack } from 'werift';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { resolveSource, MediaEncoder } from './media.mjs';
import { MediaToolError } from './tool-errors.mjs';

export function peerConfig(config) {
  return {
    codecs: {
      audio: [new RTCRtpCodecParameters({ mimeType: 'audio/opus', clockRate: 48000, channels: 2, payloadType: 111 })],
      video: [new RTCRtpCodecParameters({ mimeType: 'video/VP8', clockRate: 90000, payloadType: 96, rtcpFeedback: [{ type: 'nack' }, { type: 'nack', parameter: 'pli' }] })],
    }, bundlePolicy: 'max-bundle', iceServers: [{ urls: config.stun || 'stun:stun.l.google.com:19302' }],
    iceUseIpv6: false, iceUseTcp: false, iceTcpPassive: false,
    ...(config.portMin ? { icePortRange: [config.portMin, config.portMax] } : {}),

  };
}
export function candidate(c, publicAddress) {
  let value = c.candidate;
  // Pelican publishes the same host port as the container allocation. Keep the
  // socket bound to its real interface; advertise the mapped address over ICE.
  if (publicAddress) {
    const fields = value.split(" ");
    if (fields[6] === "typ" && fields[7] === "host") { fields[4] = publicAddress; value = fields.join(" "); }
  }
  return { candidate: value, sdpMid: c.sdpMid ?? null, sdpMLineIndex: c.sdpMLineIndex ?? null };
}
export function canWatch(client, viewerId, channelId, count, limit) {
  return count < limit && client.view.client(viewerId)?.channelID === BigInt(channelId) && client.view.client(viewerId)?.type === 0;
}
export class VideoPublisher {
  constructor(config, emit) {
    this.config = config; this.emit = emit; this.queue = []; this.active = null; this.generation = 0; this.closed = false;
    this.state = { connected: false, clientId: 0, channelId: config.channelId, current: null, title: null, phase: 'STOPPED', paused: false, volume: 25, queued: [], viewers: 0, streamId: null, error: null, viewerError: null };
  }
  snapshot() { return { ...this.state, queued: [...this.queue] }; }
  changed() { this.emit(this.snapshot()); }
  async connect() {
    await mkdir(this.config.directory, { recursive: true, mode: 0o700 });
    await mkdir(this.config.files, { recursive: true });
    let identity;
    try { identity = parseIdentity(await readFile(this.config.identity, 'utf8')); }
    catch (error) {
      if (error.code !== 'ENOENT') throw new Error('Video-Identität konnte nicht gelesen werden.');
      identity = await generateIdentity(8, { signal: AbortSignal.timeout(30000) });
      await writeFile(this.config.identity, identity.export(), { mode: 0o600, flag: 'wx' });
    }
    const host = this.config.host.includes(':') ? `[${this.config.host}]` : this.config.host;
    this.client = new Client(identity, `${host}:${this.config.port}`, this.config.nickname, { serverPassword: this.config.serverPassword || '' });
    this.client.on('disconnected', () => {
      if (this.closed) return;
      this.state.connected = false;
      this.fail('Video-Verbindung getrennt. Bot neu verbinden.');
    });
    await this.client.connect({ signal: AbortSignal.timeout(30000) });
    await this.client.view.whenReady({ signal: AbortSignal.timeout(10000) });
    if (!this.client.serverCapabilities.extensions.includes('STREAM_P2P')) throw new Error('Dieser Server unterstützt keine TS6-Videostreams.');
    await moveClient(this.client, this.client.clientID, BigInt(this.state.channelId), { password: this.config.channelPassword || undefined });
    this.streams = new Streams(this.client);
    this.streams.on('error', () => { this.state.viewerError = 'TS6-Streaming-Signalisierung fehlgeschlagen.'; this.changed(); });
    this.state.connected = true; this.state.clientId = Number(this.client.clientID); this.changed();
    this.guard = setInterval(() => {
      const active = this.active; if (!active) return;
      const own = this.client.view.client(this.client.clientID);
      if (own && own.channelID !== BigInt(this.state.channelId)) { this.state.channelId = Number(own.channelID); this.changed(); }
      for (const [id, entry] of active.peers) if (!canWatch(this.client, id, this.state.channelId, 0, 1)) void entry.close();
    }, 1000);
  }
  async action(body) {
    if (this.closed || !this.state.connected) throw new Error('Video-Bot ist getrennt.');
    switch (body.action) {
      case 'play': await this.validate(body.source); this.queue = [body.source]; await this.cancel(); this.next(); break;
      case 'enqueue': await this.validate(body.source); if (this.queue.length >= this.config.maxQueue) throw new Error('Warteschlange voll.'); this.queue.push(body.source); if (!this.active) this.next(); break;
      case 'stop': this.queue = []; await this.cancel(); break;
      case 'skip': await this.cancel(); this.next(); break;
      case 'pause': case 'resume':
        if (this.state.phase === 'LOADING') throw new Error('Video wird noch geladen.');
        this.active?.encoder?.pause(body.action === 'pause'); this.state.paused = !!this.active && body.action === 'pause'; this.state.phase = this.active ? (this.state.paused ? 'PAUSED' : 'PLAYING') : 'STOPPED'; break;
      case 'volume':
        if (!Number.isInteger(body.volume) || body.volume < 0 || body.volume > 100) throw new Error('Lautstärke: 0 bis 100.');
        this.state.volume = body.volume; this.active?.encoder?.volume(body.volume); break;
      case 'move':
        if (!Number.isSafeInteger(body.channelId) || body.channelId <= 0) throw new Error('Ungültiger Channel.');
        // Stop publication before moving so the old channel's viewers lose access immediately.
        this.queue = []; await this.cancel();
        await moveClient(this.client, this.client.clientID, BigInt(body.channelId), { password: body.channelPassword || undefined });
        this.state.channelId = body.channelId; break;
      default: throw new Error('Unbekannte Video-Aktion.');
    }
    this.changed(); return this.snapshot();
  }
  async validate(source) {
    if (typeof source !== 'string' || !source.trim() || source.length > 8192 || /[\x00\r\n]/.test(source)) throw new Error('Videoquelle fehlt oder ist ungültig.');
  }
  next() {
    if (this.closed || this.active || !this.queue.length || !this.state.connected) return;
    const active = { generation: ++this.generation, abort: new AbortController(), peers: new Map(), counters: { video: 0, audio: 0 } };
    this.active = active;
    this.state.current = this.queue.shift(); this.state.title = null; this.state.phase = 'LOADING'; this.state.paused = false; this.state.error = null; this.state.viewerError = null; this.changed();
    void this.load(active, this.state.current).catch(async error => {
      if (this.active !== active) return;
      await this.cancel();
      this.state.error = error instanceof MediaToolError || /^(Videoquelle|Diese Quelle|YouTube-Link|Nicht unterstützte|Ungültige Medien)/.test(error.message || '') ? error.message.slice(0, 200) : 'Video konnte nicht gestartet werden. FFmpeg, yt-dlp und Quelle prüfen.';
      this.state.phase = 'ERROR'; this.changed();
      // An error needs explicit intervention; do not churn through a broken queue.
    });
  }
  async load(active, source) {
    const media = await resolveSource(source, this.config, active.abort.signal);
    if (this.active !== active) return;
    this.state.title = media.title;
    let first; const ready = new Promise(resolve => { first = resolve; active.ready = resolve; });
    active.encoder = await MediaEncoder.open(media, { ...this.config, volume: this.state.volume }, (kind, packet) => {
      if (this.active !== active) return;
      active.counters[kind]++;
      if (active.counters.video && active.counters.audio) first();
      if (this.state.paused) return;
      for (const entry of active.peers.values()) if (entry.pc.connectionState === 'connected') entry[kind].writeRtp(packet);
    }, ok => {
      if (this.active !== active) return;
      if (!ok) { first(); void this.fail('Video-Decoder abgebrochen. Quelle und FFmpeg prüfen.'); }
      else { first(); void this.cancel().then(() => this.next()); }
    });
    if (this.active !== active) { await active.encoder.close(); return; }
    const timer = setTimeout(first, 15000);
    await ready; clearTimeout(timer);
    if (this.active !== active) return;
    if (!active.counters.video || !active.counters.audio) throw new Error('Videoquelle liefert keine Bild- und Tonpakete.');
    active.stream = await this.streams.publish({ name: media.title.slice(0, 100), source: 'screen', audio: true, viewerLimit: this.config.maxViewers });
    if (this.active !== active) { await active.stream.stop(); return; }
    active.stream.on('request', request => { void this.accept(active, request).catch(() => {
      this.state.viewerError = 'Ein Zuschauer konnte nicht verbunden werden. UDP-Freigaben prüfen.'; this.changed();
    }); });
    active.stream.on('ended', () => { if (this.active === active) void this.fail('Der TS6-Stream wurde beendet.'); });
    this.state.streamId = active.stream.id; this.state.phase = 'PLAYING'; this.changed();
    // Metadata is best effort; playback must not depend on this permission.
    void updateClient(this.client, { description: `Cerberus Video · ${media.title}`.slice(0, 500) }).catch(() => {});
  }
  async accept(active, request) {
    const id = request.viewer.clientId;
    if (this.active !== active || active.peers.has(id) || !canWatch(this.client, id, this.state.channelId, active.peers.size, this.config.maxViewers)) { await request.deny('Nur Zuschauer im Bot-Channel; Zuschauerlimit beachten.'); return; }
    const pc = new RTCPeerConnection(peerConfig(this.config));
    const audio = new MediaStreamTrack({ kind: 'audio' }); const video = new MediaStreamTrack({ kind: 'video' });
    pc.addTrack(audio); pc.addTrack(video);
    let peer; const pending = [];
    const entry = { pc, audio, video, close: async () => {
      if (active.peers.get(id) !== entry) return;
      active.peers.delete(id); clearTimeout(entry.timer); peer?.close().catch(() => {});
      audio.stop(); video.stop(); await pc.close(); this.count(active);
    } };
    active.peers.set(id, entry); // Reserve capacity before any asynchronous negotiation.
    entry.timer = setTimeout(() => { if (pc.connectionState !== 'connected') void entry.close(); }, 25000);
    request.on('closed', () => { if (!peer) void entry.close(); });
    pc.onIceCandidate.subscribe(c => { if (!c) return; if (peer) void peer.sendCandidate(candidate(c, this.config.publicAddress)).catch(() => entry.close()); else pending.push(candidate(c, this.config.publicAddress)); });
    pc.connectionStateChange.subscribe(state => {
      if (state === 'failed' || state === 'closed' || state === 'disconnected') void entry.close();
      this.count(active);
    });
    try {
      await pc.setLocalDescription(await pc.createOffer());
      if (this.active !== active || !canWatch(this.client, id, this.state.channelId, 0, 1) || active.peers.get(id) !== entry) { await entry.close(); return; }
      peer = await request.accept(pc.localDescription.sdp);
      peer.on('answer', sdp => { void pc.setRemoteDescription({ type: 'answer', sdp }).catch(() => entry.close()); });
      peer.on('candidate', c => { void pc.addIceCandidate(c).catch(() => entry.close()); });
      peer.on('closed', () => { void entry.close(); });
      for (const c of pending) await peer.sendCandidate(c);
    } catch (error) { await entry.close(); throw error; }
  }
  count(active) { if (this.active === active) { this.state.viewers = [...active.peers.values()].filter(e => e.pc.connectionState === 'connected').length; this.changed(); } }
  async cancel() {
    const active = this.active; this.active = null; ++this.generation;
    this.state.current = null; this.state.title = null; this.state.streamId = null; this.state.viewers = 0; this.state.paused = false; this.state.phase = 'STOPPED';
    if (!active) return;
    active.abort.abort(); active.ready?.();
    await Promise.allSettled([...active.peers.values()].map(e => e.close()));
    await active.encoder?.close();
    await active.stream?.stop().catch(() => {});
  }
  async fail(message) { await this.cancel(); this.state.error = message; this.state.phase = 'ERROR'; this.changed(); }
  async close() {
    if (this.closed) return; this.closed = true; clearInterval(this.guard); this.queue = [];
    await this.cancel(); this.streams?.dispose(); await this.client?.disconnect().catch(() => {});
    this.state.connected = false; this.changed();
  }
}
