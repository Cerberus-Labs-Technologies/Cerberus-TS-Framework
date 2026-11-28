import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, writeFile, mkdir, symlink, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { resolveSource, MediaEncoder } from '../media.mjs';
import { canWatch, peerConfig, candidate, addMediaTracks, publicOffer } from '../publisher.mjs';
import { RTCPeerConnection } from 'werift';

const ffmpeg = process.env.CERBERUS_TEST_FFMPEG;
const ffprobe = process.env.CERBERUS_TEST_FFPROBE;
test('viewer policy fails closed for other channels, query clients, unknown clients and capacity', () => {
  const client = { view: { client: id => ({ 1: { channelID: 7n, type: 0 }, 2: { channelID: 8n, type: 0 }, 3: { channelID: 7n, type: 1 } })[id] } };
  assert.equal(canWatch(client, 1, 7, 0, 4), true);
  for (const id of [2, 3, 4]) assert.equal(canWatch(client, id, 7, 0, 4), false);
  assert.equal(canWatch(client, 1, 7, 4, 4), false);
});
test('offer includes audio first and interoperable VP8/Opus only', async () => {
  const pc = new RTCPeerConnection({ ...peerConfig({}), iceServers: [] });
  try {
    addMediaTracks(pc);
    const offer = await pc.createOffer();
    assert.ok(offer.sdp.indexOf('m=audio') < offer.sdp.indexOf('m=video'));
    assert.match(offer.sdp, /opus\/48000\/2/); assert.match(offer.sdp, /VP8\/90000/);
    assert.ok(offer.sdp.length < 6000);
    const ids = [...offer.sdp.matchAll(/^a=msid:([^ ]+) /gm)].map(m => m[1]);
    assert.equal(ids.length, 2); assert.notEqual(ids[0], '-'); assert.equal(ids[0], ids[1]);
    assert.equal((offer.sdp.match(/a=sendonly/g)||[]).length, 2);
  } finally { await pc.close(); }
});
test('sources reject protocols, credentials, traversal and symlinks', async () => {
  const root = await mkdtemp(join(tmpdir(), 'cerberus-video-source-'));
  try {
    const files = join(root, 'files'); await mkdir(files);
    await writeFile(join(root, 'outside.mp4'), 'x'); await symlink(join(root, 'outside.mp4'), join(files, 'link.mp4'));
    for (const source of ['file:///etc/passwd', 'https://user:secret@example.com/x', '../outside.mp4', 'link.mp4', 'https://example.com/\nsecret'])
      await assert.rejects(resolveSource(source, { files }, new AbortController().signal));
  } finally { await rm(root, { recursive: true, force: true }); }
});
test('real FFmpeg sends both RTP tracks and responds to stop without leaked processes', { skip: !ffmpeg || !ffprobe, timeout: 30000 }, async () => {
  const { spawnSync } = await import('node:child_process');
  const root = await mkdtemp(join(tmpdir(), 'cerberus-video-media-'));
  let encoder;
  try {
    const file = join(root, 'test.mp4');
    const generated = spawnSync(ffmpeg, ['-v','error','-f','lavfi','-i','testsrc2=size=320x180:rate=25','-f','lavfi','-i','sine=frequency=440:sample_rate=48000','-t','12','-c:v','mpeg4','-c:a','aac',file]);
    assert.equal(generated.status, 0, generated.stderr?.toString());
    const config = { files: root, ffmpeg, ffprobe, volume: 25 };
    const media = await resolveSource('test.mp4', config, new AbortController().signal);
    const packets = { video: 0, audio: 0 }; let ended;
    encoder = await MediaEncoder.open(media, config, kind => packets[kind]++, ok => { ended = ok; });
    const until = async predicate => { const deadline = Date.now() + 8000; while (!predicate() && Date.now() < deadline) await new Promise(r => setTimeout(r, 20)); assert.ok(predicate(), JSON.stringify({packets, ended})); };
    await until(() => packets.video >= 15 && packets.audio >= 15);
    encoder.pause(true); await new Promise(r => setTimeout(r, 200));
    const snapshot = { ...packets }; await new Promise(r => setTimeout(r, 300)); assert.deepEqual(packets, snapshot);
    encoder.pause(false); encoder.volume(0);
    await until(() => packets.audio > snapshot.audio + 10);
    await encoder.close(); assert.notEqual(encoder.child.exitCode, null);
    assert.equal(ended, undefined);
  } finally { await encoder?.close(); await rm(root, { recursive: true, force: true }); }
});

test('Pelican candidate rewrites only host addresses and preserves the allocated UDP port', () => {
  const input = { candidate: 'candidate:1 1 udp 2130706431 172.18.0.4 24903 typ host', sdpMid: '0', sdpMLineIndex: 0 };
  assert.equal(candidate(input, '65.108.107.32').candidate, 'candidate:1 1 udp 2130706431 65.108.107.32 24903 typ host');
  const reflexive = { ...input, candidate: 'candidate:2 1 udp 123 65.108.107.32 44000 typ srflx' };
  assert.equal(candidate(reflexive, '65.108.107.32').candidate, reflexive.candidate);
});

test('video without audio receives silence and ends cleanly at file EOF', { skip: !ffmpeg || !ffprobe, timeout: 15000 }, async () => {
  const { spawnSync } = await import('node:child_process');
  const root = await mkdtemp(join(tmpdir(), 'cerberus-video-silent-')); let encoder;
  try {
    assert.equal(spawnSync(ffmpeg, ['-v','error','-f','lavfi','-i','testsrc2=size=320x180:rate=25','-t','1','-c:v','mpeg4',join(root,'silent.mp4')]).status, 0);
    const config = { files: root, ffmpeg, ffprobe, volume: 25 };
    const media = await resolveSource('silent.mp4', config, new AbortController().signal);
    assert.equal(media.audio, -1);
    const packets = {video:0,audio:0}; let finished;
    encoder = await MediaEncoder.open(media, config, kind => packets[kind]++, ok => { finished=ok; });
    const deadline=Date.now()+8000;while(finished===undefined&&Date.now()<deadline)await new Promise(r=>setTimeout(r,20));
    assert.equal(finished,true);assert.ok(packets.video>0&&packets.audio>0);
  } finally {await encoder?.close();await rm(root,{recursive:true,force:true});}
});

test('FFmpeg runtime volume command actually mutes decoded PCM', {skip:!ffmpeg,timeout:10000}, async()=>{
  const {spawn}=await import('node:child_process'); const {once}=await import('node:events');
  const child=spawn(ffmpeg,['-v','error','-re','-f','lavfi','-i','sine=frequency=440:sample_rate=48000','-t','2.5','-af','volume@cerberus=0.25','-f','s16le','pipe:1']);
  const started=Date.now();let before=0,after=0;
  child.stdout.on('data',bytes=>{let sum=0;for(let i=0;i<bytes.length-1;i+=2)sum+=bytes.readInt16LE(i)**2;const rms=Math.sqrt(sum/(bytes.length/2));if(Date.now()-started<700)before=Math.max(before,rms);if(Date.now()-started>1400)after=Math.max(after,rms);});
  child.stderr.resume();
  const timer=setTimeout(()=>MediaEncoder.prototype.volume.call({closed:false,child},0),900);
  try{const [code]=await once(child,'close');assert.equal(code,0);assert.ok(before>500);assert.equal(after,0);}finally{clearTimeout(timer);if(child.exitCode===null)child.kill('SIGKILL');}
});

test('public IPv4 is present in embedded offer candidates as well as trickle candidates', () => {
  const sdp = 'v=0\r\na=candidate:1 1 udp 123 172.18.0.4 24903 typ host\r\na=candidate:2 1 udp 12 65.108.107.32 44000 typ srflx\r\n';
  const offer = publicOffer(sdp, '65.108.107.32');
  assert.ok(offer.includes('65.108.107.32 24903 typ host')); assert.ok(!offer.includes('172.18.0.4'));
  assert.ok(offer.includes('65.108.107.32 44000 typ srflx')); assert.equal(publicOffer(sdp, null), sdp);
});

test('failed viewer connection becomes a visible safe error and releases its reserved slot', {timeout:15000}, async()=>{
  const {EventEmitter}=await import('node:events'); const {VideoPublisher}=await import('../publisher.mjs');
  const publisher=new VideoPublisher({channelId:1,maxViewers:4,stun:'stun:127.0.0.1:9'},()=>{});
  const active={peers:new Map(),abort:new AbortController()};publisher.active=active;publisher.state.phase='PLAYING';
  publisher.client={view:{client:()=>({channelID:1n,type:0})}};
  const request=new EventEmitter(),peer=new EventEmitter();request.viewer={clientId:1};request.deny=async()=>assert.fail('Denied');
  peer.sendCandidate=async()=>{};peer.close=async()=>{};request.accept=async()=>peer;
  try{
    await publisher.accept(active,request);
    const entry=active.peers.get(1);assert.ok(entry);
    entry.pc.connectionStateChange.execute('failed');
    assert.match(publisher.state.viewerError,/UDP-Allocations/);assert.equal(active.peers.size,0);
    assert.equal(publisher.state.phase,'PLAYING');assert.equal(publisher.state.viewers,0);
    await entry.pc.close();
  }finally{await publisher.cancel();}
});
