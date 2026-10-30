(() => {
  'use strict';
  const byId = id => document.getElementById(id);
  const API = '/api/v1';
  const POLL_MS = 5000;
  let csrf = '';
  let timer = null;
  let busy = false;
  const copied = new Set();

  function show(view) {
    byId('sign-in').hidden = view !== 'sign-in';
    byId('inbox').hidden = view !== 'inbox';
    byId('account').hidden = view !== 'inbox';
  }

  async function call(path, options = {}) {
    const headers = { ...(options.headers || {}) };
    if (options.body) headers['Content-Type'] = 'application/json';
    if (options.method && options.method !== 'GET' && csrf) headers['X-CSRF-Token'] = csrf;
    const response = await fetch(API + path, { ...options, headers, credentials: 'same-origin', cache: 'no-store', referrerPolicy: 'no-referrer' });
    let data = null;
    if (response.status !== 204) { try { data = await response.json(); } catch (_) { data = null; } }
    return { response, data };
  }

  function message(data, fallback) { return data && typeof data.detail === 'string' ? data.detail : fallback; }

  async function startSession() {
    const { response, data } = await call('/auth/session');
    if (response.ok && data && data.csrf_token) {
      if (data.requires_legal_acceptance) {
        show('sign-in');
        byId('sign-in-status').textContent = 'Open the Amilya phone app and accept the current Terms first, then sign in here.';
        return;
      }
      csrf = data.csrf_token; show('inbox'); refresh(); startPolling(); return;
    }
    stopPolling(); csrf = ''; show('sign-in');
  }

  byId('sign-in-form').addEventListener('submit', async event => {
    event.preventDefault();
    if (busy) return;
    busy = true; byId('sign-in-button').disabled = true;
    const status = byId('sign-in-status'); status.classList.remove('error'); status.textContent = 'Signing in…';
    try {
      const { response, data } = await call('/auth/login', { method: 'POST', body: JSON.stringify({ email: byId('email').value, password: byId('password').value }) });
      byId('password').value = '';
      if (!response.ok) { status.classList.add('error'); status.textContent = message(data, 'Sign in did not work. Check your email and password.'); return; }
      status.textContent = '';
      await startSession();
    } catch (_) {
      status.classList.add('error'); status.textContent = 'No connection. Check the internet and try again.';
    } finally { busy = false; byId('sign-in-button').disabled = false; }
  });

  function startPolling() {
    stopPolling();
    timer = window.setInterval(() => { if (document.visibilityState === 'visible') refresh(); }, POLL_MS);
  }
  function stopPolling() { if (timer) window.clearInterval(timer); timer = null; }

  async function refresh() {
    try {
      const { response, data } = await call('/desk/notes');
      if (response.status === 401) { stopPolling(); csrf = ''; clearScreen(); show('sign-in'); byId('sign-in-status').textContent = 'You were signed out. Sign in again to keep receiving notes.'; return; }
      if (!response.ok || !data || !Array.isArray(data.notes)) { byId('live').textContent = 'Reconnecting…'; return; }
      byId('live').textContent = 'Connected. New notes appear here by themselves.';
      render(data.notes.filter(note => !copied.has(note.id + note.updated_at)));
    } catch (_) { byId('live').textContent = 'Reconnecting…'; }
  }

  function time(value) {
    try { return new Date(value).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' }); } catch (_) { return ''; }
  }

  function render(notes) {
    const container = byId('notes');
    const seen = new Set();
    notes.forEach(note => {
      const key = note.id + note.updated_at;
      seen.add(key);
      if (container.querySelector(`[data-key="${CSS.escape(key)}"]`)) return;
      // A resend of the same visit replaces the older card.
      container.querySelectorAll(`[data-id="${CSS.escape(note.id)}"]`).forEach(old => old.remove());
      container.prepend(card(note, key));
    });
    container.querySelectorAll('[data-key]').forEach(element => {
      if (!seen.has(element.dataset.key) && !element.classList.contains('done')) element.remove();
    });
    byId('empty').hidden = container.children.length > 0;
  }

  function card(note, key) {
    const article = document.createElement('article');
    article.className = 'panel note'; article.dataset.key = key; article.dataset.id = note.id;
    const meta = document.createElement('div'); meta.className = 'meta';
    const title = document.createElement('h2'); title.textContent = note.title;
    const when = document.createElement('span'); when.className = 'small'; when.textContent = `Received ${time(note.updated_at)} · erased by ${time(note.expires_at)} if not copied`;
    meta.append(title, when);
    const text = document.createElement('div'); text.className = 'text'; text.textContent = note.text; text.tabIndex = 0;
    const actions = document.createElement('div'); actions.className = 'actions';
    const copy = document.createElement('button'); copy.type = 'button'; copy.className = 'copy'; copy.textContent = 'Copy';
    const remove = document.createElement('button'); remove.type = 'button'; remove.className = 'secondary'; remove.textContent = 'Remove';
    copy.addEventListener('click', async () => {
      try { await navigator.clipboard.writeText(note.text); }
      catch (_) {
        // Keep the note on the server if the browser blocked copying, so nothing is lost.
        const range = document.createRange(); range.selectNodeContents(text);
        const selection = window.getSelection(); selection.removeAllRanges(); selection.addRange(range);
        byId('action-status').textContent = 'This browser blocked Copy. The note is selected: press Ctrl+C (or Cmd+C).';
        return;
      }
      copied.add(key);
      copy.textContent = 'Copied'; copy.disabled = true; remove.hidden = true; article.classList.add('done');
      byId('action-status').textContent = `${note.title} copied. Paste it into your chart. It has been erased from the server.`;
      await erase(note.id);
    });
    remove.addEventListener('click', async () => { copied.add(key); article.remove(); byId('empty').hidden = byId('notes').children.length > 0; await erase(note.id); });
    actions.append(copy, remove);
    article.append(meta, text, actions);
    return article;
  }

  async function erase(id) {
    try { await call('/desk/notes/' + encodeURIComponent(id), { method: 'DELETE' }); } catch (_) { /* it still expires at the end of the day */ }
  }

  function clearScreen() { byId('notes').replaceChildren(); byId('action-status').textContent = ''; }

  byId('clear-all').addEventListener('click', async () => {
    if (!window.confirm('Erase every waiting note from the server? Copy anything you still need first.')) return;
    const { response } = await call('/desk/notes', { method: 'DELETE' });
    if (response.ok) { clearScreen(); byId('empty').hidden = false; byId('action-status').textContent = 'All waiting notes were erased.'; }
  });

  byId('sign-out').addEventListener('click', async () => {
    try { await call('/auth/logout', { method: 'POST' }); } catch (_) { /* signed out locally anyway */ }
    stopPolling(); csrf = ''; clearScreen(); show('sign-in');
  });

  // Do not keep clinical text in a page parked in the browser's back-forward cache.
  window.addEventListener('pagehide', clearScreen);
  document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'visible' && csrf) refresh(); });
  startSession();
})();
