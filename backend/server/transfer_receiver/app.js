(() => {
  'use strict';
  // Fragment-only capability: neither initial page request nor access logs receive it.
  let token = window.location.hash.slice(1);
  window.history.replaceState(null, '', window.location.pathname);
  let documents = [];
  let busy = false;
  const byId = id => document.getElementById(id);
  const status = byId('status');
  const actionStatus = byId('action-status');
  const showError = message => { status.textContent = message; status.classList.add('error'); };
  if (!/^[A-Za-z0-9_-]{43}$/.test(token)) {
    byId('pin-form').hidden = true;
    showError('Open the complete transfer link from the sender. If you already opened it, request a new transfer.');
  }
  function download(text, filename) {
    const url = URL.createObjectURL(new Blob([text], { type: 'text/plain;charset=utf-8' }));
    const link = document.createElement('a');
    link.href = url; link.download = filename;
    document.body.appendChild(link); link.click(); link.remove();
    window.setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
  function renderReports() {
    const container = byId('documents');
    container.replaceChildren();
    documents.forEach((report, index) => {
      const article = document.createElement('article'); article.className = 'report-card';
      const heading = document.createElement('h2'); heading.className = 'no-print'; heading.textContent = `Report ${index + 1} · ${report.template}`;
      const metadata = document.createElement('p'); metadata.className = 'metadata no-print';
      metadata.textContent = `Version ${report.version} · ${report.status === 'signed' ? 'Signed' : 'Draft — not signed'}${report.signed_at ? ' · Signed ' + new Date(report.signed_at).toLocaleString() : ''}`;
      const text = document.createElement('div'); text.className = 'report'; text.textContent = report.text;
      const actions = document.createElement('div'); actions.className = 'actions no-print';
      const copy = document.createElement('button'); copy.type = 'button'; copy.className = 'secondary'; copy.textContent = 'Copy report';
      copy.addEventListener('click', async () => {
        try { await navigator.clipboard.writeText(report.text); actionStatus.textContent = `Report ${index + 1} copied.`; }
        catch (_) { actionStatus.textContent = 'Copy is unavailable in this browser. Select the report text or download it.'; }
      });
      const save = document.createElement('button'); save.type = 'button'; save.className = 'secondary'; save.textContent = 'Download text';
      save.addEventListener('click', () => download(report.text, `WoundCoPilot-report-${index + 1}.txt`));
      actions.append(copy, save); article.append(heading, metadata, text, actions); container.append(article);
    });
    byId('intro').hidden = true; byId('unlock').hidden = true; byId('received').hidden = false;
    byId('download-all').focus();
  }
  byId('pin-form').addEventListener('submit', async event => {
    event.preventDefault();
    if (busy || !token || !byId('pin-form').reportValidity()) return;
    busy = true; byId('open').disabled = true;
    status.classList.remove('error'); status.textContent = 'Opening your reports…';
    try {
      const response = await fetch('/api/v1/transfers/redeem', {
        method: 'POST', credentials: 'omit', cache: 'no-store', referrerPolicy: 'no-referrer',
        headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ token, code: byId('code').value })
      });
      const data = await response.json();
      if (!response.ok) {
        showError(typeof data.detail === 'string' ? data.detail : 'The request could not be completed. Check the code and try again.');
        if (response.status === 410) { token = ''; byId('pin-form').hidden = true; }
        return;
      }
      if (!Array.isArray(data.documents) || !data.documents.length || data.documents.some(report => typeof report.text !== 'string')) {
        throw new Error('Incomplete response');
      }
      documents = data.documents; token = ''; byId('code').value = ''; renderReports();
    } catch (_) {
      showError('The connection was interrupted. Try once more. If this transfer already opened, ask the sender for a new link.');
    } finally { busy = false; byId('open').disabled = false; }
  });
  byId('download-all').addEventListener('click', () => {
    const text = documents.length === 1 ? documents[0].text : documents.map(report => report.text).join('\n\n' + '—'.repeat(32) + '\n\n');
    download(text, 'WoundCoPilot-reports.txt');
  });
  byId('print').addEventListener('click', () => window.print());
  function clearPage() {
    token = ''; documents = []; byId('code').value = ''; byId('documents').replaceChildren();
    byId('received').hidden = true; byId('intro').hidden = false; byId('unlock').hidden = false;
    byId('pin-form').hidden = true; showError('This page has been cleared. Request a new transfer to receive reports again.');
  }
  byId('clear').addEventListener('click', clearPage);
  // Do not retain clinical content when a page is parked in browser back-forward cache.
  window.addEventListener('pagehide', clearPage);
})();
