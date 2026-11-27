import labs.cerberus.ts.*;
import labs.cerberus.ts.event.*;
import labs.cerberus.ts.query.*;
import labs.cerberus.ts.service.*;
import labs.cerberus.ts.teamspeak.*;
import labs.cerberus.ts.web.*;
import com.fasterxml.jackson.databind.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Creates and removes only uniquely named test objects; never assigns rights to real clients. */
public class WebAdministrationLive {
 static final ObjectMapper json=new ObjectMapper();
 static String token;static int port;static HttpClient client;
 static final List<String> checks=new ArrayList<>();
 static JsonNode request(String method,String path,Object body,int expected)throws Exception {
  var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/web"+path)).timeout(Duration.ofSeconds(30)).header("Authorization","Bearer "+token);
  var response=client.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
  if(response.statusCode()!=expected)throw new IllegalStateException(method+" "+path+" returned "+response.statusCode()+": "+response.body());
  return response.statusCode()==204?json.nullNode():json.readTree(response.body());
 }
 static void require(boolean ok,String message){if(!ok)throw new IllegalStateException(message);}
 public static void main(String[] args)throws Exception {
  if(args.length==0||!args[0].equals("--allow-live"))throw new IllegalArgumentException("Explicit --allow-live is required; use a dedicated test server.");
  String suffix=UUID.randomUUID().toString().substring(0,8),prefix="CL Web QA "+suffix;
  var env=new HashMap<>(System.getenv());env.put("CERBERUS_NICKNAME","Cerberus Web QA "+suffix);
  var config=BotConfig.Companion.load(Path.of(args.length > 1 ? args[1] : "config/music-live.properties"),env);
  var data=Files.createTempDirectory("cerberus-admin-live-");
  var ownedChannels=new ArrayList<Integer>();var ownedGroups=new LinkedHashMap<String,List<Integer>>();
  var cleanupFailures=new ArrayList<String>();
  boolean passed=false;String version="";
  try(var server=new TeamSpeakServer(config.getConnection(),new EventBus(),QueryConnections.INSTANCE::open)) {
   server.connect();version=server.getVersion().get("version");
   int originalChannel=server.clients(true).stream().filter(c->Objects.equals(c.getId(),server.getOwnClientId())).map(Client::getChannelId).findFirst().orElse(1);
   var tokens=new TokenService(data.resolve("tokens.json"),Clock.systemUTC());
   token=tokens.generate(Set.of("GET /api/web/*","POST /api/web/*","PUT /api/web/*","DELETE /api/web/*"),Duration.ofMinutes(30));
   try(var rest=new RestService(tokens,null);var api=new WebAdministrationApi(server,rest);var http=HttpClient.newHttpClient()) {
    client=http;rest.start(new InetSocketAddress("127.0.0.1",0));port=rest.getPort();
    try {
     int parent=request("POST","/channels",Map.of("name",prefix+" P","codec",4,"codecQuality",6,"maxClients",-1),200).get("id").asInt();ownedChannels.add(parent);
     int child=request("POST","/channels",Map.of("name",prefix+" C","parentId",parent,"topic","Web API test"),200).get("id").asInt();ownedChannels.add(child);
     var detail=request("GET","/channels/"+child,null,200);
     request("PUT","/channels/"+child,Map.of("name",prefix+" R","topic","Geändert","description","TS6 Webprüfung\nZeile 2","codecQuality",8,"expectedRevision",detail.get("revision").asText()),200);
     var updated=request("GET","/channels/"+child,null,200);require(updated.get("topic").asText().equals("Geändert"),"Channel topic not updated");
     request("PUT","/channels/"+child,Map.of("topic","Stale","expectedRevision",detail.get("revision").asText()),409);
     request("POST","/channels/"+child+"/move",Map.of("parentId",0,"order",0),204);checks.add("channel create/read/update/conflict/move");
     var catalog=request("GET","/permission-catalog",null,200);int sortId=0,joinId=0;
     for(var permission:catalog){if(permission.get("name").asText().equals("i_group_sort_id"))sortId=permission.get("id").asInt();if(permission.get("name").asText().equals("i_channel_needed_join_power"))joinId=permission.get("id").asInt();}
     require(sortId>0&&joinId>0,"Expected TS6 permissions unavailable");
     request("PUT","/permissions/channel/"+child+"/"+joinId,Map.of("value",5),204);
     require(!request("GET","/permissions/channel/"+child,null,200).isEmpty(),"Channel permission missing");
     request("DELETE","/permissions/channel/"+child+"/"+joinId,null,204);checks.add("channel permission set/read/remove");
     for(String kind:List.of("server","channel")) {
      String name=prefix+(kind.equals("server")?" SG":" CG");
      int id=request("POST","/groups/"+kind,Map.of("name",name),200).get("id").asInt();ownedGroups.computeIfAbsent(kind,k->new ArrayList<>()).add(id);
      request("PUT","/groups/"+kind+"/"+id,Map.of("name",name+" R","expectedName",name),204);
      String permissionPath="/permissions/"+kind+"-group/"+id;
      request("PUT",permissionPath+"/"+sortId,Map.of("value",90),204);
      boolean foundRight=false;for(var p:request("GET",permissionPath,null,200))if(p.get("id").asInt()==sortId&&p.get("value").asInt()==90)foundRight=true;require(foundRight,"Group permission missing");
      request("GET","/groups/"+kind+"/"+id+"/members",null,200);
      int copied=request("POST","/groups/"+kind,Map.of("name",name+" K","copyFrom",id),200).get("id").asInt();ownedGroups.get(kind).add(copied);
      boolean copiedRight=false;for(var p:request("GET","/permissions/"+kind+"-group/"+copied,null,200))if(p.get("id").asInt()==sortId&&p.get("value").asInt()==90)copiedRight=true;require(copiedRight,"Group copy lost rights");
      request("DELETE","/groups/"+kind+"/"+copied,Map.of("expectedName",name+" K"),204);ownedGroups.get(kind).remove(Integer.valueOf(copied));
      request("DELETE",permissionPath+"/"+sortId,null,204);
      request("DELETE","/groups/"+kind+"/"+id,Map.of("expectedName",name+" R"),204);ownedGroups.get(kind).remove(Integer.valueOf(id));
      checks.add(kind+" group create/rename/permissions/copy/empty-members/delete");
     }
     // A Query channel creation may move this probe's own client. Restore only that new Query session.
     if(server.clients(true).stream().anyMatch(c->Objects.equals(c.getId(),server.getOwnClientId())&&ownedChannels.contains(c.getChannelId())))server.move(server.getOwnClientId(),originalChannel,null).checked();
     request("DELETE","/channels/"+child,Map.of("expectedName",prefix+" R"),204);ownedChannels.remove(Integer.valueOf(child));
     request("DELETE","/channels/"+parent,Map.of("expectedName",prefix+" P"),204);ownedChannels.remove(Integer.valueOf(parent));checks.add("empty channel delete");passed=true;
    } finally {
     if(!ownedChannels.isEmpty()&&server.clients(true).stream().anyMatch(c->Objects.equals(c.getId(),server.getOwnClientId())&&ownedChannels.contains(c.getChannelId())))server.move(server.getOwnClientId(),originalChannel,null).checked();
     var reversed=new ArrayList<>(ownedChannels);Collections.reverse(reversed);
     for(int id:reversed)try{var found=server.channels(true).stream().filter(c->c.getId()==id).findFirst();if(found.isPresent()&&found.get().getName().startsWith(prefix))server.execute(new QueryCommand("channeldelete",Map.of("cid",""+id,"force","0"),Set.of(),List.of(),QueryScope.VIRTUAL_SERVER)).checked();}catch(Exception e){cleanupFailures.add("channel "+id+": "+e.getClass().getSimpleName());}
     for(var item:ownedGroups.entrySet())for(int id:item.getValue())try{String kind=item.getKey(),key=kind.equals("server")?"sgid":"cgid";server.execute(new QueryCommand(kind+"groupdel",Map.of(key,""+id,"force","0"),Set.of(),List.of(),QueryScope.VIRTUAL_SERVER)).checked();}catch(Exception e){cleanupFailures.add(item.getKey()+" group "+id+": "+e.getClass().getSimpleName());}
    }
   } finally {
    System.out.println(json.writeValueAsString(Map.of("version",version,"checks",checks,"passed",passed&&cleanupFailures.isEmpty(),"cleanupFailures",cleanupFailures,"realClientMembershipsChanged",false)));
   }
  }
  require(passed&&cleanupFailures.isEmpty(),"Live administration or cleanup failed");
 }
}
