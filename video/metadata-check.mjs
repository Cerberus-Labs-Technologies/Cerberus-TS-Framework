/** Offline installation/regression check. No TeamSpeak connection or YouTube request. */
import assert from 'node:assert/strict';
import {mkdtemp,writeFile,rm} from 'node:fs/promises';
import {join} from 'node:path';
import {tmpdir} from 'node:os';
import {pathToFileURL} from 'node:url';
import {capture,YOUTUBE_INFO_TEMPLATE} from './media.mjs';

export async function verifyYoutubeProjection(executable) {
  const root=await mkdtemp(join(tmpdir(),'cerberus-youtube-metadata-'));
  const report={passed:false,sourceBytes:0,videoBytes:[],audioBytes:[],checks:0};
  try {
    const common={id:'fixture',title:'Quotes " & Björk',extractor:'youtube',extractor_key:'Youtube',webpage_url:'https://www.youtube.com/watch?v=fixture',
      http_headers:{'User-Agent':'fixture'},automatic_captions:{en:[{url:'https://example.invalid/captions',ext:'vtt',data:'x'.repeat(11000000)}]}};
    const video={format_id:'v',url:'https://example.invalid/video.mp4',ext:'mp4',vcodec:'avc1',acodec:'none',height:720,http_headers:{'User-Agent':'video'},
      fragments:[{url:'https://example.invalid/fragment',data:'x'.repeat(3000000)}]};
    const audio={format_id:'a',url:'https://example.invalid/audio.m4a',ext:'m4a',acodec:'aac',vcodec:'none',http_headers:{'User-Agent':'audio'}};
    const file=join(root,'info.json');
    for (const separate of [true,false]) {
      const formats=separate?[video,audio]:[{...video,format_id:'av',acodec:'aac'}];
      const input=JSON.stringify({...common,formats});report.sourceBytes=Math.max(report.sourceBytes,Buffer.byteLength(input));
      await writeFile(file,input);
      const info=await capture(executable,['--ignore-config','--load-info-json',file,'--simulate','--output-na-placeholder','null','--print',YOUTUBE_INFO_TEMPLATE,'-f',separate?'v+a':'av'],undefined,15000);
      assert.equal(info.title,common.title);assert.ok(JSON.stringify(info).length<10000);assert.equal(info.automatic_captions,undefined);
      if(separate){assert.equal(info.url,null);assert.equal(info.requested_formats.length,2);assert.deepEqual(Object.keys(info.requested_formats[0]).sort(),['http_headers','url']);}
      else{assert.equal(info.url,video.url);assert.deepEqual(info.requested_formats,[]);}
      report.videoBytes.push(Buffer.byteLength(JSON.stringify(info)));report.checks++;
      const music=await capture(executable,['--ignore-config','--load-info-json',file,'--simulate','--print','%(.{url,http_headers,title,artist,uploader,thumbnail})j','-f',separate?'a':'av'],undefined,15000);
      assert.ok(JSON.stringify(music).length<10000);assert.equal(music.title,common.title);assert.equal(music.url,separate?audio.url:video.url);
      report.audioBytes.push(Buffer.byteLength(JSON.stringify(music)));report.checks++;
    }
    report.passed=true;return report;
  } finally { await rm(root,{recursive:true,force:true}); }
}
if(process.argv[1]&&import.meta.url===pathToFileURL(process.argv[1]).href) {
  try { console.log(JSON.stringify(await verifyYoutubeProjection(process.env.CERBERUS_TEST_YTDLP||process.env.CERBERUS_MUSIC_YTDLP||'yt-dlp'))); }
  catch(error) { console.error(JSON.stringify({passed:false,code:error.code||'METADATA_CHECK_FAILED'}));process.exitCode=1; }
}
