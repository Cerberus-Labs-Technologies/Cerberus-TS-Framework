import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, readFile, writeFile, stat, access, symlink, rm } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { withYoutubeCookies } from '../cookies.mjs';
import { resolveSource } from '../media.mjs';
const fixture='# Netscape HTTP Cookie File\n.youtube.com\tTRUE\t/\tTRUE\t2147483647\tTEST\tfictional-session\n';
test('optional cookies use isolated private copies and preserve the original across concurrent rewrites and failures',async()=>{
  const root=await mkdtemp(join(tmpdir(),'cerberus-cookie-test-'));const copies=[];
  try {
    const files=join(root,'files');await mkdir(files);const source=join(root,'cookies.txt');await writeFile(source,fixture);
    assert.deepEqual(await withYoutubeCookies({},async args=>args),[]);
    const config={files,cookiesFile:source};
    await Promise.all([1,2].map(()=>withYoutubeCookies(config,async args=>{
      assert.equal(args[0],'--cookies');copies.push(args[1]);assert.equal(await readFile(args[1],'utf8'),fixture);
      assert.equal((await stat(args[1])).mode&0o777,0o600);
      assert.equal((await stat(join(args[1],'..'))).mode&0o777,0o700);
      await writeFile(args[1],'rewritten');await new Promise(r=>setTimeout(r,10));
    })));
    assert.equal(new Set(copies).size,2);assert.equal(await readFile(source,'utf8'),fixture);
    await assert.rejects(withYoutubeCookies(config,async args=>{copies.push(args[1]);throw new Error('expected');}),/expected/);
    for(const file of copies)await assert.rejects(access(file));
  }finally{await rm(root,{recursive:true,force:true});}
});
test('invalid, missing, oversized and media-accessible cookie files fail without revealing contents or paths',async()=>{
  const root=await mkdtemp(join(tmpdir(),'cerberus-cookie-reject-'));
  try {
    const files=join(root,'files');await mkdir(files);
    const file=join(root,'secret.txt');await writeFile(file,'secret-invalid-cookie');
    const check=path=>assert.rejects(withYoutubeCookies({files,cookiesFile:path},()=>assert.fail('must not run')),error=>error.code==='YOUTUBE_COOKIES'&&!error.message.includes(root)&&!error.message.includes('secret-invalid'));
    await check(file);await check(join(root,'missing'));await check(root);
    await writeFile(file,fixture+'x'.repeat(2*1024*1024));await check(file);
    await writeFile(join(files,'hidden.mp4'),fixture);await check(join(files,'hidden.mp4'));
    await symlink(join(files,'hidden.mp4'),join(root,'link.txt'));await check(join(root,'link.txt'));
  }finally{await rm(root,{recursive:true,force:true});}
});
test('video resolver passes only the private copy to yt-dlp and removes it after extraction',async()=>{
  const root=await mkdtemp(join(tmpdir(),'cerberus-cookie-resolver-'));
  try {
    const files=join(root,'files');await mkdir(files);const source=join(root,'cookies.txt');await writeFile(source,fixture);
    const marker=join(root,'marker');const tool=join(root,'tool');
    await writeFile(tool,`#!${process.execPath}\nimport fs from 'node:fs';\nconst args=process.argv.slice(2);if(args.includes('--show_streams')||args.includes('-show_streams'))console.log(JSON.stringify({streams:[{codec_type:'video'},{codec_type:'audio'}]}));else {const file=args[args.indexOf('--cookies')+1];fs.writeFileSync(${JSON.stringify(marker)},file);fs.writeFileSync(file,'updated');console.log(JSON.stringify({title:'Fixture',url:'https://media.example.invalid/clip.mp4',http_headers:{}}));}\n`,{mode:0o700});
    const media=await resolveSource('https://www.youtube.com/watch?v=fixture',{files,cookiesFile:source,ytDlp:tool,node:process.execPath,ffprobe:tool});
    assert.equal(media.title,'Fixture');assert.equal(media.video,0);assert.equal(media.audio,0);
    const copy=await readFile(marker,'utf8');assert.notEqual(copy,source);await assert.rejects(access(copy));assert.equal(await readFile(source,'utf8'),fixture);
  }finally{await rm(root,{recursive:true,force:true});}
});
