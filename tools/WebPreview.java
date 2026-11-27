import labs.cerberus.ts.*;
import labs.cerberus.ts.query.*;
import labs.cerberus.ts.service.*;
import labs.cerberus.ts.web.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;
import java.io.*;

/** Local QA harness. Live mode has no plugins and rejects every server mutation. */
public class WebPreview {
  private static final class Fixture implements QueryConnection {
    private boolean open = true;
    public boolean isOpen() { return open; }
    public boolean getSupportsNotifications() { return true; }
    public QueryResponse execute(QueryCommand command) {
      var rows = switch(command.getName()) {
        case "version" -> List.of(Map.of("version", "6.0.0-beta14"));
        case "whoami" -> List.of(Map.of("client_id", "99", "virtualserver_id", "1"));
        case "serverinfo" -> List.of(Map.of("virtualserver_name", "Lokaler UI-Integrationstest", "virtualserver_maxclients", "16", "virtualserver_clientsonline", "3", "virtualserver_queryclientsonline", "1"));
        case "clientlist" -> List.of(
          Map.of("clid", "11", "cid", "1", "client_database_id", "7", "client_unique_identifier", "fixture-alice", "client_nickname", "UI-Testnutzer", "client_servergroups", "6"),
          Map.of("clid", "12", "cid", "2", "client_database_id", "8", "client_unique_identifier", "fixture-bot", "client_nickname", "Musik-Testclient", "client_servergroups", "6"),
          Map.of("clid", "99", "cid", "1", "client_type", "1", "client_nickname", "Test-Query"));
        case "channellist" -> List.of(
          Map.of("cid", "1", "pid", "0", "channel_name", "Lobby", "total_clients", "1"),
          Map.of("cid", "2", "pid", "1", "channel_name", "Musik", "total_clients", "1"),
          Map.of("cid", "3", "pid", "0", "channel_name", "Support", "total_clients", "0"));
        case "use", "clientupdate", "servernotifyregister", "servernotifyunregister", "sendtextmessage" -> List.<Map<String,String>>of();
        default -> throw new IllegalStateException("Unexpected fixture command: " + command.getName());
      };
      return new QueryResponse(rows, new QueryError(0, "ok", Map.of()));
    }
    public AutoCloseable onNotification(kotlin.jvm.functions.Function1<? super QueryNotification,kotlin.Unit> callback) { return () -> {}; }
    public AutoCloseable onDisconnect(kotlin.jvm.functions.Function1<? super Throwable,kotlin.Unit> callback) { return () -> {}; }
    public void close() { open = false; }
  }
  private static QueryConnection readOnly(ConnectionConfig config) {
    var connection = QueryConnections.INSTANCE.open(config);
    return new QueryConnection() {
      public boolean isOpen() { return connection.isOpen(); }
      public boolean getSupportsNotifications() { return connection.getSupportsNotifications(); }
      public QueryResponse execute(QueryCommand command) {
        if (!Set.of("version", "use", "whoami", "clientupdate", "servernotifyregister", "servernotifyunregister", "serverinfo", "clientlist", "channellist", "clientinfo").contains(command.getName()))
          throw new IllegalStateException("Live web preview is read-only: " + command.getName());
        return connection.execute(command);
      }
      public AutoCloseable onNotification(kotlin.jvm.functions.Function1<? super QueryNotification,kotlin.Unit> callback) { return connection.onNotification(callback); }
      public AutoCloseable onDisconnect(kotlin.jvm.functions.Function1<? super Throwable,kotlin.Unit> callback) { return connection.onDisconnect(callback); }
      public void close() throws Exception { connection.close(); }
    };
  }
  private static void page(WebInterfaceService web, String id, String title, String script, boolean stylesheet) {
    var assets = stylesheet ? Map.of("index.js", "index.js", "style.css", "style.css") : Map.of("index.js", "index.js");
    web.register("qa", "Lokale Integrationsprüfung", new WebPage(id, title, "Nur im lokalen Test-Harness registriert.", "index.js", stylesheet ? "style.css" : null, 900), assets,
      name -> new ByteArrayInputStream((name.equals("index.js") ? script : "[data-plugin-page^='qa/']{color:var(--foreground)}").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
  }
  public static void main(String[] args) throws Exception {
    String mode = args.length == 0 ? "fixture" : args[0];
    if (!Set.of("fixture", "live").contains(mode)) throw new IllegalArgumentException("Use fixture or live");
    var base = Files.createTempDirectory("cerberus-web-" + mode + "-");
    var plugins = Files.createDirectory(base.resolve("plugins"));
    if (mode.equals("fixture")) Files.copy(Path.of("examples/welcome-plugin/build/libs/welcome-plugin.jar"), plugins.resolve("welcome.jar"));
    var environment = new HashMap<>(System.getenv());
    environment.put("CERBERUS_DATA_DIRECTORY", base.resolve("data").toString());
    environment.put("CERBERUS_PLUGIN_DIRECTORY", plugins.toString());
    environment.put("CERBERUS_REST_PORT", "5178"); environment.put("CERBERUS_REST_HOST", "127.0.0.1");
    environment.put("CERBERUS_WEB_ENABLED", "true"); environment.put("CERBERUS_NICKNAME", "Cerberus Web Preview");
    if (mode.equals("fixture")) environment.put("CERBERUS_PASSWORD", "local-fixture-only");
    var config = BotConfig.Companion.load(mode.equals("live") ? Path.of("config/music-live.properties") : base.resolve("unused.properties"), environment);
    var fixture = new Fixture();
    try (var bot = new CerberusBot(config, connection -> mode.equals("live") ? readOnly(connection) : fixture)) {
      var verbs = Set.of("GET", "PUT", "POST", "PATCH", "DELETE");
      var scopes = new HashSet<String>();
      for (var verb : verbs) { scopes.add(verb+" /api/web/*"); scopes.add(verb+" /api/plugins/*"); }
      var admin = bot.getTokens().generate(scopes, Duration.ofHours(2));
      var read = bot.getTokens().generate(Set.of("GET /api/web/*", "GET /api/plugins/*"), Duration.ofHours(2));
      if (mode.equals("fixture")) {
        var web = Objects.requireNonNull(bot.getWeb());
        page(web, "slow", "Verspäteter Inhalt", "export async function mount(root){root.textContent='Wird vorbereitet';await new Promise(r=>setTimeout(r,700));root.textContent='Verspäteter Inhalt';return()=>{}}", false);
        page(web, "cleanup", "Fehler beim Aufräumen", "export function mount(root){root.textContent='Seite mit fehlerhaftem Cleanup';return()=>{throw new Error('expected cleanup failure')}}", false);
        page(web, "failure", "Fehler beim Laden", "export function mount(root){root.textContent='UNVOLLSTAENDIG';throw new Error('Erwarteter Pluginfehler')}", false);
        page(web, "stylesheet", "Stylesheet-Prüfung", "export function mount(root){root.textContent='Stylesheet geladen';return()=>{}}", true);
        page(web, "cancel", "Anfrage abbrechen", "export function mount(root,api){root.textContent='Anfrage gestartet';const own=new AbortController();api.request('/delay',{signal:own.signal}).then(()=>root.textContent='Antwort',()=>{});return()=>{}}", false);
        bot.getServices().get(RestService.class).route("GET", "/api/plugins/qa/delay", request -> {
          try { Thread.sleep(3000); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
          return new RestResponse(200, Map.of("ok", true), Map.of(), "application/json");
        });
      }
      bot.start();
      var deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
      while (bot.getState() != labs.cerberus.ts.event.BotState.CONNECTED && !bot.getFailed() && System.nanoTime() < deadline) Thread.sleep(50);
      if (bot.getState() != labs.cerberus.ts.event.BotState.CONNECTED) throw new IllegalStateException("Preview connection failed");
      var metadata = base.resolve("metadata.json");
      Files.writeString(metadata, new ObjectMapper().writeValueAsString(Map.of("url", "http://127.0.0.1:5178/", "mode", mode,
        "admin", admin, "read", read, "data", base.resolve("data").toString())));
      Files.setPosixFilePermissions(metadata, PosixFilePermissions.fromString("rw-------"));
      System.out.println("WEB_READY mode="+mode+" url=http://127.0.0.1:5178/ metadata="+metadata);
      var input = new BufferedReader(new InputStreamReader(System.in));
      for (String line; (line = input.readLine()) != null;) if (line.equals("stop")) break;
    }
  }
}
