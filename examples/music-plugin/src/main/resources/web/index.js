import { mountVideo } from "./video.js";

export async function mount(root, api) {
  let disposed = false;
  let busy = false;
  let bots = [];
  let selected;
  let timer;
  let revision = 0;
  let firstLoad = true;
  let pollingError = false;
  const abort = new AbortController();
  root.innerHTML = `
    <div class="music-layout">
      <aside class="music-sidebar">
        <section class="panel music-box" aria-labelledby="music-bots-title">
          <div class="music-section-head"><h2 id="music-bots-title">Deine Bots</h2><span id="music-count" class="music-muted">Lädt …</span></div>
          <div id="music-bots" class="music-bot-list" aria-label="Musik-Bot auswählen"></div>
          <p id="music-empty" hidden>Noch kein Musik-Bot verbunden. Erstelle einen Bot in deinem Musik-Channel.</p>
        </section>
        <details class="panel music-box" id="music-create-details" open>
          <summary>Bot verbinden</summary>
          <form id="music-create" class="music-form">
            <div><label>Bot-ID<input aria-describedby="music-id-help" name="id" required pattern="[a-z][a-z0-9_-]{0,19}" maxlength="20" placeholder="radio" autocomplete="off"></label><small id="music-id-help">Kleinbuchstaben, Ziffern, _ und −; maximal 20 Zeichen.</small></div>
            <label>Anzeigename<input name="nickname" minlength="3" maxlength="30" placeholder="Cerberus Music radio" autocomplete="off"></label>
            <label>Start-Channel<select name="channelId" required aria-label="Start-Channel"></select></label>
            <label>Channel-Passwort <span class="music-muted">(optional)</span><input name="channelPassword" type="password" autocomplete="off"></label>
            <button type="submit" class="music-primary">Bot verbinden</button>
          </form>
        </details>
      </aside>
      <div class="music-main">
        <div id="music-error" class="music-error" role="alert" hidden></div>
        <p id="music-notice" role="status" aria-live="polite" class="music-notice"></p>
        <section class="panel music-box" aria-labelledby="music-player-title">
          <div class="music-section-head"><h2 id="music-player-title">Wiedergabe</h2><span id="music-phase" class="music-status">Kein Bot gewählt</span></div>
          <h3 id="music-title">Musik bereitstellen</h3>
          <p id="music-subtitle" class="music-muted">Verbinde einen Bot oder wähle ihn links aus.</p>
          <p id="music-current" class="music-source"></p>
          <p id="music-playback-error" class="music-error" role="alert" hidden></p>
          <p id="music-presentation-error" class="music-error" hidden></p>
          <fieldset id="music-controls" disabled>
            <legend class="music-sr">Ausgewählten Musik-Bot steuern</legend>
            <div class="music-actions">
              <button type="button" data-action="pause" id="music-pause">Pausieren</button>
              <button type="button" data-action="skip">Nächster Titel</button>
              <button type="button" data-action="stop">Stoppen & Queue leeren</button>
            </div>
            <form id="music-source-form" class="music-form music-separated">
              <div><label>Musikquelle<input aria-describedby="music-source-help" id="music-source" name="source" required maxlength="8192" placeholder="YouTube-Link, Radio-URL oder Dateiname" autocomplete="off"></label><small id="music-source-help">Lokale Dateien liegen im Musik-Dateiverzeichnis des Servers.</small></div>
              <div class="music-actions"><button type="submit" name="mode" value="play" class="music-primary">Jetzt abspielen</button><button type="submit" name="mode" value="enqueue">Zur Queue hinzufügen</button></div>
              <small>„Jetzt abspielen“ ersetzt die laufende Wiedergabe und die Warteschlange.</small>
            </form>
            <form id="music-volume-form" class="music-inline-form music-separated">
              <label>Lautstärke in %<input name="volume" id="music-volume" type="number" min="0" max="100" required value="50"></label><button type="submit">Lautstärke setzen</button>
            </form>
          </fieldset>
        </section>
        <section class="panel music-box" aria-labelledby="music-queue-title">
          <div class="music-section-head"><h2 id="music-queue-title">Warteschlange</h2><span id="music-queue-count" class="music-muted">0 Titel</span></div>
          <ol id="music-queue"></ol><p id="music-queue-empty" class="music-muted">Die Warteschlange ist leer.</p>
        </section>
        <section class="panel music-box" aria-labelledby="music-connection-title">
          <h2 id="music-connection-title">Verbindung</h2>
          <p id="music-client" class="music-muted">Kein Bot gewählt.</p>
          <fieldset id="music-connection" disabled>
            <legend class="music-sr">Bot-Verbindung ändern</legend>
            <form id="music-move-form" class="music-form music-separated">
              <label>Ziel-Channel<select name="channelId" required aria-label="Ziel-Channel"></select></label>
              <label>Passwort des Ziel-Channels <span class="music-muted">(optional)</span><input name="channelPassword" type="password" autocomplete="off"></label>
              <button type="submit">In Channel verschieben</button>
            </form>
            <button type="button" id="music-remove" class="music-danger music-separated">Bot trennen</button>
            <small>Trennt den Voice-Client und entfernt seine Warteschlange.</small>
          </fieldset>
        </section>
      </div>
    </div>`;
  const audioPanel = document.createElement("div");
  while (root.firstChild) audioPanel.append(root.firstChild);
  const videoPanel = document.createElement("div"); videoPanel.hidden = true;
  const modes = document.createElement("div"); modes.className = "music-actions music-mode-nav"; modes.setAttribute("aria-label", "Wiedergabeart");
  const audioMode = document.createElement("button"); audioMode.type = "button"; audioMode.textContent = "Audio-Bots"; audioMode.setAttribute("aria-pressed", "true");
  const videoMode = document.createElement("button"); videoMode.type = "button"; videoMode.textContent = "Video-Bots (Vorschau)"; videoMode.setAttribute("aria-pressed", "false");
  audioMode.onclick = () => { audioPanel.hidden = false; videoPanel.hidden = true; audioMode.setAttribute("aria-pressed", "true"); videoMode.setAttribute("aria-pressed", "false"); };
  videoMode.onclick = () => { audioPanel.hidden = true; videoPanel.hidden = false; audioMode.setAttribute("aria-pressed", "false"); videoMode.setAttribute("aria-pressed", "true"); };
  modes.append(audioMode, videoMode); root.append(modes, audioPanel, videoPanel);
  const disposeVideo = await mountVideo(videoPanel, api);
  const find = (selector) => root.querySelector(selector);
  const text = (selector, value) => {
    find(selector).textContent = value;
  };
  const buttons = new Map();
  function showError(message = "") {
    text("#music-error", message);
    find("#music-error").hidden = !message;
  }
  function selectedBot() {
    return bots.find((bot) => bot.id === selected);
  }
  function locks() {
    find("#music-controls").disabled = busy || !selectedBot()?.connected;
    find("#music-connection").disabled = busy || !selectedBot();
    for (const input of find("#music-create").elements)
      input.disabled =
        busy || (input.type === "submit" && !find('[name="channelId"]').value);
    for (const button of buttons.values()) button.disabled = busy;
    root.setAttribute("aria-busy", String(busy));
  }
  function render() {
    if (!bots.some((bot) => bot.id === selected)) selected = bots[0]?.id;
    for (const [id, button] of buttons)
      if (!bots.some((bot) => bot.id === id)) {
        button.remove();
        buttons.delete(id);
      }
    for (const bot of bots) {
      let button = buttons.get(bot.id);
      if (!button) {
        button = document.createElement("button");
        button.type = "button";
        button.className = "music-bot";
        button.addEventListener("click", () => {
          selected = bot.id;
          find("#music-volume").dataset.dirty = "";
          render();
        });
        buttons.set(bot.id, button);
        find("#music-bots").append(button);
      }
      button.textContent =
        bot.id + (bot.connected ? " · verbunden" : " · getrennt");
      button.setAttribute("aria-pressed", String(bot.id === selected));
    }
    text(
      "#music-count",
      `${bots.filter((bot) => bot.connected).length} / ${bots.length} online`,
    );
    find("#music-empty").hidden = bots.length > 0;
    const bot = selectedBot();
    const phase = !bot
      ? "Kein Bot gewählt"
      : !bot.connected
        ? "Getrennt"
        : bot.paused
          ? "Pausiert"
          : {
              LOADING: "Lädt …",
              PLAYING: "Spielt",
              ERROR: "Fehler",
              STOPPED: "Bereit",
            }[bot.phase] || "Bereit";
    text("#music-phase", phase);
    text(
      "#music-title",
      bot?.metadata?.title ||
        (bot?.current
          ? "Wiedergabe"
          : bot
            ? "Bereit für Musik"
            : "Musik bereitstellen"),
    );
    text(
      "#music-subtitle",
      bot
        ? [bot.id, bot.metadata?.artist, bot.metadata?.station]
            .filter(Boolean)
            .join(" · ")
        : "Verbinde einen Bot oder wähle ihn links aus.",
    );
    text("#music-current", bot?.current || "");
    text(
      "#music-client",
      bot
        ? `Bot ${bot.id} · Client-ID ${bot.clientId} · ${bot.connected ? "Verbunden" : "Getrennt"} · ${bot.volume} % Lautstärke`
        : "Kein Bot gewählt.",
    );
    for (const [selector, error] of [
      ["#music-playback-error", bot?.error],
      [
        "#music-presentation-error",
        bot?.presentationError
          ? `Beschreibung / Avatar: ${bot.presentationError}`
          : null,
      ],
    ]) {
      text(selector, error || "");
      find(selector).hidden = !error;
    }
    const pause = find("#music-pause");
    pause.dataset.action = bot?.paused ? "resume" : "pause";
    pause.textContent = bot?.paused ? "Fortsetzen" : "Pausieren";
    const volume = find("#music-volume");
    if (!volume.dataset.dirty && document.activeElement !== volume)
      volume.value = String(bot?.volume ?? 50);
    const items = bot?.queued || [];
    find("#music-queue").replaceChildren(
      ...items.map((source) => {
        const li = document.createElement("li");
        li.textContent = source;
        return li;
      }),
    );
    text("#music-queue-count", `${items.length} Titel`);
    find("#music-queue-empty").hidden = items.length > 0;
    locks();
  }
  async function refresh() {
    const current = ++revision;
    const result = await api.request("/bots", { signal: abort.signal });
    if (disposed || current !== revision) return;
    bots = result;
    if (firstLoad) {
      find("#music-create-details").open = bots.length === 0;
      firstLoad = false;
    }
    if (pollingError) {
      pollingError = false;
      showError();
    }
    render();
  }
  async function write(path, body, message, method = "POST") {
    if (busy || disposed) return;
    busy = true;
    pollingError = false;
    revision++;
    locks();
    showError();
    text("#music-notice", "Aktion wird ausgeführt …");
    try {
      const result = await api.request(path, {
        method,
        signal: abort.signal,
        ...(body === undefined ? {} : { body: JSON.stringify(body) }),
      });
      if (disposed) return;
      if (path === "/bots") {
        selected = result.id;
        find("#music-create-details").open = false;
        find('#music-create [name="channelPassword"]').value = "";
      }
      if (body?.action === "volume") find("#music-volume").dataset.dirty = "";
      if (body?.action === "move")
        find('#music-move-form [name="channelPassword"]').value = "";
      text("#music-notice", message);
      api.notify(message, "success");
      await refresh();
    } catch (error) {
      if (!disposed) {
        showError(error.message);
        text("#music-notice", "Status vor erneutem Senden prüfen.");
      }
    } finally {
      if (!disposed) {
        busy = false;
        locks();
      }
    }
  }
  const botPath = () => `/bots/${encodeURIComponent(selected)}`;
  const action = (body, message) =>
    write(`${botPath()}/actions`, body, message);
  find("#music-create").addEventListener("submit", (event) => {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    void write(
      "/bots",
      {
        id: data.get("id"),
        nickname: data.get("nickname"),
        channelId: Number(data.get("channelId")),
        channelPassword: data.get("channelPassword") || null,
      },
      "Musik-Bot verbunden.",
    );
  });
  for (const button of root.querySelectorAll("[data-action]"))
    button.addEventListener("click", () => {
      const messages = {
        pause: "Pausiert.",
        resume: "Wiedergabe fortgesetzt.",
        skip: "Titel übersprungen.",
        stop: "Wiedergabe gestoppt und Warteschlange geleert.",
      };
      void action(
        { action: button.dataset.action },
        messages[button.dataset.action],
      );
    });
  find("#music-source-form").addEventListener("submit", (event) => {
    event.preventDefault();
    const mode = event.submitter?.value || "play";
    void action(
      { action: mode, source: find("#music-source").value.trim() },
      mode === "play"
        ? "Wiedergabe wird geladen."
        : "Zur Warteschlange hinzugefügt.",
    );
  });
  find("#music-volume").addEventListener("input", (event) => {
    event.target.dataset.dirty = "true";
  });
  find("#music-volume-form").addEventListener("submit", (event) => {
    event.preventDefault();
    void action(
      { action: "volume", volume: Number(find("#music-volume").value) },
      "Lautstärke gesetzt.",
    );
  });
  find("#music-move-form").addEventListener("submit", (event) => {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    void action(
      {
        action: "move",
        channelId: Number(data.get("channelId")),
        channelPassword: data.get("channelPassword") || null,
      },
      "Musik-Bot verschoben.",
    );
  });
  find("#music-remove").addEventListener("click", () => {
    void write(botPath(), undefined, "Musik-Bot getrennt.", "DELETE");
  });
  try {
    await refresh();
    try {
      const channels = await api.request("/channels", { signal: abort.signal });
      if (!disposed)
        for (const select of root.querySelectorAll(
          'select[name="channelId"]',
        )) {
          for (const channel of channels) {
            const option = document.createElement("option");
            option.value = String(channel.id);
            option.textContent = `${channel.name} (#${channel.id})`;
            select.append(option);
          }
        }
    } catch (error) {
      if (!disposed)
        showError(`Channels konnten nicht geladen werden: ${error.message}`);
    }
    if (!disposed) locks();
  } catch (error) {
    if (!disposed) showError(error.message);
  }
  async function poll() {
    if (disposed) return;
    if (!busy)
      try {
        await refresh();
      } catch (error) {
        if (!disposed) {
          pollingError = true;
          showError(
            `Status konnte nicht aktualisiert werden: ${error.message}`,
          );
        }
      }
    if (!disposed) timer = setTimeout(poll, 4000);
  }
  if (!disposed) timer = setTimeout(poll, 4000);
  return () => {
    disposed = true;
    revision++;
    clearTimeout(timer);
    abort.abort();
    disposeVideo();
    root.replaceChildren();
  };
}
