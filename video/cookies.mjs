import { open, realpath, mkdtemp, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, sep } from 'node:path';
import { MediaToolError } from './tool-errors.mjs';

/** Each lookup owns a private copy: yt-dlp rewrites its jar, so never share or mutate the uploaded original. */
export async function withYoutubeCookies(config, run) {
  if (!config.cookiesFile) return run([]);
  let directory;
  try {
    let bytes;
    try {
      const file = await realpath(config.cookiesFile);
      const mediaRoot = await realpath(config.files);
      if (file === mediaRoot || file.startsWith(mediaRoot + sep)) throw new Error();
      const handle = await open(file, 'r');
      try {
        const stat = await handle.stat();
        if (!stat.isFile() || stat.size > 2 * 1024 * 1024) throw new Error();
        const buffer = Buffer.alloc(2 * 1024 * 1024 + 1);
        const { bytesRead } = await handle.read(buffer, 0, buffer.length, 0);
        bytes = buffer.subarray(0, bytesRead);
        if (bytes.length > 2 * 1024 * 1024 || !/^# (?:Netscape HTTP Cookie File|HTTP Cookie File)\r?\n/.test(bytes.toString('utf8', 0, 80))) throw new Error();
      } finally { await handle.close(); }
      directory = await mkdtemp(join(tmpdir(), 'cerberus-youtube-'));
      await writeFile(join(directory, 'cookies.txt'), bytes, { mode: 0o600, flag: 'wx' });
    } catch { throw new MediaToolError('YOUTUBE_COOKIES'); }
    return await run(['--cookies', join(directory, 'cookies.txt')]);
  } finally { if (directory) await rm(directory, { recursive: true, force: true }); }
}
