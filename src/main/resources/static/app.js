// CaptureU 최소 화면 (#15). 프레임워크 없이 API만 부른다. 화면에 넣는 값은 모두 textContent로 넣는다.
const HOOK_LABELS = {
  QUESTION: '질문형', TARGET: '대상 지목형', LIST: '목록형', HOW_TO: '방법·꿀팁형',
  STORY: '이야기·후기형', ANNOUNCE: '공지·예고형', OTHER: '기타',
};
const SCOPE_LABELS = { COMMON: '공통 패턴', OWN: '내 계정', SINGLE: '한 계정 스타일' };
const LAST_ANALYSIS = 'captureu.lastAnalysisId';

const $ = (sel) => document.querySelector(sel);
const fmt = (n) => (n == null ? '-' : Math.round(n).toLocaleString('ko-KR'));
const pct = (n) => (n == null ? '-' : `${n}%`);

function setMsg(el, text, error = false) {
  el.textContent = text;
  el.classList.toggle('error', error);
}

function el(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text != null) node.textContent = text;
  return node;
}

async function api(method, url, body) {
  const res = await fetch(url, {
    method,
    headers: body ? { 'Content-Type': 'application/json' } : {},
    body: body ? JSON.stringify(body) : undefined,
  });
  const data = await res.json().catch(() => null);
  if (!res.ok) {
    const err = new Error((data && data.detail) || `요청에 실패했어요 (${res.status})`);
    err.data = data;
    throw err;
  }
  return data;
}

function remember(id) {
  try { localStorage.setItem(LAST_ANALYSIS, String(id)); } catch (e) { /* 저장이 막혀 있어도 화면은 동작한다 */ }
}
function recall() {
  try { return localStorage.getItem(LAST_ANALYSIS); } catch (e) { return null; }
}

// 1. 컨셉
const concept = { main: null, sub: null };

function renderConceptChips(options) {
  const draw = (boxSel, key) => {
    const box = $(boxSel);
    box.replaceChildren();
    options.forEach((o) => {
      const b = el('button', null, o.label);
      b.type = 'button';
      b.setAttribute('aria-pressed', String(concept[key] === o.code));
      b.addEventListener('click', () => chooseConcept(key, o.code, options));
      box.append(b);
    });
  };
  draw('#main-concepts', 'main');
  draw('#sub-concepts', 'sub');
}

async function chooseConcept(key, code, options) {
  if (key === 'sub' && code === concept.main) return; // 메인과 같은 컨셉은 2순위로 못 고른다
  const prev = { ...concept };
  if (key === 'main') {
    concept.main = code;
    if (concept.sub === code) concept.sub = null;
  } else {
    concept.sub = concept.sub === code ? null : code; // 다시 누르면 해제
  }
  renderConceptChips(options);
  if (!concept.main) { setMsg($('#concept-msg'), '메인 컨셉을 먼저 골라 주세요.'); return; }
  try {
    await api('PUT', '/api/profile', { main: concept.main, sub: concept.sub });
    setMsg($('#concept-msg'), '저장했어요.');
  } catch (e) {
    Object.assign(concept, prev);
    renderConceptChips(options);
    setMsg($('#concept-msg'), e.message, true);
  }
}

async function loadConcepts() {
  try {
    const [options, res] = await Promise.all([
      api('GET', '/api/profile/concepts'),
      fetch('/api/profile'),
    ]);
    if (res.status === 200) Object.assign(concept, await res.json());
    renderConceptChips(options);
  } catch (e) {
    setMsg($('#concept-msg'), e.message, true);
  }
}

// 2. 참고 계정
async function loadRefs() {
  try {
    const refs = await api('GET', '/api/reference-accounts');
    const inputs = document.querySelectorAll('#refs-form input');
    refs.slice(0, inputs.length).forEach((r, i) => { inputs[i].value = r.username; });
  } catch (e) {
    setMsg($('#refs-msg'), e.message, true);
  }
}

$('#refs-form').addEventListener('submit', async (ev) => {
  ev.preventDefault();
  const usernames = [...ev.target.querySelectorAll('input')]
    .map((i) => i.value.trim().replace(/^@/, ''))
    .filter(Boolean);
  const btn = ev.target.querySelector('button');
  btn.disabled = true;
  try {
    const saved = await api('PUT', '/api/reference-accounts', { usernames });
    const few = saved.length < 3 ? ' 3개를 넣으면 한 계정 스타일에 덜 치우쳐요.' : '';
    setMsg($('#refs-msg'), `${saved.length}개를 저장했어요.${few}`);
  } catch (e) {
    const rejections = (e.data && e.data.rejections) || [];
    const detail = rejections.map((r) => `@${r.username}: ${r.message}`).join('\n');
    setMsg($('#refs-msg'), detail || e.message, true);
  } finally {
    btn.disabled = false;
  }
});

// 3. 분석
$('#analyze-btn').addEventListener('click', async () => {
  const btn = $('#analyze-btn');
  btn.disabled = true;
  setMsg($('#analysis-msg'), '게시물과 지표를 모으는 중이에요...');
  try {
    const a = await api('POST', '/api/analyses');
    if (a.status !== 'DONE') {
      setMsg($('#analysis-msg'), `분석에 실패했어요: ${a.failureMessage || a.failureType}`, true);
      return;
    }
    const failed = a.references.filter((r) => r.status !== 'DONE').map((r) => `@${r.username}`);
    const sec = a.durationMs != null ? ` (${(a.durationMs / 1000).toFixed(1)}초)` : '';
    setMsg($('#analysis-msg'), `게시물 ${a.mediaCount}개를 분석했어요${sec}.`
      + (failed.length ? `\n${failed.join(', ')}는 가져오지 못했어요.` : ''));
    remember(a.id);
    await showReport(a.id);
  } catch (e) {
    setMsg($('#analysis-msg'), e.message, true);
  } finally {
    btn.disabled = false;
  }
});

// 4. 리포트
let currentAnalysisId = null;

async function showReport(id) {
  const report = await api('GET', `/api/analyses/${id}/report`);
  currentAnalysisId = id;
  renderSummary(report.ownSummary);
  const box = $('#accounts');
  box.replaceChildren(renderAccount(report.own), ...report.references.map(renderAccount));
  if (report.fewReferences) {
    box.prepend(el('p', 'hint', '참고 계정이 3개보다 적어 한 계정 스타일에 치우칠 수 있어요.'));
  }
  ['#step-report', '#step-recommend'].forEach((s) => $(s).classList.remove('hidden'));
}

function renderSummary(s) {
  const box = $('#own-summary');
  box.replaceChildren();
  if (!s) return;
  const grid = el('div', 'summary');
  const metric = (title, value, note) => {
    const m = el('div', 'metric');
    m.append(el('span', null, title), el('strong', null, value));
    if (note) m.append(el('span', 'hint', note));
    return m;
  };
  grid.append(
    metric('스킵률 (잘된 릴스 / 나머지)', `${pct(s.outlierMedianSkipRate)} / ${pct(s.otherMedianSkipRate)}`, '첫 3초에 넘겨진 비율, 낮을수록 좋아요'),
    metric('저장률 (잘된 릴스 / 나머지)', `${pct(s.outlierMedianSaveRate)} / ${pct(s.otherMedianSaveRate)}`, '조회수 대비 저장'),
  );
  box.append(el('p', 'hint', '내 계정에서 조회수 중앙값의 2배 이상 나온 릴스를 "잘된 릴스"로 봐요.'), grid);
}

function renderAccount(a) {
  const node = $('#tpl-account').content.firstElementChild.cloneNode(true);
  node.querySelector('.name').textContent = a.mine ? `내 계정 @${a.username}` : `@${a.username}`;
  const badge = node.querySelector('.badge');
  if (a.lowSample) badge.textContent = '표본 적음'; else badge.remove();
  node.querySelector('.stats').textContent =
    `릴스 ${a.judgedCount}개 · 조회수 중앙값 ${fmt(a.medianViews)} · 잘된 릴스 ${a.outlierCount}개`;
  const types = node.querySelector('.hooktypes');
  (a.hookTypes || []).filter((t) => t.type !== 'OTHER' && t.outlierCount > 0).slice(0, 4).forEach((t) => {
    const chip = el('span', 'chip', `${HOOK_LABELS[t.type]} ${t.count}개 중 `);
    chip.append(el('b', null, `${t.outlierCount}개`));
    types.append(chip);
  });
  const posts = node.querySelector('.posts');
  a.cards.forEach((c) => {
    const p = $('#tpl-post').content.firstElementChild.cloneNode(true);
    if (c.permalink && c.permalink.startsWith('https://')) p.href = c.permalink;
    const img = p.querySelector('img');
    if (c.thumbnailUrl && c.thumbnailUrl.startsWith('https://')) img.src = c.thumbnailUrl; else img.remove();
    p.querySelector('.multiple').textContent = c.multiple != null ? `${c.multiple}배` : '';
    p.querySelector('.line').textContent = c.captionHook || c.captionFirstLine || '(첫 줄 없음)';
    posts.append(p);
  });
  if (!a.cards.length) posts.append(el('p', 'hint', '아직 잘된 릴스가 없어요.'));
  return node;
}

// 5. 추천
$('#topic-form').addEventListener('submit', async (ev) => {
  ev.preventDefault();
  const topic = $('#topic').value.trim();
  if (!topic) { setMsg($('#rec-msg'), '주제를 한 줄로 넣어 주세요.', true); return; }
  const btn = ev.target.querySelector('button');
  btn.disabled = true;
  setMsg($('#rec-msg'), '추천을 만드는 중이에요...');
  try {
    const rec = await api('POST', `/api/analyses/${currentAnalysisId}/recommendations`, { topic });
    setMsg($('#rec-msg'), '');
    renderRecommendation(rec);
  } catch (e) {
    setMsg($('#rec-msg'), e.message, true);
  } finally {
    btn.disabled = false;
  }
});

function renderRecommendation(rec) {
  const box = $('#recommendation');
  box.replaceChildren();
  const r = rec.result;
  const gen = rec.generator === 'LLM' ? `Claude로 만들었어요 (${rec.model})` : '규칙 기반 틀로 만들었어요';
  box.append(el('p', 'gen', gen));

  box.append(el('h3', null, '첫 줄 후보'));
  const list = el('ol', 'hooks');
  r.hooks.forEach((h, index) => {
    const li = $('#tpl-hook').content.firstElementChild.cloneNode(true);
    li.querySelector('.type').textContent = HOOK_LABELS[h.type] || h.type;
    li.querySelector('.text').textContent = h.text;
    li.querySelector('.why').textContent = h.rationale || '';
    li.querySelector('.copy').addEventListener('click', async (ev) => {
      try {
        await navigator.clipboard.writeText(h.text);
        ev.target.textContent = '복사됨';
      } catch (e) {
        ev.target.textContent = '복사 실패';
      }
    });
    li.querySelector('.adopt').addEventListener('click', async (ev) => {
      ev.target.disabled = true;
      try {
        await api('POST', `/api/recommendations/${rec.id}/adoptions`, { hookIndex: index });
        li.classList.add('adopted');
        ev.target.textContent = '채택함';
        await navigator.clipboard.writeText(h.text).catch(() => {});
        // 채택한 뒤 다음 화면: 올린 뒤 추적 기능 소개
        $('#step-more').classList.remove('hidden');
        $('#step-more').scrollIntoView({ behavior: 'smooth', block: 'start' });
      } catch (e) {
        ev.target.disabled = false;
        setMsg($('#rec-msg'), e.message, true);
      }
    });
    list.append(li);
  });
  box.append(list);

  box.append(el('h3', null, '썸네일 구성안'));
  const thumb = el('div', 'thumb');
  const desc = el('div', 'desc');
  desc.append(el('p', null, r.thumbnail.layout), el('p', 'why', r.thumbnail.rationale));
  thumb.append(el('div', 'preview', r.thumbnail.overlayText), desc);
  box.append(thumb);

  const notes = [...(r.notes || [])];
  (r.patterns || []).filter((p) => p.scope === 'SINGLE').forEach((p) => {
    notes.push(`${HOOK_LABELS[p.type]}은 @${p.references[0].username}에서만 잘된 ${SCOPE_LABELS.SINGLE}이에요.`);
  });
  if (notes.length) {
    const ul = el('ul', 'notes');
    notes.forEach((n) => ul.append(el('li', null, n)));
    box.append(ul);
  }
}

// 더 이어서 하기 (M5에서 게시 후 추적을 붙인다)
$('#more-btn').addEventListener('click', () => {
  setMsg($('#more-msg'), '준비 중이에요. 추천대로 올린 릴스의 초반 반응 추적은 곧 열려요.');
});

loadConcepts();
loadRefs();
const last = recall();
if (last) showReport(last).catch(() => { /* 지난 분석이 없거나 실패했으면 처음부터 */ });
