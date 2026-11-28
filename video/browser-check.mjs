/** Opt-in native Chromium receiver. Uses the production publisher and encoder; TS signalling is isolated. */
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { createRequire } from 'node:module';
import { VideoPublisher } from './publisher.mjs';
const require = createRequire(new URL('../web/package.json', import.meta.url));
const { chromium } = require('@playwright/test');
const ffmpeg = process.env.CERBERUS_TEST_FFMPEG || 'ffmpeg';
const ffprobe = process.env.CERBERUS_TEST_FFPROBE || 'ffprobe';
const root = await mkdtemp(join(tmpdir(), 'cerberus-native-video-'));
let browser, publisher;
const report = { mode: 'local-native-webrtc', teamSpeakClientVerified: false };
const waitFor = async predicate => { const end=Date.now()+20000; while (!await predicate() && Date.now()<end) await new Promise(r=>setTimeout(r,100)); assert.ok(await predicate(), 'Media condition timed out'); };
try {
  assert.equal(spawnSync(ffmpeg, ['-v','error','-f','lavfi','-i','testsrc2=size=320x180:rate=25','-f','lavfi','-i','sine=frequency=440:sample_rate=48000','-t','45','-c:v','mpeg4','-c:a','aac',join(root,'test.mp4')]).status,0);
  browser=await chromium.launch({headless:true,executablePath:process.env.CERBERUS_BROWSER,args:['--use-angle=swiftshader','--enable-unsafe-swiftshader','--autoplay-policy=no-user-gesture-required']});
  const page=await browser.newPage();
  await page.setContent('<video autoplay playsinline></video>');
  const peer=new EventEmitter(); peer.close=async()=>{};
  await page.exposeFunction('sendAnswer',sdp=>peer.emit('answer',sdp));
  await page.exposeFunction('sendCandidate',c=>peer.emit('candidate',c));
  await page.evaluate(()=>{
    window.pc=new RTCPeerConnection({iceServers:[]}); window.trackEvents=[];
    pc.onicecandidate=e=>{if(e.candidate)void window.sendCandidate(e.candidate.toJSON());};
    pc.ontrack=e=>{
      trackEvents.push({kind:e.track.kind,streamIds:e.streams.map(s=>s.id)});
      // Match clients which render the signalled MediaStream instead of rebuilding it from tracks.
      if(e.streams[0])document.querySelector('video').srcObject=e.streams[0];
    };
    window.mediaStats=async()=>{
      const rows=[...(await pc.getStats()).values()]; const v=document.querySelector('video');
      return {connection:pc.connectionState,tracks:trackEvents,video:rows.filter(r=>r.type==='inbound-rtp'&&r.kind==='video').map(r=>({packets:r.packetsReceived,frames:r.framesDecoded,width:r.frameWidth,height:r.frameHeight})),audio:rows.filter(r=>r.type==='inbound-rtp'&&r.kind==='audio').map(r=>({packets:r.packetsReceived,samples:r.totalSamplesReceived,energy:r.totalAudioEnergy})),render:{width:v.videoWidth,height:v.videoHeight,time:v.currentTime,frames:v.getVideoPlaybackQuality().totalVideoFrames}};
    };
  });
  const candidates=[]; let remoteReady=false;
  peer.sendCandidate=async c=>{if(remoteReady)await page.evaluate(c=>pc.addIceCandidate(c),c);else candidates.push(c);};
  const stream=new EventEmitter();stream.id='native-test';stream.stop=async()=>{};
  publisher=new VideoPublisher({files:root,ffmpeg,ffprobe,maxQueue:10,maxViewers:4,channelId:1},()=>{});
  publisher.client={view:{client:()=>({channelID:1n,type:0})}};
  publisher.streams={publish:async()=>stream};publisher.state.connected=true;
  await publisher.action({action:'play',source:'test.mp4'});
  await waitFor(()=>publisher.state.phase==='PLAYING'||publisher.state.error);assert.equal(publisher.state.error,null);
  const request=new EventEmitter();request.viewer={clientId:1};request.deny=async()=>assert.fail('Denied');
  let negotiation;
  request.accept=async sdp=>{
    report.offerHasSharedStream=/a=msid:(?!- )/.test(sdp);
    negotiation=(async()=>{
      await page.evaluate(async sdp=>{await pc.setRemoteDescription({type:'offer',sdp});await pc.setLocalDescription(await pc.createAnswer());void window.sendAnswer(pc.localDescription.sdp);},sdp);
      remoteReady=true;for(const c of candidates.splice(0))await page.evaluate(c=>pc.addIceCandidate(c),c);
    })();return peer;
  };
  await publisher.accept(publisher.active,request);await negotiation;
  await waitFor(async()=>{const s=await page.evaluate(()=>mediaStats());return s.video[0]?.frames>25&&s.audio[0]?.samples>24000;});
  report.receiver=await page.evaluate(()=>mediaStats());
  assert.ok(report.receiver.tracks.every(t=>t.streamIds.length===1),'Streamless tracks cannot be rendered by MediaStream-based clients');
  assert.equal(new Set(report.receiver.tracks.flatMap(t=>t.streamIds)).size,1,'Audio and video must share a MediaStream');
  await waitFor(async()=>{const s=await page.evaluate(()=>mediaStats());return s.render.width===320&&s.render.frames>25&&s.render.time>1&&s.audio[0]?.energy>0;});
  report.receiver=await page.evaluate(()=>mediaStats());
  await page.evaluate(()=>{const v=document.querySelector('video'),c=document.createElement('canvas');c.width=v.videoWidth;c.height=v.videoHeight;c.getContext('2d').drawImage(v,0,0);window.pixels=c.getContext('2d').getImageData(0,0,c.width,c.height).data;});
  report.pictureHasContent=await page.evaluate(()=>{let min=255,max=0;for(let i=0;i<pixels.length;i+=4){min=Math.min(min,pixels[i]);max=Math.max(max,pixels[i]);}return max-min>100;});assert.ok(report.pictureHasContent);
  report.passed=true;
} catch(error) {report.passed=false;report.error=error.message;process.exitCode=1;}
finally {
  if(publisher){await publisher.cancel();report.publisherCleanedUp=publisher.active===null;}
  await browser?.close();await rm(root,{recursive:true,force:true});
  const result=JSON.stringify(report,null,2)+'\n';if(process.argv[2])await writeFile(process.argv[2],result);process.stdout.write(result);
}
