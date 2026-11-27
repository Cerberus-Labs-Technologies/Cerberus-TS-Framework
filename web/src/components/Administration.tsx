import {
  Children,
  cloneElement,
  isValidElement,
  type ReactElement,
  useEffect,
  useId,
  useRef,
  useState,
  type FormEvent,
  type ReactNode,
} from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { type Api, type Channel, type Client, useResource } from "@/lib/api";

type Detail = {
  parentId: number | null;
  order: number | null;
  id: number;
  name: string;
  topic: string;
  description: string;
  hasPassword: boolean;
  lifetime: string;
  maxClients: number | null;
  codec: number | null;
  codecQuality: number | null;
  neededTalkPower: number | null;
  revision: string;
};
type Group = { id: number; name: string; type: number | null };
type Permission = {
  id: number;
  value: number;
  negated: boolean;
  skip: boolean;
};
type CatalogEntry = { id: number; name: string; description: string };
type Member = {
  databaseId: number;
  nickname: string | null;
  channelId: number | null;
};
const json = (method: string, body?: unknown, signal?: AbortSignal) => ({
  method,
  signal,
  ...(body === undefined ? {} : { body: JSON.stringify(body) }),
});
function ErrorBox({ message }: { message?: string }) {
  return message ? (
    <p className="inline-error" role="alert">
      {message}
    </p>
  ) : null;
}
function useOperation() {
  const [busy, setBusy] = useState(false),
    [error, setError] = useState<string>(),
    [message, setMessage] = useState<string>();
  const active = useRef<AbortController | null>(null);
  const lock = useRef(false);
  useEffect(() => {
    const controller = new AbortController();
    active.current = controller;
    return () => {
      controller.abort();
      active.current = null;
    };
  }, []);
  async function run(
    action: (signal: AbortSignal) => Promise<unknown>,
    success: string,
  ) {
    const controller = active.current;
    if (lock.current || !controller) return false;
    lock.current = true;
    setBusy(true);
    setError(undefined);
    setMessage(undefined);
    try {
      await action(controller.signal);
      if (controller.signal.aborted) return false;
      setMessage(success);
      return true;
    } catch (error) {
      if (!controller.signal.aborted)
        setError(
          error instanceof Error
            ? error.message
            : "Die Aktion ist fehlgeschlagen.",
        );
      return false;
    } finally {
      lock.current = false;
      if (!controller.signal.aborted) setBusy(false);
    }
  }
  return {
    busy,
    run,
    feedback: (
      <>
        <ErrorBox message={error} />
        <p role="status" className="admin-notice">
          {message}
        </p>
      </>
    ),
  };
}
// Explicit label association excludes option/helper text from the accessible control name.
function FormLabel({
  label,
  children,
  help,
}: {
  label: string;
  children: ReactNode;
  help?: string;
}) {
  const id = useId();
  return (
    <div className="admin-field">
      <label className="admin-label" htmlFor={id}>
        {label}
      </label>
      {Children.map(children, (child, index) =>
        index === 0 && isValidElement(child)
          ? cloneElement(
              child as ReactElement<{
                id?: string;
                "aria-describedby"?: string;
              }>,
              { id, "aria-describedby": help ? `${id}-help` : undefined },
            )
          : child,
      )}
      {help && (
        <p id={`${id}-help`} className="admin-help">
          {help}
        </p>
      )}
    </div>
  );
}
function DeleteAction({
  name,
  group = false,
  busy,
  onDelete,
}: {
  name: string;
  group?: boolean;
  busy: boolean;
  onDelete: (force: boolean) => void;
}) {
  const [force, setForce] = useState(false);
  return (
    <details className="admin-danger">
      <summary>{group ? "Gruppe löschen" : "Channel löschen"}</summary>
      <p>
        „{name}“ wird dauerhaft gelöscht.{" "}
        {group
          ? "Die Gruppenrechte gehen dabei verloren."
          : "Der Channel muss leer sein und darf keine Unterchannels enthalten."}
      </p>
      {group && (
        <label className="admin-check">
          <input
            type="checkbox"
            checked={force}
            onChange={(e) => setForce(e.target.checked)}
            disabled={busy}
          />
          Auch vorhandene Zuweisungen entfernen
        </label>
      )}
      <Button
        type="button"
        variant="destructive"
        disabled={busy}
        onClick={() => onDelete(force)}
      >
        Endgültig löschen
      </Button>
    </details>
  );
}
export function ChannelEditor({
  api,
  channel,
  channels,
  onSaved,
  onDeleted,
}: {
  api: Api;
  channel?: Channel;
  channels: Channel[];
  onSaved: (id: number) => void;
  onDeleted: () => void;
}) {
  const [detail, setDetail] = useState<Detail>(),
    [error, setError] = useState<string>(),
    [reload, setReload] = useState(0);
  useEffect(() => {
    if (!channel) return;
    const abort = new AbortController();
    setError(undefined);
    setDetail(undefined);
    api
      .request<Detail>(`/api/web/channels/${channel.id}`, {
        signal: abort.signal,
      })
      .then(setDetail, (e) => {
        if (!abort.signal.aborted) setError(e.message);
      });
    return () => abort.abort();
  }, [api, channel?.id, reload]);
  if (channel && !detail)
    return (
      <div className="p-6">
        <ErrorBox message={error} />
        {error ? (
          <Button variant="outline" onClick={() => setReload((v) => v + 1)}>
            Details neu laden
          </Button>
        ) : (
          <p role="status">Channel-Details werden geladen …</p>
        )}
      </div>
    );
  return (
    <ChannelForm
      key={detail?.revision ?? "new"}
      api={api}
      detail={detail}
      channel={channel}
      channels={channels}
      onSaved={(id) => {
        setReload((v) => v + 1);
        onSaved(id);
      }}
      onDeleted={onDeleted}
    />
  );
}
function ChannelForm({
  api,
  detail,
  channel,
  channels,
  onSaved,
  onDeleted,
}: {
  api: Api;
  detail?: Detail;
  channel?: Channel;
  channels: Channel[];
  onSaved: (id: number) => void;
  onDeleted: () => void;
}) {
  const op = useOperation();
  const [name, setName] = useState(detail?.name ?? ""),
    [topic, setTopic] = useState(detail?.topic ?? ""),
    [description, setDescription] = useState(detail?.description ?? "");
  const [lifetime, setLifetime] = useState(detail?.lifetime ?? "permanent"),
    [maxClients, setMaxClients] = useState(detail?.maxClients ?? -1);
  const [codec, setCodec] = useState(detail?.codec ?? 4),
    [quality, setQuality] = useState(detail?.codecQuality ?? 6),
    [talkPower, setTalkPower] = useState(detail?.neededTalkPower ?? 0);
  const [password, setPassword] = useState(""),
    [changePassword, setChangePassword] = useState(false);
  const [parent, setParent] = useState(
      detail?.parentId ?? channel?.parentId ?? 0,
    ),
    [order, setOrder] = useState(detail?.order ?? channel?.order ?? 0);
  const placement = (
    <>
      <FormLabel label="Übergeordneter Channel">
        <select
          value={parent}
          onChange={(e) => {
            setParent(Number(e.target.value));
            setOrder(0);
          }}
        >
          <option value="0">Root</option>
          {channels
            .filter((c) => c.id !== channel?.id)
            .map((c) => (
              <option key={c.id} value={c.id}>
                {c.name} (#{c.id})
              </option>
            ))}
        </select>
      </FormLabel>
      <FormLabel label="Einsortieren nach">
        <select
          value={order}
          onChange={(e) => setOrder(Number(e.target.value))}
        >
          <option value="0">An erster Stelle</option>
          {channels
            .filter((c) => c.parentId === parent && c.id !== channel?.id)
            .map((c) => (
              <option key={c.id} value={c.id}>
                {c.name} (#{c.id})
              </option>
            ))}
        </select>
      </FormLabel>
    </>
  );
  async function save(e: FormEvent) {
    e.preventDefault();
    const result = {
      name,
      topic,
      description,
      lifetime,
      maxClients,
      codec,
      codecQuality: quality,
      neededTalkPower: talkPower,
      ...(changePassword ? { newPassword: password } : {}),
      ...(detail
        ? { expectedRevision: detail.revision }
        : { parentId: parent, order }),
    };
    let id = channel?.id;
    if (
      await op.run(async (signal) => {
        const saved = await api.request<{ id: number }>(
          channel ? `/api/web/channels/${channel.id}` : "/api/web/channels",
          json(channel ? "PUT" : "POST", result, signal),
        );
        id = saved.id;
      }, "Channel gespeichert.")
    )
      onSaved(id!);
  }
  return (
    <div className="admin-body">
      {op.feedback}
      <form onSubmit={save}>
        <fieldset disabled={op.busy} className="admin-form">
          <legend className="sr-only">Channel-Einstellungen</legend>
          <FormLabel label="Channel-Name">
            <Input
              required
              maxLength={40}
              value={name}
              onChange={(e) => setName(e.target.value)}
            />
          </FormLabel>
          <FormLabel label="Thema">
            <Input
              maxLength={255}
              value={topic}
              onChange={(e) => setTopic(e.target.value)}
            />
          </FormLabel>
          <FormLabel label="Beschreibung">
            <textarea
              rows={4}
              maxLength={8192}
              value={description}
              onChange={(e) => setDescription(e.target.value)}
            />
          </FormLabel>
          <div className="admin-two">
            <FormLabel label="Channel-Typ">
              <select
                value={lifetime}
                onChange={(e) => setLifetime(e.target.value)}
              >
                <option value="permanent">Permanent</option>
                <option value="semi">Semi-permanent</option>
                <option value="temporary">Temporär</option>
              </select>
            </FormLabel>
            <FormLabel label="Maximale Clients" help="−1 bedeutet unbegrenzt.">
              <Input
                type="number"
                min={-1}
                max={2147483647}
                required
                value={maxClients}
                onChange={(e) => setMaxClients(Number(e.target.value))}
              />
            </FormLabel>
          </div>
          <details>
            <summary>Audio und Passwort</summary>
            <div className="admin-form mt-4">
              <FormLabel label="Audio-Codec">
                <select
                  value={codec}
                  onChange={(e) => setCodec(Number(e.target.value))}
                >
                  {[
                    "Speex Narrowband",
                    "Speex Wideband",
                    "Speex Ultra-Wideband",
                    "CELT Mono",
                    "Opus Voice",
                    "Opus Music",
                  ].map((name, id) => (
                    <option key={id} value={id}>
                      {name}
                    </option>
                  ))}
                </select>
              </FormLabel>
              <div className="admin-two">
                <FormLabel label="Codec-Qualität">
                  <Input
                    type="number"
                    min={0}
                    max={10}
                    required
                    value={quality}
                    onChange={(e) => setQuality(Number(e.target.value))}
                  />
                </FormLabel>
                <FormLabel label="Benötigte Talk-Power">
                  <Input
                    type="number"
                    min={0}
                    max={2147483647}
                    required
                    value={talkPower}
                    onChange={(e) => setTalkPower(Number(e.target.value))}
                  />
                </FormLabel>
              </div>
              <p className="admin-help">
                {detail?.hasPassword
                  ? "Ein Channel-Passwort ist gesetzt."
                  : "Kein Channel-Passwort gesetzt."}
              </p>
              <label className="admin-check">
                <input
                  type="checkbox"
                  checked={changePassword}
                  onChange={(e) => setChangePassword(e.target.checked)}
                />
                Passwort ändern
              </label>
              {changePassword && (
                <FormLabel
                  label="Neues Channel-Passwort"
                  help="Ein leeres Feld entfernt das Passwort."
                >
                  <Input
                    type="password"
                    autoComplete="new-password"
                    maxLength={128}
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                  />
                </FormLabel>
              )}
            </div>
          </details>
          {!channel && placement}
          <Button type="submit">
            {channel ? "Channel speichern" : "Channel erstellen"}
          </Button>
        </fieldset>
      </form>
      {channel && (
        <>
          <form
            onSubmit={async (e) => {
              e.preventDefault();
              if (
                await op.run(
                  (signal) =>
                    api.request(
                      `/api/web/channels/${channel.id}/move`,
                      json("POST", { parentId: parent, order }, signal),
                    ),
                  "Channel verschoben.",
                )
              )
                onSaved(channel.id);
            }}
          >
            <fieldset disabled={op.busy} className="admin-form admin-separated">
              <legend>Position ändern</legend>
              {placement}
              <Button variant="outline" type="submit">
                Channel verschieben
              </Button>
            </fieldset>
          </form>
          <PermissionEditor api={api} kind="channel" id={channel.id} />
          <DeleteAction
            name={detail!.name}
            busy={op.busy}
            onDelete={async () => {
              if (
                await op.run(
                  (signal) =>
                    api.request(
                      `/api/web/channels/${channel.id}`,
                      json("DELETE", { expectedName: detail!.name }, signal),
                    ),
                  "Channel gelöscht.",
                )
              )
                onDeleted();
            }}
          />
        </>
      )}
    </div>
  );
}

export function GroupsPage({ api, refresh }: { api: Api; refresh: number }) {
  const [kind, setKind] = useState<"server" | "channel">("server");
  return (
    <div className="admin-page">
      <div className="admin-tabs" role="group" aria-label="Gruppentyp">
        {(["server", "channel"] as const).map((value) => (
          <Button
            key={value}
            variant={kind === value ? "default" : "outline"}
            aria-pressed={kind === value}
            onClick={() => setKind(value)}
          >
            {value === "server" ? "Servergruppen" : "Channelgruppen"}
          </Button>
        ))}
      </div>
      <GroupManagement key={kind} api={api} kind={kind} refresh={refresh} />
    </div>
  );
}
function GroupManagement({
  api,
  kind,
  refresh,
}: {
  api: Api;
  kind: "server" | "channel";
  refresh: number;
}) {
  const [selected, setSelected] = useState<number>(),
    [creating, setCreating] = useState(false),
    [changed, setChanged] = useState(0),
    [notice, setNotice] = useState("");
  const groups = useResource<Group[]>(
    api,
    `/api/web/groups/${kind}`,
    refresh + changed,
  );
  const group = groups.data?.find((g) => g.id === selected);
  return (
    <>
      <p role="status" className="admin-notice">
        {notice}
      </p>
      <div className="admin-layout">
        <section className="panel">
          <div className="panel-heading">
            <h2>{kind === "server" ? "Servergruppen" : "Channelgruppen"}</h2>
            <Button
              variant="outline"
              onClick={() => {
                setCreating(true);
                setSelected(undefined);
                setNotice("");
              }}
            >
              Neue Gruppe
            </Button>
          </div>
          <ErrorBox message={groups.error} />
          {!groups.data && groups.loading ? (
            <p className="p-6" role="status">
              Gruppen werden geladen …
            </p>
          ) : (
            <div className="admin-list">
              {groups.data?.map((group) => (
                <button
                  key={group.id}
                  className="admin-list-item"
                  aria-pressed={!creating && selected === group.id}
                  onClick={() => {
                    setSelected(group.id);
                    setCreating(false);
                    setNotice("");
                  }}
                >
                  <span>{group.name}</span>
                  <small>
                    #{group.id}
                    {group.type !== 1 ? " · schreibgeschützt" : ""}
                  </small>
                </button>
              ))}
              {groups.data?.length === 0 && <p>Keine Gruppen gefunden.</p>}
            </div>
          )}
        </section>
        <section className="panel">
          <div className="panel-heading">
            <h2>{creating ? "Gruppe erstellen" : "Gruppen-Details"}</h2>
          </div>
          {creating || group ? (
            <GroupEditor
              key={`${kind}-${creating ? "new" : selected}-${refresh}`}
              api={api}
              kind={kind}
              group={creating ? undefined : group}
              groups={groups.data ?? []}
              onSaved={(id) => {
                setChanged((v) => v + 1);
                setSelected(id);
                setCreating(false);
                setNotice("Gruppe gespeichert.");
              }}
              onDeleted={() => {
                setSelected(undefined);
                setChanged((v) => v + 1);
                setNotice("Gruppe gelöscht.");
              }}
            />
          ) : (
            <p className="p-6 text-muted-foreground">
              Wähle eine Gruppe oder erstelle eine neue.
            </p>
          )}
        </section>
      </div>
    </>
  );
}
function GroupEditor({
  api,
  kind,
  group,
  groups,
  onSaved,
  onDeleted,
}: {
  api: Api;
  kind: "server" | "channel";
  group?: Group;
  groups: Group[];
  onSaved: (id: number) => void;
  onDeleted: () => void;
}) {
  const [name, setName] = useState(group?.name ?? ""),
    [expectedName, setExpectedName] = useState(group?.name),
    [copy, setCopy] = useState(0);
  const op = useOperation(),
    editable = !group || group.type === 1;
  return (
    <div className="admin-body">
      {op.feedback}
      {!editable && (
        <p className="admin-help mb-4">
          Vorlagen- und Query-Gruppen sind hier schreibgeschützt.
        </p>
      )}
      <form
        onSubmit={async (e) => {
          e.preventDefault();
          let id = group?.id;
          if (
            await op.run(async (signal) => {
              const saved = await api.request<{ id: number } | undefined>(
                `/api/web/groups/${kind}${group ? `/${group.id}` : ""}`,
                json(
                  group ? "PUT" : "POST",
                  {
                    name,
                    ...(group
                      ? { expectedName }
                      : copy
                        ? { copyFrom: copy }
                        : {}),
                  },
                  signal,
                ),
              );
              if (saved) id = saved.id;
            }, "Gruppe gespeichert.")
          ) {
            setExpectedName(name);
            onSaved(id!);
          }
        }}
      >
        <fieldset disabled={op.busy || !editable} className="admin-form">
          <legend className="sr-only">Gruppe bearbeiten</legend>
          <FormLabel label="Gruppenname">
            <Input
              required
              maxLength={30}
              value={name}
              onChange={(e) => setName(e.target.value)}
            />
          </FormLabel>
          {!group && (
            <FormLabel label="Rechte kopieren von">
              <select
                value={copy}
                onChange={(e) => setCopy(Number(e.target.value))}
              >
                <option value="0">Neue Gruppe mit Servervorgaben</option>
                {groups
                  .filter((g) => g.type === 0 || g.type === 1)
                  .map((g) => (
                    <option key={g.id} value={g.id}>
                      {g.name} (#{g.id})
                    </option>
                  ))}
              </select>
            </FormLabel>
          )}
          <Button type="submit">
            {group ? "Gruppenname speichern" : "Gruppe erstellen"}
          </Button>
        </fieldset>
      </form>
      {group && (
        <>
          <PermissionEditor
            api={api}
            kind={`${kind}-group`}
            id={group.id}
            readOnly={!editable}
          />
          <MemberEditor
            api={api}
            kind={kind}
            id={group.id}
            readOnly={!editable}
          />
          {editable && (
            <DeleteAction
              name={expectedName ?? group.name}
              group
              busy={op.busy}
              onDelete={async (force) => {
                if (
                  await op.run(
                    (signal) =>
                      api.request(
                        `/api/web/groups/${kind}/${group.id}`,
                        json("DELETE", { expectedName, force }, signal),
                      ),
                    "Gruppe gelöscht.",
                  )
                )
                  onDeleted();
              }}
            />
          )}
        </>
      )}
    </div>
  );
}
function PermissionEditor({
  api,
  kind,
  id,
  readOnly = false,
}: {
  api: Api;
  kind: string;
  id: number;
  readOnly?: boolean;
}) {
  const [reload, setReload] = useState(0),
    [search, setSearch] = useState(""),
    [permission, setPermission] = useState(""),
    [value, setValue] = useState(0),
    [skip, setSkip] = useState(false),
    [negated, setNegated] = useState(false);
  const catalog = useResource<CatalogEntry[]>(
    api,
    "/api/web/permission-catalog",
    0,
    60000,
  );
  const permissions = useResource<Permission[]>(
    api,
    `/api/web/permissions/${kind}/${id}`,
    reload,
    10000,
  );
  const op = useOperation(),
    datalist = useId();
  const entry = catalog.data?.find(
    (p) => p.name === permission || String(p.id) === permission,
  );
  const matching = permissions.data?.filter((p) =>
    `${catalog.data?.find((item) => item.id === p.id)?.name ?? ""} ${p.id}`
      .toLowerCase()
      .includes(search.toLowerCase()),
  );
  return (
    <section className="admin-separated" aria-label="Rechte bearbeiten">
      <h3>Rechte</h3>
      <ErrorBox message={catalog.error || permissions.error} />
      {op.feedback}
      <FormLabel label="Gesetzte Rechte durchsuchen">
        <Input
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          placeholder="Name oder Permission-ID"
        />
      </FormLabel>
      <div className="admin-rights">
        {matching?.map((p) => (
          <div key={p.id} className="admin-permission">
            <div>
              <code>
                {catalog.data?.find((item) => item.id === p.id)?.name ??
                  `Permission ${p.id}`}
              </code>
              <p className="admin-help">
                Wert {p.value}
                {p.negated ? " · Negated" : ""}
                {p.skip ? " · Skip" : ""}
              </p>
            </div>
            <div className="admin-actions">
              <Button
                type="button"
                variant="outline"
                disabled={op.busy || readOnly}
                aria-label={`Recht ${p.id} bearbeiten`}
                onClick={() => {
                  setPermission(
                    catalog.data?.find((item) => item.id === p.id)?.name ??
                      String(p.id),
                  );
                  setValue(p.value);
                  setNegated(p.negated);
                  setSkip(p.skip);
                }}
              >
                Bearbeiten
              </Button>
              <Button
                type="button"
                variant="outline"
                disabled={op.busy || readOnly}
                aria-label={`Recht ${p.id} entfernen`}
                onClick={async () => {
                  if (
                    await op.run(
                      (signal) =>
                        api.request(
                          `/api/web/permissions/${kind}/${id}/${p.id}`,
                          json("DELETE", undefined, signal),
                        ),
                      "Recht entfernt.",
                    )
                  )
                    setReload((v) => v + 1);
                }}
              >
                Entfernen
              </Button>
            </div>
          </div>
        ))}
        {permissions.loading && !permissions.data && (
          <p role="status">Rechte werden geladen …</p>
        )}
        {permissions.data && !matching?.length && (
          <p className="admin-help">Keine passenden gesetzten Rechte.</p>
        )}
      </div>
      <form
        onSubmit={async (e) => {
          e.preventDefault();
          if (
            await op.run((signal) => {
              const permissionId = entry?.id ?? Number(permission);
              if (!Number.isInteger(permissionId) || permissionId <= 0)
                throw new Error(
                  "Wähle ein Recht aus dem Katalog oder gib seine numerische ID ein.",
                );
              return api.request(
                `/api/web/permissions/${kind}/${id}/${permissionId}`,
                json("PUT", { value, negated, skip }, signal),
              );
            }, "Recht gespeichert.")
          )
            setReload((v) => v + 1);
        }}
      >
        <fieldset disabled={op.busy || readOnly} className="admin-form">
          <legend className="sr-only">Recht setzen</legend>
          <FormLabel label="Recht" help={entry?.description}>
            <Input
              required
              list={datalist}
              value={permission}
              onChange={(e) => setPermission(e.target.value)}
              placeholder="i_client_talk_power oder numerische ID"
            />
            <datalist id={datalist}>
              {catalog.data?.map((p) => (
                <option key={p.id} value={p.name}>
                  {p.description}
                </option>
              ))}
            </datalist>
          </FormLabel>
          <FormLabel label="Rechtewert">
            <Input
              type="number"
              min={-2147483648}
              max={2147483647}
              required
              value={value}
              onChange={(e) => setValue(Number(e.target.value))}
            />
          </FormLabel>
          {kind === "server-group" && (
            <div className="admin-actions">
              <label className="admin-check">
                <input
                  type="checkbox"
                  checked={negated}
                  onChange={(e) => setNegated(e.target.checked)}
                />
                Negated
              </label>
              <label className="admin-check">
                <input
                  type="checkbox"
                  checked={skip}
                  onChange={(e) => setSkip(e.target.checked)}
                />
                Skip
              </label>
            </div>
          )}
          <Button type="submit" variant="outline">
            Recht speichern
          </Button>
        </fieldset>
      </form>
    </section>
  );
}
function MemberEditor({
  api,
  kind,
  id,
  readOnly,
}: {
  api: Api;
  kind: "server" | "channel";
  id: number;
  readOnly: boolean;
}) {
  const [reload, setReload] = useState(0),
    [databaseId, setDatabaseId] = useState(""),
    [channelId, setChannelId] = useState("");
  const members = useResource<Member[]>(
    api,
    `/api/web/groups/${kind}/${id}/members`,
    reload,
    10000,
  );
  const clients = useResource<Client[]>(api, "/api/web/clients", 0, 15000),
    channels = useResource<Channel[]>(api, "/api/web/channels", 0, 15000);
  const op = useOperation(),
    datalist = useId();
  return (
    <section className="admin-separated" aria-label="Gruppenzuweisungen">
      <h3>{kind === "server" ? "Mitglieder" : "Channel-Zuweisungen"}</h3>
      <ErrorBox message={members.error || clients.error || channels.error} />
      {op.feedback}
      <div className="admin-rights">
        {members.data?.map((member) => (
          <div
            className="admin-permission"
            key={`${member.databaseId}-${member.channelId}`}
          >
            <div>
              <strong>
                {member.nickname ||
                  clients.data?.find((c) => c.databaseId === member.databaseId)
                    ?.nickname ||
                  `Client #${member.databaseId}`}
              </strong>
              <p className="admin-help">
                DB-ID {member.databaseId}
                {member.channelId
                  ? ` · ${channels.data?.find((c) => c.id === member.channelId)?.name ?? `Channel #${member.channelId}`}`
                  : ""}
              </p>
            </div>
            <Button
              variant="outline"
              disabled={op.busy || readOnly}
              aria-label={`Client ${member.databaseId}${member.channelId ? ` in Channel ${member.channelId}` : ""} entfernen`}
              onClick={async () => {
                if (
                  await op.run(
                    (signal) =>
                      api.request(
                        `/api/web/groups/${kind}/${id}/members/${member.databaseId}${kind === "channel" ? `/${member.channelId}` : ""}`,
                        json("DELETE", undefined, signal),
                      ),
                    kind === "server"
                      ? "Mitglied entfernt."
                      : "Auf Standard-Channelgruppe zurückgesetzt.",
                  )
                )
                  setReload((v) => v + 1);
              }}
            >
              {kind === "server" ? "Entfernen" : "Zurücksetzen"}
            </Button>
          </div>
        ))}
        {members.loading && !members.data && (
          <p role="status">Zuweisungen werden geladen …</p>
        )}
        {members.data?.length === 0 && (
          <p className="admin-help">Keine expliziten Zuweisungen gefunden.</p>
        )}
      </div>
      <form
        onSubmit={async (e) => {
          e.preventDefault();
          if (
            await op.run(
              (signal) =>
                api.request(
                  `/api/web/groups/${kind}/${id}/members`,
                  json(
                    "POST",
                    {
                      databaseId: Number(databaseId),
                      ...(kind === "channel"
                        ? { channelId: Number(channelId) }
                        : {}),
                    },
                    signal,
                  ),
                ),
              "Gruppe zugewiesen.",
            )
          )
            setReload((v) => v + 1);
        }}
      >
        <fieldset disabled={op.busy || readOnly} className="admin-form">
          <legend className="sr-only">Gruppe zuweisen</legend>
          <FormLabel
            label="Client-Datenbank-ID"
            help="Online-Client aus der Liste wählen oder die DB-ID eines Offline-Clients eingeben."
          >
            <Input
              required
              type="number"
              min={1}
              max={2147483647}
              list={datalist}
              value={databaseId}
              onChange={(e) => setDatabaseId(e.target.value)}
            />
            <datalist id={datalist}>
              {clients.data
                ?.filter((c) => c.databaseId)
                .map((c) => (
                  <option key={c.id} value={c.databaseId!}>
                    {c.nickname}
                  </option>
                ))}
            </datalist>
          </FormLabel>
          {kind === "channel" && (
            <FormLabel label="Channel für Zuweisung">
              <select
                required
                value={channelId}
                onChange={(e) => setChannelId(e.target.value)}
              >
                <option value="">Channel wählen</option>
                {channels.data?.map((c) => (
                  <option key={c.id} value={c.id}>
                    {c.name} (#{c.id})
                  </option>
                ))}
              </select>
            </FormLabel>
          )}
          <Button type="submit" variant="outline">
            Gruppe zuweisen
          </Button>
        </fieldset>
      </form>
    </section>
  );
}
