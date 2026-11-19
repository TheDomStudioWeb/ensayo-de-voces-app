/* Ensayo de Voces — interfaz. El teléfono se maneja con window.Android (ver MainActivity.kt). */
(function () {
  const $ = (s) => document.querySelector(s);
  const A = window.Android || mock();
  const J = (s) => { try { return JSON.parse(s); } catch (e) { return null; } };
  const css = (v) => getComputedStyle(document.documentElement).getPropertyValue(v).trim();

  let view = 'lib', current = null, mode = 'voces', hq = true, motor = 'xnnpack';
  try { motor = localStorage.getItem('motor') || 'xnnpack'; hq = localStorage.getItem('hq') !== '0'; } catch (e) {}

  const MOTOR = { xnnpack: 'Acelerado', nnapi: 'Chip de IA', cpu: 'Básico' };
  const MNOTE = {
    xnnpack: 'Usa el procesador con instrucciones aceleradas. Recomendado.',
    nnapi: 'Prueba el chip gráfico / de IA del teléfono. Puede ser más rápido o no andar (si falla, usa Básico).',
    cpu: 'El más compatible y el más lento.' };
  const MODO = { voces: 'Voces · música', coro: 'Principal · coros · música', fm: 'Femenina · masculina · música' };
  const STATE = { lista: 'Lista', nueva: 'Sin separar', procesando: 'Separando', error: 'Error' };
  const modoDe = (s) => s.modo || (s.coro ? 'coro' : 'voces');
  const fmt = (s) => { s = Math.max(0, Math.round(s || 0)); return Math.floor(s / 60) + ':' + String(s % 60).padStart(2, '0'); };
  const esc = (t) => String(t).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
  const NOTAS = ['Do', 'Do#', 'Re', 'Re#', 'Mi', 'Fa', 'Fa#', 'Sol', 'Sol#', 'La', 'La#', 'Si'];
  const nn = (m) => { m = Math.round(m); return NOTAS[((m % 12) + 12) % 12] + (Math.floor(m / 12) - 1); };
  const store = (k, v) => { try { v === undefined ? localStorage.removeItem(k) : localStorage.setItem(k, v); } catch (e) {} };
  const load = (k) => { try { return localStorage.getItem(k); } catch (e) { return null; } };

  // Colores de cada pista (kit The DOM: dorado, marfil, bronce)
  function colorFor(file, i) {
    if (/^musica/.test(file)) return css('--bronze');
    return i === 0 ? css('--gold') : i === 1 ? css('--ivory') : css('--gold2');
  }

  function show(v) {
    view = v;
    for (const id of ['lib', 'opts', 'proc', 'play']) $('#v-' + id).classList.toggle('hidden', id !== v);
    window.scrollTo(0, 0);
  }

  // ================= Biblioteca =================
  function renderLib() {
    const songs = J(A.songs()) || [];
    const st = J(A.status()) || {};
    $('#count').textContent = songs.length === 1 ? '1 canción' : songs.length + ' canciones';
    const list = $('#list');
    if (!songs.length) {
      list.innerHTML = '<div class="empty">Todavía no hay canciones.<br>Tocá <b>Agregar canción</b>, o abrí desde WhatsApp un <b>paquete de ensayo</b> que te hayan mandado: se escucha directo, sin separar.</div>';
      return;
    }
    list.innerHTML = songs.map((s) => {
      let state = s.state || 'nueva';
      if (state === 'procesando' && !(st.running && st.id === s.id)) state = 'error';
      const parts = [];
      if (s.seconds) parts.push(fmt(s.seconds));
      if (state === 'lista') parts.push(MODO[modoDe(s)]);
      if (s.received) parts.push('recibida');
      if (state === 'procesando' && st.total != null) parts.push(Math.round(st.total * 100) + '%');
      let sub = '';
      if (state === 'lista' && s.procSeconds) sub = `<br>Separada en ${fmt(s.procSeconds)}${s.motor ? ' · ' + (MOTOR[s.motor] || s.motor) : ''}${s.hq ? ' · calidad alta' : ''}`;
      const ini = esc((s.name || '?').trim().charAt(0).toUpperCase());
      const bars = state === 'lista' ? '<i><b style="height:40%"></b><b style="height:90%"></b><b style="height:60%"></b><b style="height:100%"></b><b style="height:50%"></b></i>' : '';
      return `<button class="card" data-id="${s.id}"><span class="art">${ini}${bars}</span><div class="info"><div class="name">${esc(s.name)}</div>
        <div class="meta">${parts.join(' · ') || '&nbsp;'}${sub}</div></div><span class="badge ${state}">${STATE[state]}</span></button>`;
    }).join('');
  }

  $('#list').addEventListener('click', (e) => { const c = e.target.closest('.card'); if (c) open(c.dataset.id); });

  function open(id) {
    current = J(A.song(id)); if (!current) return renderLib();
    const st = J(A.status()) || {};
    if (st.running && st.id === id) return showProc();
    if (current.state === 'lista') return showPlayer();
    showOpts();
  }

  const addLabel = () => { $('#add span').textContent = 'Agregar canción'; };
  $('#add').onclick = () => A.pick();
  window.onImporting = () => { $('#add span').textContent = 'Abriendo…'; };
  window.onPicked = (m) => {
    addLabel();
    if (!m) return;
    if (m.error) return alert(m.error);
    current = m; renderLib();
    m.state === 'lista' ? showPlayer() : showOpts();
  };

  // ================= Opciones =================
  function showOpts() {
    $('#o-name').textContent = current.name;
    if (current.modo || current.coro != null) mode = modoDe(current);
    paintOpts(); show('opts');
  }
  function paintOpts() {
    document.querySelectorAll('.opt').forEach((b) => b.classList.toggle('on', b.dataset.mode === mode));
    $('#hq').classList.toggle('on', hq);
    document.querySelectorAll('#motor button').forEach((b) => b.classList.toggle('on', b.dataset.m === motor));
    $('#m-note').textContent = MNOTE[motor];
    const m = J(A.models()) || {};
    const need = [];
    if (!m.voces) need.push('65 MB');
    if (mode === 'coro' && !m.coro) need.push('50 MB');
    if (mode === 'fm' && !m.fm) need.push('270 MB');
    let note = need.length ? `La primera vez baja el motor (${need.join(' + ')}): conviene estar con Wi-Fi. ` : '';
    if (current.error && current.state !== 'lista') note += 'Último intento: ' + current.error;
    $('#o-note').textContent = note;
  }
  document.querySelectorAll('.opt').forEach((b) => b.onclick = () => { mode = b.dataset.mode; paintOpts(); });
  $('#hq').onclick = () => { hq = !hq; store('hq', hq ? '1' : '0'); paintOpts(); };
  document.querySelectorAll('#motor button').forEach((b) => b.onclick = () => { motor = b.dataset.m; store('motor', motor); paintOpts(); });
  $('#go').onclick = () => { A.separate(current.id, mode, hq, motor); showProc(); };
  function delSong() {
    if (!confirm('¿Borrar "' + current.name + '" y sus pistas?')) return;
    if (view === 'play') leavePlayer();
    A.delete(current.id); current = null; renderLib(); show('lib');
  }
  $('#del').onclick = delSong;
  $('#del2').onclick = delSong;

  // ================= Procesando =================
  function showProc() {
    $('#p-name').textContent = current.name;
    $('#p-bar').style.width = '0'; $('#p-pct').textContent = '0%';
    show('proc'); tick();
  }
  let wasRunning = false;
  function tick() {
    const st = J(A.status()) || {};
    if (view === 'proc' && current && st.id === current.id) {
      if (st.running) {
        const p = Math.round((st.total != null ? st.total : st.progress || 0) * 100);
        $('#p-stage').textContent = st.stage || 'Separando…';
        $('#p-bar').style.width = p + '%'; $('#p-pct').textContent = p + '%';
        $('#p-el').textContent = 'Lleva ' + fmt(st.elapsed);
        $('#p-eta').textContent = st.eta != null ? 'Faltan ~' + fmt(st.eta) : '';
      } else if (st.done) {
        current = J(A.song(current.id)); showPlayer();
      } else if (st.error) {
        current = J(A.song(current.id)) || current; showOpts();
      }
    }
    if (view === 'lib' && (st.running || wasRunning)) renderLib();
    wasRunning = !!st.running;
  }
  $('#cancel').onclick = () => { A.cancel(); $('#p-stage').textContent = 'Cancelando…'; };
  setInterval(tick, 1000);

  // ================= Reproductor (Web Audio: pistas en sincronía exacta) =================
  let ac = null, buffers = [], gains = [], sources = [], state = [], dur = 0;
  let playing = false, startAt = 0, offset = 0, loopA = null, loopB = null, raf = null, seeking = false;
  let P = null, sel = 0, colors = [];

  async function showPlayer() {
    stopAll(); offset = 0; loopA = loopB = null; paintLoop(); P = null; OV = null;
    $('#y-name').textContent = current.name;
    const md = modoDe(current);
    $('#y-mode').textContent = MODO[md] + (current.seconds ? ' · ' + fmt(current.seconds) : '');
    $('#y-body').classList.add('hidden');
    $('#y-loading').classList.remove('hidden'); $('#y-loading').textContent = 'Cargando pistas…';
    $('#swap').classList.toggle('hidden', md === 'voces');
    $('#swapTxt').textContent = md === 'fm' ? 'Intercambiar voz femenina y masculina' : 'Intercambiar voz principal y coros';
    $('#redo').classList.toggle('hidden', !!current.received);
    $('#shareTxt').textContent = 'Mandar paquete de ensayo';
    show('play');
    if (!ac) ac = new (window.AudioContext || window.webkitAudioContext)();
    const stems = current.stems || [];
    const id = current.id;
    try {
      buffers = await Promise.all(stems.map(async (s) => {
        const r = await fetch('/songs/' + id + '/' + s.file);
        return ac.decodeAudioData(await r.arrayBuffer());
      }));
      if (!current || current.id !== id || view !== 'play') return;
    } catch (e) { $('#y-loading').textContent = 'No se pudieron cargar las pistas: ' + e.message; return; }
    dur = Math.max(...buffers.map((b) => b.duration));
    gains = buffers.map(() => { const g = ac.createGain(); g.connect(ac.destination); return g; });
    state = stems.map(() => ({ on: true, vol: 1 }));
    paintTracks();
    $('#dur').textContent = fmt(dur);
    $('#y-loading').classList.add('hidden'); $('#y-body').classList.remove('hidden');
    applyGains(); paintPos();
    loadPitch();
  }

  function paintTracks() {
    let v = 0;
    colors = current.stems.map((s) => colorFor(s.file, /^musica/.test(s.file) ? -1 : v++));
    $('#tracks').innerHTML = current.stems.map((s, i) => `<div class="track" data-i="${i}" style="--c:${colors[i]}"><div class="row">
      <button class="tog" data-tog="${i}" aria-label="Prender o apagar"></button><span class="lbl">${esc(s.label)}</span>
      <button class="solo" data-solo="${i}">SOLO</button></div>
      <input type="range" min="0" max="100" value="${Math.round((state[i] ? state[i].vol : 1) * 100)}" data-vol="${i}" aria-label="Volumen"></div>`).join('');
  }

  function applyGains() {
    state.forEach((s, i) => {
      gains[i] && gains[i].gain.setTargetAtTime(s.on ? s.vol : 0, ac.currentTime, 0.02);
      const el = document.querySelector(`.track[data-i="${i}"]`);
      if (el) { el.classList.toggle('off', !s.on); el.querySelector('.tog').classList.toggle('on', s.on); }
    });
    const onCount = state.filter((s) => s.on).length;
    document.querySelectorAll('.solo').forEach((b) => b.classList.toggle('on', onCount === 1 && state[+b.dataset.solo].on));
  }

  $('#tracks').addEventListener('click', (e) => {
    const t = e.target.closest('[data-tog]'), so = e.target.closest('[data-solo]');
    if (t) { const i = +t.dataset.tog; state[i].on = !state[i].on; applyGains(); }
    if (so) {
      const i = +so.dataset.solo;
      const isSolo = state.every((s, k) => s.on === (k === i));
      state.forEach((s, k) => { s.on = isSolo ? true : k === i; });
      applyGains();
    }
  });
  $('#tracks').addEventListener('input', (e) => {
    const v = e.target.closest('[data-vol]'); if (!v) return;
    const i = +v.dataset.vol; state[i].vol = v.value / 100; state[i].on = v.value > 0; applyGains();
  });

  const pos = () => playing ? offset + (ac.currentTime - startAt) : offset;

  function setPlayIcon() { $('#play use').setAttribute('href', playing ? '#i-pause' : '#i-play'); }
  function startAll(from) {
    stopSources();
    offset = Math.max(0, Math.min(from, dur - 0.05));
    startAt = ac.currentTime + 0.06;
    sources = buffers.map((b, i) => {
      const s = ac.createBufferSource(); s.buffer = b; s.connect(gains[i]);
      s.start(startAt, Math.min(offset, b.duration - 0.01)); return s;
    });
    playing = true; setPlayIcon();
    cancelAnimationFrame(raf); raf = requestAnimationFrame(frame);
  }
  function stopSources() { sources.forEach((s) => { try { s.stop(); } catch (e) {} }); sources = []; }
  function stopAll() {
    if (playing) offset = pos();
    stopSources(); playing = false; setPlayIcon(); cancelAnimationFrame(raf); toneOff();
  }
  function leavePlayer() { stopAll(); buffers = []; P = null; }

  function frame() {
    const p = pos();
    if (loopA != null && loopB != null && p >= loopB) { startAll(loopA); return; }
    if (p >= dur) { stopAll(); offset = 0; paintPos(); return; }
    paintPos();
    raf = requestAnimationFrame(frame);
  }
  function paintPos() {
    const p = Math.min(pos(), dur);
    $('#cur').textContent = fmt(p);
    if (!seeking) $('#seek').value = dur ? Math.round(p / dur * 1000) : 0;
    drawGuide(p);
  }
  function goTo(p) { p = Math.max(0, Math.min(dur, p)); playing ? startAll(p) : (offset = p, paintPos()); }

  $('#play').onclick = () => { if (!buffers.length) return; ac.resume(); playing ? stopAll() : startAll(offset); };
  $('#rw').onclick = () => goTo(pos() - 10);
  $('#ff').onclick = () => goTo(pos() + 10);
  $('#seek').addEventListener('input', () => { seeking = true; $('#cur').textContent = fmt($('#seek').value / 1000 * dur); });
  $('#seek').addEventListener('change', () => { seeking = false; goTo($('#seek').value / 1000 * dur); });

  function paintLoop() {
    $('#loopA').classList.toggle('on', loopA != null);
    $('#loopB').classList.toggle('on', loopB != null);
    $('#loopA').textContent = loopA != null ? fmt(loopA) : 'A';
    $('#loopB').textContent = loopB != null ? fmt(loopB) : 'B';
    $('#loopX').classList.toggle('hidden', loopA == null && loopB == null);
  }
  $('#loopA').onclick = () => { loopA = pos(); if (loopB != null && loopB <= loopA) loopB = null; paintLoop(); paintPos(); };
  $('#loopB').onclick = () => { const p = pos(); if (loopA != null && p > loopA + 0.5) { loopB = p; paintLoop(); if (playing) startAll(loopA); else paintPos(); } };
  $('#loopX').onclick = () => { loopA = loopB = null; paintLoop(); paintPos(); };

  $('#redo').onclick = () => { stopAll(); showOpts(); };
  let sharing = false;
  $('#share').onclick = () => { if (sharing) return; sharing = true; $('#shareTxt').textContent = 'Armando el paquete…'; A.share(current.id); };
  window.onShareProgress = (p, err) => {
    if (p >= 0) { $('#shareTxt').textContent = 'Armando el paquete… ' + Math.round(p * 100) + '%'; return; }
    sharing = false; $('#shareTxt').textContent = 'Mandar paquete de ensayo';
    if (p === -2) alert(err);
  };

  // Intercambia los nombres de las dos voces sin volver a cargar el audio
  $('#swap').onclick = () => {
    const m = J(A.swapVoices(current.id)); if (!m || !m.stems) return;
    const files = (current.stems || []).map((s) => s.file);
    current = m;
    const order = m.stems.map((s) => files.indexOf(s.file));
    buffers = order.map((i) => buffers[i]); gains = order.map((i) => gains[i]); state = order.map((i) => state[i]);
    if (playing) startAll(pos());
    paintTracks(); applyGains(); buildVoiceSel(); paintPos();
  };

  // ================= Guía de notas (gráfico tonal) =================
  function loadPitch() {
    const voices = (current.stems || []).filter((s) => !/^musica/.test(s.file));
    if (!voices.length) { $('#guide').classList.add('hidden'); return; }
    $('#guide').classList.remove('hidden');
    P = J(A.pitch(current.id));
    if (!P || !P.voces || !P.voces.length) {
      P = null; buildVoiceSel();
      $('#g-note').textContent = 'Armando el gráfico de notas…';
      A.analyze(current.id);
      return;
    }
    $('#g-note').textContent = 'Deslizá el gráfico para moverte. Tocá el mapa de abajo para saltar a cualquier parte.';
    const saved = parseInt(load('voz-' + current.id), 10);
    sel = saved >= 0 && saved < P.voces.length ? saved : 0;
    buildOverview(); buildVoiceSel(); paintPos();
  }
  window.onPitchProgress = (id, p) => { if (current && id === current.id && !P) $('#g-note').textContent = 'Armando el gráfico de notas… ' + Math.round(p * 100) + '%'; };
  window.onPitchReady = (id, err) => {
    if (!current || id !== current.id || view !== 'play') return;
    if (err) { $('#g-note').textContent = 'No se pudo armar el gráfico: ' + err; return; }
    loadPitch();
  };

  // Datos de cada voz del gráfico, con el nombre y color actuales de su pista
  function voiceInfo() {
    if (!P) return [];
    return P.voces.map((v) => {
      const i = current.stems.findIndex((s) => s.file === v.file);
      return { v, label: i >= 0 ? current.stems[i].label : v.label, color: i >= 0 ? colors[i] : css('--gold') };
    });
  }
  function buildVoiceSel() {
    const vi = voiceInfo();
    $('#vsel').innerHTML = vi.length > 1 ? vi.map((x, i) => `<button class="${i === sel ? 'on' : ''}" data-v="${i}" style="--c:${x.color}"><i></i>${esc(x.label)}</button>`).join('') : '';
    $('#n-oth-box').classList.toggle('hidden', vi.length < 2);
    if (vi[sel]) document.documentElement.style.setProperty('--sel', vi[sel].color);
    $('#n-oth-l').textContent = vi.length > 1 ? vi[sel === 0 ? 1 : 0].label.replace(/^Voz /i, '') : 'Otra voz';
  }
  $('#vsel').addEventListener('click', (e) => {
    const b = e.target.closest('[data-v]'); if (!b) return;
    sel = +b.dataset.v; store('voz-' + current.id, String(sel)); buildVoiceSel(); paintPos();
  });

  function noteAt(list, t) { let lo = 0, hi = list.length - 1; while (lo <= hi) { const m = (lo + hi) >> 1; if (list[m][1] < t) lo = m + 1; else if (list[m][0] > t) hi = m - 1; else return list[m]; } return null; }
  function nextNote(list, t) { for (const n of list) if (n[0] > t) return n; return null; }
  function sizeCanvas(c) {
    const r = c.getBoundingClientRect(), d = window.devicePixelRatio || 1;
    if (c.width !== Math.round(r.width * d) || c.height !== Math.round(r.height * d)) { c.width = Math.round(r.width * d); c.height = Math.round(r.height * d); }
    return [r.width, r.height, d];
  }
  function rrect(g, x, y, w, h, r) { g.beginPath(); g.moveTo(x + r, y); g.arcTo(x + w, y, x + w, y + h, r); g.arcTo(x + w, y + h, x, y + h, r); g.arcTo(x, y + h, x, y, r); g.arcTo(x, y, x + w, y, r); g.closePath(); }
  function alpha(hex, a) { const n = parseInt(hex.replace('#', ''), 16); return `rgba(${n >> 16 & 255},${n >> 8 & 255},${n & 255},${a})`; }

  let yLo = 52, yHi = 72, drag = null;
  const SPAN = 8;           // segundos visibles
  const GUT = 40;           // margen de nombres de notas
  function drawGuide(t) {
    if (!P || view !== 'play') return;
    const vi = voiceInfo(); if (!vi.length) return;
    const step = P.paso || 0.04;
    const c = $('#roll'), [W, H, dpr] = sizeCanvas(c), g = c.getContext('2d'); g.setTransform(dpr, 0, 0, dpr, 0, 0);
    const left = t - SPAN * 0.3, right = left + SPAN, X = (s) => GUT + (s - left) / SPAN * (W - GUT);
    // rango de notas visible (se acomoda suave)
    let mn = 99, mx = 0;
    for (const x of vi) { const a = x.v.linea; const i0 = Math.max(0, Math.floor(left / step)), i1 = Math.min(a.length, Math.ceil(right / step)); for (let i = i0; i < i1; i++) { const m = a[i]; if (m != null) { if (m < mn) mn = m; if (m > mx) mx = m; } } }
    if (mn > mx) { mn = 55; mx = 67; }
    let lo = Math.floor(mn) - 2, hi = Math.ceil(mx) + 2; if (hi - lo < 13) { const cc = (hi + lo) / 2; lo = Math.floor(cc - 6.5); hi = lo + 13; }
    if (playing && !drag) { yLo += (lo - yLo) * 0.12; yHi += (hi - yHi) * 0.12; } else { yLo = lo; yHi = hi; }
    const rowH = H / (yHi - yLo), Y = (m) => H - (m - yLo) * rowH;

    g.fillStyle = css('--bg'); g.fillRect(0, 0, W, H);
    g.font = '600 10px Montserrat, sans-serif'; g.textBaseline = 'middle';
    for (let m = Math.floor(yLo); m <= Math.ceil(yHi); m++) {
      const pc = ((m % 12) + 12) % 12, black = [1, 3, 6, 8, 10].includes(pc), y = Y(m);
      if (black) { g.fillStyle = '#111317'; g.fillRect(GUT, y - rowH / 2, W - GUT, rowH); }
      if (pc === 0) { g.strokeStyle = 'rgba(212,175,55,.22)'; g.lineWidth = 1; g.beginPath(); g.moveTo(GUT, y + rowH / 2); g.lineTo(W, y + rowH / 2); g.stroke(); }
      if (!black && (rowH > 14 || [0, 4, 7].includes(pc))) { g.fillStyle = pc === 0 ? css('--gold') : '#7d786d'; g.fillText(nn(m), 4, y); }
    }
    g.strokeStyle = '#1E2126'; g.lineWidth = 1;
    for (let s = Math.ceil(left); s < right; s++) { const x = X(s); g.globalAlpha = s % 4 === 0 ? 1 : 0.5; g.beginPath(); g.moveTo(x, 0); g.lineTo(x, H); g.stroke(); }
    g.globalAlpha = 1;
    if (loopA != null) { const a = Math.max(GUT, X(loopA)), b = loopB != null ? X(loopB) : a + 2; if (b > GUT) { g.fillStyle = 'rgba(212,175,55,.08)'; g.fillRect(a, 0, Math.max(2, b - a), H); } }
    const bars = (x, strong) => {
      const h = Math.max(6, rowH * 0.78);
      g.fillStyle = alpha(x.color, strong ? 0.24 : 0.12); g.strokeStyle = alpha(x.color, strong ? 0.9 : 0.4); g.lineWidth = 1;
      for (const n of x.v.notas) { if (n[1] < left || n[0] > right) continue; const x0 = Math.max(GUT, X(n[0])); rrect(g, x0, Y(n[2]) - h / 2, Math.max(2, X(n[1]) - x0), h, Math.min(3, h / 2)); g.fill(); g.stroke(); }
    };
    const line = (x, w, a) => {
      const arr = x.v.linea; const i0 = Math.max(0, Math.floor(left / step)), i1 = Math.min(arr.length, Math.ceil(right / step));
      g.strokeStyle = alpha(x.color, a); g.lineWidth = w; g.lineJoin = 'round'; g.lineCap = 'round'; g.beginPath(); let pen = false;
      for (let i = i0; i < i1; i++) {
        const m = arr[i];
        if (m == null || (i > 0 && arr[i - 1] != null && Math.abs(arr[i - 1] - m) > 4)) { pen = false; if (m == null) continue; }
        const px = X(i * step); if (px < GUT) continue;
        if (!pen) { g.moveTo(px, Y(m)); pen = true; } else g.lineTo(px, Y(m));
      }
      g.stroke();
    };
    vi.forEach((x, i) => { if (i !== sel) { bars(x, false); line(x, 1.4, 0.55); } });
    const me = vi[sel] || vi[0];
    bars(me, true); line(me, 2.6, 1);
    g.font = '600 11px Montserrat, sans-serif'; g.textBaseline = 'bottom'; g.fillStyle = me.color;
    let lastX = -1e9, lastM = null;
    for (const n of me.v.notas) {
      if (n[1] < left || n[0] > right || n[1] - n[0] < 0.2) continue;
      const lx = Math.max(GUT + 2, X(n[0]));
      if (lx - lastX < 44 || n[2] === lastM && lx - lastX < 90) continue;   // sin etiquetas encimadas
      g.fillText(nn(n[2]), lx, Y(n[2]) - Math.max(6, rowH * 0.78) / 2 - 2); lastX = lx; lastM = n[2];
    }
    const px = X(t); g.strokeStyle = css('--gold'); g.lineWidth = 2; g.beginPath(); g.moveTo(px, 0); g.lineTo(px, H); g.stroke();
    g.fillStyle = css('--gold'); g.beginPath(); g.moveTo(px - 5, 0); g.lineTo(px + 5, 0); g.lineTo(px, 6); g.fill();

    const cur = noteAt(me.v.notas, t), nx = nextNote(me.v.notas, t);
    const ot = vi.length > 1 ? noteAt(vi[sel === 0 ? 1 : 0].v.notas, t) : null;
    $('#n-cur').textContent = cur ? nn(cur[2]) : 'silencio';
    $('#n-nxt').innerHTML = nx ? nn(nx[2]) + ' <i>' + fmt(nx[0]) + '</i>' : '—';
    $('#n-oth').textContent = ot ? nn(ot[2]) : '—';
    toneSet(cur);
    drawOverview(t);
  }

  let OV = null;
  function buildOverview() {
    const b = 0.5, NB = Math.max(1, Math.ceil(dur / b));
    OV = { b, NB, rows: P.voces.map((v) => { const a = new Uint8Array(NB); v.notas.forEach((n) => { for (let k = Math.floor(n[0] / b); k <= Math.floor(n[1] / b) && k < NB; k++) a[k] = 1; }); return { file: v.file, a }; }) };
  }
  function drawOverview(t) {
    if (!OV) return;
    const c = $('#overview'), [W, H, dpr] = sizeCanvas(c), g = c.getContext('2d'); g.setTransform(dpr, 0, 0, dpr, 0, 0);
    g.fillStyle = css('--bg'); g.fillRect(0, 0, W, H);
    const vi = voiceInfo(), bw = W / OV.NB, rh = (H - 4) / OV.rows.length;
    OV.rows.forEach((r, ri) => {
      const x = vi.find((q) => q.v.file === r.file);
      g.fillStyle = alpha(x ? x.color : css('--gold'), ri === sel ? 0.95 : 0.45);
      for (let k = 0; k < OV.NB; k++) if (r.a[k]) g.fillRect(k * bw, 2 + ri * rh, Math.ceil(bw), rh - 2);
    });
    if (loopA != null) { g.fillStyle = 'rgba(212,175,55,.18)'; const a = loopA / dur * W, b = (loopB != null ? loopB : loopA) / dur * W; g.fillRect(a, 0, Math.max(2, b - a), H); }
    g.fillStyle = css('--txt'); g.fillRect(t / dur * W - 1, 0, 2, H);
  }
  $('#overview').addEventListener('click', (e) => { const r = $('#overview').getBoundingClientRect(); goTo((e.clientX - r.left) / r.width * dur); });
  const roll = $('#roll');
  roll.addEventListener('pointerdown', (e) => { drag = { x: e.clientX, t: pos(), was: playing }; if (playing) stopAll(); roll.setPointerCapture(e.pointerId); });
  roll.addEventListener('pointermove', (e) => { if (!drag) return; const W = roll.getBoundingClientRect().width; offset = Math.max(0, Math.min(dur, drag.t - (e.clientX - drag.x) / (W - GUT) * SPAN)); paintPos(); });
  const endDrag = () => { if (!drag) return; const was = drag.was; drag = null; if (was) startAll(offset); };
  roll.addEventListener('pointerup', endDrag); roll.addEventListener('pointercancel', endDrag);

  // Tono guía: un sonido suave que sigue la nota de la voz elegida
  let toneOn = false, osc = null, tg = null;
  $('#tone').onclick = () => {
    toneOn = !toneOn; $('#tone').classList.toggle('on', toneOn);
    if (toneOn && !osc && ac) {
      osc = ac.createOscillator(); osc.type = 'triangle';
      const lp = ac.createBiquadFilter(); lp.type = 'lowpass'; lp.frequency.value = 1800;
      tg = ac.createGain(); tg.gain.value = 0; osc.connect(lp); lp.connect(tg); tg.connect(ac.destination); osc.start();
    }
    if (!toneOn) toneOff();
  };
  function toneOff() { if (tg) tg.gain.setTargetAtTime(0, ac.currentTime, 0.03); }
  function toneSet(note) {
    if (!tg) return;
    if (!toneOn || !playing || !note) { toneOff(); return; }
    osc.frequency.setTargetAtTime(440 * Math.pow(2, (note[2] - 69) / 12), ac.currentTime, 0.015);
    tg.gain.setTargetAtTime(0.08, ac.currentTime, 0.02);
  }

  // ================= Navegación =================
  function back() {
    if (view === 'lib') return false;
    if (view === 'play') leavePlayer();
    renderLib(); show('lib'); return true;
  }
  document.querySelectorAll('[data-back]').forEach((b) => b.onclick = back);
  window.onBack = back;
  window.addEventListener('resize', () => { if (view === 'play' && buffers.length) paintPos(); });

  renderLib();

  // Para probar la interfaz en una computadora, sin el teléfono
  function mock() {
    const songs = [{ id: 'a', name: 'Canción de prueba', created: 1, state: 'nueva' }];
    return {
      songs: () => JSON.stringify(songs), song: (id) => JSON.stringify(songs.find((s) => s.id === id) || null),
      status: () => '{"running":false}', models: () => '{"voces":false,"coro":false,"fm":false}', swapVoices: () => 'null',
      pitch: () => 'null', analyze() {}, pick() {}, share() {}, separate() {}, cancel() {}, delete() {}, rename() {},
    };
  }
})();
