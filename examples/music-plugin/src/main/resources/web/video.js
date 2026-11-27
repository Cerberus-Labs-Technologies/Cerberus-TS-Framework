export async function mountVideo(root, api) {
  let disposed = false, busy = false, bots = [], selected, timer, revision = 0;
  const abort = new AbortController();
  root.innerHTML = `
    <div class="music-layout">
      <aside class="music-sidebar">
        <section class="panel music-box">
          <h2>Video-Bots</h2><div id="video-list" class="music-bot-list" aria-label="Video-Bot auswählen"></div>
          <p id="video-empty" class="music-muted">Noch kein Video-Bot verbunden.</p>
        </section>
        <section class="panel music-box">
          <h2>Video-Bot verbinden</h2>
          <form id="video-create" class="music-form">
            <label>Video-Bot-ID<input name="id" required pattern="[a-z][a-z0-9_-]{0,19}" maxlength="20" placeholder="kino" autocomplete="off"></label>
            <label>Video-Anzeigename<input name="nickname" minlength="3" maxlength="30" placeholder="Cerberus Video" autocomplete="off"></label>
            <label>Video-Channel<select name="channelId" required></select></label>
            <label>Video-Channel-Passwort<input name="channelPassword" type="password" autocomplete="off"></label>
            <button class="music-primary">Video-Bot verbinden</button>
          </form>
        </section>
      </aside>
      <div class="music-main">
        <div id="video-error" class="music-error" role="alert" hidden></div>
        <p id="video-notice" class="music-notice" role="status" aria-live="polite"></p>
        <section class="panel music-box">
          <div class="music-section-head"><h2>Video abspielen</h2><span id="video-phase" class="music-status">Kein Video-Bot gewählt</span></div>
          <h3 id="video-title">Video bereitstellen</h3><p id="video-status" class="music-muted"></p>
          <p id="video-current" class="music-source"></p><p id="video-problem" class="music-error" role="alert" hidden></p>
          <p class="music-muted">Öffne den Stream beim Bot im TeamSpeak-6-Client. Bis zu 720p bei 25 Bildern/s und vier Zuschauer im selben Channel. Der Ton gehört zum Videostream.</p>
          <fieldset id="video-controls" disabled><legend class="music-sr">Video-Bot steuern</legend>
            <div class="music-actions"><button type="button" id="video-pause" data-video-action="pause">Video pausieren</button><button type="button" data-video-action="skip">Nächstes Video</button><button type="button" data-video-action="stop">Video stoppen & Queue leeren</button></div>
            <form id="video-source-form" class="music-form music-separated">
              <label>Videoquelle<input id="video-source" name="source" required maxlength="8192" placeholder="YouTube-Link, Video-URL oder Dateiname" autocomplete="off"></label>
              <small>Lokale MP4-, WebM-, MKV- und MOV-Dateien liegen im Musik-Dateiverzeichnis.</small>
              <div class="music-actions"><button class="music-primary" name="mode" value="play">Video jetzt abspielen</button><button name="mode" value="enqueue">Video zur Queue hinzufügen</button></div>
            </form>
            <form id="video-volume-form" class="music-inline-form music-separated"><label>Video-Lautstärke in %<input id="video-volume" name="volume" type="number" min="0" max="100" value="25" required></label><button>Video-Lautstärke setzen</button></form>
          </fieldset>
        </section>
        <section class="panel music-box"><h2>Video-Warteschlange</h2><ol id="video-queue"></ol><p id="video-queue-empty" class="music-muted">Die Video-Warteschlange ist leer.</p></section>
        <section class="panel music-box"><h2>Video-Verbindung</h2>
          <p class="music-muted">Vorschau: Das TS6-Streaming-Protokoll ist undokumentiert. Für Pelican die zusätzlichen UDP-Ports freigeben; Anleitung unter docs/video.md.</p>
          <fieldset id="video-connection" disabled><legend class="music-sr">Video-Verbindung ändern</legend>
            <form id="video-move-form" class="music-form"><label>Video-Ziel-Channel<select name="channelId" required></select></label><label>Video-Ziel-Passwort<input name="channelPassword" type="password" autocomplete="off"></label><button>Video-Bot verschieben</button><small>Beim Verschieben wird der Stream beendet und die Video-Warteschlange geleert.</small></form>
            <button type="button" id="video-remove" class="music-danger music-separated">Video-Bot trennen</button>
          </fieldset>
        </section>
      </div>
    </div>`;
  const find = selector => root.querySelector(selector);
  const text = (selector, value) => { find(selector).textContent = value; };
  const error = message => { text('#video-error', message || ''); find('#video-error').hidden = !message; };
  const chosen = () => bots.find(bot => bot.id === selected);
  function locks() {
    find('#video-controls').disabled = busy || !chosen()?.connected;
    find('#video-connection').disabled = busy || !chosen();
    for (const element of find('#video-create').elements) element.disabled = busy || root.dataset.available !== 'true';
    for (const element of find('#video-list').children) element.disabled = busy;
  }
  function render() {
    if (!chosen()) selected = bots[0]?.id;
    const list = find('#video-list');
    for (const element of [...list.children]) if (!bots.some(bot => bot.id === element.dataset.id)) element.remove();
    for (const bot of bots) {
      let button = [...list.children].find(element => element.dataset.id === bot.id);
      if (!button) { button = document.createElement('button'); button.type = 'button'; button.className = 'music-bot'; button.dataset.id = bot.id; button.onclick = () => { selected = bot.id; find('#video-volume').dataset.dirty = ''; render(); }; list.append(button); }
      button.textContent = `${bot.id} · ${bot.connected ? 'verbunden' : 'getrennt'}`; button.setAttribute('aria-pressed', String(bot.id === selected));
    }
    find('#video-empty').hidden = bots.length > 0;
    const bot = chosen();
    text('#video-phase', bot ? ({ LOADING: 'Lädt …', PLAYING: 'Stream läuft', PAUSED: 'Pausiert', ERROR: 'Fehler', STOPPED: 'Bereit' }[bot.phase] || bot.phase) : 'Kein Video-Bot gewählt');
    text('#video-title', bot?.title || 'Video bereitstellen'); text('#video-current', bot?.current || '');
    text('#video-status', bot ? `${bot.id} · Client ${bot.clientId} · Channel ${bot.channelId} · ${bot.viewers} / 4 Zuschauer` : 'Verbinde einen Video-Bot oder wähle ihn links aus.');
    text('#video-problem', [bot?.error, bot?.viewerError].filter(Boolean).join(' ')); find('#video-problem').hidden = !find('#video-problem').textContent;
    find('#video-pause').dataset.videoAction = bot?.paused ? 'resume' : 'pause'; text('#video-pause', bot?.paused ? 'Video fortsetzen' : 'Video pausieren');
    if (!find('#video-volume').dataset.dirty && document.activeElement !== find('#video-volume')) find('#video-volume').value = String(bot?.volume ?? 25);
    find('#video-queue').replaceChildren(...(bot?.queued || []).map(source => { const li = document.createElement('li'); li.textContent = source; return li; }));
    find('#video-queue-empty').hidden = !!bot?.queued?.length; locks();
  }
  async function refresh() {
    const current = ++revision; const response = await api.request('/video', { signal: abort.signal });
    if (disposed || current !== revision) return;
    bots = response.bots; root.dataset.available = String(response.available);
    if (!response.available) error('Video-Worker fehlt in dieser Installation. Installiere die vollständige Distribution.');
    render();
  }
  async function write(path, body, message, method = 'POST') {
    if (busy || disposed) return; busy = true; revision++; locks(); error(); text('#video-notice', 'Video-Aktion läuft …');
    try {
      const result = await api.request(path, { method, signal: abort.signal, ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
      if (disposed) return;
      if (path === '/video') { selected = result.id; find('#video-create [name="channelPassword"]').value = ''; }
      if (body?.action === 'volume') find('#video-volume').dataset.dirty = '';
      if (body?.action === 'move') find('#video-move-form [name="channelPassword"]').value = '';
      text('#video-notice', message); api.notify(message, 'success'); await refresh();
    } catch (failure) { if (!disposed) { error(failure.message); text('#video-notice', 'Status vor erneutem Senden prüfen.'); } }
    finally { if (!disposed) { busy = false; locks(); } }
  }
  const path = () => `/video/${encodeURIComponent(selected)}`;
  const action = (body, message) => write(path() + '/actions', body, message);
  find('#video-create').onsubmit = event => { event.preventDefault(); const data = new FormData(event.currentTarget); void write('/video', { id: data.get('id'), nickname: data.get('nickname'), channelId: Number(data.get('channelId')), channelPassword: data.get('channelPassword') || null }, 'Video-Bot verbunden.'); };
  find('#video-source-form').onsubmit = event => { event.preventDefault(); const mode = event.submitter?.value || 'play'; void action({ action: mode, source: find('#video-source').value.trim() }, mode === 'play' ? 'Video wird geladen.' : 'Video zur Queue hinzugefügt.'); };
  for (const button of root.querySelectorAll('[data-video-action]')) button.onclick = () => { void action({ action: button.dataset.videoAction }, 'Video-Aktion ausgeführt.'); };
  find('#video-volume').oninput = event => { event.target.dataset.dirty = 'true'; };
  find('#video-volume-form').onsubmit = event => { event.preventDefault(); void action({ action: 'volume', volume: Number(find('#video-volume').value) }, 'Video-Lautstärke gesetzt.'); };
  find('#video-move-form').onsubmit = event => { event.preventDefault(); const data = new FormData(event.currentTarget); void action({ action: 'move', channelId: Number(data.get('channelId')), channelPassword: data.get('channelPassword') || null }, 'Video-Bot verschoben.'); };
  find('#video-remove').onclick = () => { void write(path(), undefined, 'Video-Bot getrennt.', 'DELETE'); };
  try {
    await refresh(); const channels = await api.request('/channels', { signal: abort.signal });
    if (!disposed) for (const select of root.querySelectorAll('select[name="channelId"]')) for (const channel of channels) { const option = document.createElement('option'); option.value = String(channel.id); option.textContent = `${channel.name} (#${channel.id})`; select.append(option); }
  } catch (failure) { if (!disposed) error(failure.message); }
  async function poll() { if (disposed) return; if (!busy) try { await refresh(); } catch (failure) { if (!disposed) error(failure.message); } if (!disposed) timer = setTimeout(poll, 4000); }
  if (!disposed) timer = setTimeout(poll, 4000);
  return () => { disposed = true; abort.abort(); clearTimeout(timer); root.replaceChildren(); };
}
