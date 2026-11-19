/* Ensayo de Voces — interfaz. El teléfono se maneja con window.Android (ver MainActivity.kt). */
(function () {
  const $ = (s) => document.querySelector(s);
  const A = window.Android || mock();
  const J = (s) => { try { return JSON.parse(s); } catch (e) { return null; } };

  let view = 'lib', current = null, mode = 'voces', hq = true, poll = null;
  let motor = 'xnnpack';
  try { motor = localStorage.getItem('motor') || 'xnnpack'; hq = localStorage.getItem('hq') !== '0'; } catch (e) {}
  const MOTOR = { xnnpack: 'Acelerado', nnapi: 'Chip de IA', cpu: 'Básico' };
  const MNOTE = {
    xnnpack: 'Usa el procesador con instrucciones aceleradas. Recomendado.',
    nnapi: 'Prueba usar el chip gráfico / de IA del teléfono. Puede ser mucho más rápido o no andar en algunos teléfonos (si falla, usa Básico).',
    cpu: 'El más compatible y el más lento.' };

  function show(v) {
    view = v;
    for (const id of ['lib', 'opts', 'proc', 'play']) $('#v-' + id).classList.toggle('hidden', id !== v);
    window.scrollTo(0, 0);
  }

  const fmt = (s) => { s = Math.max(0, Math.round(s || 0)); return Math.floor(s / 60) + ':' + String(s % 60).padStart(2, '0'); };
  const esc = (t) => String(t).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
  const STATE = { lista: 'Lista', nueva: 'Sin separar', procesando: 'Separando', error: 'Error' };

  // ---------- Biblioteca ----------
  function renderLib() {
    const songs = J(A.songs()) || [];
    const st = J(A.status()) || {};
    const list = $('#list');
    if (!songs.length) {
      list.innerHTML = '<div class="empty">Todavía no hay canciones.<br>Tocá <b>Agregar canción</b>, o abrí desde WhatsApp un <b>paquete de ensayo</b> que te hayan mandado (se escucha directo, sin separar).</div>';
      return;
    }
    list.innerHTML = songs.map((s) => {
      let state = s.state || 'nueva';
      if (state === 'procesando' && !(st.running && st.id === s.id)) state = 'error';
      const parts = [];
      if (s.seconds) parts.push(fmt(s.seconds));
      if (state === 'lista') parts.push(s.coro ? 'principal · coros · música' : 'voces · música');
      if (s.received) parts.push('recibida');
      if (state === 'lista' && s.procSeconds) parts.push('separada en ' + fmt(s.procSeconds) + (s.motor ? ' (' + (MOTOR[s.motor] || s.motor) + (s.hq ? ', calidad alta' : '') + ')' : ''));
      if (state === 'procesando' && st.total != null) parts.push(Math.round(st.total * 100) + '%');
      return `<button class="card" data-id="${s.id}"><div class="info"><div class="name">${esc(s.name)}</div>
        <div class="meta">${parts.join(' · ') || '&nbsp;'}</div></div><span class="badge ${state}">${STATE[state]}</span></button>`;
    }).join('');
  }

  $('#list').addEventListener('click', (e) => {
    const c = e.target.closest('.card'); if (!c) return;
    open(c.dataset.id);
  });

  function open(id) {
    current = J(A.song(id)); if (!current) return renderLib();
    const st = J(A.status()) || {};
    if (st.running && st.id === id) return showProc();
    if (current.state === 'lista') return showPlayer();
    showOpts();
  }

  $('#add').onclick = () => A.pick();
  window.onImporting = () => { $('#add').textContent = 'Abriendo…'; };
  window.onPicked = (m) => {
    $('#add').textContent = '＋ Agregar canción';
    if (!m) return;
    if (m.error) return alert(m.error);
    current = m; renderLib(); showOpts();
  };

  // ---------- Opciones ----------
  function showOpts() {
    $('#o-name').textContent = current.name;
    if (current.coro != null) mode = current.coro ? 'coro' : 'voces';
    paintOpts();
    show('opts');
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
    let note = need.length ? `La primera vez baja el motor (${need.join(' + ')}). Conviene estar con Wi-Fi. ` : '';
    if (current.error && current.state !== 'lista') note += 'Último intento: ' + current.error;
    $('#o-note').textContent = note;
  }
  document.querySelectorAll('.opt').forEach((b) => b.onclick = () => { mode = b.dataset.mode; paintOpts(); });
  $('#hq').onclick = () => { hq = !hq; try { localStorage.setItem('hq', hq ? '1' : '0'); } catch (e) {} paintOpts(); };
  document.querySelectorAll('#motor button').forEach((b) => b.onclick = () => {
    motor = b.dataset.m; try { localStorage.setItem('motor', motor); } catch (e) {} paintOpts(); });
  $('#go').onclick = () => { A.separate(current.id, mode === 'coro', hq, motor); showProc(); };
  function delSong() {
    if (!confirm('¿Borrar "' + current.name + '" y sus pistas?')) return;
    if (view === 'play') { stopAll(); buffers = []; }
    A.delete(current.id); current = null; renderLib(); show('lib');
  }
  $('#del').onclick = delSong;
  $('#del2').onclick = delSong;

  // ---------- Procesando ----------
  function showProc() {
    $('#p-name').textContent = current.name;
    $('#p-bar').style.width = '0';
    show('proc');
    tick();
  }
  function tick() {
    const st = J(A.status()) || {};
    if (view === 'proc' && current && st.id === current.id) {
      if (st.running) {
        $('#p-stage').textContent = st.stage || 'Separando…';
        $('#p-bar').style.width = Math.round((st.total != null ? st.total : st.progress || 0) * 100) + '%';
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
  let wasRunning = false;
  $('#cancel').onclick = () => { A.cancel(); $('#p-stage').textContent = 'Cancelando…'; };
  setInterval(tick, 1000);

  // ---------- Reproductor (Web Audio: todas las pistas en sincronía exacta) ----------
  let ac = null, buffers = [], gains = [], sources = [], state = [], dur = 0;
  let playing = false, startAt = 0, offset = 0, loopA = null, loopB = null, raf = null, seeking = false;

  async function showPlayer() {
    stopAll(); offset = 0; loopA = loopB = null; paintLoop();
    $('#y-name').textContent = current.name;
    $('#tracks').innerHTML = ''; $('#transport').classList.add('hidden');
    $('#swap').classList.toggle('hidden', !current.coro);
    $('#redo').classList.toggle('hidden', !!current.received);
    $('#share').textContent = 'Mandar paquete de ensayo (WhatsApp)';
    $('#y-loading').classList.remove('hidden'); $('#y-loading').textContent = 'Cargando pistas…';
    show('play');
    if (!ac) ac = new (window.AudioContext || window.webkitAudioContext)();
    const stems = current.stems || [];
    try {
      const id = current.id;
      buffers = await Promise.all(stems.map(async (s) => {
        const r = await fetch('/songs/' + id + '/' + s.file);
        return ac.decodeAudioData(await r.arrayBuffer());
      }));
      if (!current || current.id !== id || view !== 'play') return;
    } catch (e) { $('#y-loading').textContent = 'No se pudieron cargar las pistas: ' + e.message; return; }
    dur = Math.max(...buffers.map((b) => b.duration));
    gains = buffers.map(() => { const g = ac.createGain(); g.connect(ac.destination); return g; });
    state = stems.map(() => ({ on: true, vol: 1 }));
    $('#tracks').innerHTML = stems.map((s, i) => `<div class="track on" data-i="${i}"><div class="row">
      <button class="tog on" data-tog="${i}"></button><span class="lbl">${esc(s.label)}</span>
      <button class="solo" data-solo="${i}">SOLO</button></div>
      <input type="range" min="0" max="100" value="100" data-vol="${i}"></div>`).join('');
    $('#dur').textContent = fmt(dur);
    $('#y-loading').classList.add('hidden'); $('#transport').classList.remove('hidden');
    applyGains(); paintPos();
  }

  function applyGains() {
    state.forEach((s, i) => {
      gains[i] && gains[i].gain.setTargetAtTime(s.on ? s.vol : 0, ac.currentTime, 0.02);
      const el = document.querySelector(`.track[data-i="${i}"]`);
      if (el) {
        el.classList.toggle('on', s.on); el.classList.toggle('off', !s.on);
        el.querySelector('.tog').classList.toggle('on', s.on);
      }
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

  function startAll(from) {
    stopSources();
    offset = Math.max(0, Math.min(from, dur - 0.05));
    startAt = ac.currentTime + 0.06;
    sources = buffers.map((b, i) => {
      const s = ac.createBufferSource(); s.buffer = b; s.connect(gains[i]);
      s.start(startAt, Math.min(offset, b.duration - 0.01)); return s;
    });
    playing = true; $('#play').textContent = '❚❚';
    cancelAnimationFrame(raf); raf = requestAnimationFrame(loopTick);
  }
  function stopSources() { sources.forEach((s) => { try { s.stop(); } catch (e) {} }); sources = []; }
  function stopAll() { if (playing) offset = pos(); stopSources(); playing = false; $('#play').textContent = '▶'; cancelAnimationFrame(raf); }

  function loopTick() {
    const p = pos();
    if (loopA != null && loopB != null && p >= loopB) { startAll(loopA); return; }
    if (p >= dur) { stopAll(); offset = 0; paintPos(); return; }
    paintPos();
    raf = requestAnimationFrame(loopTick);
  }
  function paintPos() {
    const p = Math.min(pos(), dur);
    $('#cur').textContent = fmt(p);
    if (!seeking) $('#seek').value = dur ? Math.round(p / dur * 1000) : 0;
  }

  $('#play').onclick = () => { if (!buffers.length) return; ac.resume(); playing ? stopAll() : startAll(offset); };
  $('#rw').onclick = () => jump(-10);
  $('#ff').onclick = () => jump(10);
  function jump(d) { const p = Math.max(0, Math.min(dur, pos() + d)); playing ? startAll(p) : (offset = p, paintPos()); }
  $('#seek').addEventListener('input', () => { seeking = true; $('#cur').textContent = fmt($('#seek').value / 1000 * dur); });
  $('#seek').addEventListener('change', () => { seeking = false; const p = $('#seek').value / 1000 * dur; playing ? startAll(p) : (offset = p, paintPos()); });

  function paintLoop() {
    $('#loopA').classList.toggle('on', loopA != null);
    $('#loopB').classList.toggle('on', loopB != null);
    $('#loopA').textContent = loopA != null ? fmt(loopA) : 'A';
    $('#loopB').textContent = loopB != null ? fmt(loopB) : 'B';
  }
  $('#loopA').onclick = () => { loopA = pos(); if (loopB != null && loopB <= loopA) loopB = null; paintLoop(); };
  $('#loopB').onclick = () => { const p = pos(); if (loopA != null && p > loopA + 0.5) { loopB = p; paintLoop(); if (playing) startAll(loopA); } };
  $('#loopX').onclick = () => { loopA = loopB = null; paintLoop(); };

  $('#redo').onclick = () => { stopAll(); showOpts(); };
  let sharing = false;
  $('#share').onclick = () => { if (sharing) return; sharing = true; $('#share').textContent = 'Armando el paquete…'; A.share(current.id); };
  window.onShareProgress = (p, err) => {
    if (p >= 0) { $('#share').textContent = 'Armando el paquete… ' + Math.round(p * 100) + '%'; return; }
    sharing = false; $('#share').textContent = 'Mandar paquete de ensayo (WhatsApp)';
    if (p === -2) alert(err);
  };
  // Intercambia los nombres sin volver a cargar el audio
  $('#swap').onclick = () => {
    const m = J(A.swapVoices(current.id)); if (!m || !m.stems) return;
    const files = (current.stems || []).map((s) => s.file);
    current = m;
    const order = m.stems.map((s) => files.indexOf(s.file));
    buffers = order.map((i) => buffers[i]); gains = order.map((i) => gains[i]); state = order.map((i) => state[i]);
    if (playing) { const p = pos(); startAll(p); }
    $('#tracks').innerHTML = m.stems.map((s, i) => `<div class="track on" data-i="${i}"><div class="row">
      <button class="tog on" data-tog="${i}"></button><span class="lbl">${esc(s.label)}</span>
      <button class="solo" data-solo="${i}">SOLO</button></div>
      <input type="range" min="0" max="100" value="${Math.round(state[i].vol * 100)}" data-vol="${i}"></div>`).join('');
    applyGains();
  };

  // ---------- Navegación ----------
  function back() {
    if (view === 'lib') return false;
    if (view === 'play') { stopAll(); buffers = []; }
    renderLib(); show('lib'); return true;
  }
  document.querySelectorAll('[data-back]').forEach((b) => b.onclick = back);
  window.onBack = back;

  renderLib();

  // Para probar la interfaz en una computadora, sin el teléfono
  function mock() {
    const songs = [{ id: 'a', name: 'Canción de prueba', created: 1, state: 'nueva' }];
    return {
      songs: () => JSON.stringify(songs), song: (id) => JSON.stringify(songs.find((s) => s.id === id) || null),
      status: () => '{"running":false}', models: () => '{"voces":false,"coro":false}', swapVoices: () => 'null',
      pick() {}, share() {}, separate() {}, cancel() {}, delete() {}, rename() {},
    };
  }
})();
