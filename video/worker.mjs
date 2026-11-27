import { createInterface } from 'node:readline';
import { VideoPublisher } from './publisher.mjs';

const emit = data => process.stdout.write(JSON.stringify(data) + '\n');
let publisher; let chain = Promise.resolve(); let closing;
async function close() {
  if (closing) return closing;
  closing = (async () => { await publisher?.close(); process.exitCode = 0; })();
  return closing;
}
const input = createInterface({ input: process.stdin, crlfDelay: Infinity });
input.on('line', line => {
  if (line.length > 32768) { void close(); input.close(); return; }
  chain = chain.then(async () => {
    let body;
    try {
      body = JSON.parse(line);
      if (body.action === 'init' && !publisher) {
        publisher = new VideoPublisher(body.config, status => emit({ event: 'status', status }));
        await publisher.connect();
      } else if (body.action === 'close') { await close(); input.close(); }
      else {
        if (!publisher || closing) throw new Error('Video-Worker ist nicht verbunden.');
        await publisher.action(body);
      }
      emit({ requestId: body.requestId, status: publisher?.snapshot() });
    } catch (error) {
      // Never emit URLs, credentials, SDP, identity material or raw tool stderr.
      const safe = /^(Video|Diese Quelle|Dieser Server|Nur |Ungültige|HTTP|Medienquelle|Warteschlange|Lautstärke|Unbekannte|Decoder)/.test(error.message || '') ? error.message.slice(0, 200) : 'Video-Aktion fehlgeschlagen. Verbindung, Rechte und Medienwerkzeuge prüfen.';
      emit({ requestId: body?.requestId, error: safe });
      if (body?.action === 'init') { await close(); input.close(); }
    }
  }).catch(() => close());
});
input.on('close', () => { void chain.finally(close); });
process.on('SIGTERM', () => { void close().finally(() => process.exit(0)); });
process.on('SIGINT', () => { void close().finally(() => process.exit(0)); });
process.on('uncaughtException', () => { emit({ event: 'fatal', error: 'Video-Worker wurde unerwartet beendet.' }); void close().finally(() => process.exit(1)); });
process.on('unhandledRejection', () => { emit({ event: 'fatal', error: 'Video-Worker wurde unerwartet beendet.' }); void close().finally(() => process.exit(1)); });
