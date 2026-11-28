import { spawn } from 'node:child_process';
import { createSocket } from 'node:dgram';
import { realpath } from 'node:fs/promises';
import { resolve, sep, extname } from 'node:path';
import { once } from 'node:events';
import { MediaToolError, toolFailure } from './tool-errors.mjs';
import { withYoutubeCookies } from './cookies.mjs';

// Project only playback fields. Full YouTube metadata can include >10 MiB of captions.
export const YOUTUBE_INFO_TEMPLATE = '{"title":%(title)j,"url":%(url)j,"http_headers":%(http_headers)j,"requested_formats":%(requested_formats.:.{url,http_headers})j}';

export async function capture(command, args, signal, timeout = 60000, failure = 'Medienquelle konnte nicht geladen werden. Werkzeuge und Link prüfen.', { youtube = false } = {}) {
  let child;
  try { child = spawn(command, args, { stdio: ['ignore', 'pipe', 'pipe'], signal }); }
  catch (error) { if (signal?.aborted) throw error; throw toolFailure('', { youtube, spawnCode: error.code || 'SPAWN', fallback: failure }); }
  const chunks = []; let size = 0, stderr = '', timedOut = false, tooLarge = false;
  const timer = setTimeout(() => { timedOut = true; child.kill('SIGKILL'); }, timeout);
  // Drain stderr continuously and retain only a bounded tail, never return it verbatim.
  child.stderr.on('data', chunk => { stderr = (stderr + chunk.toString('utf8')).slice(-32768); });
  child.stdout.on('data', chunk => {
    size += chunk.length;
    if (size > 2 * 1024 * 1024) { tooLarge = true; child.kill('SIGKILL'); }
    else chunks.push(chunk);
  });
  try {
    let code;
    try { [code] = await once(child, 'close'); }
    catch (error) { if (signal?.aborted) throw error; throw toolFailure(stderr, { youtube, spawnCode: error.code || 'SPAWN', fallback: failure }); }
    if (code !== 0 || tooLarge) throw toolFailure(stderr, { youtube, timedOut, tooLarge, fallback: failure });
    try { return JSON.parse(Buffer.concat(chunks).toString('utf8')); }
    catch { throw new MediaToolError('MEDIA_INVALID_OUTPUT'); }
  } finally { clearTimeout(timer); if (child.exitCode === null) child.kill('SIGKILL'); }
}
function http(location) {
  const url = new URL(location);
  if (!['http:', 'https:'].includes(url.protocol) || !url.hostname || url.username || url.password) throw new Error('HTTP-/HTTPS-Adresse ohne Zugangsdaten erwartet.');
  return url;
}
export async function resolveSource(source, config, signal) {
  if (typeof source !== 'string' || !source.trim() || source.length > 8192 || /[\x00\r\n]/.test(source)) throw new Error('Ungültige Videoquelle.');
  let inputs; let title;
  if (/^https?:/i.test(source)) {
    const url = http(source);
    if (/(^|\.)(youtube\.com|youtube-nocookie\.com|youtu\.be)$/.test(url.hostname)) {
      const info = await withYoutubeCookies(config, cookieArgs => capture(config.ytDlp, ['--ignore-config', ...cookieArgs, '--no-playlist', '--no-warnings', '--check-formats', '--socket-timeout', '15', '--js-runtimes', config.node,
        '--output-na-placeholder', 'null', '--print', YOUTUBE_INFO_TEMPLATE, '--skip-download', '-f', 'bestvideo[height<=720][vcodec^=avc1]+bestaudio/bestvideo[height<=720]+bestaudio/best[vcodec!=none][acodec!=none]', '--', source], signal, 60000, 'YouTube-Link konnte nicht aufgelöst werden.', { youtube: true }));
      inputs = (Array.isArray(info.requested_formats) && info.requested_formats.length ? info.requested_formats : [info]).map(format => ({ location: http(format.url).href, headers: format.http_headers || info.http_headers || {}, remote: true }));
      if (inputs.length > 2) throw new Error('Nicht unterstützte YouTube-Formate.');
      title = String(info.title || 'YouTube').slice(0, 180);
    } else { inputs = [{ location: url.href, remote: true }]; title = url.hostname; }
  } else {
    if (/^[a-z][a-z0-9+.-]*:/i.test(source)) throw new Error('Nur Videodateien und HTTP-/HTTPS-Links sind erlaubt.');
    const root = await realpath(config.files);
    const file = await realpath(resolve(root, source));
    if (!file.startsWith(root + sep) || !['.mp4', '.webm', '.mkv', '.mov', '.m4v'].includes(extname(file).toLowerCase())) throw new Error('Videodatei muss im Musik-Dateiverzeichnis liegen.');
    inputs = [{ location: file, remote: false }]; title = source;
  }
  for (const input of inputs) {
    const headers = Object.entries(input.headers || {});
    if (headers.length > 30 || headers.some(([key, value]) => !/^[A-Za-z0-9-]+$/.test(key) || typeof value !== 'string' || value.length > 8192 || /[\x00\r\n]/.test(value))) throw new Error('Ungültige Medien-Header.');
  }
  const probes = [];
  for (const input of inputs) probes.push(await capture(config.ffprobe, ['-v', 'error', ...inputOptions(input), '-i', input.location, '-show_streams', '-of', 'json'], signal, 20000, 'Videoquelle konnte nicht geprüft werden. Link oder ffprobe prüfen.'));
  const video = probes.findIndex(p => p.streams?.some(s => s.codec_type === 'video'));
  const audio = probes.findIndex(p => p.streams?.some(s => s.codec_type === 'audio'));
  if (video < 0) throw new Error('Diese Quelle enthält kein Video. Für Radio bitte den Audio-Bot verwenden.');
  return { inputs, title, video, audio };
}
function inputOptions(input) {
  return [...(input.remote ? ['-rw_timeout', '15000000', '-protocol_whitelist', 'http,https,tcp,tls,crypto'] : ['-protocol_whitelist', 'file,crypto']),
    ...(Object.keys(input.headers || {}).length ? ['-headers', Object.entries(input.headers).map(([k,v]) => `${k}: ${v}\r\n`).join('')] : [])];
}
async function receiver() {
  const socket = createSocket('udp4'); socket.bind(0, '127.0.0.1'); await once(socket, 'listening'); return socket;
}
/** One FFmpeg clock supplies both tracks; UDP/RTP stays on loopback until SRTP encryption. */
export class MediaEncoder {
  static async open(media, config, onPacket, onEnd) {
    const video = await receiver(); let audio;
    try { audio = await receiver(); } catch (error) { video.close(); throw error; }
    const encoder = new MediaEncoder(video, audio);
    for (const [kind, socket] of [['video', video], ['audio', audio]]) socket.on('message', packet => {
      // RTCP is never forwarded as media. FFmpeg's RTP packets are bounded by pkt_size.
      if (packet.length >= 12 && packet[0] >>> 6 === 2 && (packet[1] & 127) === (kind === 'video' ? 96 : 111)) onPacket(kind, packet);
    });
    const args = ['-hide_banner', '-loglevel', 'error', '-threads', '2'];
    for (const input of media.inputs) args.push('-re', ...inputOptions(input), '-i', input.location);
    if (media.audio < 0) args.push('-re', '-f', 'lavfi', '-i', 'anullsrc=r=48000:cl=stereo');
    args.push('-map', `${media.video}:v:0`, '-map', `${media.audio < 0 ? media.inputs.length : media.audio}:a:0`,
      '-vf', "scale=w='min(1280,iw)':h='min(720,ih)':force_original_aspect_ratio=decrease:force_divisible_by=2,fps=25",
      '-af', `volume@cerberus=${config.volume / 100}`, '-c:v', 'libvpx', '-deadline', 'realtime', '-cpu-used', '5', '-threads', '2',
      '-b:v', '1500k', '-maxrate', '1500k', '-bufsize', '1500k', '-g', '25', '-pix_fmt', 'yuv420p',
      '-c:a', 'libopus', '-ar', '48000', '-ac', '2', '-b:a', '96k', '-frame_duration', '20', '-shortest', '-f', 'tee',
      `[select=v:f=rtp:payload_type=96]rtp://127.0.0.1:${video.address().port}?pkt_size=1200|[select=a:f=rtp:payload_type=111]rtp://127.0.0.1:${audio.address().port}?pkt_size=1200`);
    encoder.child = spawn(config.ffmpeg, args, { stdio: ['pipe', 'ignore', 'ignore'] });
    encoder.child.stdin.on('error', () => {});
    encoder.child.once('error', () => { if (!encoder.closed) onEnd(false); });
    encoder.child.once('exit', code => { if (!encoder.closed) onEnd(code === 0); });
    return encoder;
  }
  constructor(video, audio) { this.video = video; this.audio = audio; this.closed = false; this.paused = false; }
  pause(value) {
    if (!this.child || this.closed) return;
    if (process.platform === 'win32') throw new Error('Video-Pause benötigt Linux oder macOS.');
    if (value !== this.paused) {
      if (!this.child.kill(value ? 'SIGSTOP' : 'SIGCONT')) throw new Error('Decoder ist nicht mehr aktiv.');
      this.paused = value;
    }
  }
  volume(percent) { if (!this.closed) this.child?.stdin.write(`call -1 volume ${percent / 100}\n`); }
  async close() {
    if (this.closed) return; this.closed = true;
    this.video.close(); this.audio.close();
    if (this.child && this.child.exitCode === null) {
      if (this.paused) this.child.kill('SIGCONT');
      this.child.kill('SIGTERM');
      const timer = setTimeout(() => this.child.kill('SIGKILL'), 1500);
      await once(this.child, 'close').catch(() => {}); clearTimeout(timer);
    }
  }
}
