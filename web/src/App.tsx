import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type FormEvent,
  type ReactNode,
} from "react";
import {
  ArrowRightIcon,
  CheckCircledIcon,
  ChevronDownIcon,
  ChevronRightIcon,
  CubeIcon,
  DashboardIcon,
  ExitIcon,
  LayersIcon,
  LockClosedIcon,
  MixerHorizontalIcon,
  PersonIcon,
  ReloadIcon,
  SpeakerLoudIcon,
  HamburgerMenuIcon,
  MagnifyingGlassIcon,
  Cross2Icon,
} from "@radix-ui/react-icons";
import { cn } from "cn";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Badge } from "@/components/ui/badge";
import { Skeleton } from "@/components/ui/skeleton";
import { Separator } from "@/components/ui/separator";
import {
  Sheet,
  SheetContent,
  SheetHeader,
  SheetTitle,
  SheetTrigger,
} from "@/components/ui/sheet";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
} from "@/components/ui/tooltip";
import { PluginView } from "@/components/PluginView";
import {
  createApi,
  useResource,
  type Api,
  type Channel,
  type Client,
  type MusicBot,
  type Plugin,
  type PluginPage,
  type Status,
} from "@/lib/api";

function Mark({ compact = false }: { compact?: boolean }) {
  return (
    <span className="flex items-center gap-3">
      <img src="/favicon.svg" alt="" className="size-9" />
      {!compact && (
        <span className="text-[21px] font-medium tracking-tight">
          Cerberus <span className="text-muted-foreground">TS</span>
        </span>
      )}
    </span>
  );
}
function Login({ onLogin }: { onLogin: (token: string) => Promise<void> }) {
  const [token, setToken] = useState("");
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string>();
  async function submit(event: FormEvent) {
    event.preventDefault();
    setError(undefined);
    if (!token.trim()) {
      setError("Gib deinen Zugriffstoken ein.");
      document.getElementById("access-token")?.focus();
      return;
    }
    setPending(true);
    try {
      await onLogin(token.trim());
    } catch (error) {
      setError(
        error instanceof Error ? error.message : "Anmeldung fehlgeschlagen.",
      );
    } finally {
      setPending(false);
    }
  }
  return (
    <div className="login-page">
      <a className="skip-link" href="#login">
        Zum Inhalt
      </a>
      <header className="login-header">
        <Mark />
        <span className="text-sm text-muted-foreground">Cerberus Labs</span>
      </header>
      <main id="login" className="login-grid">
        <section className="login-copy" aria-labelledby="login-title">
          <p className="section-label">Cerberus TS 2.0</p>
          <h1 id="login-title">
            TeamSpeak-Verwaltung
            <br />
            <span className="text-muted-foreground">für deine Community.</span>
          </h1>
          <p className="max-w-md text-lg leading-relaxed text-muted-foreground">
            Server, Channels und Plugins von Cerberus TS in einer gemeinsamen
            Oberfläche.
          </p>
          <div
            className="login-diagram"
            aria-label="Cerberus TS verbindet TeamSpeak und die installierten Plugins"
          >
            <div className="diagram-node">
              <SpeakerLoudIcon />
              <span>TeamSpeak</span>
            </div>
            <span className="diagram-line" />
            <div className="diagram-node diagram-core">
              <img src="/favicon.svg" alt="" />
              <span>Cerberus TS</span>
            </div>
            <span className="diagram-line" />
            <div className="diagram-node">
              <CubeIcon />
              <span>Plugins</span>
            </div>
          </div>
        </section>
        <section className="login-form-section" aria-labelledby="sign-in-title">
          <div className="login-lock">
            <LockClosedIcon className="size-5" />
          </div>
          <h2
            id="sign-in-title"
            className="text-2xl font-medium tracking-tight"
          >
            Am Bot anmelden
          </h2>
          <p className="mt-2 text-muted-foreground">
            Verwende einen Zugriffstoken aus der lokalen Bot-Konsole.
          </p>
          <form className="mt-8 space-y-5" onSubmit={submit}>
            <div className="space-y-2">
              <Label htmlFor="access-token">Zugriffstoken</Label>
              <Input
                id="access-token"
                type="password"
                autoComplete="off"
                spellCheck={false}
                value={token}
                onChange={(event) => setToken(event.target.value)}
                aria-invalid={Boolean(error)}
                aria-describedby={error ? "login-error" : "login-help"}
                className="h-12 font-mono"
              />
              {error ? (
                <p
                  id="login-error"
                  className="text-sm text-destructive"
                  role="alert"
                >
                  {error}
                </p>
              ) : (
                <p id="login-help" className="text-sm text-muted-foreground">
                  Erstelle den Token in der Bot-Konsole mit{" "}
                  <code>web-token</code>.
                </p>
              )}
            </div>
            <Button
              type="submit"
              className="h-12 w-full justify-between"
              disabled={pending}
            >
              {pending ? "Anmeldung wird geprüft …" : "Mit Token anmelden"}
              <ArrowRightIcon />
            </Button>
            <p role="status" aria-live="polite" className="sr-only">
              {pending ? "Anmeldung wird geprüft." : ""}
            </p>
            <p className="text-sm leading-relaxed text-muted-foreground">
              Dein Token bleibt für diese Sitzung im Arbeitsspeicher des
              Browsers.
            </p>
          </form>
        </section>
      </main>
      <footer className="login-footer">
        <span>Cerberus Labs</span>
        <span>TeamSpeak-Verwaltung mit Plugin-Erweiterungen</span>
      </footer>
    </div>
  );
}
function InlineError({
  message,
  retry,
}: {
  message?: string;
  retry?: () => void;
}) {
  if (!message) return null;
  return (
    <div className="inline-error" role="alert">
      <p>{message}</p>
      {retry && (
        <Button variant="outline" onClick={retry}>
          Abruf wiederholen
        </Button>
      )}
    </div>
  );
}
function Empty({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div className="empty-state">
      <LayersIcon className="mb-4 size-6 text-muted-foreground" />
      <h3 className="font-medium">{title}</h3>
      <p className="mt-2 max-w-md text-sm leading-relaxed text-muted-foreground">
        {children}
      </p>
    </div>
  );
}
function LoadingRows() {
  return (
    <div
      className="space-y-4 p-6"
      role="status"
      aria-label="Daten werden geladen"
    >
      {[0, 1, 2, 3].map((value) => (
        <Skeleton key={value} className="h-10 w-full" />
      ))}
    </div>
  );
}

function ChannelTree({
  channels,
  clients,
  selected,
  onSelect,
  compact = false,
}: {
  channels: Channel[];
  clients: Client[];
  selected?: number;
  onSelect?: (id: number) => void;
  compact?: boolean;
}) {
  const [collapsed, setCollapsed] = useState<Set<number>>(new Set());
  const known = new Set(channels.map((channel) => channel.id));
  function rows(parent: number, ancestors = new Set<number>()): ReactNode {
    return channels
      .filter((channel) =>
        parent === 0
          ? channel.parentId === 0 || !known.has(channel.parentId)
          : channel.parentId === parent,
      )
      .map((channel) => {
        if (ancestors.has(channel.id)) return null;
        const children = channels.some(
          (child) => child.parentId === channel.id,
        );
        const users = clients.filter(
          (client) => client.channelId === channel.id,
        );
        const closed = collapsed.has(channel.id);
        return (
          <li key={channel.id}>
            <div
              className={cn(
                "channel-row",
                selected === channel.id && "channel-row-selected",
              )}
            >
              <button
                className="tree-toggle"
                disabled={!children && users.length === 0}
                aria-label={`${channel.name} ${closed ? "aufklappen" : "zuklappen"}`}
                aria-expanded={children || users.length ? !closed : undefined}
                onClick={() =>
                  setCollapsed((previous) => {
                    const next = new Set(previous);
                    if (next.has(channel.id)) next.delete(channel.id);
                    else next.add(channel.id);
                    return next;
                  })
                }
              >
                {children || users.length ? (
                  closed ? (
                    <ChevronRightIcon />
                  ) : (
                    <ChevronDownIcon />
                  )
                ) : (
                  <span className="size-4" />
                )}
              </button>
              <button
                className="channel-name"
                onClick={() => onSelect?.(channel.id)}
                aria-pressed={selected === channel.id}
              >
                <SpeakerLoudIcon className="shrink-0 text-muted-foreground" />
                <span className="min-w-0 truncate">{channel.name}</span>
              </button>
              <span className="channel-count">
                {channel.clients ?? users.length}
              </span>
            </div>
            {!closed && (children || users.length > 0) && (
              <ul className="tree-children">
                {!compact &&
                  users.map((client) => (
                    <li key={`client-${client.id}`} className="tree-user">
                      <span className="client-initial">
                        {client.nickname.slice(0, 1).toUpperCase()}
                      </span>
                      <span className="truncate">{client.nickname}</span>
                    </li>
                  ))}
                {children &&
                  rows(channel.id, new Set([...ancestors, channel.id]))}
              </ul>
            )}
          </li>
        );
      });
  }
  return (
    <ul className="channel-tree" aria-label="Channelstruktur">
      {rows(0)}
    </ul>
  );
}

function Overview({
  api,
  refresh,
  status,
  statusError,
  pages,
}: {
  api: Api;
  refresh: number;
  status?: Status;
  statusError?: string;
  pages: PluginPage[];
}) {
  const channels = useResource<Channel[]>(api, "/api/web/channels", refresh);
  const clients = useResource<Client[]>(api, "/api/web/clients", refresh);
  const music = useResource<MusicBot[]>(api, "/api/web/music", refresh);
  const [selected, setSelected] = useState<number>();
  const selectedChannel = channels.data?.find(
    (channel) => channel.id === selected,
  );
  const online =
    clients.data?.length ??
    (status?.onlineClients != null && status.queryClients != null
      ? Math.max(0, status.onlineClients - status.queryClients)
      : undefined);
  return (
    <>
      <div className="metric-row">
        {[
          {
            label: "Clients verbunden",
            value: online,
            detail: status?.maxClients
              ? `von ${status.maxClients} Plätzen`
              : "Aktueller Serverstand",
          },
          {
            label: "Channels",
            value: channels.data?.length,
            detail: "Auf deinem Server",
          },
          {
            label: "Musikbots",
            value: music.data?.filter((bot) => bot.connected).length,
            detail: "Mit Voice verbunden",
          },
          {
            label: "Plugin-Seiten",
            value: pages.length,
            detail: "Im Webinterface",
          },
        ].map((item) => (
          <div className="metric" key={item.label}>
            <span className="text-sm text-muted-foreground">{item.label}</span>
            {item.value == null ? (
              <Skeleton className="my-3 h-10 w-14" />
            ) : (
              <strong>{item.value}</strong>
            )}
            <span className="text-sm text-muted-foreground">{item.detail}</span>
          </div>
        ))}
      </div>
      <div className="overview-grid">
        <section className="panel">
          <div className="panel-heading">
            <h2>Dein Server</h2>
            <Badge variant="secondary">Channelstruktur</Badge>
          </div>
          <InlineError message={channels.error} retry={channels.retry} />
          {!channels.data && channels.loading ? (
            <LoadingRows />
          ) : channels.data?.length ? (
            <ChannelTree
              channels={channels.data}
              clients={clients.data ?? []}
              selected={selected}
              onSelect={setSelected}
            />
          ) : (
            <Empty title="Keine Channels geladen">
              Sobald eine TeamSpeak-Verbindung besteht, erscheint hier deine
              Channelstruktur.
            </Empty>
          )}
        </section>
        <div className="space-y-6">
          <section className="panel">
            <div className="panel-heading">
              <h2>{selectedChannel ? "Channel im Fokus" : "Verbindung"}</h2>
              <span
                className="status-dot"
                data-connected={!statusError && Boolean(status?.connected)}
              />
            </div>
            <div className="p-6">
              {selectedChannel ? (
                <>
                  <p className="text-xl font-medium tracking-tight">
                    {selectedChannel.name}
                  </p>
                  <dl className="detail-list">
                    <div>
                      <dt>Channel-ID</dt>
                      <dd>#{selectedChannel.id}</dd>
                    </div>
                    <div>
                      <dt>Verbundene Clients</dt>
                      <dd>{selectedChannel.clients ?? "—"}</dd>
                    </div>
                    <div>
                      <dt>Parent-ID</dt>
                      <dd>{selectedChannel.parentId || "Root"}</dd>
                    </div>
                  </dl>
                </>
              ) : (
                <>
                  <p className="text-xl font-medium tracking-tight">
                    {statusError
                      ? "Status unbekannt"
                      : status?.connected
                        ? "TeamSpeak ist verbunden"
                        : status
                          ? "TeamSpeak ist getrennt"
                          : "Status wird geladen"}
                  </p>
                  <dl className="detail-list">
                    <div>
                      <dt>Server-ID</dt>
                      <dd>{status?.serverId ?? "—"}</dd>
                    </div>
                    <div>
                      <dt>TeamSpeak-Version</dt>
                      <dd>{status?.version ?? "—"}</dd>
                    </div>
                    <div>
                      <dt>Bot-Status</dt>
                      <dd>{status?.state ?? "Wird geladen"}</dd>
                    </div>
                  </dl>
                </>
              )}
            </div>
          </section>
          <section className="panel">
            <div className="panel-heading">
              <h2>Musik</h2>
              <SpeakerLoudIcon className="text-muted-foreground" />
            </div>
            <InlineError message={music.error} retry={music.retry} />
            {music.data?.length ? (
              <div className="divide-y">
                {music.data.map((bot) => (
                  <div className="p-6" key={bot.id}>
                    <div className="mb-3 flex items-center justify-between gap-3">
                      <Badge variant="secondary">{bot.id}</Badge>
                      <span className="text-sm text-muted-foreground">
                        {bot.volume} %
                      </span>
                    </div>
                    <p className="text-lg font-medium">
                      {bot.metadata?.title ??
                        (bot.current ? "Audio läuft" : "Keine Wiedergabe")}
                    </p>
                    <p className="mt-1 text-sm text-muted-foreground">
                      {[bot.metadata?.artist, bot.metadata?.station]
                        .filter(Boolean)
                        .join(" · ") ||
                        (bot.paused
                          ? "Pausiert"
                          : bot.connected
                            ? "Verbunden"
                            : "Getrennt")}
                    </p>
                    {bot.error && (
                      <p className="mt-3 text-sm text-destructive">
                        {bot.error}
                      </p>
                    )}
                  </div>
                ))}
              </div>
            ) : (
              <div className="p-6">
                <p className="text-muted-foreground">
                  Keine Musikbots in dieser Bot-Instanz.
                </p>
              </div>
            )}
          </section>
          <section className="px-1">
            <h2 className="mb-3 text-base font-medium">Deine Erweiterungen</h2>
            {pages.length ? (
              <div className="space-y-2">
                {pages.map((page) => (
                  <a
                    className="extension-link"
                    key={`${page.plugin}/${page.id}`}
                    href={`#/plugins/${page.plugin}/${page.id}`}
                  >
                    <CubeIcon />
                    <span>{page.title}</span>
                    <ArrowRightIcon className="ml-auto" />
                  </a>
                ))}
              </div>
            ) : (
              <p className="text-sm text-muted-foreground">
                Noch kein Plugin hat eine Web-Seite registriert.
              </p>
            )}
          </section>
        </div>
      </div>
    </>
  );
}
function ClientsPage({ api, refresh }: { api: Api; refresh: number }) {
  const clients = useResource<Client[]>(api, "/api/web/clients", refresh);
  const channels = useResource<Channel[]>(api, "/api/web/channels", refresh);
  const [search, setSearch] = useState("");
  const filtered = clients.data?.filter((client) =>
    client.nickname
      .toLocaleLowerCase("de")
      .includes(search.toLocaleLowerCase("de")),
  );
  return (
    <section className="panel">
      <div className="p-6">
        <Label htmlFor="client-search" className="mb-2">
          Clients suchen
        </Label>
        <div className="relative max-w-sm">
          <MagnifyingGlassIcon className="pointer-events-none absolute top-3.5 left-3 text-muted-foreground" />
          <Input
            id="client-search"
            value={search}
            onChange={(event) => setSearch(event.target.value)}
            className="h-11 pl-9"
            placeholder="Nach Namen filtern"
          />
        </div>
      </div>
      <InlineError message={clients.error} retry={clients.retry} />
      {!clients.data && clients.loading ? (
        <LoadingRows />
      ) : filtered?.length ? (
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Client</TableHead>
              <TableHead>Channel</TableHead>
              <TableHead>Servergruppen</TableHead>
              <TableHead className="text-right">Client-ID</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {filtered.map((client) => (
              <TableRow key={client.id}>
                <TableCell>
                  <span className="flex items-center gap-3">
                    <span className="client-initial">
                      {client.nickname.slice(0, 1).toUpperCase()}
                    </span>
                    <span className="font-medium">{client.nickname}</span>
                  </span>
                </TableCell>
                <TableCell>
                  {channels.data?.find(
                    (channel) => channel.id === client.channelId,
                  )?.name ?? `#${client.channelId}`}
                </TableCell>
                <TableCell>
                  <span className="font-mono text-xs text-muted-foreground">
                    {client.serverGroups
                      .map((group) => `#${group}`)
                      .join(", ") || "Keine"}
                  </span>
                </TableCell>
                <TableCell className="text-right font-mono text-xs">
                  #{client.id}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      ) : (
        <Empty
          title={
            search ? "Kein Client passt zur Suche" : "Keine Clients verbunden"
          }
        >
          {search
            ? "Versuche einen anderen Namen."
            : "Verbundene Benutzer und Voice-Bots erscheinen hier. Query-Verbindungen sind ausgeblendet."}
        </Empty>
      )}
    </section>
  );
}
function ChannelsPage({ api, refresh }: { api: Api; refresh: number }) {
  const channels = useResource<Channel[]>(api, "/api/web/channels", refresh);
  const clients = useResource<Client[]>(api, "/api/web/clients", refresh);
  const [selected, setSelected] = useState<number>();
  const channel = channels.data?.find((item) => item.id === selected);
  return (
    <div className="overview-grid">
      <section className="panel">
        <div className="panel-heading">
          <h2>Channelstruktur</h2>
          <span className="text-sm text-muted-foreground">
            {channels.data?.length ?? 0} Channels
          </span>
        </div>
        <InlineError message={channels.error} retry={channels.retry} />
        {!channels.data && channels.loading ? (
          <LoadingRows />
        ) : channels.data?.length ? (
          <ChannelTree
            channels={channels.data}
            clients={clients.data ?? []}
            selected={selected}
            onSelect={setSelected}
          />
        ) : (
          <Empty title="Keine Channels geladen">
            Prüfe die TeamSpeak-Verbindung.
          </Empty>
        )}
      </section>
      <section className="panel h-fit">
        <div className="panel-heading">
          <h2>Channel-Details</h2>
        </div>
        {channel ? (
          <div className="p-6">
            <p className="text-xl font-medium">{channel.name}</p>
            <dl className="detail-list">
              <div>
                <dt>Channel-ID</dt>
                <dd>#{channel.id}</dd>
              </div>
              <div>
                <dt>Parent</dt>
                <dd>
                  {channels.data?.find((item) => item.id === channel.parentId)
                    ?.name ?? "Root"}
                </dd>
              </div>
              <div>
                <dt>Verbundene Clients</dt>
                <dd>{channel.clients ?? 0}</dd>
              </div>
            </dl>
          </div>
        ) : (
          <Empty title="Wähle einen Channel">
            Klicke links auf einen Namen, um die Details anzuzeigen.
          </Empty>
        )}
      </section>
    </div>
  );
}
function PluginsPage({
  api,
  refresh,
  pages,
}: {
  api: Api;
  refresh: number;
  pages: PluginPage[];
}) {
  const plugins = useResource<Plugin[]>(api, "/api/web/plugins", refresh);
  return (
    <section className="panel">
      <div className="panel-heading">
        <h2>Installierte Plugins</h2>
        <Badge variant="secondary">{plugins.data?.length ?? 0} geladen</Badge>
      </div>
      <InlineError message={plugins.error} retry={plugins.retry} />
      {!plugins.data && plugins.loading ? (
        <LoadingRows />
      ) : plugins.data?.length ? (
        <div className="divide-y">
          {plugins.data.map((plugin) => (
            <div className="plugin-row" key={plugin.id}>
              <span className="plugin-icon">
                <CubeIcon />
              </span>
              <div className="min-w-0 flex-1">
                <div className="flex flex-wrap items-center gap-3">
                  <h3 className="font-medium">{plugin.name}</h3>
                  <span className="font-mono text-xs text-muted-foreground">
                    v{plugin.version}
                  </span>
                </div>
                <p className="mt-1 text-sm leading-relaxed text-muted-foreground">
                  {plugin.description || plugin.id}
                </p>
                <div className="mt-3 flex flex-wrap gap-2">
                  {pages
                    .filter((page) => page.plugin === plugin.id)
                    .map((page) => (
                      <Button variant="outline" asChild key={page.id}>
                        <a href={`#/plugins/${page.plugin}/${page.id}`}>
                          {page.title} öffnen
                          <ArrowRightIcon />
                        </a>
                      </Button>
                    ))}
                </div>
              </div>
            </div>
          ))}
        </div>
      ) : (
        <Empty title="Keine Plugins geladen">
          Lege ein Plugin-JAR im konfigurierten Plugin-Ordner ab und starte den
          Bot neu.
        </Empty>
      )}
    </section>
  );
}

function Workspace({
  token,
  onLogout,
}: {
  token: string;
  onLogout: () => void;
}) {
  const api = useMemo(() => createApi(token), [token]);
  const [refresh, setRefresh] = useState(0);
  const status = useResource<Status>(api, "/api/web/status", refresh);
  const pages = useResource<PluginPage[]>(api, "/api/web/pages", refresh);
  const [path, setPath] = useState(location.hash.slice(1) || "/");
  const [menuOpen, setMenuOpen] = useState(false);
  const mobileSidebar = useRef<HTMLDivElement>(null);
  const [notice, setNotice] = useState<{
    text: string;
    kind: "success" | "error";
  }>();
  useEffect(() => {
    const update = () => {
      setPath(location.hash.slice(1) || "/");
      setMenuOpen(false);
    };
    addEventListener("hashchange", update);
    return () => removeEventListener("hashchange", update);
  }, []);
  const notify = useCallback(
    (text: string, kind: "success" | "error" = "success") =>
      setNotice({ text, kind }),
    [],
  );
  const navigation = [
    { path: "/", label: "Übersicht", icon: DashboardIcon },
    { path: "/clients", label: "Clients", icon: PersonIcon },
    { path: "/channels", label: "Channels", icon: SpeakerLoudIcon },
    { path: "/plugins", label: "Plugins", icon: CubeIcon },
  ];
  const pluginPage = pages.data?.find(
    (page) => path === `/plugins/${page.plugin}/${page.id}`,
  );
  const active = navigation.find((item) => item.path === path);
  const waitingForPage =
    path.startsWith("/plugins/") && !pages.data && pages.loading;
  const heading =
    pluginPage?.title ??
    active?.label ??
    (waitingForPage ? "Erweiterung wird geladen" : "Seite nicht gefunden");
  const connectionKnown = Boolean(status.data) && !status.error;
  const connected = connectionKnown && Boolean(status.data?.connected);
  const connectionLabel = status.error
    ? "Status unbekannt"
    : !status.data
      ? "Status wird geladen"
      : connected
        ? "Verbunden"
        : "Getrennt";
  const sidebar = (
    <>
      <div className="sidebar-brand">
        <a href="#/" aria-label="Cerberus TS Übersicht">
          <Mark />
        </a>
      </div>
      <div className="server-chip">
        <span className="status-dot" data-connected={connected} />
        <div className="min-w-0">
          <p className="truncate text-sm font-medium">
            {status.data?.serverName ?? "TeamSpeak-Server"}
          </p>
          <p className="mt-1 text-xs text-muted-foreground">
            {status.error
              ? "Statusabruf fehlgeschlagen"
              : !status.data
                ? "Status wird geladen"
                : connected
                  ? "Mit TeamSpeak verbunden"
                  : "TeamSpeak ist getrennt"}
          </p>
        </div>
      </div>
      <nav aria-label="Hauptnavigation" className="sidebar-nav">
        <p className="nav-label">Verwaltung</p>
        {navigation.map((item) => (
          <a
            key={item.path}
            href={`#${item.path}`}
            onClick={() => setMenuOpen(false)}
            className={cn("nav-item", path === item.path && "nav-item-active")}
            aria-current={path === item.path ? "page" : undefined}
          >
            <item.icon />
            <span>{item.label}</span>
          </a>
        ))}
        {Boolean(pages.data?.length) && (
          <>
            <p className="nav-label mt-7">Erweiterungen</p>
            {pages.data!.map((page) => (
              <a
                key={`${page.plugin}/${page.id}`}
                href={`#/plugins/${page.plugin}/${page.id}`}
                onClick={() => setMenuOpen(false)}
                className={cn(
                  "nav-item",
                  pluginPage === page && "nav-item-active",
                )}
                aria-current={pluginPage === page ? "page" : undefined}
              >
                <MixerHorizontalIcon />
                <span className="truncate">{page.title}</span>
              </a>
            ))}
          </>
        )}
      </nav>
      <div className="sidebar-bottom">
        <Separator />
        <div className="mt-5 flex items-center gap-3">
          <span className="admin-avatar">CL</span>
          <div className="flex-1">
            <p className="text-sm font-medium">Webzugriff</p>
            <p className="text-xs text-muted-foreground">Cerberus Labs</p>
          </div>
          <Tooltip>
            <TooltipTrigger asChild>
              <Button
                variant="ghost"
                size="icon"
                aria-label="Vom Webinterface abmelden"
                onClick={onLogout}
              >
                <ExitIcon />
              </Button>
            </TooltipTrigger>
            <TooltipContent>Abmelden</TooltipContent>
          </Tooltip>
        </div>
      </div>
    </>
  );
  return (
    <div className="app-shell">
      <a className="skip-link" href="#main">
        Zum Inhalt
      </a>
      <aside className="desktop-sidebar">{sidebar}</aside>
      <div className="app-content">
        <header className="app-topbar">
          <div className="flex min-w-0 items-center gap-3">
            <Sheet open={menuOpen} onOpenChange={setMenuOpen}>
              <SheetTrigger asChild>
                <Button
                  variant="ghost"
                  size="icon"
                  className="lg:hidden"
                  aria-label="Navigation öffnen"
                >
                  <HamburgerMenuIcon />
                </Button>
              </SheetTrigger>
              <SheetContent
                aria-describedby={undefined}
                onOpenAutoFocus={(event) => {
                  const target =
                    mobileSidebar.current?.querySelector<HTMLAnchorElement>(
                      'nav a[aria-current="page"]',
                    ) ??
                    mobileSidebar.current?.querySelector<HTMLAnchorElement>(
                      "nav a",
                    );
                  if (target) {
                    event.preventDefault();
                    target.focus();
                  }
                }}
                side="left"
                className="w-[290px] gap-0 p-0"
              >
                <SheetHeader className="sr-only">
                  <SheetTitle>Navigation</SheetTitle>
                </SheetHeader>
                <div ref={mobileSidebar} className="mobile-sidebar">
                  {sidebar}
                </div>
              </SheetContent>
            </Sheet>
            <span className="truncate text-sm text-muted-foreground">
              Server <span className="px-2 text-border">/</span>
              <span className="text-foreground">{heading}</span>
            </span>
          </div>
          <div className="flex items-center gap-3">
            <span className="connection-label">
              <span className="status-dot" data-connected={connected} />
              {connectionLabel}
            </span>
            <Badge variant="outline" className="hidden sm:inline-flex">
              Cerberus TS 2.0
            </Badge>
          </div>
        </header>
        <main id="main" className="workspace">
          <div className="page-heading">
            <div>
              <p className="section-label">
                {pluginPage
                  ? pluginPage.pluginName
                  : (status.data?.serverName ?? "Cerberus TS")}
              </p>
              <h1>
                {heading === "Übersicht" ? "Dein Server im Überblick" : heading}
              </h1>
              <p className="mt-2 text-muted-foreground">
                {pluginPage?.description ||
                  (path === "/"
                    ? "Verbindungen, Channels und Erweiterungen auf einen Blick."
                    : path === "/clients"
                      ? "Benutzer und Voice-Bots auf deinem TeamSpeak-Server."
                      : path === "/channels"
                        ? "Alle Channels mit ihrer Hierarchie und Belegung."
                        : path === "/plugins"
                          ? "Die Erweiterungen deiner Cerberus-Instanz."
                          : "Diese Seite ist nicht mehr registriert.")}
              </p>
            </div>
            <Button
              variant="outline"
              onClick={() => setRefresh((value) => value + 1)}
              disabled={status.loading}
            >
              <ReloadIcon />
              Daten aktualisieren
            </Button>
          </div>
          {notice && (
            <div
              className={cn(
                "notice",
                notice.kind === "error" && "notice-error",
              )}
              role={notice.kind === "error" ? "alert" : "status"}
            >
              <CheckCircledIcon />
              <span>{notice.text}</span>
              <Button
                variant="ghost"
                size="icon"
                aria-label="Meldung schließen"
                onClick={() => setNotice(undefined)}
              >
                <Cross2Icon />
              </Button>
            </div>
          )}
          <InlineError message={status.error} retry={status.retry} />
          {pluginPage ? (
            <PluginView
              page={pluginPage}
              api={api}
              notify={notify}
              refresh={refresh}
            />
          ) : waitingForPage ? (
            <LoadingRows />
          ) : path === "/" ? (
            <Overview
              api={api}
              refresh={refresh}
              status={status.data}
              statusError={status.error}
              pages={pages.data ?? []}
            />
          ) : path === "/clients" ? (
            <ClientsPage api={api} refresh={refresh} />
          ) : path === "/channels" ? (
            <ChannelsPage api={api} refresh={refresh} />
          ) : path === "/plugins" ? (
            <PluginsPage api={api} refresh={refresh} pages={pages.data ?? []} />
          ) : (
            <Empty title="Seite nicht gefunden">
              Wähle eine Seite in der Navigation.
            </Empty>
          )}
          <InlineError message={pages.error} retry={pages.retry} />
          <footer className="workspace-footer">
            <span>Cerberus Labs</span>
            <span>
              {status.updated
                ? `Status abgerufen um ${status.updated.toLocaleTimeString("de-DE", { hour: "2-digit", minute: "2-digit", second: "2-digit" })}`
                : "Status wird abgerufen"}
            </span>
          </footer>
        </main>
      </div>
    </div>
  );
}
export default function App() {
  const [token, setToken] = useState<string>();
  async function login(value: string) {
    await createApi(value).request<Status>("/api/web/status");
    setToken(value);
  }
  return (
    <TooltipProvider>
      {token ? (
        <Workspace
          token={token}
          onLogout={() => {
            setToken(undefined);
            location.hash = "/";
          }}
        />
      ) : (
        <Login onLogin={login} />
      )}
    </TooltipProvider>
  );
}
