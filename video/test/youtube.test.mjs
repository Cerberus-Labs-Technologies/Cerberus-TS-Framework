import {test} from 'node:test';
import assert from 'node:assert/strict';
import {capture} from '../media.mjs';
import {toolFailure} from '../tool-errors.mjs';

test('tool diagnostics classify refusals without returning source URLs or secrets',()=>{
  for(const [stderr,code] of [
    ['ERROR: Sign in to confirm you’re not a bot','YOUTUBE_BOT_CHALLENGE'],
    ['ERROR: HTTP Error 429: Too Many Requests','YOUTUBE_RATE_LIMIT'],
    ['ERROR: Private video','YOUTUBE_UNAVAILABLE'],
    ['ERROR: Sign in to confirm your age','YOUTUBE_RESTRICTED'],
    ['ERROR: This video is not available in your country','YOUTUBE_REGION'],
    ['WARNING: No supported JavaScript runtime could be found\nERROR: Requested format is not available','YOUTUBE_JS_RUNTIME'],
    ['ERROR: Requested format is not available','YOUTUBE_FORMAT'],
    ['ERROR: HTTP Error 403: Forbidden','YOUTUBE_FORBIDDEN'],
    ['ERROR: connection timed out','YOUTUBE_NETWORK'],
    ['unknown failure','YOUTUBE_FAILED'],
  ]){
    const error=toolFailure(stderr+' https://host.invalid/watch?token=secret\nAuthorization: Bearer private-key',{youtube:true});
    assert.equal(error.code,code);assert.doesNotMatch(error.message,/secret|private-key|https:|Authorization/);
  }
});
test('stderr is drained under load and safe failure survives the process boundary',async()=>{
  const script=`process.stderr.write('x'.repeat(200000));process.stderr.write("ERROR: Sign in to confirm you’re not a bot https://example.invalid/?secret=hidden");process.exitCode=1;`;
  await assert.rejects(capture(process.execPath,['-e',script],undefined,5000,'fallback',{youtube:true}),error=>error.code==='YOUTUBE_BOT_CHALLENGE'&&!error.message.includes('hidden'));
});
test('missing tools, timeouts, huge replies and invalid JSON have distinct safe errors',async()=>{
  await assert.rejects(capture('/missing-cerberus-tool',[],undefined,1000,'fallback',{youtube:true}),error=>error.code==='YOUTUBE_TOOL_MISSING');
  await assert.rejects(capture(process.execPath,['-e','setTimeout(()=>{},10000)'],undefined,50,'fallback',{youtube:true}),error=>error.code==='YOUTUBE_TIMEOUT');
  await assert.rejects(capture(process.execPath,['-e',"process.stdout.write('x'.repeat(2200000))"],undefined,5000,'fallback',{youtube:true}),error=>error.code==='MEDIA_OUTPUT_LIMIT');
  await assert.rejects(capture(process.execPath,['-e',"console.log('credential=hidden')"],undefined,5000),error=>error.code==='MEDIA_INVALID_OUTPUT'&&!error.message.includes('hidden'));
});

test('real yt-dlp projects large metadata for combined and separate formats without captions or fragments', {skip:!process.env.CERBERUS_TEST_YTDLP,timeout:30000}, async()=>{
  const {verifyYoutubeProjection}=await import('../metadata-check.mjs');
  const report=await verifyYoutubeProjection(process.env.CERBERUS_TEST_YTDLP);
  assert.equal(report.passed,true);assert.equal(report.checks,4);assert.ok(report.sourceBytes>10000000);
});

test('publisher exposes a specific safe YouTube failure instead of replacing it with the old generic message',async()=>{
  const {mkdtemp,writeFile,rm}=await import('node:fs/promises');
  const {join}=await import('node:path');const {tmpdir}=await import('node:os');
  const {VideoPublisher}=await import('../publisher.mjs');
  const root=await mkdtemp(join(tmpdir(),'cerberus-youtube-failure-'));
  const executable=join(root,'yt-dlp-fixture');let publisher;
  try {
    await writeFile(executable,`#!${process.execPath}\nprocess.stderr.write("ERROR: Sign in to confirm you're not a bot https://example.invalid?secret=hidden");process.exitCode=1;\n`,{mode:0o700});
    publisher=new VideoPublisher({ytDlp:executable,node:process.execPath,maxQueue:10},()=>{});publisher.state.connected=true;
    await publisher.action({action:'play',source:'https://www.youtube.com/watch?v=fixture'});
    const deadline=Date.now()+5000;while(publisher.state.phase!=='ERROR'&&Date.now()<deadline)await new Promise(r=>setTimeout(r,10));
    assert.equal(publisher.state.phase,'ERROR');assert.match(publisher.state.error,/Bot-Bestätigung/);assert.doesNotMatch(publisher.state.error,/hidden|https:|JavaScript-Runtime prüfen/);
  } finally {await publisher?.close();await rm(root,{recursive:true,force:true});}
});
