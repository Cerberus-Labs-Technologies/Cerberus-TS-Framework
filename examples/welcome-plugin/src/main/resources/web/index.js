export async function mount(root, api) {
  const settings = await api.request('/settings')
  root.innerHTML = `<section class="panel welcome-panel" aria-labelledby="welcome-heading">
    <div class="panel-heading"><h2 id="welcome-heading">Begrüßungstext</h2></div>
    <form class="welcome-form">
      <div><label for="welcome-greeting">Text vor dem Namen</label><input id="welcome-greeting" name="greeting" maxlength="100" required autocomplete="off" aria-describedby="welcome-hint welcome-status"><p id="welcome-hint">Der Befehl hello verwendet diesen Text vor dem Namen.</p></div>
      <button type="submit">Begrüßung speichern</button><p id="welcome-status" role="status" aria-live="polite"></p>
    </form>
  </section>`
  const form = root.querySelector('form')
  const input = root.querySelector('input')
  const button = root.querySelector('button')
  const status = root.querySelector('#welcome-status')
  input.value = settings.greeting
  let disposed = false
  async function submit(event) {
    event.preventDefault()
    if (!input.value.trim()) { input.setAttribute('aria-invalid', 'true'); status.textContent = 'Gib einen Begrüßungstext ein.'; input.focus(); return }
    button.disabled = true; status.textContent = 'Begrüßung wird gespeichert …'; input.removeAttribute('aria-invalid')
    try {
      const saved = await api.request('/settings', { method: 'PUT', body: JSON.stringify({ greeting: input.value }) })
      if (!disposed) { input.value = saved.greeting; status.textContent = 'Begrüßung gespeichert.'; api.notify('Begrüßung gespeichert.') }
    } catch (error) {
      if (!disposed) { status.textContent = error.message || 'Die Begrüßung konnte nicht gespeichert werden.'; input.setAttribute('aria-invalid', 'true') }
    } finally { if (!disposed) button.disabled = false }
  }
  form.addEventListener('submit', submit)
  return () => { disposed = true; form.removeEventListener('submit', submit) }
}
