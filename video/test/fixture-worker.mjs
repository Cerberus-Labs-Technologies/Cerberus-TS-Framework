// Deterministic IPC worker for the local HTTP/browser harness. No network access.
import {createInterface} from 'node:readline';
if (process.env.CERBERUS_VIDEO_TEST_FIXTURE !== '1') process.exit(2);
const input = createInterface({input:process.stdin});
const send = data => process.stdout.write(JSON.stringify(data)+'\n');
let state={connected:true,clientId:142,channelId:1,current:null,title:null,phase:'STOPPED',paused:false,volume:25,queued:[],viewers:0,streamId:null,error:null,viewerError:null};
input.on('line', line => {
  const body=JSON.parse(line);
  if(body.action==='close'){input.close();return;}
  switch(body.action){
    case 'init':state.channelId=body.config.channelId;break;
    case 'play':state={...state,current:body.source,title:'Video aus HTTP-Fixture',phase:'PLAYING',paused:false,queued:[],streamId:'fixture-stream',viewers:2,error:null};break;
    case 'enqueue':state.queued.push(body.source);break;
    case 'pause':state.paused=true;state.phase='PAUSED';break;
    case 'resume':state.paused=false;state.phase='PLAYING';break;
    case 'volume':state.volume=body.volume;break;
    case 'move':state.channelId=body.channelId; // falls through: moving stops publication.
    case 'stop':state={...state,current:null,title:null,phase:'STOPPED',paused:false,queued:[],streamId:null,viewers:0};break;
    case 'skip':state.current=state.queued.shift()||null;state.phase=state.current?'PLAYING':'STOPPED';break;
    default:send({requestId:body.requestId,error:'Unbekannte Video-Aktion.'});return;
  }
  send({requestId:body.requestId,status:state});
});
input.on('close',()=>process.exit(0));
