/** Only fixed messages cross the worker boundary. stderr can contain signed URLs and credentials. */
const messages = {
  YOUTUBE_COOKIES: 'YouTube-Cookie-Datei ungültig oder nicht lesbar. Netscape-Format und music.cookies.file prüfen; außerhalb des Medienverzeichnisses ablegen.',
  YOUTUBE_BOT_CHALLENGE: 'YouTube verlangt eine Bot-Bestätigung für diese Server-IP. Eine gültige YouTube-Cookie-Datei hinterlegen oder erneuern; siehe Video-Anleitung.',
  YOUTUBE_RATE_LIMIT: 'YouTube begrenzt Anfragen von dieser Server-IP (HTTP 429). Bitte später erneut versuchen.',
  YOUTUBE_RESTRICTED: 'Dieses YouTube-Video verlangt eine Anmeldung, Altersfreigabe oder Mitgliedschaft. Bitte eine frei verfügbare Quelle verwenden.',
  YOUTUBE_REGION: 'Dieses YouTube-Video ist am Standort des Bot-Servers nicht verfügbar.',
  YOUTUBE_UNAVAILABLE: 'Dieses YouTube-Video ist privat, entfernt oder nicht verfügbar.',
  YOUTUBE_JS_RUNTIME: 'YouTube-JavaScript-Prüfung fehlgeschlagen. Node und yt-dlp-ejs im Container prüfen; siehe Video-Anleitung.',
  YOUTUBE_FORMAT: 'YouTube liefert kein abrufbares Videoformat. Link und Server-Zugriff prüfen.',
  YOUTUBE_FORBIDDEN: 'YouTube verweigert den Zugriff vom Bot-Server (HTTP 403). Der Abruf ist von dieser Server-IP derzeit nicht möglich.',
  YOUTUBE_TOOL_MISSING: 'yt-dlp konnte nicht gestartet werden. Den Werkzeugpfad und das verwendete Container-Image prüfen.',
  YOUTUBE_TIMEOUT: 'Das Laden der YouTube-Formate hat zu lange gedauert. Bitte später erneut versuchen.',
  YOUTUBE_NETWORK: 'Der Bot-Server erreicht YouTube nicht. Netzwerk, DNS und ausgehendes HTTPS prüfen.',
  YOUTUBE_FAILED: 'YouTube-Auflösung fehlgeschlagen. Link und Zugriff vom Bot-Server prüfen; siehe Video-Anleitung.',
  MEDIA_TOOL_MISSING: 'Das Medienwerkzeug konnte nicht gestartet werden. FFmpeg-/ffprobe-Pfad und Container-Image prüfen.',
  MEDIA_FORBIDDEN: 'Die Videoquelle verweigert den Medienzugriff (HTTP 403). Bitte einen anderen Link verwenden.',
  MEDIA_TIMEOUT: 'Die Prüfung der Videoquelle hat zu lange gedauert. Verbindung und Quelle prüfen.',
  MEDIA_OUTPUT_LIMIT: 'Die Antwort des Medienwerkzeugs ist zu groß.',
  MEDIA_INVALID_OUTPUT: 'Das Medienwerkzeug hat keine gültigen Videodaten geliefert.',
};
export class MediaToolError extends Error {
  constructor(code, fallback) { super(messages[code] || fallback || messages.YOUTUBE_FAILED); this.name = 'MediaToolError'; this.code = code; }
}
export function toolFailure(stderr, { youtube = false, timedOut = false, tooLarge = false, spawnCode, fallback } = {}) {
  if (tooLarge) return new MediaToolError('MEDIA_OUTPUT_LIMIT');
  if (spawnCode) return new MediaToolError(youtube ? 'YOUTUBE_TOOL_MISSING' : 'MEDIA_TOOL_MISSING');
  if (timedOut) return new MediaToolError(youtube ? 'YOUTUBE_TIMEOUT' : 'MEDIA_TIMEOUT');
  if (youtube) {
    // Prefer the real refusal to incidental runtime warnings printed earlier.
    if (/sign in to confirm.*(?:bot|not a robot)|confirm you(?:'|’)?re not a bot/i.test(stderr)) return new MediaToolError('YOUTUBE_BOT_CHALLENGE');
    if (/HTTP(?: Error| error|\/\d(?:\.\d)?)?\s*429|too many requests/i.test(stderr)) return new MediaToolError('YOUTUBE_RATE_LIMIT');
    if (/not available in your country|uploader has not made.*available in your country|geo.?restricted/i.test(stderr)) return new MediaToolError('YOUTUBE_REGION');
    if (/age.restricted|confirm your age|members.only|channel.s members|requires.*(?:login|authentication)|login required/i.test(stderr)) return new MediaToolError('YOUTUBE_RESTRICTED');
    if (/private video|video (?:is )?unavailable|video has been (?:removed|deleted)/i.test(stderr)) return new MediaToolError('YOUTUBE_UNAVAILABLE');
    if (/no supported javascript runtime|unsupported javascript runtime|signature solving failed|n challenge solving failed|challenge solver.*(?:failed|unavailable)|yt.dlp.ejs.*(?:missing|not found)|(?:missing|not found).*yt.dlp.ejs/i.test(stderr)) return new MediaToolError('YOUTUBE_JS_RUNTIME');
    if (/HTTP(?: Error| error|\/\d(?:\.\d)?)?\s*403|403 forbidden/i.test(stderr)) return new MediaToolError('YOUTUBE_FORBIDDEN');
    if (/requested format is not available|no video formats found|only images are available/i.test(stderr)) return new MediaToolError('YOUTUBE_FORMAT');
    if (/temporary failure in name resolution|name or service not known|failed to resolve|network is unreachable|connection timed out|unable to download.*(?:webpage|API page)|certificate verify failed/i.test(stderr)) return new MediaToolError('YOUTUBE_NETWORK');
    return new MediaToolError('YOUTUBE_FAILED');
  }
  if (/HTTP(?: error| Error|\/\d(?:\.\d)?)?\s*403|403 Forbidden/i.test(stderr)) return new MediaToolError('MEDIA_FORBIDDEN');
  return new MediaToolError('MEDIA_FAILED', fallback);
}
