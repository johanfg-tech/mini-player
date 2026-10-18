// Mini Player — ventana flotante que controla YouTube dentro de Brave.
// Brave se abre con su propio perfil, minimizado, y reproduce la música
// (con el bloqueo de anuncios de Brave Shields). Este programa solo es el control.

const { app, BrowserWindow, ipcMain, dialog, globalShortcut, screen } = require('electron');
const path = require('path');
const fs = require('fs');
const { spawn } = require('child_process');
const WebSocket = require('ws');

const PORT = 9333;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ---------------- Configuración guardada ----------------
let cfg = { bravePath: null, favorites: [], shuffle: false, repeat: false, pinned: true, pos: null };
const cfgFile = () => path.join(app.getPath('userData'), 'config.json');
function loadCfg() { try { Object.assign(cfg, JSON.parse(fs.readFileSync(cfgFile(), 'utf8'))); } catch {} }
function saveCfg() { try { fs.writeFileSync(cfgFile(), JSON.stringify(cfg, null, 2)); } catch {} }

function findBrave() {
  const pf = process.env['ProgramFiles'] || 'C:\\Program Files';
  const pf86 = process.env['ProgramFiles(x86)'] || 'C:\\Program Files (x86)';
  const local = process.env['LOCALAPPDATA'] || '';
  const rel = ['BraveSoftware', 'Brave-Browser', 'Application', 'brave.exe'];
  const candidates = [
    cfg.bravePath,
    path.join(pf, ...rel),
    path.join(pf86, ...rel),
    local && path.join(local, ...rel),
  ];
  return candidates.find((p) => p && fs.existsSync(p)) || null;
}

// ---------------- Cliente mínimo de Chrome DevTools Protocol ----------------
class CDP {
  constructor(url) {
    this.ws = new WebSocket(url, { perMessageDeflate: false });
    this.id = 0;
    this.pending = new Map();
    this.handlers = [];
    this.closed = false;
    this.ready = new Promise((res, rej) => { this.ws.once('open', res); this.ws.once('error', rej); });
    this.ws.on('message', (raw) => {
      let d; try { d = JSON.parse(raw); } catch { return; }
      if (d.id && this.pending.has(d.id)) {
        const { res, rej } = this.pending.get(d.id);
        this.pending.delete(d.id);
        d.error ? rej(new Error(d.error.message)) : res(d.result);
      } else if (d.method) {
        this.handlers.forEach((h) => h(d));
      }
    });
    this.ws.on('close', () => {
      this.closed = true;
      for (const { rej } of this.pending.values()) rej(new Error('closed'));
      this.pending.clear();
      this.handlers.forEach((h) => h({ method: '__closed' }));
    });
    this.ws.on('error', () => {});
  }
  send(method, params = {}, sessionId) {
    if (this.closed) return Promise.reject(new Error('closed'));
    const id = ++this.id;
    const msg = { id, method, params };
    if (sessionId) msg.sessionId = sessionId;
    this.ws.send(JSON.stringify(msg));
    return new Promise((res, rej) => {
      this.pending.set(id, { res, rej });
      setTimeout(() => {
        if (this.pending.has(id)) { this.pending.delete(id); rej(new Error('timeout ' + method)); }
      }, 15000);
    });
  }
  on(h) { this.handlers.push(h); }
}

let win = null;
let cdp = null;
let session = null;
let targetId = null;
let braveShown = false;
let starting = null;
let lastState = null;
let queue = [];
let qIndex = -1;

function sendUI(channel, data) { if (win && !win.isDestroyed()) win.webContents.send(channel, data); }
function status(kind, msg = '') { sendUI('status', { kind, msg }); }

async function getJSON(p) {
  const r = await fetch(`http://127.0.0.1:${PORT}${p}`);
  return r.json();
}

async function startBrave() {
  if (starting) return starting;
  starting = (async () => {
    status('connecting');
    let ver = null;
    try { ver = await getJSON('/json/version'); } catch {}
    if (!ver) {
      const exe = findBrave();
      if (!exe) { status('nobrave'); throw new Error('nobrave'); }
      const profile = path.join(app.getPath('userData'), 'brave-profile');
      const child = spawn(exe, [
        `--remote-debugging-port=${PORT}`,
        `--user-data-dir=${profile}`,
        '--no-first-run',
        '--no-default-browser-check',
        '--autoplay-policy=no-user-gesture-required',
        '--disable-features=HardwareMediaKeyHandling',
        '--window-size=1000,700',
        '--app=https://www.youtube.com',
      ], { stdio: 'ignore', detached: true });
      child.unref();
      const end = Date.now() + 25000;
      while (!ver && Date.now() < end) {
        await sleep(400);
        try { ver = await getJSON('/json/version'); } catch {}
      }
      if (!ver) { status('error', 'Brave no respondió. Ciérralo e intenta de nuevo.'); throw new Error('timeout'); }
    }
    cdp = new CDP(ver.webSocketDebuggerUrl);
    await cdp.ready;
    cdp.on(onCdpEvent);
    await cdp.send('Target.setDiscoverTargets', { discover: true });
    await attachPage();
    await setBraveWindow(false);
    status('ready');
  })();
  try { await starting; } finally { starting = null; }
}

async function attachPage() {
  const { targetInfos } = await cdp.send('Target.getTargets');
  let t = targetInfos.find((x) => x.type === 'page' && /youtube\.com/.test(x.url))
       || targetInfos.find((x) => x.type === 'page');
  if (!t) {
    const r = await cdp.send('Target.createTarget', { url: 'https://www.youtube.com' });
    t = { targetId: r.targetId };
  }
  targetId = t.targetId;
  const r = await cdp.send('Target.attachToTarget', { targetId, flatten: true });
  session = r.sessionId;
  await cdp.send('Runtime.enable', {}, session);
  await cdp.send('Page.enable', {}, session);
  await cdp.send('Runtime.addBinding', { name: 'mpEvent' }, session);
}

function onCdpEvent(e) {
  if (e.method === '__closed') {
    session = null; cdp = null; braveShown = false;
    status('closed');
    return;
  }
  if (e.method === 'Runtime.bindingCalled' && e.params.name === 'mpEvent' && e.params.payload === 'ended') {
    onEnded();
  }
  if ((e.method === 'Target.targetDestroyed' && e.params.targetId === targetId) ||
      (e.method === 'Target.detachedFromTarget' && e.params.sessionId === session)) {
    session = null;
    setTimeout(() => { if (cdp && !cdp.closed && !session) attachPage().catch(() => {}); }, 800);
  }
}

async function ensure() {
  if (!cdp || cdp.closed) await startBrave();
  if (!session) await attachPage();
}

async function ev(expression) {
  await ensure();
  const r = await cdp.send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true, userGesture: true }, session);
  return r && r.result ? r.result.value : undefined;
}

async function setBraveWindow(show) {
  if (!cdp || !targetId) return;
  try {
    const { windowId } = await cdp.send('Browser.getWindowForTarget', { targetId });
    await cdp.send('Browser.setWindowBounds', { windowId, bounds: { windowState: show ? 'normal' : 'minimized' } });
    braveShown = show;
  } catch {}
}

// ---------------- Estado de reproducción ----------------
const POLL_JS = (repeat) => `(() => {
  const v = document.querySelector('video');
  const player = document.querySelector('#movie_player');
  const ad = !!(player && player.classList.contains('ad-showing'));
  if (v) {
    if (!v.__mp) { v.__mp = 1; v.addEventListener('ended', () => { try { window.mpEvent('ended'); } catch (e) {} }); }
    v.loop = ${repeat ? 'true' : 'false'};
    if (ad) {
      if (!window.__mpAdMuted) { window.__mpAdMuted = 1; window.__mpWasMuted = v.muted; }
      v.muted = true;
      if (isFinite(v.duration) && v.duration > 0) v.currentTime = v.duration;
      const b = document.querySelector('.ytp-skip-ad-button, .ytp-ad-skip-button, .ytp-ad-skip-button-modern');
      if (b) b.click();
    } else if (window.__mpAdMuted) {
      v.muted = !!window.__mpWasMuted; window.__mpAdMuted = 0;
    }
  }
  const id = location.pathname === '/watch' ? new URLSearchParams(location.search).get('v') : null;
  const h1 = document.querySelector('ytd-watch-metadata h1');
  const ch = document.querySelector('ytd-watch-metadata ytd-channel-name a, #owner ytd-channel-name a');
  return {
    id,
    title: (h1 && h1.textContent.trim()) || document.title.replace(/^\\(\\d+\\)\\s*/, '').replace(/ - YouTube$/, ''),
    channel: ch ? ch.textContent.trim() : '',
    cur: v ? v.currentTime : 0,
    dur: v && isFinite(v.duration) ? v.duration : 0,
    paused: v ? v.paused : true,
    ad
  };
})()`;

let polling = false;
async function poll() {
  if (polling || !session || !cdp || cdp.closed) return;
  polling = true;
  try {
    const s = await ev(POLL_JS(cfg.repeat));
    if (s) {
      if (!s.id) { s.title = ''; s.channel = ''; }
      s.fav = !!(s.id && cfg.favorites.some((f) => f.id === s.id));
      s.shuffle = cfg.shuffle;
      s.repeat = cfg.repeat;
      s.braveShown = braveShown;
      lastState = s;
      sendUI('state', s);
    }
  } catch {}
  polling = false;
}

function nextIndex() {
  if (!queue.length) return -1;
  if (cfg.shuffle && queue.length > 1) {
    let i; do { i = Math.floor(Math.random() * queue.length); } while (i === qIndex);
    return i;
  }
  return qIndex + 1 < queue.length ? qIndex + 1 : -1;
}

async function navigate(id) {
  await ensure();
  await cdp.send('Page.navigate', { url: `https://www.youtube.com/watch?v=${id}` }, session);
}

async function playItem(item, list) {
  if (Array.isArray(list) && list.length) {
    queue = list;
    qIndex = Math.max(0, list.findIndex((x) => x.id === item.id));
  } else {
    queue = [item]; qIndex = 0;
  }
  await navigate(item.id);
}

async function next() {
  const i = nextIndex();
  if (i >= 0) { qIndex = i; return navigate(queue[i].id); }
  return ev(`(() => { const b = document.querySelector('.ytp-next-button'); if (b) b.click(); })()`);
}

async function prev() {
  const cur = await ev(`(document.querySelector('video') || {}).currentTime || 0`);
  if (cur > 3 || qIndex <= 0 || !queue.length) {
    return ev(`(() => { const v = document.querySelector('video'); if (v) v.currentTime = 0; })()`);
  }
  qIndex--;
  return navigate(queue[qIndex].id);
}

let lastEndedId = null;
function onEnded() {
  if (cfg.repeat || !lastState) return;
  if (lastEndedId === lastState.id) return;
  lastEndedId = lastState.id;
  setTimeout(() => { lastEndedId = null; }, 5000);
  const cur = queue[qIndex];
  if (cur && cur.id === lastState.id && nextIndex() >= 0) next().catch(() => {});
}

// ---------------- Búsqueda en YouTube ----------------
function txt(t) {
  if (!t) return '';
  if (t.simpleText) return t.simpleText;
  if (t.runs) return t.runs.map((r) => r.text).join('');
  return '';
}
function walk(o, out, seen) {
  if (!o || typeof o !== 'object' || out.length >= 25) return;
  if (o.videoRenderer && o.videoRenderer.videoId) {
    const v = o.videoRenderer;
    if (!seen.has(v.videoId)) {
      seen.add(v.videoId);
      out.push({ id: v.videoId, title: txt(v.title), channel: txt(v.ownerText || v.longBylineText), duration: txt(v.lengthText) });
    }
    return;
  }
  for (const k in o) walk(o[k], out, seen);
}
async function search(q) {
  const url = 'https://www.youtube.com/results?hl=es&gl=CO&search_query=' + encodeURIComponent(q);
  const r = await fetch(url, {
    headers: {
      'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0 Safari/537.36',
      'Accept-Language': 'es-CO,es;q=0.9',
    },
  });
  const html = await r.text();
  const m = html.match(/var ytInitialData\s*=\s*(\{.+?\});\s*<\/script>/s);
  if (!m) return [];
  const out = [];
  walk(JSON.parse(m[1]), out, new Set());
  return out;
}

// ---------------- Ventana ----------------
function createWindow() {
  const W = 380, H = 340;
  const wa = screen.getPrimaryDisplay().workArea;
  let x = wa.x + wa.width - W - 12, y = wa.y + wa.height - H - 12;
  if (cfg.pos) {
    const d = screen.getDisplayMatching({ x: cfg.pos.x, y: cfg.pos.y, width: W, height: H }).workArea;
    if (cfg.pos.x >= d.x - 40 && cfg.pos.y >= d.y - 40 && cfg.pos.x + W <= d.x + d.width + 40 && cfg.pos.y + H <= d.y + d.height + 40) {
      x = cfg.pos.x; y = cfg.pos.y;
    }
  }
  win = new BrowserWindow({
    width: W, height: H, x, y,
    frame: false, transparent: true, resizable: false, maximizable: false, fullscreenable: false,
    hasShadow: false, backgroundColor: '#00000000', show: false, title: 'Mini Player',
    icon: path.join(__dirname, 'build', 'icon.ico'),
    webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, nodeIntegration: false },
  });
  win.setAlwaysOnTop(cfg.pinned, 'floating');
  win.loadFile(path.join(__dirname, 'renderer', 'index.html'));
  win.once('ready-to-show', () => win.show());
  win.on('moved', () => { const [px, py] = win.getPosition(); cfg.pos = { x: px, y: py }; saveCfg(); });
}

function registerIpc() {
  const safe = (fn) => async (...a) => { try { return await fn(...a); } catch (e) { return { error: String(e.message || e) }; } };
  ipcMain.handle('search', safe((_e, q) => search(q)));
  ipcMain.handle('play', safe((_e, item, list) => playItem(item, list)));
  ipcMain.handle('toggle', safe(() => ev(`(() => { const v = document.querySelector('video'); if (!v) return; v.paused ? v.play() : v.pause(); })()`)));
  ipcMain.handle('next', safe(() => next()));
  ipcMain.handle('prev', safe(() => prev()));
  ipcMain.handle('seek', safe((_e, frac) => ev(`(() => { const v = document.querySelector('video'); if (v && isFinite(v.duration)) v.currentTime = v.duration * ${Math.min(1, Math.max(0, Number(frac) || 0))}; })()`)));
  ipcMain.handle('shuffle', () => { cfg.shuffle = !cfg.shuffle; saveCfg(); return cfg.shuffle; });
  ipcMain.handle('repeat', () => { cfg.repeat = !cfg.repeat; saveCfg(); return cfg.repeat; });
  ipcMain.handle('fav', () => {
    if (!lastState || !lastState.id) return false;
    const i = cfg.favorites.findIndex((f) => f.id === lastState.id);
    if (i >= 0) cfg.favorites.splice(i, 1);
    else cfg.favorites.unshift({ id: lastState.id, title: lastState.title, channel: lastState.channel, duration: lastState.dur ? fmt(lastState.dur) : '' });
    saveCfg();
    return i < 0;
  });
  ipcMain.handle('favorites', () => cfg.favorites);
  ipcMain.handle('brave', safe(async () => { await ensure(); await setBraveWindow(!braveShown); return braveShown; }));
  ipcMain.handle('pin', () => { cfg.pinned = !cfg.pinned; win.setAlwaysOnTop(cfg.pinned, 'floating'); saveCfg(); return cfg.pinned; });
  ipcMain.handle('getPin', () => cfg.pinned);
  ipcMain.handle('minimize', () => win.minimize());
  ipcMain.handle('close', () => app.quit());
  ipcMain.handle('retry', safe(() => startBrave()));
  ipcMain.handle('pickBrave', safe(async () => {
    const r = await dialog.showOpenDialog(win, { title: 'Selecciona brave.exe', filters: [{ name: 'Brave', extensions: ['exe'] }], properties: ['openFile'] });
    if (r.canceled || !r.filePaths[0]) return false;
    cfg.bravePath = r.filePaths[0]; saveCfg();
    await startBrave();
    return true;
  }));
}

function fmt(t) { t = Math.floor(t); return Math.floor(t / 60) + ':' + String(t % 60).padStart(2, '0'); }

// ---------------- Arranque ----------------
if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  app.setAppUserModelId('com.johan.miniplayer');
  app.on('second-instance', () => { if (win) { win.restore(); win.show(); win.focus(); } });

  app.whenReady().then(() => {
    loadCfg();
    registerIpc();
    createWindow();
    win.webContents.once('did-finish-load', () => { startBrave().catch(() => {}); });
    setInterval(poll, 500);
    const k = (acc, fn) => { try { globalShortcut.register(acc, () => fn().catch(() => {})); } catch {} };
    k('MediaPlayPause', () => ev(`(() => { const v = document.querySelector('video'); if (v) v.paused ? v.play() : v.pause(); })()`));
    k('MediaNextTrack', () => next());
    k('MediaPreviousTrack', () => prev());
  });

  app.on('will-quit', () => { globalShortcut.unregisterAll(); });
  app.on('before-quit', () => {
    // Cierra el Brave del reproductor (perfil propio), no tu Brave normal.
    if (cdp && !cdp.closed) { cdp.send('Browser.close').catch(() => {}); }
  });
  app.on('window-all-closed', () => app.quit());
}
