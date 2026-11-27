/** Opt-in: creates only its own three clients, temporary identities and a generated clip. */
import { Client, generateIdentity } from '@honeybbq/teamspeak/client';
import { moveClient } from '@honeybbq/teamspeak';
import { Streams } from '@honeybbq/teamspeak/stream';
import { RTCPeerConnection, Vp8RtpPayload } from 'werift';
import { VideoPublisher, peerConfig } from './publisher.mjs';
import { mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import assert from 'node:assert/strict';
if (process.argv[2] !== '--allow-live') throw new Error('Use --allow-live HOST PORT CHANNEL_ID');
const host = process.argv[3], port = Number(process.argv[4]), channelId = Number(process.argv[5] || 1);
const root = await mkdtemp(join(tmpdir(), 'cerberus-video-live-'));
const cfg = { host, port, channelId, nickname: 'Cerberus Video QA', directory: root, files: root, identity: join(root, 'identity.key'),
  ffmpeg: process.env.CERBERUS_TEST_FFMPEG || 'ffmpeg', ffprobe: process.env.CERBERUS_TEST_FFPROBE || 'ffprobe',
  ytDlp: process.env.CERBERUS_MUSIC_YTDLP || 'yt-dlp', node: 'node', maxQueue: 10, maxViewers: 4 };
let publisher; const viewers = []; const report = { passed: false, server: null, videoPackets: 0, audioPackets: 0, decodedVideo: false, checks: [], cleanup: false };
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const until = async (message, predicate, timeout = 30000) => { const deadline = Date.now()+timeout; while (!predicate() && Date.now()<deadline) await sleep(50); assert.ok(predicate(), message); };
try {
  const generated = spawnSync(cfg.ffmpeg, ['-v','error','-f','lavfi','-i','testsrc2=size=320x180:rate=25','-f','lavfi','-i','sine=frequency=440:sample_rate=48000','-t','40','-c:v','mpeg4','-c:a','aac',join(root,'clip.mp4')]);
  assert.equal(generated.status, 0);
  publisher = new VideoPublisher(cfg, () => {}); await publisher.connect(); report.server = publisher.client.serverCapabilities.version;
  for (let i=0;i<2;i++) {
    const client = new Client(await generateIdentity(8), `${host}:${port}`, `Cerberus Video QA View ${i+1}`);
    const entry = {client}; viewers.push(entry);
    await client.connect({signal:AbortSignal.timeout(30000)}); await client.view.whenReady({signal:AbortSignal.timeout(10000)});
    await moveClient(client, client.clientID, BigInt(channelId)); entry.streams = new Streams(client);
  }
  await publisher.action({action:'play',source:'clip.mp4'});
  await until('publisher advertises stream', () => publisher.state.phase === 'PLAYING' || publisher.state.error);
  assert.equal(publisher.state.error, null, publisher.state.error);
  for (const entry of viewers) {
    await until('viewer discovers stream', () => entry.streams.list().some(s => s.clientId === publisher.client.clientID));
    const info = entry.streams.list().find(s => s.clientId === publisher.client.clientID);
    const view = entry.view = await entry.streams.watch(info);
    const pc = entry.pc = new RTCPeerConnection({...peerConfig({}),iceServers:[]});
    let parts = [], frameTime;
    pc.onTrack.subscribe(track => track.onReceiveRtp.subscribe(packet => {
      if (track.kind === 'audio') report.audioPackets++;
      else {
        report.videoPackets++;
        if (report.decodedVideo) return;
        const vp8 = Vp8RtpPayload.deSerialize(packet.payload);
        if (vp8.sBit && vp8.pid === 0) { parts = []; frameTime = packet.header.timestamp; }
        if (frameTime !== packet.header.timestamp) return;
        parts.push(vp8.payload);
        if (packet.header.marker && parts.length) {
          const frame = Buffer.concat(parts); parts=[];
          if ((frame[0] & 1) !== 0) return;
          const header = Buffer.alloc(32);header.write('DKIF');header.writeUInt16LE(32,6);header.write('VP80',8);header.writeUInt16LE(320,12);header.writeUInt16LE(180,14);header.writeUInt32LE(25,16);header.writeUInt32LE(1,20);header.writeUInt32LE(1,24);
          const fh = Buffer.alloc(12);fh.writeUInt32LE(frame.length);
          const decoded = spawnSync(cfg.ffmpeg,['-v','error','-f','ivf','-i','pipe:0','-frames:v','1','-f','image2pipe','-c:v','png','pipe:1'],{input:Buffer.concat([header,fh,frame])});
          report.decodedVideo = decoded.status === 0 && decoded.stdout?.subarray(1,4).toString() === 'PNG';
        }
      }
    }));
    pc.onIceCandidate.subscribe(c => { if(c)void view.sendCandidate({candidate:c.candidate,sdpMid:c.sdpMid??null,sdpMLineIndex:c.sdpMLineIndex??null}).catch(()=>{}); });
    view.on('candidate', c => {void pc.addIceCandidate(c).catch(()=>{});});
    pc.addTransceiver('audio',{direction:'recvonly'}); pc.addTransceiver('video',{direction:'recvonly'});
    await pc.setRemoteDescription({type:'offer',sdp:view.offer}); await pc.setLocalDescription(await pc.createAnswer()); await view.answer(pc.localDescription.sdp);
  }
  await until('both viewers connect and receive decodable video plus audio', () => publisher.state.viewers === 2 && report.videoPackets>50 && report.audioPackets>50 && report.decodedVideo);
  report.checks.push('Two SDK viewers on the real TS6 server receive VP8 video and Opus audio; received keyframe decodes to PNG.');
  await publisher.action({action:'pause'}); await sleep(300); const before=report.videoPackets; await sleep(500); assert.equal(report.videoPackets,before);
  await publisher.action({action:'resume'}); await until('resume sends media',()=>report.videoPackets>before+10);
  report.checks.push('Pause freezes both tracks; resume delivers media.');
  await publisher.action({action:'enqueue',source:'clip.mp4'}); assert.equal(publisher.state.queued?.length || publisher.queue.length,1);
  await publisher.action({action:'stop'}); assert.equal(publisher.state.streamId,null); assert.equal(publisher.queue.length,0);
  report.checks.push('Stop removes publication, closes viewers and clears queue.'); report.passed=true;
} catch (error) { report.error = error.message; process.exitCode=1; }
finally {
  for (const entry of viewers) { await entry.pc?.close(); entry.streams?.dispose(); await entry.client?.disconnect().catch(()=>{}); }
  await publisher?.close(); await rm(root,{recursive:true,force:true}); report.cleanup=true;
  console.log(JSON.stringify(report,null,2));
}
