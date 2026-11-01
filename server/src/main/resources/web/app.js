const $ = (sel, root = document) => root.querySelector(sel);
const form = $('#searchForm'), input = $('#q'), results = $('#results'), main = document.querySelector('main');
const detail = $('#detail'), detailsCache = new Map(), epCache = new Map();
let es = null, timer = 0, activeQuery = '', done = false, playerState = null, currentDetailId = null;
const timeout = () => ({});

function el(tag, props, ...kids) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(props || {})) {
    if (value == null || value === false) continue;
    if (key === 'text') node.textContent = value;
    else if (key === 'class') node.className = value;
    else node.setAttribute(key, value === true ? '' : value);
  }
  for (const kid of kids.flat(2)) if (kid) node.append(kid);
  return node;
}

function safeUrl(value) {
  try { const url = new URL(value, location.href); return /^https?:$/.test(url.protocol) ? url.href : null; }
  catch { return null; }
}

const pretty = (v) => String(v).split('_').map((w) => (w.length <= 3 ? w : w[0] + w.slice(1).toLowerCase())).join(' ');
const num = (v) => (v == null || !Number.isFinite(Number(v)) ? null : Number(v));
const urlQ = () => new URLSearchParams(location.search).get('q') || '';
const currentPathId = () => (location.pathname.match(/^\/(\d+)\/?$/) || [])[1] || null;
const detailUrl = (id) => '/' + encodeURIComponent(String(id));
const listUrl = (q) => (q ? '/?q=' + encodeURIComponent(q) : '/');
const titleLang = () => { try { return localStorage.getItem('animeTitleLang') === 'en' ? 'en' : 'orig'; } catch { return 'orig'; } };

function setQueryUrl(q) {
  try {
    const url = new URL(location.href);
    q ? url.searchParams.set('q', q) : url.searchParams.delete('q');
    history.replaceState(null, '', url);
  } catch { }
}

function cacheDetail(id, data) {
  while (detailsCache.size >= 40) detailsCache.delete(detailsCache.keys().next().value);
  detailsCache.set(id, data);
}
function cacheEps(id, data) { epCache.set(id, { at: Date.now(), data }); while (epCache.size > 12) epCache.delete(epCache.keys().next().value); }
function cachedEps(id) { const entry = epCache.get(id); if (!entry || Date.now() - entry.at > 120000) { epCache.delete(id); return null; } return entry.data; }

function hint() {
  return el('div', { class: 'empty' }, el('strong', { text: 'Search anime' }), el('span', { text: 'Type at least 2 characters.' }));
}
function skeletonGroup() {
  const card = el('section', { class: 'group skeleton', 'aria-hidden': 'true' });
  for (let i = 0; i < 3; i++) card.append(el('div', { class: 'sk-row' }, el('div', { class: 'sk-thumb' }),
    el('div', { class: 'sk-body' }, el('div', { class: 'sk-line' }), el('div', { class: 'sk-line sk-short' }))));
  return card;
}
function hitRow(hit) {
  const row = el('a', { class: 'row', target: '_blank', rel: 'noopener', 'data-id': hit.externalId == null || hit.externalId === '' ? undefined : String(hit.externalId) });
  const href = safeUrl(hit.url);
  if (href) row.href = href;
  const frame = el('div', { class: 'thumb' });
  if (typeof hit.image === 'string' && hit.image) {
    const img = el('img', { src: '/api/thumb?u=' + encodeURIComponent(hit.image), alt: '', loading: 'lazy', decoding: 'async', width: 48, height: 68 });
    img.onerror = () => { img.remove(); frame.classList.add('empty'); };
    frame.append(img);
  } else frame.classList.add('empty');
  const meta = [hit.year, hit.episodes != null && `${hit.episodes} ep`, hit.format, hit.score != null && hit.score > 0 && `★ ${Math.round(hit.score)}`].filter(Boolean).join(' · ');
  row.append(frame, el('div', { class: 'body' },
    el('div', { class: 'rtitle', text: hit.title || hit.titleEn || 'Untitled' }),
    meta && el('div', { class: 'rmeta', text: meta }),
    hit.snippet && el('div', { class: 'snippet', text: hit.snippet })));
  return row;
}
function groupCard(result) {
  const hits = Array.isArray(result.hits) ? result.hits : [];
  const card = el('section', { class: 'group' });
  if (result.error) card.append(el('div', { class: 'empty small', text: 'No results — source failed.' }));
  else if (!hits.length) card.append(el('div', { class: 'empty small', text: 'No hits.' }));
  else {
    const body = document.createDocumentFragment();
    hits.slice(0, 30).forEach((hit) => body.append(hitRow(hit)));
    if (hits.length > 30) body.append(el('div', { class: 'more', text: `+${hits.length - 30} more` }));
    card.append(body);
  }
  return card;
}
function renderResult(result) {
  const key = result.sourceId || result.sourceName || 'source';
  const card = groupCard(result);
  const previous = results.querySelector(`[data-src="${CSS.escape(key)}"]`);
  card.dataset.src = key;
  if (previous) { card.classList.add('flash'); previous.replaceWith(card); }
  else results.append(card);
}

function reset() {
  clearTimeout(timer);
  if (es) es.close();
  es = null; activeQuery = ''; done = false;
  results.replaceChildren(hint());
  setQueryUrl('');
}
function fail() {
  results.replaceChildren(el('div', { class: 'banner', role: 'alert' },
    el('span', { text: 'Connection lost. Is the server running?' })));
}
function search(raw, options = {}) {
  const q = String(raw == null ? '' : raw).trim();
  clearTimeout(timer);
  if (!q) return reset();
  if (!options.force && q === activeQuery) return;
  if (es) es.close();
  activeQuery = q; done = false;
  results.replaceChildren(skeletonGroup());
  setQueryUrl(q);
  const stream = new EventSource('/api/search/stream?q=' + encodeURIComponent(q));
  es = stream;
  stream.onmessage = (message) => {
    if (es !== stream) return;
    let event;
    try { event = JSON.parse(message.data); } catch { return; }
    if (event.type === 'result' && event.result) { results.querySelector('.skeleton')?.remove(); renderResult(event.result); }
    if (event.type === 'done') {
      done = true; stream.close(); es = null;
      if (!results.querySelector('.group')) results.replaceChildren(el('div', { class: 'empty' }, el('strong', { text: 'Nothing found' })));
    }
  };
  stream.onerror = () => { if (es === stream && !done) { stream.close(); es = null; fail(); } };
}

/* ---------- thumbnails ---------- */
function thumbImg(src, w, h) {
  if (typeof src === 'string' && src) return '/api/thumb?u=' + encodeURIComponent(src) + '&w=' + w + '&h=' + h;
  return null;
}
function detailImage(src, wrapClass, width, height) {
  const wrap = el('div', { class: wrapClass }), href = safeUrl(src);
  if (!href) { wrap.classList.add('empty'); return wrap; }
  const img = el('img', { src: href, alt: '', loading: 'lazy', decoding: 'async', width, height });
  img.onerror = () => { img.remove(); wrap.classList.add('empty'); };
  wrap.append(img);
  return wrap;
}

/* ---------- detail ---------- */
function attachScrollFade(node) {
  const sync = () => {
    node.classList.toggle('fade-l', node.scrollLeft > 2);
    node.classList.toggle('fade-r', node.scrollWidth - node.clientWidth - node.scrollLeft > 2);
  };
  node.addEventListener('scroll', sync, { passive: true });
  requestAnimationFrame(sync);
}
function fitLines(node, text, lines) {
  node.textContent = text;
  const lh = parseFloat(getComputedStyle(node).lineHeight || '0') || 20;
  const max = lh * lines + 2;
  if (node.scrollHeight <= max) return text;
  let lo = 0, hi = text.length;
  while (lo < hi) {
    const mid = Math.ceil((lo + hi) / 2);
    node.textContent = text.slice(0, mid);
    if (node.scrollHeight <= max) lo = mid; else hi = mid - 1;
  }
  const cleanup = (v) => v.replace(/[\s.,;:!?…·、。「」"'“”‘’()\[\]{}<>—–\-/\\|]+$/u, '');
  let n = lo;
  let cut = cleanup(text.slice(0, n)) || text.slice(0, n);
  node.textContent = cut + '…';
  while (node.scrollHeight > max && n > 0) {
    const prev = text.lastIndexOf(' ', n - 1);
    n = prev > 0 ? prev : 0;
    cut = cleanup(text.slice(0, n)) || text.slice(0, n);
    node.textContent = cut + '…';
  }
  return cut + '…';
}
function detailSkeleton() {
  return el('div', { class: 'dsk', 'aria-hidden': 'true' },
    el('header', { class: 'd-bar' }, Object.assign(el('button', { class: 'd-back', type: 'button', 'aria-label': 'Back' }), { onclick: () => backFromDetail() })), el('div', { class: 'd-hero' }),
    el('div', { class: 'd-head' }, el('div', { class: 'sk-line d-sk-cover' }), el('div', { class: 'd-titlebox' },
      el('div', { class: 'sk-line d-sk-t1' }), el('div', { class: 'sk-line' }), el('div', { class: 'sk-line sk-short' }))),
    el('div', { class: 'dsec' }, el('div', { class: 'sk-line', style: 'width:32%' }), el('div', { class: 'sk-line' })));
}
function renderDetail(d) {
  const t = d.title && typeof d.title === 'object' ? d.title : {};
  const lang = titleLang();
  const name = (lang === 'en' ? t.english || t.romaji || t.native : t.romaji || t.native || t.english)
    || (typeof d.title === 'string' ? d.title : null) || 'Untitled';
  const hero = el('div', { class: 'd-hero' + (safeUrl(d.bannerImage) ? '' : ' no-banner') });
  if (typeof d.bannerImage === 'string' && d.bannerImage) {
    const bi = el('img', { src: d.bannerImage, alt: '', class: 'd-banner', loading: 'lazy', decoding: 'async' });
    bi.onerror = () => { bi.remove(); hero.classList.add('no-banner'); };
    hero.append(bi);
  }
  const seasonName = d.season ? pretty(d.season) : null;
  const seasonYear = num(d.seasonYear);
  const score = num(d.averageScore);
  const ring = score == null ? null : el('span', {
    class: 'ring ' + (score >= 75 ? 'hi' : score >= 50 ? 'mid' : 'lo'),
    role: 'img', 'aria-label': 'Score ' + Math.round(score) + ' out of 100', title: 'Score ' + Math.round(score) + ' / 100',
  }, scoreRing(score), el('b', { text: String(Math.round(score)) }));
  const studios = (Array.isArray(d.studios) ? d.studios : []).filter((v) => typeof v === 'string' && v).join(', ');
  const genreNames = (Array.isArray(d.genres) ? d.genres : []).filter((v) => typeof v === 'string' && v);
  const tagNames = (Array.isArray(d.tags) ? d.tags : [])
    .filter((tg) => tg && tg.name && num(tg.rank) > 50)
    .sort((a, b) => num(b.rank) - num(a.rank))
    .map((tg) => tg.name);
  const tagRow = tagNames.length && el('div', { class: 'd-tax-tags' }, tagNames.map((name2) => el('span', { class: 'd-tax-tag', text: name2 })));
  if (tagRow) attachScrollFade(tagRow);
  const tax = (genreNames.length || tagNames.length) && el('div', { class: 'd-tax' },
    genreNames.length && el('div', { class: 'd-tax-genres' }, genreNames.map((g) => el('span', { class: 'd-tax-genre', text: g }))),
    genreNames.length && tagNames.length && el('span', { class: 'd-tax-sep', text: '·' }),
    tagRow);

  let descParts = null;
  const descText = typeof d.description === 'string' ? d.description.trim() : '';
  if (descText) {
    const desc = el('div', { class: 'd-desc', text: descText });
    let truncated = false;
    desc.addEventListener('click', () => { if (truncated) openDescription(descText); });
    requestAnimationFrame(() => {
      const preview = fitLines(desc, descText, 3);
      if (preview === descText) return;
      truncated = true;
      desc.textContent = preview;
      desc.classList.add('trunc');
    });
    descParts = [desc];
  }

  const chars = (Array.isArray(d.characters) ? d.characters : []).filter((c) => c && typeof c === 'object').slice(0, 18)
    .map((c) => el('div', { class: 'char', title: c.role || undefined }, detailImage(c.image, 'c-frame', 72, 72),
      el('span', { class: 'c-name', text: c.name || 'Unknown' })));
  const charsRow = chars.length && el('div', { class: 'd-chars' }, chars);
  if (charsRow) attachScrollFade(charsRow);

  const status = typeof d.status === 'string' && d.status ? d.status.toUpperCase() : null;
  const dots = status && status !== 'FINISHED'
    ? el('span', { class: 'tag ep-more', title: pretty(status), 'aria-label': pretty(status), text: '⋯' })
    : null;

  const epBody = el('div', { class: 'ep-body' });
  epBody.replaceChildren(epSkeleton());

  const back = Object.assign(el('button', { class: 'd-back', type: 'button', 'aria-label': 'Back' }), { onclick: () => backFromDetail() });
  const langToggle = Object.assign(el('button', {
    type: 'button', class: 'd-lang-btn',
    'aria-label': lang === 'en' ? 'Show original title' : 'Show English title',
    title: lang === 'en' ? 'Show original title' : 'Show English title',
    'aria-pressed': String(lang === 'en'),
  }, lang === 'en' ? 'JP' : 'EN'), { onclick: () => setTitleLang(lang === 'en' ? 'orig' : 'en') });

  const siteLink = safeUrl(d.siteUrl);
  const cover = el('div', { class: 'd-cover-wrap' }, detailImage(d.coverImage, 'd-cover', 160, 240),
    ring && el('div', { class: 'd-cover-rate' }, ring));

  detail.replaceChildren(
    el('header', { class: 'd-bar' }, back, langToggle), hero,
    el('div', { class: 'd-head' }, cover,
      el('div', { class: 'd-titlebox' },
        (seasonName != null || seasonYear != null || studios) && el('div', { class: 'd-eyebrow' },
          seasonName != null && el('span', { class: 'd-meta-k', text: seasonName }),
          seasonYear != null && el('span', { class: 'd-meta-v', text: String(seasonYear) }),
          (seasonName != null || seasonYear != null) && studios && el('span', { class: 'd-tax-sep', text: '·' }),
          studios && el('span', { class: 'd-meta-k', text: 'Studio' }),
          studios && el('span', { class: 'd-meta-v', text: studios })),
        el('h2', { class: 'd-title' }, siteLink
          ? el('a', { class: 'd-title-link', href: siteLink, target: '_blank', rel: 'noopener noreferrer', text: name })
          : name),
        tax,
        descParts && el('div', { class: 'd-desc-block' }, ...descParts),
        charsRow && el('div', { class: 'd-chars-block' }, charsRow))),
    el('section', { class: 'dsec ep-sec' },
      dots && el('div', { class: 'src-hrow ep-hrow' }, dots),
      epBody));
  loadEpisodes(currentDetailId, epBody, status);
}

function scoreRing(score) {
  const NS = 'http://www.w3.org/2000/svg';
  const svg = document.createElementNS(NS, 'svg');
  svg.setAttribute('viewBox', '0 0 42 42');
  svg.setAttribute('class', 'ring-svg');
  svg.setAttribute('aria-hidden', 'true');
  const arc = document.createElementNS(NS, 'circle');
  const len = 2 * Math.PI * 18;
  arc.setAttribute('cx', '21');
  arc.setAttribute('cy', '21');
  arc.setAttribute('r', '18');
  arc.setAttribute('stroke-dasharray', ((score / 100) * len).toFixed(2) + ' ' + len.toFixed(2));
  svg.append(arc);
  return svg;
}

function openDescription(text) {
  const dialog = el('div', { class: 'd-overlay' },
    el('div', { class: 'd-overlay-bg', 'data-close': true }),
    el('section', { class: 'd-dialog', role: 'dialog', 'aria-modal': 'true', 'aria-label': 'Description' },
      el('h3', { class: 'd-dialog-h', text: 'Description' }),
      el('p', { class: 'd-dialog-text', text }),
      el('button', { class: 'btn d-dialog-close', type: 'button', text: 'Close' })));
  const close = () => {
    dialog.remove();
    document.body.classList.remove('no-scroll');
    document.removeEventListener('keydown', onKey);
  };
  const onKey = (event) => { if (event.key === 'Escape') { event.preventDefault(); close(); } };
  dialog.addEventListener('click', (event) => {
    if (event.target === dialog || event.target.closest('[data-close]') || event.target.closest('.d-dialog-close')) close();
  });
  document.addEventListener('keydown', onKey);
  document.body.append(dialog);
  document.body.classList.add('no-scroll');
  dialog.querySelector('.d-dialog-close').focus();
}

/* ---------- episodes ---------- */
async function loadEpisodes(id, body, status) {
  try {
    let data = cachedEps(id);
    if (!data) {
      body.replaceChildren(epSkeleton());
      const res = await fetch('/api/episodes?id=' + encodeURIComponent(String(id)), { headers: { accept: 'application/json' } });
      if (!res.ok) throw new Error(res.status);
      data = await res.json();
      if (!data || typeof data !== 'object' || !Array.isArray(data.seasons)) throw new Error('bad payload');
      cacheEps(id, data);
    }
    if (id !== currentDetailId) return;
    body.replaceChildren(episodesBody(data, status));
  } catch {
    if (id === currentDetailId) body.replaceChildren(el('div', { class: 'empty small', text: 'episode list unavailable' }));
  }
}
function epSkeleton() {
  return el('div', { class: 'ep-skel' },
    el('div', { class: 'sk-line', style: 'width:22%' }),
    el('div', { class: 'sk-tile-row' },
      el('div', { class: 'sk-ep-tile' }), el('div', { class: 'sk-ep-tile' }), el('div', { class: 'sk-ep-tile' })));
}
function epTile(e, season) {
  const no = num(e.number);
  const tile = el('div', { class: 'ep-tile', role: 'button', tabindex: '0', 'aria-label': 'Play episode ' + no, title: 'Play episode' });
  const play = () => { if (Number.isFinite(no)) openPlayer(currentDetailId, season, no); };
  tile.addEventListener('click', play);
  tile.addEventListener('keydown', (event) => { if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); play(); } });
  if (typeof e.thumb === 'string' && e.thumb) {
    const img = el('img', { src: thumbImg(e.thumb, 320, 180), alt: '', loading: 'lazy', decoding: 'async' });
    img.onerror = () => { img.remove(); tile.classList.add('empty'); };
    tile.append(img);
  } else tile.classList.add('empty');
  tile.append(
    el('span', { class: 'ep-no', text: 'E' + String(Number.isFinite(no) ? Math.round(no) : 0).padStart(2, '0') }),
    el('span', { class: 'ep-tile-title', text: (typeof e.title === 'string' && e.title) || 'Episode ' + no }));
  const fraction = episodeFraction(currentDetailId, season, no);
  if (fraction) {
    tile.append(el('span', { class: 'ep-progress-track' }, el('i', { class: 'ep-progress-fill', style: 'width:' + (fraction * 100).toFixed(1) + '%' })));
  }
  return tile;
}
function seasonBlock(s) {
  const eps = (Array.isArray(s.episodes) ? s.episodes : []).filter((e) => e && typeof e === 'object');
  const season = num(s.season);
  const name = season === 0 ? 'Specials' : (season >= 1900 && season <= 2099 ? String(season) : 'Season ' + (Number.isFinite(season) ? Math.round(season) : '?'));
  const scroller = eps.length && el('div', { class: 'ep-scroller' }, eps.map((e) => epTile(e, season)));
  if (scroller) attachScrollFade(scroller);
  return el('div', { class: 'ep-season' }, el('span', { class: 'd-meta-k ep-season-label', text: name }),
    scroller || el('div', { class: 'empty small', text: 'no episodes listed' }));
}
function statusNotice(status) {
  if (!status) return null;
  const text = { RELEASING: 'Still airing — more episodes to come', HIATUS: 'On hiatus', NOT_YET_RELEASED: 'Not aired yet', CANCELLED: 'Cancelled' }[String(status).toUpperCase()];
  return text ? el('div', { class: 'ep-notice', text }) : null;
}
function episodesBody(list, status) {
  const seasons = (Array.isArray(list.seasons) ? list.seasons : []).filter((s) => s && typeof s === 'object');
  const frag = document.createDocumentFragment();
  if (!seasons.some((s) => (Array.isArray(s.episodes) ? s.episodes : []).length)) {
    frag.append(el('div', { class: 'empty small', text: 'no episode data yet' }));
  } else {
    seasons.forEach((s) => frag.append(seasonBlock(s)));
  }
  const notice = statusNotice(status);
  if (notice) frag.append(notice);
  return frag;
}

/* ---------- player ---------- */
function resumeKey(id, season, episode) { return 'pos:' + id + ':' + season + ':' + episode; }
function loadResume(id, season, episode) {
  try {
    const raw = localStorage.getItem(resumeKey(id, season, episode));
    if (!raw) return 0;
    const [pos, dur] = raw.split('|').map(Number);
    if (!Number.isFinite(pos) || !Number.isFinite(dur) || dur <= 0) return 0;
    if (pos < 3000 || pos >= dur - 20000) return 0;
    return pos;
  } catch { return 0; }
}
function saveResume(id, season, episode, pos, dur) {
  try {
    if (dur > 0 && pos > 3000 && pos < dur - 20000) localStorage.setItem(resumeKey(id, season, episode), pos + '|' + dur);
    else if (pos >= dur - 20000) localStorage.removeItem(resumeKey(id, season, episode));
  } catch { }
}
function episodeFraction(id, season, episode) {
  try {
    const raw = localStorage.getItem(resumeKey(id, season, episode));
    if (!raw) return null;
    const [pos, dur] = raw.split('|').map(Number);
    if (!Number.isFinite(pos) || !Number.isFinite(dur) || dur <= 0) return null;
    return Math.min(1, Math.max(0, pos / dur));
  } catch { return null; }
}
function openPlayer(id, season, episode) {
  closePlayer();
  const title = (detailsCache.get(id)?.title?.romaji || detailsCache.get(id)?.title?.english || 'Episode') + ' — S' + season + ' E' + episode;
  const video = el('video', { class: 'p-video', playsinline: true, controls: true });
  const status = el('div', { class: 'p-status', text: 'Resolving stream…' });
  const chips = el('div', { class: 'p-sources' });
  const close = el('button', { class: 'p-close', type: 'button', 'aria-label': 'Close player', text: '✕' });
  const wrap = el('div', { class: 'player', role: 'dialog', 'aria-modal': 'true' },
    el('div', { class: 'p-bar' }, el('div', { class: 'p-title', text: title }), close), chips, video, status);
  document.body.append(wrap);
  document.body.classList.add('no-scroll');
  const onKey = (event) => { if (event.key === 'Escape') { event.preventDefault(); closePlayer(); } };
  document.addEventListener('keydown', onKey);
  close.addEventListener('click', () => closePlayer());
  playerState = { wrap, video, chips, status, sources: [], index: -1, hls: null, onKey, resumeFrom: 0 };

  fetch('/api/play?id=' + encodeURIComponent(String(id)) + '&s=' + encodeURIComponent(season) + '&e=' + encodeURIComponent(episode), { headers: { accept: 'application/json' } })
    .then((res) => res.json().then((data) => ({ ok: res.ok, data })).catch(() => ({ ok: false, data: null })))
    .then(({ ok, data }) => {
      if (playerState?.wrap !== wrap) return;
      if (!ok || !data || !Array.isArray(data.sources) || !data.sources.length) {
        status.textContent = (data && data.error) ? String(data.error) : 'No playable source for this episode.';
        return;
      }
      playerState.sources = data.sources;
      renderChips();
      startSource(0);
      fetch('/api/play?id=' + encodeURIComponent(String(id)) + '&s=' + encodeURIComponent(season) + '&e=' + encodeURIComponent(episode + 1), { headers: { accept: 'application/json' } }).catch(() => {});
    })
    .catch(() => { if (playerState?.wrap === wrap) status.textContent = 'Could not resolve a stream.'; });

  function renderChips() {
    const st = playerState;
    if (!st) return;
    st.chips.replaceChildren(...st.sources.map((src, i) =>
      Object.assign(el('button', { type: 'button', class: 'p-src' + (i === st.index ? ' on' : ''), text: src.name }), { onclick: () => startSource(i) })));
  }
  function startSource(index) {
    const st = playerState;
    if (!st) return;
    if (st.video.currentTime > 0 && st.video.duration > 0) st.resumeFrom = st.video.currentTime * 1000;
    if (st.hls) { runCatching(() => st.hls.destroy()); st.hls = null; }
    st.video.pause();
    st.video.removeAttribute('src');
    st.video.load();
    const src = st.sources[index];
    if (!src) { st.status.textContent = 'All sources failed.'; st.index = -1; renderChips(); return; }
    st.index = index;
    renderChips();
    st.status.textContent = 'Loading ' + src.name + '…';
    st.resumeFrom = st.index === 0 ? loadResume(id, season, episode) : st.resumeFrom;
    startPlayback(st, src, () => nextSource());
  }
  function nextSource() {
    const st = playerState;
    if (!st) return;
    if (st.index + 1 < st.sources.length) startSource(st.index + 1);
    else { st.status.textContent = 'All sources failed.'; st.index = -1; renderChips(); }
  }
}
function runCatching(fn) { try { fn(); } catch { } }
function loadHlsJs() {
  if (window.Hls) return Promise.resolve(window.Hls);
  return new Promise((resolve, reject) => {
    const script = document.createElement('script');
    script.src = '/static/hls.min.js';
    script.onload = () => resolve(window.Hls);
    script.onerror = () => reject(new Error('hls.js load failed'));
    document.head.append(script);
  });
}
async function startPlayback(st, source, onFatal) {
  const { video, status, wrap } = st;
  video.onerror = () => onFatal();
  if (source.kind === 'hls' && video.canPlayType('application/vnd.apple.mpegurl') && !window.Hls) {
    video.src = source.url;
    await video.play().catch(() => { });
    status.textContent = '';
    return;
  }
  const Hls = await loadHlsJs();
  if (Hls && Hls.isSupported()) {
    const hls = new Hls({
      maxBufferLength: 30, maxMaxBufferLength: 60, backBufferLength: 30,
      enableWorker: true, capLevelToPlayerSize: true, startLevel: -1,
    });
    st.hls = hls;
    hls.loadSource(source.url);
    hls.attachMedia(video);
    hls.on(Hls.Events.MANIFEST_PARSED, () => {
      if (st.resumeFrom > 0) { try { video.currentTime = st.resumeFrom / 1000; } catch { } st.resumeFrom = 0; }
      video.play().catch(() => { });
      status.textContent = '';
    });
    const saveTick = () => saveResume(id, season, episode, video.currentTime * 1000, video.duration * 1000);
    video.addEventListener('timeupdate', saveTick);
    hls.on(Hls.Events.ERROR, (event, data) => {
      if (!data || !data.fatal || playerState?.wrap !== wrap) return;
      hls.destroy();
      st.hls = null;
      onFatal();
    });
    return;
  }
  video.src = source.url;
  await video.play().catch(() => { });
  status.textContent = '';
}
function closePlayer() {
  if (!playerState) return;
  const { wrap, hls, onKey } = playerState;
  if (hls) runCatching(() => hls.destroy());
  document.removeEventListener('keydown', onKey);
  wrap.remove();
  document.body.classList.remove('no-scroll');
  playerState = null;
}

/* ---------- navigation ---------- */
function openDetail(id, q) {
  currentDetailId = id;
  document.body.classList.add('detail-mode');
  main.hidden = true;
  detail.hidden = false; window.scrollTo(0, 0);
  const data = detailsCache.get(id);
  if (data) { renderDetail(data); return; }
  detail.replaceChildren(detailSkeleton());
  fetch('/api/details?id=' + encodeURIComponent(id), { headers: { accept: 'application/json' }, ...timeout() })
    .then((res) => { if (!res.ok) throw new Error(res.status); return res.json(); })
    .then((data2) => { if (!data2 || typeof data2 !== 'object') throw new Error('bad payload'); cacheDetail(id, data2); if (currentDetailId === id) renderDetail(data2); })
    .catch(() => { if (currentDetailId === id) showList(q, true); });
}
function showList(q, setUrl) {
  currentDetailId = null;
  detail.hidden = true; detail.replaceChildren();
  document.body.classList.remove('detail-mode'); main.hidden = false;
  if (setUrl) { try { history.replaceState(null, '', listUrl(q)); } catch { } }
  const eq = String(q == null ? '' : q).trim();
  if (eq !== activeQuery || !results.querySelector('.group')) { input.value = eq; search(eq); }
  else setQueryUrl(eq);
}
function backFromDetail() { if (history.state) history.back(); else showList(urlQ(), true); }
function pushDetail(id) {
  try { history.pushState({ detail: id }, '', detailUrl(id)); } catch { }
  openDetail(id, '');
}
function setTitleLang(mode) {
  try { localStorage.setItem('animeTitleLang', mode); } catch { }
  closePlayer();
  const data = currentDetailId && detailsCache.get(currentDetailId);
  if (!data) return;
  const y = window.scrollY;
  renderDetail(data);
  requestAnimationFrame(() => window.scrollTo(0, y));
}

/* ---------- events ---------- */
form.addEventListener('submit', (event) => { event.preventDefault(); search(input.value, { force: true }); });
input.addEventListener('input', () => {
  clearTimeout(timer);
  const q = input.value.trim();
  if (q.length >= 2) timer = setTimeout(() => search(q), 350);
  else if (!q) timer = setTimeout(reset, 350);
});
window.addEventListener('popstate', () => { const id = currentPathId(); if (id) openDetail(id, ''); else showList(urlQ()); });
results.addEventListener('click', (event) => {
  const row = event.target.closest('.row');
  if (!row || !row.dataset.id || event.button || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
  event.preventDefault(); pushDetail(row.dataset.id);
});
document.addEventListener('keydown', (event) => {
  if (event.key !== 'Escape') return;
  if (playerState) return;
  if (!detail.hidden) { event.preventDefault(); backFromDetail(); return; }
  if (input.value) { event.preventDefault(); input.value = ''; reset(); input.focus(); }
});

const bootId = currentPathId(), bootQ = new URLSearchParams(location.search).get('q') || '';
if (bootId) { input.value = ''; openDetail(bootId, ''); }
else if (bootQ.trim()) { input.value = bootQ.trim(); search(bootQ, { force: true }); }
else reset();
