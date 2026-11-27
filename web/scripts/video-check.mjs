import fs from 'node:fs/promises';
import assert from 'node:assert/strict';
import {chromium,expect} from '@playwright/test';
const metadata=JSON.parse(await fs.readFile(process.argv[2],'utf8'));
if(metadata.mode!=='fixture')throw new Error('Use the isolated fixture.');
const results=[],uncaught=[];
const browser=await chromium.launch({headless:true,executablePath:process.env.CERBERUS_BROWSER,args:['--use-angle=swiftshader','--enable-unsafe-swiftshader']});
const context=await browser.newContext({viewport:{width:1440,height:900},locale:'de-DE',reducedMotion:'reduce'});
const page=await context.newPage();page.on('pageerror',error=>uncaught.push(error.message));
const base=metadata.url.replace(/\/$/,'');
async function check(name,action){try{await action();results.push({name,passed:true});console.log('PASS '+name);}catch(error){results.push({name,passed:false,error:error.message.replaceAll(metadata.admin,'[token]').replaceAll(metadata.read,'[token]')});throw error;}}
async function api(path,body,token=metadata.admin,method=body?'POST':'GET'){return fetch(base+'/api/plugins/music'+path,{method,headers:{Authorization:`Bearer ${token}`,'Content-Type':'application/json'},...(body?{body:JSON.stringify(body)}:{})});}
async function notice(text){await expect(page.locator('#video-notice')).toHaveText(text);}
try{
  await page.goto(base);await page.getByLabel('Zugriffstoken',{exact:true}).fill(metadata.admin);await page.getByRole('button',{name:'Mit Token anmelden',exact:true}).click();
  await page.getByRole('navigation',{name:'Hauptnavigation'}).getByRole('link',{name:'Musik-Bots',exact:true}).click();
  await check('video module loads as a registered authenticated plugin asset',async()=>{await page.getByRole('button',{name:'Video-Bots (Vorschau)',exact:true}).click();await expect(page.getByRole('heading',{name:'Video abspielen',exact:true})).toBeVisible();});
  await check('read tokens cannot create, change or delete video clients',async()=>{for(const [path,body,method]of [['/video',{id:'denied',channelId:7},'POST'],['/video/denied/actions',{action:'play',source:'clip.mp4'},'POST'],['/video/denied',null,'DELETE']])assert.equal((await api(path,body,metadata.read,method)).status,401);});
  await check('create video bot through real HTTP and worker IPC',async()=>{await page.getByLabel('Video-Bot-ID',{exact:true}).fill('videoqa');await page.getByLabel('Video-Anzeigename',{exact:true}).fill('Cerberus Video QA');await page.getByRole('button',{name:'Video-Bot verbinden',exact:true}).click();await notice('Video-Bot verbunden.');await expect(page.locator('#video-status')).toContainText('Client 142');});
  await check('video playback exposes stream title and viewer count',async()=>{await page.getByLabel('Videoquelle',{exact:true}).fill('clip.mp4');await page.getByRole('button',{name:'Video jetzt abspielen',exact:true}).click();await notice('Video wird geladen.');await expect(page.locator('#video-title')).toHaveText('Video aus HTTP-Fixture');await expect(page.locator('#video-status')).toContainText('2 / 4 Zuschauer');});
  await check('polling keeps video input focus and unfinished source',async()=>{const input=page.getByLabel('Videoquelle',{exact:true});await input.fill('unfinished-url');await page.waitForTimeout(4500);await expect(input).toBeFocused();await expect(input).toHaveValue('unfinished-url');});
  await check('video pause and resume reflect worker state',async()=>{await page.getByRole('button',{name:'Video pausieren',exact:true}).click();await expect(page.locator('#video-phase')).toHaveText('Pausiert');await page.getByRole('button',{name:'Video fortsetzen',exact:true}).click();await expect(page.locator('#video-phase')).toHaveText('Stream läuft');});
  await check('enqueue and skip use the selected video bot',async()=>{await page.getByLabel('Videoquelle',{exact:true}).fill('next.mp4');await page.getByRole('button',{name:'Video zur Queue hinzufügen',exact:true}).click();await expect(page.locator('#video-queue')).toContainText('next.mp4');await page.getByRole('button',{name:'Nächstes Video',exact:true}).click();await expect(page.locator('#video-current')).toHaveText('next.mp4');});
  await check('volume command reaches worker',async()=>{await page.getByLabel('Video-Lautstärke in %',{exact:true}).fill('35');await page.getByRole('button',{name:'Video-Lautstärke setzen',exact:true}).click();await notice('Video-Lautstärke gesetzt.');const response=await api('/video');assert.equal((await response.json()).bots[0].volume,35);});
  await check('moving video bot ends publication and queue',async()=>{await page.getByRole('button',{name:'Video-Bot verschieben',exact:true}).click();await notice('Video-Bot verschoben.');await expect(page.locator('#video-phase')).toHaveText('Bereit');await expect(page.locator('#video-current')).toHaveText('');});
  for(const width of [1440,1024,820,768,390,320])await check(`video controls fit at ${width}px`,async()=>{await page.setViewportSize({width,height:900});await expect(page.getByLabel('Videoquelle',{exact:true})).toBeVisible();assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1));const tooSmall=await page.locator('#video-controls button,#video-connection button').evaluateAll(elements=>elements.filter(e=>e.getBoundingClientRect().height<43.5).map(e=>e.textContent));assert.deepEqual(tooSmall,[]);});
  await check('delete disconnects worker and clears selection',async()=>{await page.getByRole('button',{name:'Video-Bot trennen',exact:true}).click();await notice('Video-Bot getrennt.');await expect(page.locator('#video-empty')).toBeVisible();assert.equal((await api('/video/videoqa/actions',{action:'stop'})).status,404);});
  await check('no uncaught browser exceptions',async()=>assert.deepEqual(uncaught,[]));
}finally{
  await api('/video/videoqa',null,metadata.admin,'DELETE').catch(()=>{});
  await fs.writeFile(process.argv[3]||'../docs/validation/video-browser.json',JSON.stringify({mode:'fixture',checks:results,uncaught},null,2)+'\n');await browser.close();
}
