package labs.cerberus.ts;

import labs.cerberus.ts.query.*;
import java.util.*;
import java.util.concurrent.*;

/** Stateful protocol partner shared by HTTP tests and the isolated browser harness; never used in production. */
public final class AdministrationFixture implements QueryConnection {
    private volatile boolean open = true;
    public final List<QueryCommand> commands = new CopyOnWriteArrayList<>();
    public volatile CountDownLatch blocked, release;
    private int nextChannel = 40, nextGroup = 40;
    private final Map<Integer, Map<String,String>> channels = new LinkedHashMap<>();
    private final Map<String, Map<Integer, Map<String,String>>> groups = new HashMap<>();
    private final Map<String, Map<Integer,Map<String,String>>> permissions = new HashMap<>();
    private final Map<Integer, Set<Integer>> serverMembers = new HashMap<>();
    private final Map<String, Integer> channelMembers = new HashMap<>();
    public AdministrationFixture() {
        channels.put(1, channel(1,0,"Lobby")); channels.put(2, channel(2,1,"Musik")); channels.put(3, channel(3,0,"Support"));
        groups.put("servergroup", new LinkedHashMap<>()); groups.put("channelgroup", new LinkedHashMap<>());
        addGroup("servergroup", 6,"Server Admin",1); addGroup("servergroup",7,"Mitglied",1); addGroup("servergroup",8,"Query Admin",2);
        addGroup("channelgroup",5,"Channel Admin",1); addGroup("channelgroup",6,"Guest",1);
        permissions.put("servergroup:6", new LinkedHashMap<>(Map.of(100, permission(100,75,false,false))));
    }
    private Map<String,String> channel(int id, int parent, String name) {
        var row = new LinkedHashMap<String,String>();
        row.put("cid",""+id); row.put("pid",""+parent); row.put("channel_name",name); row.put("channel_order","0");
        row.put("channel_topic",""); row.put("channel_description",""); row.put("channel_flag_permanent","1");
        row.put("channel_flag_semi_permanent","0"); row.put("channel_flag_password","0"); row.put("channel_maxclients","0");
        row.put("channel_flag_maxclients_unlimited","1"); row.put("channel_codec","4"); row.put("channel_codec_quality","6");
        row.put("channel_needed_talk_power","0"); row.put("total_clients", id < 3 ? "1" : "0");
        return row;
    }
    private void addGroup(String kind, int id, String name, int type) { groups.get(kind).put(id,new LinkedHashMap<>(Map.of(kind.equals("servergroup")?"sgid":"cgid",""+id,"name",name,"type",""+type))); }
    private Map<String,String> permission(int id, int value, boolean negated, boolean skip) { return new LinkedHashMap<>(Map.of("permid",""+id,"permvalue",""+value,"permnegated",negated?"1":"0","permskip",skip?"1":"0")); }
    private QueryResponse response(List<Map<String,String>> rows) { return new QueryResponse(rows,new QueryError(0,"ok",Map.of())); }
    private QueryResponse failure(int id, String message) { return new QueryResponse(List.of(),new QueryError(id,message,Map.of())).checked(); }
    public boolean isOpen() { return open; }
    public boolean getSupportsNotifications() { return true; }
    public QueryResponse execute(QueryCommand command) {
        commands.add(command);
        if (command.getName().equals("channelcreate") && command.getParameters().get("channel_name").equals("Blocked") && blocked != null) {
            blocked.countDown();
            try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out in fixture"); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
        }
        synchronized(this) { return executeLocked(command); }
    }
    private QueryResponse executeLocked(QueryCommand command) {
        String name = command.getName(); var p = command.getParameters();
        if (name.equals("version")) return response(List.of(Map.of("version","6.0.0-beta14")));
        if (name.equals("whoami")) return response(List.of(Map.of("client_id","99","virtualserver_id","1")));
        if (name.equals("serverinfo")) return response(List.of(Map.of("virtualserver_port","24871","virtualserver_default_channel_group","6","virtualserver_name","Lokaler UI-Integrationstest","virtualserver_maxclients","16","virtualserver_clientsonline","3","virtualserver_queryclientsonline","1")));
        if (name.equals("clientlist")) return response(List.of(
            Map.of("clid","11","cid","1","client_database_id","7","client_unique_identifier","fixture-alice","client_nickname","UI-Testnutzer","client_servergroups","7"),
            Map.of("clid","12","cid","2","client_database_id","8","client_unique_identifier","fixture-bot","client_nickname","Musik-Testclient","client_servergroups","7"),
            Map.of("clid","99","cid","1","client_type","1","client_nickname","Test-Query")));
        if (name.equals("channellist")) return response(channels.values().stream().map(Map::copyOf).toList());
        if (name.equals("channelinfo")) return response(List.of(Map.copyOf(channels.get(Integer.parseInt(p.get("cid"))))));
        if (name.equals("channelcreate")) {
            int id=nextChannel++; var row=channel(id,Integer.parseInt(p.get("cpid")),p.get("channel_name")); row.putAll(p); row.remove("cpid");
            if(p.containsKey("channel_password")) row.put("channel_flag_password",p.get("channel_password").isEmpty()?"0":"1");
            channels.put(id,row); return response(List.of(Map.of("cid",""+id)));
        }
        if (name.equals("channeledit")) { var row=channels.get(Integer.parseInt(p.get("cid")));row.putAll(p);if(p.containsKey("channel_password"))row.put("channel_flag_password",p.get("channel_password").isEmpty()?"0":"1");return response(List.of()); }
        if (name.equals("channelmove")) { var row=channels.get(Integer.parseInt(p.get("cid")));row.put("pid",p.get("cpid"));row.put("channel_order",p.get("order"));return response(List.of()); }
        if (name.equals("channeldelete")) { int id=Integer.parseInt(p.get("cid")); if(id<=2)return failure(771,"channel not empty"); channels.remove(id); return response(List.of()); }
        if (name.equals("permissionlist")) return response(List.of(
            Map.of("permid","100","permname","i_client_talk_power","permdesc","Client talk power"),
            Map.of("permid","101","permname","i_channel_needed_join_power","permdesc","Needed join power"),
            Map.of("permid","102","permname","b_channel_join_permanent","permdesc","Join permanent channels")));
        if (name.equals("setclientchannelgroup")) { channelMembers.put(p.get("cldbid")+":"+p.get("cid"),Integer.parseInt(p.get("cgid"))); return response(List.of()); }
        if (name.equals("servergroupaddclient") || name.equals("servergroupdelclient")) {
            var values=serverMembers.computeIfAbsent(Integer.parseInt(p.get("sgid")),ignored->new LinkedHashSet<>());
            if(name.equals("servergroupaddclient")) values.add(Integer.parseInt(p.get("cldbid"))); else values.remove(Integer.parseInt(p.get("cldbid")));
            return response(List.of());
        }
        if (name.equals("servergroupclientlist")) {
            var result=serverMembers.getOrDefault(Integer.parseInt(p.get("sgid")),Set.of()).stream().map(id->Map.of("cldbid",""+id,"client_nickname","Fixture client "+id)).toList();
            return result.isEmpty()?failure(1281,"database empty result set"):response(result);
        }
        if (name.equals("channelgroupclientlist")) {
            List<Map<String,String>> result=new ArrayList<>();
            channelMembers.forEach((key,group)-> { var ids=key.split(":"); if((!p.containsKey("cgid")||p.get("cgid").equals(""+group))&&(!p.containsKey("cldbid")||p.get("cldbid").equals(ids[0]))&&(!p.containsKey("cid")||p.get("cid").equals(ids[1]))) result.add(Map.of("cgid",""+group,"cldbid",ids[0],"cid",ids[1])); });
            return result.isEmpty()?failure(1281,"database empty result set"):response(result);
        }
        for(String kind: List.of("servergroup","channelgroup","channel")) {
            if(!name.startsWith(kind)) continue;
            String idKey=kind.equals("servergroup")?"sgid":kind.equals("channelgroup")?"cgid":"cid";
            if(name.equals(kind+"list")) return response(groups.get(kind).values().stream().map(Map::copyOf).toList());
            if(name.equals(kind+"add")||name.equals(kind+"copy")) {
                int id=nextGroup++; addGroup(kind,id,p.get("name"),1);
                if(name.endsWith("copy")) { String source=kind+":"+p.get("s"+idKey); permissions.put(kind+":"+id,new LinkedHashMap<>(permissions.getOrDefault(source,Map.of()))); }
                return response(List.of(Map.of(idKey,""+id)));
            }
            if(name.equals(kind+"rename")) { groups.get(kind).get(Integer.parseInt(p.get(idKey))).put("name",p.get("name")); return response(List.of()); }
            if(name.equals(kind+"del")) {
                int id=Integer.parseInt(p.get(idKey));
                if(p.get("force").equals("0") && (kind.equals("servergroup")?!serverMembers.getOrDefault(id,Set.of()).isEmpty():channelMembers.containsValue(id))) return failure(1283,"group not empty");
                if(kind.equals("servergroup"))serverMembers.remove(id);else channelMembers.entrySet().removeIf(e->e.getValue()==id);
                groups.get(kind).remove(id);permissions.remove(kind+":"+id);return response(List.of());
            }
            String key=kind+":"+p.get(idKey);
            if(name.equals(kind+"permlist")) { var result=permissions.getOrDefault(key,Map.of()).values().stream().map(Map::copyOf).toList();return result.isEmpty()?failure(1281,"database empty result set"):response(result); }
            if(name.equals(kind+"addperm")) {
                int id=Integer.parseInt(p.get("permid"));if(id==9999)return failure(2568,"insufficient client permissions");
                permissions.computeIfAbsent(key,ignored->new LinkedHashMap<>()).put(id,permission(id,Integer.parseInt(p.get("permvalue")),p.getOrDefault("permnegated","0").equals("1"),p.getOrDefault("permskip","0").equals("1")));return response(List.of());
            }
            if(name.equals(kind+"delperm")) { permissions.computeIfAbsent(key,ignored->new LinkedHashMap<>()).remove(Integer.parseInt(p.get("permid")));return response(List.of()); }
        }
        if(Set.of("use","clientupdate","servernotifyregister","servernotifyunregister","sendtextmessage").contains(name)) return response(List.of());
        throw new IllegalStateException("Unexpected fixture command: "+name);
    }
    public AutoCloseable onNotification(kotlin.jvm.functions.Function1<? super QueryNotification,kotlin.Unit> listener) { return ()->{}; }
    public AutoCloseable onDisconnect(kotlin.jvm.functions.Function1<? super Throwable,kotlin.Unit> listener) { return ()->{}; }
    public void close() { open=false; }
}
