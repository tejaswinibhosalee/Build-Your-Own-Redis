// mini-redis dashboard - vanilla JS, no dependencies.
//
// Security notes for readers:
//  * All server data is inserted with textContent (via el()), never innerHTML,
//    so a key named "<img onerror=...>" is displayed as text, not executed.
//  * Every API call goes through api(), which adds the session token and the
//    anti-CSRF header in exactly one place.
//  * The session token lives in sessionStorage (dies with the tab), and the
//    password itself is never stored.
'use strict';

(() => {
  const POLL_MS = 1000;
  const TOKEN_KEY = 'mini-redis-token';
  const MAX_CONSOLE_ENTRIES = 200;
  const MAX_ACTIVITY_ROWS = 120;

  const QUICK_COMMANDS = [
    'PING',
    'SET greeting "Hello, world!"',
    'GET greeting',
    'INCR visits',
    'RPUSH tasks design build ship',
    'LRANGE tasks 0 -1',
    'HSET user:42 name Sam role engineer',
    'HGETALL user:42',
    'SADD skills java redis networking',
    'SET temp "self-destructs" EX 15',
    'TTL temp',
    'KEYS *',
  ];

  const state = {
    token: safeStorage(() => sessionStorage.getItem(TOKEN_KEY)),
    session: null,
    started: false,
    online: false,
    selectedKey: null,
    selectedSnapshot: null,
    typeFilter: 'all',
    pattern: '',
    knownKeys: new Set(),
    keyExpiries: new Map(), // key -> absolute expiry (ms) for live countdowns
    lastActivityId: 0,
    history: [],
    historyIndex: -1,
    suggestIndex: -1,
    tick: 0,
    busy: {},
  };

  // ------------------------------------------------------------------
  // Small helpers
  // ------------------------------------------------------------------

  const $ = (id) => document.getElementById(id);

  /** Creates an element. Text is always set via textContent (XSS-safe). */
  function el(tag, opts = {}, ...children) {
    const node = document.createElement(tag);
    if (opts.class) node.className = opts.class;
    if (opts.text !== undefined) node.textContent = String(opts.text);
    if (opts.title) node.title = opts.title;
    if (opts.type) node.type = opts.type;
    if (opts.attrs) for (const [k, v] of Object.entries(opts.attrs)) node.setAttribute(k, v);
    if (opts.on) for (const [evt, fn] of Object.entries(opts.on)) node.addEventListener(evt, fn);
    for (const child of children) {
      if (child == null) continue;
      node.append(child instanceof Node ? child : document.createTextNode(String(child)));
    }
    return node;
  }

  function safeStorage(fn, fallback = null) {
    try { return fn(); } catch { return fallback; }
  }

  const fmtInt = (n) => Number(n || 0).toLocaleString('en-US');

  function fmtDuration(ms) {
    let s = Math.floor(ms / 1000);
    const d = Math.floor(s / 86400); s %= 86400;
    const h = Math.floor(s / 3600); s %= 3600;
    const m = Math.floor(s / 60); s %= 60;
    if (d) return `${d}d ${h}h`;
    if (h) return `${h}h ${m}m`;
    if (m) return `${m}m ${s}s`;
    return `${s}s`;
  }

  function fmtTtl(ms) {
    if (ms <= 0) return 'expired';
    const s = Math.ceil(ms / 1000);
    if (s < 60) return `${s}s`;
    if (s < 3600) return `${Math.floor(s / 60)}m ${s % 60}s`;
    return fmtDuration(ms);
  }

  const fmtMicros = (us) => (us >= 1000 ? `${(us / 1000).toFixed(1)}ms` : `${us}µs`);

  function fmtTime(ms) {
    const d = new Date(ms);
    return d.toTimeString().slice(0, 8);
  }

  /** Quotes an argument so the server's redis-cli style parser reads it back exactly. */
  function quoteArg(s) {
    if (s !== '' && /^[^\s"'\\]+$/.test(s)) return s;
    return '"' + s.replace(/\\/g, '\\\\').replace(/"/g, '\\"')
      .replace(/\n/g, '\\n').replace(/\r/g, '\\r').replace(/\t/g, '\\t') + '"';
  }

  let toastTimer;
  function toast(message) {
    const t = $('toast');
    t.textContent = message;
    t.classList.remove('hidden');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => t.classList.add('hidden'), 2600);
  }

  // ------------------------------------------------------------------
  // API
  // ------------------------------------------------------------------

  class ApiError extends Error {
    constructor(status, message) { super(message); this.status = status; }
  }

  async function api(path, { method = 'GET', body } = {}) {
    const headers = {};
    if (state.token) headers.Authorization = `Bearer ${state.token}`;
    if (method !== 'GET') {
      headers['X-Requested-With'] = 'mini-redis-dashboard'; // anti-CSRF marker
      headers['Content-Type'] = 'text/plain; charset=utf-8';
    }
    let res;
    try {
      res = await fetch(path, { method, headers, body, cache: 'no-store', credentials: 'omit' });
    } catch (e) {
      setOnline(false);
      throw new ApiError(0, 'Server unreachable');
    }
    setOnline(true);
    let data = null;
    try { data = await res.json(); } catch { /* empty body */ }
    if (res.status === 401 && path !== '/api/login') {
      showLogin();
      throw new ApiError(401, 'Authentication required');
    }
    if (!res.ok) throw new ApiError(res.status, (data && data.error) || res.statusText);
    return data;
  }

  function setOnline(online) {
    if (state.online === online && state.started) return;
    state.online = online;
    const pill = $('conn-status');
    pill.className = 'pill ' + (online ? 'pill-ok' : 'pill-err');
    $('conn-text').textContent = online ? 'Online' : 'Disconnected';
  }

  // ------------------------------------------------------------------
  // Login
  // ------------------------------------------------------------------

  function showLogin(message = '') {
    state.token = null;
    safeStorage(() => sessionStorage.removeItem(TOKEN_KEY));
    $('login').classList.remove('hidden');
    $('login-error').textContent = message;
    $('login-password').value = '';
    setTimeout(() => $('login-password').focus(), 0);
  }

  async function onLoginSubmit(event) {
    event.preventDefault();
    const btn = event.target.querySelector('button');
    btn.disabled = true;
    $('login-error').textContent = '';
    try {
      const { token } = await api('/api/login', { method: 'POST', body: $('login-password').value });
      state.token = token;
      safeStorage(() => sessionStorage.setItem(TOKEN_KEY, token));
      $('login-password').value = '';
      $('login').classList.add('hidden');
      await boot();
    } catch (e) {
      $('login-error').textContent = e.status === 401 ? 'Wrong password, try again.' : e.message;
    } finally {
      btn.disabled = false;
    }
  }

  async function logout() {
    try { await api('/api/logout', { method: 'POST' }); } catch { /* ignore */ }
    showLogin('You have been logged out.');
  }

  // ------------------------------------------------------------------
  // Boot & polling
  // ------------------------------------------------------------------

  async function boot() {
    let session;
    try {
      session = await api('/api/session');
    } catch {
      setTimeout(boot, 2000); // server not up yet; retry
      return;
    }
    if (session.authRequired && !session.authenticated) {
      showLogin();
      return;
    }
    state.session = session;
    const addr = `${session.bind}:${session.port}`;
    $('server-addr').textContent = addr;
    $('console-prompt').textContent = `${addr}>`;
    $('aof-pill').textContent = session.aofEnabled ? 'AOF persistence on' : 'In-memory only';
    $('auth-pill').textContent = session.authRequired ? '🔒 Password protected' : 'Local mode (no password)';
    $('logout-btn').classList.toggle('hidden', !session.authRequired);
    $('stat-version').textContent = `v${session.version}`;

    if (!state.started) {
      state.started = true;
      printWelcome();
      setInterval(poll, POLL_MS);
      setInterval(updateCountdowns, 250);
    }
    poll(true);
  }

  async function poll(force = false) {
    if (document.hidden && !force) return;
    if (!state.session) return;
    state.tick++;
    once('stats', refreshStats);
    once('activity', refreshActivity);
    if (force || state.tick % 2 === 0) {
      once('keys', refreshKeys);
      if (state.selectedKey !== null) once('inspect', () => inspect(state.selectedKey, true));
    }
  }

  /** Runs an async task unless the previous run of the same task is still in flight. */
  async function once(name, fn) {
    if (state.busy[name]) return;
    state.busy[name] = true;
    try { await fn(); } catch { /* surfaced via connection pill / login */ } finally { state.busy[name] = false; }
  }

  // ------------------------------------------------------------------
  // Stats
  // ------------------------------------------------------------------

  async function refreshStats() {
    const s = await api('/api/stats');
    $('stat-keys').textContent = fmtInt(s.keys);
    $('stat-ops').textContent = fmtInt(s.opsPerSecond);
    $('stat-ops-foot').textContent = `peak ${fmtInt(Math.max(...s.opsHistory))} in last 60s`;
    $('stat-total').textContent = fmtInt(s.totalCommands);
    $('stat-errors').textContent = `${fmtInt(s.totalErrors)} error${s.totalErrors === 1 ? '' : 's'}`;
    $('stat-clients').textContent = fmtInt(s.connectedClients);
    $('stat-clients-foot').textContent = `${fmtInt(s.totalConnections)} total connections`;
    $('stat-heap').textContent = `${(s.heapUsedBytes / 1048576).toFixed(0)} MB`;
    $('stat-heap-bar').style.width = `${Math.min(100, (100 * s.heapUsedBytes) / s.heapMaxBytes).toFixed(1)}%`;
    $('stat-uptime').textContent = fmtDuration(s.uptimeMillis);
    $('stat-keys-foot').textContent = s.keys === 1 ? 'key in the keyspace' : 'keys in the keyspace';

    renderTypes(s.keysByType, s.keys);
    renderTopCommands(s.topCommands);
    state.lastOps = s.opsHistory;
    drawChart(s.opsHistory);
  }

  const TYPES = ['string', 'list', 'hash', 'set'];

  function renderTypes(byType, total) {
    $('keyspace-total').textContent = `${fmtInt(total)} key${total === 1 ? '' : 's'}`;
    const bar = $('typebar');
    bar.replaceChildren();
    const legend = $('type-legend');
    legend.replaceChildren();
    for (const t of TYPES) {
      const n = byType[t] || 0;
      if (total > 0 && n > 0) {
        const seg = el('span', { class: `seg-${t}`, title: `${t}: ${n}` });
        seg.style.width = `${(100 * n) / total}%`;
        bar.append(seg);
      }
      const swatch = el('i', { class: `seg-${t}` });
      swatch.style.background = `var(--t-${t})`;
      legend.append(el('li', {}, swatch, `${t} `, el('b', { text: fmtInt(n) })));
    }
  }

  function renderTopCommands(top) {
    const list = $('top-commands');
    list.replaceChildren();
    if (!top.length) {
      list.append(el('li', { class: 'none', text: 'No commands yet. Run one in the console!' }));
      return;
    }
    const max = top[0].count;
    for (const { name, count } of top) {
      const fill = el('div', { class: 'fill' });
      fill.style.width = `${Math.max(3, (100 * count) / max)}%`;
      list.append(el('li', {},
        el('span', { class: 'name', text: name, title: name }),
        el('div', { class: 'track' }, fill),
        el('span', { class: 'num', text: fmtInt(count) })));
    }
  }

  // ------------------------------------------------------------------
  // Throughput chart (hand-drawn on <canvas>)
  // ------------------------------------------------------------------

  function niceMax(v) {
    if (v <= 5) return 5;
    const pow = 10 ** Math.floor(Math.log10(v));
    for (const m of [1, 2, 2.5, 5, 10]) if (m * pow >= v) return m * pow;
    return 10 * pow;
  }

  function drawChart(data) {
    if (!data) return;
    const canvas = $('ops-chart');
    const dpr = window.devicePixelRatio || 1;
    const w = canvas.clientWidth;
    const h = canvas.clientHeight;
    if (!w || !h) return;
    if (canvas.width !== Math.round(w * dpr) || canvas.height !== Math.round(h * dpr)) {
      canvas.width = Math.round(w * dpr);
      canvas.height = Math.round(h * dpr);
    }
    const ctx = canvas.getContext('2d');
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, w, h);

    const css = getComputedStyle(document.documentElement);
    const accent = css.getPropertyValue('--accent').trim();
    const grid = css.getPropertyValue('--border').trim();
    const muted = css.getPropertyValue('--muted').trim();

    const pad = { l: 40, r: 10, t: 10, b: 20 };
    const cw = w - pad.l - pad.r;
    const ch = h - pad.t - pad.b;
    const max = niceMax(Math.max(...data));

    ctx.font = '11px ui-monospace, Menlo, Consolas, monospace';
    ctx.fillStyle = muted;
    ctx.strokeStyle = grid;
    ctx.lineWidth = 1;
    ctx.textAlign = 'right';
    ctx.textBaseline = 'middle';
    for (let i = 0; i <= 4; i++) {
      const y = pad.t + ch - (ch * i) / 4;
      ctx.beginPath();
      ctx.moveTo(pad.l, Math.round(y) + 0.5);
      ctx.lineTo(w - pad.r, Math.round(y) + 0.5);
      ctx.stroke();
      ctx.fillText(fmtInt((max * i) / 4), pad.l - 6, y);
    }
    ctx.textAlign = 'center';
    ctx.textBaseline = 'top';
    [['-60s', 0], ['-30s', 0.5], ['now', 1]].forEach(([label, f]) => {
      ctx.fillText(label, pad.l + cw * f, h - pad.b + 5);
    });

    const x = (i) => pad.l + (cw * i) / (data.length - 1);
    const y = (v) => pad.t + ch - (ch * v) / max;

    ctx.beginPath();
    data.forEach((v, i) => (i ? ctx.lineTo(x(i), y(v)) : ctx.moveTo(x(i), y(v))));
    ctx.lineTo(x(data.length - 1), pad.t + ch);
    ctx.lineTo(x(0), pad.t + ch);
    ctx.closePath();
    const gradient = ctx.createLinearGradient(0, pad.t, 0, pad.t + ch);
    gradient.addColorStop(0, accent);
    gradient.addColorStop(1, 'transparent');
    ctx.globalAlpha = 0.25;
    ctx.fillStyle = gradient;
    ctx.fill();
    ctx.globalAlpha = 1;

    ctx.beginPath();
    data.forEach((v, i) => (i ? ctx.lineTo(x(i), y(v)) : ctx.moveTo(x(i), y(v))));
    ctx.strokeStyle = accent;
    ctx.lineWidth = 2;
    ctx.lineJoin = 'round';
    ctx.stroke();

    const lx = x(data.length - 1);
    const ly = y(data[data.length - 1]);
    ctx.beginPath();
    ctx.arc(lx, ly, 3.5, 0, Math.PI * 2);
    ctx.fillStyle = accent;
    ctx.fill();
  }

  // ------------------------------------------------------------------
  // Key browser
  // ------------------------------------------------------------------

  async function refreshKeys() {
    const params = new URLSearchParams({ pattern: state.pattern || '*', type: state.typeFilter });
    const data = await api(`/api/keys?${params}`);
    renderKeys(data);
  }

  function renderKeys(data) {
    const list = $('key-list');
    const scroll = list.scrollTop;
    list.replaceChildren();
    $('keys-count').textContent = data.truncated
      ? `showing ${fmtInt(data.keys.length)} of ${fmtInt(data.total)}`
      : `${fmtInt(data.total)} key${data.total === 1 ? '' : 's'}`;

    const now = Date.now();
    const seen = new Set();
    state.keyExpiries.clear();
    if (!data.keys.length) {
      list.append(el('li', { class: 'note', text: state.pattern || state.typeFilter !== 'all'
        ? 'No keys match this filter.'
        : 'The database is empty. Click "Load demo data" or run SET in the console.' }));
    }
    for (const row of data.keys) {
      seen.add(row.key);
      if (row.ttlMillis >= 0) state.keyExpiries.set(row.key, now + row.ttlMillis);
      const ttl = el('span', { class: 'ttl', attrs: { 'data-key': row.key } });
      const btn = el('button', {
        type: 'button',
        class: row.key === state.selectedKey ? 'sel' : '',
        title: row.clipped ? 'Key name too long to inspect' : row.key,
        on: { click: () => !row.clipped && selectKey(row.key) },
      },
      el('span', { class: `badge t-${row.type}`, text: row.type }),
      el('span', { class: 'key-name', text: row.key }),
      ttl);
      const li = el('li', {}, btn);
      if (state.knownKeys.size && !state.knownKeys.has(row.key)) li.classList.add('flash');
      list.append(li);
    }
    state.knownKeys = seen;
    list.scrollTop = scroll;
    updateCountdowns();
  }

  function updateCountdowns() {
    const now = Date.now();
    document.querySelectorAll('#key-list .ttl').forEach((node) => {
      const exp = state.keyExpiries.get(node.dataset.key);
      if (exp === undefined) { node.textContent = ''; return; }
      const left = exp - now;
      node.textContent = `⏱ ${fmtTtl(left)}`;
      node.className = 'ttl' + (left < 5000 ? ' urgent' : left < 15000 ? ' soon' : '');
    });
    const snap = state.selectedSnapshot;
    const ttlNode = $('insp-ttl');
    if (snap && ttlNode && snap.expiresAt) {
      const left = snap.expiresAt - now;
      ttlNode.textContent = `TTL ${fmtTtl(left)}`;
    }
  }

  // ------------------------------------------------------------------
  // Inspector
  // ------------------------------------------------------------------

  function selectKey(key) {
    state.selectedKey = key;
    document.querySelectorAll('#key-list button').forEach((b) => {
      b.classList.toggle('sel', b.title === key);
    });
    inspect(key, false);
  }

  async function inspect(key, background) {
    let data;
    try {
      data = await api(`/api/key?${new URLSearchParams({ name: key })}`);
    } catch (e) {
      if (e.status === 404) {
        renderInspectorGone(key);
        return;
      }
      throw e;
    }
    if (state.selectedKey !== key) return; // user clicked something else meanwhile
    // Skip re-rendering identical data during background refresh (keeps scroll & selection stable).
    const fingerprint = JSON.stringify([data.type, data.size, data.value]);
    if (background && state.selectedSnapshot && state.selectedSnapshot.fingerprint === fingerprint) {
      state.selectedSnapshot.expiresAt = data.ttlMillis >= 0 ? Date.now() + data.ttlMillis : null;
      return;
    }
    state.selectedSnapshot = { fingerprint, expiresAt: data.ttlMillis >= 0 ? Date.now() + data.ttlMillis : null };
    renderInspector(data);
  }

  function renderInspectorGone(key) {
    state.selectedSnapshot = null;
    $('inspector-actions').replaceChildren();
    $('inspector').replaceChildren(el('div', { class: 'empty' },
      el('div', { class: 'empty-icon', text: '∅' }),
      el('p', {}, el('span', { class: 'mono', text: key }), ' no longer exists. It was deleted or its TTL ran out.')));
  }

  function renderInspector(d) {
    const meta = el('div', { class: 'insp-meta' },
      el('span', { class: `badge t-${d.type}`, text: d.type }),
      el('span', { class: 'pill pill-ghost', text: sizeLabel(d) }),
      el('span', { class: 'pill pill-ghost', attrs: { id: 'insp-ttl' }, text: d.ttlMillis >= 0 ? `TTL ${fmtTtl(d.ttlMillis)}` : 'No expiry' }));

    let body;
    if (d.type === 'string') {
      body = el('pre', { class: 'insp-value', text: d.value });
    } else if (d.type === 'list') {
      body = table(['#', 'element'], d.value.map((v, i) => [String(i), v]));
    } else if (d.type === 'hash') {
      body = table(['field', 'value'], d.value.map((f) => [f.field, f.value]));
    } else {
      body = el('div', { class: 'members' }, ...d.value.map((m) => el('span', { class: 'member', text: m })));
    }
    const note = d.truncated ? el('p', { class: 'trunc-note', text: 'Large value: only the first part is shown.' }) : null;
    const parts = [el('h3', { class: 'insp-key', text: d.key }), meta, body, note].filter(Boolean);
    $('inspector').replaceChildren(...parts);

    const actions = $('inspector-actions');
    actions.replaceChildren();
    if (d.ttlMillis >= 0) {
      actions.append(el('button', { class: 'btn btn-small', type: 'button', text: 'Persist',
        title: 'Remove the TTL (PERSIST)', on: { click: () => runCommand(`PERSIST ${quoteArg(d.key)}`) } }));
    }
    actions.append(el('button', { class: 'btn btn-small btn-danger', type: 'button', text: 'Delete',
      on: { click: () => {
        if (window.confirm(`Delete key "${d.key}"?`)) runCommand(`DEL ${quoteArg(d.key)}`);
      } } }));
  }

  function sizeLabel(d) {
    const unit = { string: 'char', list: 'element', hash: 'field', set: 'member' }[d.type];
    return `${fmtInt(d.size)} ${unit}${d.size === 1 ? '' : 's'}`;
  }

  function table(headers, rows) {
    const thead = el('thead', {}, el('tr', {}, ...headers.map((h) => el('th', { text: h }))));
    const tbody = el('tbody', {}, ...rows.map((r) => el('tr', {},
      ...r.map((cell, i) => el('td', { class: i === 0 && headers[0] === '#' ? 'idx' : '', text: cell })))));
    return el('table', { class: 'insp-table' }, thead, tbody);
  }

  // ------------------------------------------------------------------
  // Activity feed
  // ------------------------------------------------------------------

  async function refreshActivity() {
    const rows = await api(`/api/activity?after=${state.lastActivityId}`);
    if (!rows.length) return;
    const list = $('activity');
    const note = list.querySelector('.note');
    if (note) note.remove();
    for (const r of rows) {
      state.lastActivityId = Math.max(state.lastActivityId, r.id);
      const origin = r.origin === 'DASHBOARD'
        ? el('span', { class: 'badge badge-web', text: 'WEB' })
        : el('span', { class: 'badge badge-tcp', text: 'TCP' });
      const li = el('li', { class: 'flash' },
        el('span', { class: 'time', text: fmtTime(r.time) }),
        origin,
        el('span', { class: 'args', title: `${r.command} ${r.args}` },
          el('span', { class: 'cmd' + (r.error ? ' err' : ''), text: r.command }), ' ', r.args),
        el('span', { class: 'lat', text: fmtMicros(r.micros) }));
      list.prepend(li);
    }
    while (list.children.length > MAX_ACTIVITY_ROWS) list.lastElementChild.remove();
  }

  // ------------------------------------------------------------------
  // Console
  // ------------------------------------------------------------------

  function printWelcome() {
    const out = $('console-output');
    out.append(el('div', { class: 'c-entry' },
      el('div', { class: 'line c-hint', text: `Connected to mini-redis v${state.session.version}. This console talks to the same engine as redis-cli.` }),
      el('div', { class: 'line c-hint', text: 'Try an example below, use ↑/↓ for history, Tab to autocomplete, or type "help".' })));
  }

  async function runCommand(line) {
    line = line.trim();
    if (!line) return;
    if (state.history[state.history.length - 1] !== line) state.history.push(line);
    state.historyIndex = state.history.length;

    const lower = line.toLowerCase();
    if (lower === 'clear') { $('console-output').replaceChildren(); return; }
    if (lower === 'help') { printHelp(); return; }

    const entry = el('div', { class: 'c-entry' },
      el('div', { class: 'line c-cmd' }, el('span', { class: 'prompt-ghost', text: '› ' }), line));
    appendConsole(entry);

    try {
      const res = await api('/api/command', { method: 'POST', body: line });
      entry.firstChild.append(el('span', { class: 'c-meta', text: fmtMicros(res.micros) }));
      entry.append(el('div', { class: 'c-reply' }, ...formatReply(res.reply, '')));
      entry.append(el('div', { class: 'wire' },
        wireBox('Request → server', res.requestResp),
        wireBox('Reply ← server', res.replyResp)));
    } catch (e) {
      entry.append(el('div', { class: 'line c-err', text: `(dashboard) ${e.message}` }));
    }
    scrollConsole();
    poll(true);
  }

  function appendConsole(node) {
    const out = $('console-output');
    out.append(node);
    while (out.children.length > MAX_CONSOLE_ENTRIES) out.firstElementChild.remove();
    scrollConsole();
  }

  function scrollConsole() {
    const out = $('console-output');
    out.scrollTop = out.scrollHeight;
  }

  function printHelp() {
    const cmds = (state.session.commands || []).join('  ');
    appendConsole(el('div', { class: 'c-entry' },
      el('div', { class: 'line c-hint', text: 'Supported commands:' }),
      el('div', { class: 'line', text: cmds }),
      el('div', { class: 'line c-hint', text: 'Console-only: help, clear. Quote values with spaces: SET k "hello world"' })));
  }

  /** Formats a reply the way redis-cli prints it. Returns DOM line nodes. */
  function formatReply(reply, indent) {
    const line = (cls, text) => el('div', { class: `line ${cls}`, text: indent + text });
    switch (reply.type) {
      case 'simple': return [line('c-ok', reply.value)];
      case 'error': return [line('c-err', `(error) ${reply.value}`)];
      case 'integer': return [line('c-int', `(integer) ${reply.value}`)];
      case 'nil': return [line('c-nil', '(nil)')];
      case 'bulk': return [line('c-str', JSON.stringify(reply.value))];
      case 'array': {
        if (!reply.value.length) return [line('c-nil', '(empty array)')];
        const nodes = [];
        const width = String(reply.value.length).length;
        reply.value.forEach((item, i) => {
          const label = `${String(i + 1).padStart(width)}) `;
          const inner = formatReply(item, ' '.repeat(indent.length + label.length));
          inner[0].textContent = indent + label + inner[0].textContent.slice(indent.length + label.length);
          nodes.push(...inner);
        });
        if (reply.truncated) nodes.push(line('c-hint', '… (truncated)'));
        return nodes;
      }
      default: return [line('', String(reply.value))];
    }
  }

  /**
   * Renders raw RESP bytes, making the invisible \r\n terminators visible and
   * highlighting the one-byte type prefixes (* $ + - :) that frame each value.
   */
  function wireBox(title, resp) {
    const pre = el('pre', { class: 'wire-body' });
    const parts = resp.split('\r\n');
    let expectPayload = false;
    parts.forEach((part, i) => {
      if (i === parts.length - 1 && part === '') return; // trailing split artifact
      if (!expectPayload && part.length && '*$+-:'.includes(part[0])) {
        pre.append(el('span', { class: 'pfx', text: part[0] }), part.slice(1));
        expectPayload = part[0] === '$' && !part.startsWith('$-1');
      } else {
        pre.append(part);
        expectPayload = false;
      }
      if (i < parts.length - 1) pre.append(el('span', { class: 'crlf', text: '\\r\\n' }), '\n');
    });
    const bytes = new TextEncoder().encode(resp).length;
    return el('div', { class: 'wire-box' },
      el('div', { class: 'wire-title' }, el('span', { text: title }), el('span', { text: `${bytes} bytes` })),
      pre);
  }

  // ---- input: history + autocomplete ----

  function currentSuggestions() {
    const input = $('console-line');
    const value = input.value;
    if (!value || /\s/.test(value)) return [];
    const prefix = value.toUpperCase();
    return (state.session?.commands || []).filter((c) => c.startsWith(prefix) && c !== prefix).slice(0, 8);
  }

  function renderSuggestions() {
    const box = $('suggest');
    const items = currentSuggestions();
    box.replaceChildren();
    if (!items.length) { box.classList.add('hidden'); state.suggestIndex = -1; return; }
    if (state.suggestIndex >= items.length) state.suggestIndex = items.length - 1;
    items.forEach((cmd, i) => {
      box.append(el('button', {
        type: 'button', class: i === state.suggestIndex ? 'sel' : '', text: cmd,
        attrs: { role: 'option' },
        on: { mousedown: (e) => { e.preventDefault(); acceptSuggestion(cmd); } },
      }));
    });
    box.classList.remove('hidden');
  }

  function acceptSuggestion(cmd) {
    const input = $('console-line');
    input.value = `${cmd} `;
    state.suggestIndex = -1;
    renderSuggestions();
    input.focus();
  }

  function onConsoleKey(e) {
    const input = e.target;
    const suggestions = currentSuggestions();
    const open = suggestions.length > 0;
    if (e.key === 'Tab' && open) {
      e.preventDefault();
      acceptSuggestion(suggestions[Math.max(0, state.suggestIndex)]);
    } else if (e.key === 'ArrowDown' && open) {
      e.preventDefault();
      state.suggestIndex = (state.suggestIndex + 1) % suggestions.length;
      renderSuggestions();
    } else if (e.key === 'ArrowUp' && open && state.suggestIndex >= 0) {
      e.preventDefault();
      state.suggestIndex -= 1;
      renderSuggestions();
    } else if (e.key === 'Enter' && open && state.suggestIndex >= 0) {
      e.preventDefault();
      acceptSuggestion(suggestions[state.suggestIndex]);
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      if (state.historyIndex > 0) state.historyIndex -= 1;
      input.value = state.history[state.historyIndex] ?? input.value;
    } else if (e.key === 'ArrowDown') {
      e.preventDefault();
      if (state.historyIndex < state.history.length) state.historyIndex += 1;
      input.value = state.history[state.historyIndex] ?? '';
    } else if (e.key === 'Escape') {
      $('suggest').classList.add('hidden');
    } else if (e.key === 'l' && e.ctrlKey) {
      e.preventDefault();
      $('console-output').replaceChildren();
    }
  }

  // ------------------------------------------------------------------
  // Theme
  // ------------------------------------------------------------------

  function applyTheme(theme) {
    if (theme) document.documentElement.dataset.theme = theme;
    else delete document.documentElement.dataset.theme;
    const effective = theme || (window.matchMedia('(prefers-color-scheme: light)').matches ? 'light' : 'dark');
    $('theme-btn').textContent = effective === 'dark' ? '☀' : '☾';
    drawChart(state.lastOps);
  }

  function toggleTheme() {
    const current = document.documentElement.dataset.theme
      || (window.matchMedia('(prefers-color-scheme: light)').matches ? 'light' : 'dark');
    const next = current === 'dark' ? 'light' : 'dark';
    safeStorage(() => localStorage.setItem('mini-redis-theme', next));
    applyTheme(next);
  }

  // ------------------------------------------------------------------
  // Wiring
  // ------------------------------------------------------------------

  function init() {
    applyTheme(safeStorage(() => localStorage.getItem('mini-redis-theme')));

    $('login-form').addEventListener('submit', onLoginSubmit);
    $('logout-btn').addEventListener('click', logout);
    $('theme-btn').addEventListener('click', toggleTheme);

    $('demo-btn').addEventListener('click', async () => {
      try {
        const r = await api('/api/demo-data', { method: 'POST' });
        toast(`Loaded demo data (${r.executed} commands)`);
        poll(true);
      } catch (e) {
        toast(`Could not load demo data: ${e.message}`);
      }
    });

    const form = $('console-form');
    const input = $('console-line');
    form.addEventListener('submit', (e) => {
      e.preventDefault();
      const line = input.value;
      input.value = '';
      renderSuggestions();
      runCommand(line);
    });
    input.addEventListener('keydown', onConsoleKey);
    input.addEventListener('input', () => { state.suggestIndex = -1; renderSuggestions(); });
    input.addEventListener('blur', () => setTimeout(() => $('suggest').classList.add('hidden'), 100));

    $('console-clear').addEventListener('click', () => $('console-output').replaceChildren());
    const respToggle = $('resp-toggle');
    const setResp = () => $('console-output').classList.toggle('hide-resp', !respToggle.checked);
    respToggle.addEventListener('change', setResp);
    setResp();

    const quick = $('quick-commands');
    for (const cmd of QUICK_COMMANDS) {
      quick.append(el('button', { type: 'button', class: 'chip', text: cmd, on: { click: () => runCommand(cmd) } }));
    }

    let searchTimer;
    $('key-search').addEventListener('input', (e) => {
      clearTimeout(searchTimer);
      searchTimer = setTimeout(() => {
        state.pattern = e.target.value.trim();
        once('keys', refreshKeys);
      }, 200);
    });
    $('type-filter').addEventListener('click', (e) => {
      const chip = e.target.closest('.chip');
      if (!chip) return;
      state.typeFilter = chip.dataset.type;
      document.querySelectorAll('#type-filter .chip').forEach((c) => c.classList.toggle('active', c === chip));
      once('keys', refreshKeys);
    });

    $('activity').append(el('li', { class: 'note', text: 'Waiting for commands… run one here or from redis-cli.' }));

    window.addEventListener('resize', () => drawChart(state.lastOps));
    document.addEventListener('visibilitychange', () => { if (!document.hidden) poll(true); });

    boot();
  }

  init();
})();
