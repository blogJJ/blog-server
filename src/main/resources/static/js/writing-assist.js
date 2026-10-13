/*
 * 글쓰기 화면 도우미 (T167): 태그 추천과 맞춤법 검사.
 * - 둘 다 글쓴이가 버튼을 눌렀을 때만 서버에 묻는다. 받은 값은 후보이고, 글쓴이가 고른 것만 태그 칸과 본문에 넣는다.
 * - 태그 추천: 칩을 누르면 태그 칸에 더한다.
 * - 맞춤법 검사: 후보마다 [적용]/[무시]. 한꺼번에 고치는 버튼은 두지 않는다. 서버가 맞춤법 검사를 못 하면(키 없음) 버튼을 숨긴다.
 *
 * 사용: WritingAssist.init({ blogId, title(), content(), setContent(text), tagsInput })
 */
(function () {
  'use strict';

  var MAX_TAGS = 10;
  var TAG_TIMEOUT_MS = 30000;
  var SPELLING_TIMEOUT_MS = 65000;
  var CONTEXT = 18; // 후보 앞뒤로 보여 줄 글자 수

  var opts = null;
  var fixes = []; // { start, end, original, suggestion, reason, state }

  function $(id) {
    return document.getElementById(id);
  }

  function el(tag, className, text) {
    var node = document.createElement(tag);
    if (className) {
      node.className = className;
    }
    if (text !== undefined) {
      node.textContent = text;
    }
    return node;
  }

  function currentTags() {
    return BlogUi.splitTags(opts.tagsInput.value);
  }

  function sameTag(a, b) {
    var norm = function (t) {
      return t.replace(/^#+/, '').trim().replace(/\s+/g, '_').toLowerCase();
    };
    return norm(a) === norm(b);
  }

  // ---------- 태그 추천 ----------

  async function suggestTags() {
    var button = $('assist-tags');
    var box = $('assist-tag-box');
    var hint = $('assist-tag-hint');
    var list = $('assist-tag-chips');
    if (!opts.title().trim() && !opts.content().trim()) {
      Api.toast('제목이나 본문을 먼저 써 주세요.');
      return;
    }
    button.disabled = true;
    button.textContent = '찾는 중…';
    box.hidden = false;
    hint.textContent = '글에 어울리는 태그를 찾고 있어요.';
    list.textContent = '';
    try {
      var res = await Api.post(
        '/api/posts/assist/tags',
        { blogId: opts.blogId, title: opts.title(), content: opts.content(), tags: currentTags() },
        { timeoutMs: TAG_TIMEOUT_MS }
      );
      renderTags(res.tags || []);
    } catch (e) {
      box.hidden = true;
      Api.showError(e);
    } finally {
      button.disabled = false;
      button.textContent = '태그 추천';
    }
  }

  function renderTags(tags) {
    var box = $('assist-tag-box');
    var hint = $('assist-tag-hint');
    var list = $('assist-tag-chips');
    list.textContent = '';
    var fresh = tags.filter(function (t) {
      return !currentTags().some(function (c) {
        return sameTag(c, t);
      });
    });
    if (fresh.length === 0) {
      hint.textContent = '추천할 태그를 찾지 못했어요.';
      return;
    }
    hint.textContent = '추천 태그 · 눌러서 추가해요';
    fresh.forEach(function (tag) {
      var li = el('li', 'chip assist-chip');
      var add = el('button', 'chip__word', '+ ' + tag);
      add.type = 'button';
      add.setAttribute('aria-label', tag + ' 태그 추가');
      add.addEventListener('click', function () {
        addTag(tag, li);
      });
      li.appendChild(add);
      list.appendChild(li);
    });
    box.hidden = false;
  }

  function addTag(tag, li) {
    var tags = currentTags();
    if (tags.some(function (c) { return sameTag(c, tag); })) {
      li.remove();
      return;
    }
    if (tags.length >= MAX_TAGS) {
      Api.toast('태그는 10개까지 달 수 있어요.');
      return;
    }
    tags.push(tag);
    opts.tagsInput.value = tags.join(', ');
    opts.tagsInput.dispatchEvent(new Event('input', { bubbles: true }));
    li.remove();
    if (!$('assist-tag-chips').children.length) {
      $('assist-tag-box').hidden = true;
    }
  }

  // ---------- 맞춤법 검사 ----------

  async function checkSpelling() {
    var button = $('assist-spelling');
    var content = opts.content();
    if (!content.trim()) {
      Api.toast('본문을 먼저 써 주세요.');
      return;
    }
    button.disabled = true;
    button.textContent = '검사하는 중…';
    var panel = $('assist-spelling-panel');
    panel.hidden = false;
    panel.textContent = '';
    panel.appendChild(el('p', 'field__hint', '맞춤법과 띄어쓰기를 살펴보고 있어요. 글이 길면 조금 걸려요.'));
    try {
      var res = await Api.post(
        '/api/posts/assist/spelling',
        { blogId: opts.blogId, content: content },
        { timeoutMs: SPELLING_TIMEOUT_MS }
      );
      fixes = (res.fixes || []).map(function (f) {
        return {
          start: f.start,
          end: f.end,
          original: f.original,
          suggestion: f.suggestion,
          reason: f.reason || '',
          state: 'open'
        };
      });
      renderPanel(res.truncated, content);
    } catch (e) {
      panel.hidden = true;
      Api.showError(e);
    } finally {
      button.disabled = false;
      button.textContent = '맞춤법 검사';
    }
  }

  function renderPanel(truncated, text) {
    var panel = $('assist-spelling-panel');
    panel.textContent = '';
    var head = el('div', 'assist-panel__head');
    var open = fixes.filter(function (f) { return f.state === 'open'; }).length;
    head.appendChild(el('strong', null, fixes.length ? '맞춤법 검사 ' + open + '건' : '맞춤법 검사'));
    var close = el('button', 'button button--text', '닫기');
    close.type = 'button';
    close.addEventListener('click', function () {
      panel.hidden = true;
    });
    head.appendChild(close);
    panel.appendChild(head);
    if (truncated) {
      panel.appendChild(el('p', 'field__hint', '글이 길어 앞부분 5,000자까지만 살펴봤어요.'));
    }
    if (!fixes.length) {
      panel.appendChild(el('p', 'assist-panel__empty', '고칠 곳을 찾지 못했어요.'));
      return;
    }
    panel.appendChild(el('p', 'field__hint', '고칠지는 하나씩 골라 주세요. 적용하지 않은 곳은 그대로 둬요.'));
    var list = el('ul', 'assist-fixes');
    fixes.forEach(function (fix) {
      list.appendChild(renderFix(fix, text));
    });
    panel.appendChild(list);
  }

  function renderFix(fix, text) {
    var li = el('li', 'assist-fix');
    li.classList.toggle('is-done', fix.state !== 'open');
    var ctx = el('p', 'assist-fix__context');
    var before = text.slice(Math.max(0, fix.start - CONTEXT), fix.start);
    var after = text.slice(fix.end, fix.end + CONTEXT);
    ctx.appendChild(document.createTextNode((fix.start > CONTEXT ? '…' : '') + before));
    ctx.appendChild(el('mark', 'assist-fix__original', fix.original));
    ctx.appendChild(document.createTextNode(after + (fix.end + CONTEXT < text.length ? '…' : '')));
    li.appendChild(ctx);

    var change = el('p', 'assist-fix__change');
    change.appendChild(el('del', null, fix.original));
    change.appendChild(document.createTextNode(' → '));
    change.appendChild(el('ins', null, fix.suggestion));
    li.appendChild(change);
    if (fix.reason) {
      li.appendChild(el('p', 'field__hint assist-fix__reason', fix.reason));
    }

    var actions = el('div', 'assist-fix__actions');
    if (fix.state === 'open') {
      var apply = el('button', 'button button--primary', '적용');
      apply.type = 'button';
      apply.addEventListener('click', function () {
        applyFix(fix);
      });
      var skip = el('button', 'button', '무시');
      skip.type = 'button';
      skip.addEventListener('click', function () {
        fix.state = 'skipped';
        renderPanel(false, opts.content());
      });
      actions.appendChild(apply);
      actions.appendChild(skip);
    } else {
      actions.appendChild(el('span', 'field__hint', fix.state === 'applied' ? '적용했어요' : fix.state === 'missing' ? '글이 바뀌어 찾지 못했어요' : '무시했어요'));
    }
    li.appendChild(actions);
    return li;
  }

  // 검사한 뒤 글을 더 고쳤어도 같은 글자를 가장 가까운 곳에서 다시 찾아 바꾼다
  function locate(text, fix) {
    if (text.substring(fix.start, fix.end) === fix.original) {
      return fix.start;
    }
    var best = -1;
    for (var i = text.indexOf(fix.original); i >= 0; i = text.indexOf(fix.original, i + 1)) {
      if (best < 0 || Math.abs(i - fix.start) < Math.abs(best - fix.start)) {
        best = i;
      }
    }
    return best;
  }

  function applyFix(fix) {
    var text = opts.content();
    var at = locate(text, fix);
    if (at < 0) {
      fix.state = 'missing';
      renderPanel(false, text);
      return;
    }
    var next = text.slice(0, at) + fix.suggestion + text.slice(at + fix.original.length);
    var delta = fix.suggestion.length - fix.original.length;
    fixes.forEach(function (other) {
      if (other !== fix && other.state === 'open' && other.start >= at + fix.original.length) {
        other.start += delta;
        other.end += delta;
      }
    });
    fix.state = 'applied';
    fix.start = at;
    fix.end = at + fix.suggestion.length;
    opts.setContent(next);
    renderPanel(false, next);
  }

  async function init(options) {
    opts = options;
    var features = { tags: true, spelling: false };
    try {
      features = await Api.get('/api/posts/assist/config', { auto: true });
    } catch (e) {
      // 못 읽으면 태그 추천만 보인다(키가 없어도 단어 세기로 된다)
    }
    if (features.tags) {
      $('assist-tags').hidden = false;
      $('assist-tags').addEventListener('click', suggestTags);
    }
    if (features.spelling) {
      $('assist-spelling').hidden = false;
      $('assist-spelling').addEventListener('click', checkSpelling);
    }
  }

  window.WritingAssist = { init: init, locate: locate };
})();
