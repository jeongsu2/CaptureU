// CaptureU 최소 화면 (#15). 프레임워크 없이 API만 부른다. 화면에 넣는 값은 모두 textContent로 넣는다.
const HOOK_LABELS = {
  QUESTION: '질문형', TARGET: '대상 지목형', LIST: '목록형', HOW_TO: '방법·꿀팁형',
  STORY: '이야기·후기형', ANNOUNCE: '공지·예고형', OTHER: '기타',
};
const SCOPE_LABELS = { COMMON: '공통 패턴', OWN: '내 계정', SINGLE: '한 계정 스타일' };
const LAST_ANALYSIS = 'captureu.lastAnalysisId';
const LAST_RECOMMENDATION = 'captureu.lastRecommendationId';

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

function remember(key, id) {
  try { localStorage.setItem(key, String(id)); } catch (e) { /* 저장이 막혀 있어도 화면은 동작한다 */ }
}
function recall(key) {
  try { return localStorage.getItem(key); } catch (e) { return null; }
}

// 1. 컨셉
const concept = { main: null, sub: null };
let conceptSaving = false;

function renderConceptChips(options) {
  const draw = (boxSel, key) => {
    const box = $(boxSel);
    box.replaceChildren();
    options.forEach((o) => {
      const b = el('button', null, o.label);
      b.type = 'button';
      b.disabled = conceptSaving; // 저장 중에는 막아 요청 순서가 뒤바뀌지 않게
      b.setAttribute('aria-pressed', String(concept[key] === o.code));
      b.addEventListener('click', () => chooseConcept(key, o.code, options));
      box.append(b);
    });
  };
  draw('#main-concepts', 'main');
  draw('#sub-concepts', 'sub');
}

async function chooseConcept(key, code, options) {
  if (conceptSaving) return;
  if (key === 'sub' && code === concept.main) return; // 메인과 같은 컨셉은 2순위로 못 고른다
  if (key === 'main') {
    concept.main = code;
    if (concept.sub === code) concept.sub = null;
  } else {
    concept.sub = concept.sub === code ? null : code; // 다시 누르면 해제
  }
  if (!concept.main) {
    renderConceptChips(options);
    setMsg($('#concept-msg'), '메인 컨셉을 먼저 골라 주세요.');
    return;
  }
  conceptSaving = true;
  renderConceptChips(options);
  try {
    Object.assign(concept, await api('PUT', '/api/profile', { main: concept.main, sub: concept.sub }));
    setMsg($('#concept-msg'), '저장했어요.');
  } catch (e) {
    setMsg($('#concept-msg'), e.message, true);
    await reloadSavedConcept(); // 실패하면 서버에 저장된 값으로 되돌린다
  } finally {
    conceptSaving = false;
    renderConceptChips(options);
  }
}

/** 저장된 컨셉을 읽는다. 204는 아직 고르지 않음, 그 밖의 실패는 오류로 보여 준다. */
async function reloadSavedConcept() {
  const res = await fetch('/api/profile');
  if (res.status === 204) {
    Object.assign(concept, { main: null, sub: null });
    return;
  }
  if (!res.ok) throw new Error(`저장된 컨셉을 불러오지 못했어요 (${res.status})`);
  const saved = await res.json();
  Object.assign(concept, { main: saved.main, sub: saved.sub || null });
}

async function loadConcepts() {
  try {
    const options = await api('GET', '/api/profile/concepts');
    await reloadSavedConcept();
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
    remember(LAST_ANALYSIS, a.id);
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
  setMsg($('#thumbs-msg'), '');
  renderSummary(report.ownSummary);
  const box = $('#accounts');
  box.replaceChildren(renderAccount(report.own), ...report.references.map(renderAccount));
  if (report.fewReferences) {
    box.prepend(el('p', 'hint', '참고 계정이 3개보다 적어 한 계정 스타일에 치우칠 수 있어요.'));
  }
  ['#step-report', '#step-recommend'].forEach((s) => $(s).classList.remove('hidden'));
  await Promise.all([loadEffects(id), loadThumbnails(id)]);
}

// 썸네일 특징 (#11)
async function loadThumbnails(analysisId) {
  const list = $('#thumbs-signals');
  list.replaceChildren();
  try {
    const c = await api('GET', `/api/analyses/${analysisId}/thumbnails`);
    if (String(analysisId) !== String(currentAnalysisId)) return; // 그새 다른 리포트로 바뀜
    const tagged = c.accounts.reduce((n, a) => n + a.outlierTagged + a.otherTagged, 0);
    if (!tagged) return;
    if (!c.signals.length) {
      list.append(el('li', null, `썸네일 ${tagged}개를 분석했지만 잘된 썸네일만의 뚜렷한 특징은 아직 없어요.`));
      return;
    }
    c.signals.forEach((s) => list.append(el('li', null, s.evidence)));
  } catch (e) {
    setMsg($('#thumbs-msg'), e.message, true);
  }
}

$('#thumbs-btn').addEventListener('click', async () => {
  const btn = $('#thumbs-btn');
  const analysisId = currentAnalysisId; // 누른 시점의 리포트
  btn.disabled = true;
  setMsg($('#thumbs-msg'), '썸네일을 분석하는 중이에요. 처음엔 몇 분 걸릴 수 있어요...');
  try {
    const r = await api('POST', `/api/analyses/${analysisId}/thumbnails`);
    if (String(analysisId) !== String(currentAnalysisId)) return;
    if (r.status === 'SKIPPED_NO_API_KEY') {
      setMsg($('#thumbs-msg'), '썸네일 분석에는 Claude API 키(ANTHROPIC_API_KEY)가 필요해요.', true);
      return;
    }
    const cost = Number(r.costUsd || 0).toFixed(4);
    setMsg($('#thumbs-msg'), `새로 ${r.tagged}개, 같은 이미지 재사용 ${r.reused}개, 이미 분석 ${r.alreadyTagged}개`
      + (r.expired ? `, 주소 만료 ${r.expired}개(위에서 분석을 새로 실행하면 새 주소로 받아요)` : '')
      + (r.noThumbnail ? `, 썸네일 없음 ${r.noThumbnail}개` : '')
      + (r.failed ? `, 실패 ${r.failed}개` : '') + ` · 비용 $${cost}`
      + (r.unpriced ? ` (가격표에 없는 모델 ${r.unpriced}건은 빠짐)` : ''));
    await loadThumbnails(analysisId);
  } catch (e) {
    setMsg($('#thumbs-msg'), e.message, true);
  } finally {
    btn.disabled = false;
  }
});

// 추천 효과 (#14): 채택한 문구로 올린 게시물을 찾아 내 중앙값과 비교
const STATUS_LABELS = { AUTO: '자동 연결', CONFIRMED: '내가 확인', ASK: '확인 필요', NOT_FOUND: '아직 안 올림' };
const signed = (n, unit) => (n == null ? '-' : `${n > 0 ? '+' : ''}${n}${unit}`);

async function loadEffects(analysisId) {
  const section = $('#step-effect');
  let report;
  try {
    report = await api('GET', `/api/analyses/${analysisId}/effects`);
  } catch (e) {
    section.classList.remove('hidden');
    $('#effect').replaceChildren(el('p', 'msg error', `추천 효과를 불러오지 못했어요: ${e.message}`));
    return;
  }
  if (!report.adoptions.length) { section.classList.add('hidden'); return; }
  section.classList.remove('hidden');
  const box = $('#effect');
  box.replaceChildren();
  const s = report.summary;
  box.append(el('p', 'effect-head', s.headline || `채택 ${s.adoptedCount}개 중 올린 게시물 ${s.matchedCount}개를 찾았어요.`));
  report.adoptions.forEach((a) => box.append(renderAdoption(a, analysisId)));
  const ul = el('ul', 'notes');
  s.notes.forEach((n) => ul.append(el('li', null, n)));
  box.append(ul);
}

function postThumb(url) {
  const img = el('img');
  img.alt = '';
  img.referrerPolicy = 'no-referrer';
  if (url && url.startsWith('https://')) img.src = url;
  return img;
}

function renderAdoption(a, analysisId) {
  const node = el('div', 'adoption');
  const head = el('p');
  head.append(el('span', 'quote', `“${a.hookText}”`), el('span', 'state', STATUS_LABELS[a.status] || a.status));
  node.append(head);
  const error = el('p', 'msg error');
  const decide = async (mediaId, used) => {
    error.textContent = '';
    try {
      await api('POST', `/api/adoptions/${a.adoptionId}/match`, { analysisId: Number(analysisId), mediaId, used });
    } catch (e) {
      error.textContent = e.message;
      return;
    }
    await loadEffects(analysisId);
  };
  if (a.post) {
    const p = a.post;
    const row = el('div', 'matched');
    const info = el('div', 'info');
    if (p.excludedReason === 'NO_METRICS') {
      info.append(el('p', 'hint', '이번 분석에서 이 게시물의 지표를 받지 못했어요.'));
    } else {
      info.append(el('p', null, p.captionFirstLine || ''));
      const reasons = {
        YOUNG: `게시 ${p.ageHours}시간 (7일 전이라 참고용)`,
        NOT_REELS: '릴스가 아니라 비교에서 뺐어요',
      };
      info.append(el('p', 'hint', reasons[p.excludedReason] || '게시 7일 지남 · 평균에 넣었어요'));
      const skip = el('p', null, `스킵률 ${p.skipRate ?? '-'}% · 내 중앙값 대비 `);
      skip.append(el('span', p.skipRateDiff == null ? null : p.skipRateDiff <= 0 ? 'better' : 'worse', signed(p.skipRateDiff, '%p')));
      info.append(skip);
      info.append(el('p', null, `조회수 ${fmt(p.views)} (중앙값의 ${p.viewsMultiple ?? '-'}배) · 저장률 ${signed(p.saveRateDiff, '%p')}`));
    }
    const actions = el('div', 'actions');
    const no = el('button', 'secondary', '이 게시물 아니에요');
    no.addEventListener('click', () => decide(p.mediaId, false));
    actions.append(no);
    info.append(actions);
    row.append(postThumb(p.thumbnailUrl), info);
    node.append(row);
  } else if (a.candidate) {
    const c = a.candidate;
    const row = el('div', 'matched');
    const info = el('div', 'info');
    info.append(el('p', null, '이 게시물에 썼나요?'), el('p', 'hint', c.captionFirstLine || ''));
    const actions = el('div', 'actions');
    const yes = el('button', null, '네, 썼어요');
    yes.addEventListener('click', () => decide(c.mediaId, true));
    const no = el('button', 'secondary', '아니에요');
    no.addEventListener('click', () => decide(c.mediaId, false));
    actions.append(yes, no);
    info.append(actions);
    row.append(postThumb(c.thumbnailUrl), info);
    node.append(row);
  }
  node.append(error);
  return node;
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
  if (topic.length > 40) { setMsg($('#rec-msg'), '주제는 40자까지 넣을 수 있어요.', true); return; }
  const btn = ev.target.querySelector('button');
  btn.disabled = true;
  setMsg($('#rec-msg'), '추천을 만드는 중이에요...');
  try {
    const rec = await api('POST', `/api/analyses/${currentAnalysisId}/recommendations`, { topic });
    setMsg($('#rec-msg'), '');
    remember(LAST_RECOMMENDATION, rec.id);
    renderRecommendation(rec);
  } catch (e) {
    setMsg($('#rec-msg'), e.message, true);
  } finally {
    btn.disabled = false;
  }
});

function showAfterAdoption(scroll) {
  $('#step-more').classList.remove('hidden');
  loadTracking();
  if (scroll) $('#step-more').scrollIntoView({ behavior: 'smooth', block: 'start' });
}

function renderRecommendation(rec) {
  $('#topic').value = rec.topic;
  const adopted = new Set(rec.adoptedHookIndexes || []);
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
    const adoptBtn = li.querySelector('.adopt');
    if (adopted.has(index)) {
      li.classList.add('adopted');
      adoptBtn.textContent = '채택함';
      adoptBtn.disabled = true;
    }
    adoptBtn.addEventListener('click', async (ev) => {
      ev.target.disabled = true;
      // 클릭 직후에 복사해야 브라우저(특히 Safari)가 클립보드 쓰기를 허용한다
      const copied = navigator.clipboard.writeText(h.text).then(() => true, () => false);
      try {
        await api('POST', `/api/recommendations/${rec.id}/adoptions`, { hookIndex: index });
        li.classList.add('adopted');
        ev.target.textContent = (await copied) ? '채택함 · 복사됨' : '채택함';
        // 채택한 뒤 다음 화면: 올린 뒤 추적 기능 소개
        showAfterAdoption(true);
      } catch (e) {
        ev.target.disabled = false;
        setMsg($('#rec-msg'), e.message, true);
      }
    });
    list.append(li);
  });
  box.append(list);
  if (adopted.size) showAfterAdoption(false);

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

// 더 이어서 하기 (#5): 추적을 켜면 새 릴스의 게시 후 1·3·6·12·24시간… 조회수를 쌓아 지난 릴스와 같은 나이끼리 비교한다
function trackedPostText(p) {
  const title = p.captionFirstLine || '(첫 줄 없음)';
  const now = `게시 ${p.checkpointHours}시간 조회수 ${p.views ?? '-'}회`;
  if (p.multiple == null) {
    return `${title} · ${now} (같은 나이의 지난 릴스가 ${p.baselineCount}개라 아직 비교하지 않아요. 3개부터 비교해요)`;
  }
  return `${title} · ${now}, 평소(지난 릴스 ${p.baselineCount}개 중앙값 ${Math.round(p.baselineViews)}회)의 ${p.multiple}배`
    + (p.surge ? ' · 초반 급상승' : '');
}

function renderTracking(t) {
  $('#more-btn').classList.toggle('hidden', t.enabled);
  $('#more-stop').classList.toggle('hidden', !t.enabled);
  const list = $('#tracked-posts');
  list.replaceChildren();
  t.posts.forEach((p) => list.append(el('li', null, trackedPostText(p))));
  if (!t.enabled) {
    setMsg($('#more-msg'), t.posts.length ? '추적을 껐어요. 지금까지 잰 값은 남아 있어요.' : '');
  } else if (!t.posts.length) {
    setMsg($('#more-msg'), '추적 중이에요. 새 릴스를 올리면 게시 1시간 뒤부터 10분 간격으로 확인해 반응을 재요.');
  } else {
    setMsg($('#more-msg'), '추적 중이에요. 최근 릴스를 같은 나이의 지난 릴스 조회수와 비교해요.');
  }
}

async function loadTracking() {
  try {
    renderTracking(await api('GET', '/api/tracking'));
  } catch (e) {
    // 인스타 계정이 연결되지 않았으면 추적 상태를 보여 주지 않는다
  }
}

async function changeTracking(enabled) {
  const buttons = [$('#more-btn'), $('#more-stop')];
  buttons.forEach((b) => { b.disabled = true; });
  try {
    renderTracking(await api('PUT', '/api/tracking', { enabled }));
  } catch (e) {
    setMsg($('#more-msg'), e.message, true);
  } finally {
    buttons.forEach((b) => { b.disabled = false; });
  }
}

$('#more-btn').addEventListener('click', () => changeTracking(true));
$('#more-stop').addEventListener('click', () => changeTracking(false));

loadConcepts();
loadRefs();
// 새로고침해도 지난 분석 리포트와 마지막 추천(채택 표시 포함)을 다시 보여 준다. 추천을 새로 만들지 않는다
const lastAnalysis = recall(LAST_ANALYSIS);
if (lastAnalysis) {
  showReport(lastAnalysis)
    .then(async () => {
      const lastRec = recall(LAST_RECOMMENDATION);
      if (!lastRec) return;
      const rec = await api('GET', `/api/recommendations/${lastRec}`);
      if (String(rec.analysisId) === String(lastAnalysis)) renderRecommendation(rec);
    })
    .catch(() => { /* 지난 분석이나 추천이 없으면 처음부터 */ });
}
