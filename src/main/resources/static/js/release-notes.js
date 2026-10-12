/*
 * 릴리즈 노트 화면 (T159, D-130). release-notes.html은 목록, release-note.html?v=버전은 버전 하나를 보여 준다.
 * 내용은 서버의 release-notes.json을 GET /api/release-notes로 받는다. 모두 textContent로 넣는다(SEC-06).
 */
(function () {
  'use strict';

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

  /** "2026-10-12" → "2026년 10월 12일" (시간대에 따라 하루 밀리지 않게 직접 자른다) */
  function formatDate(value) {
    var parts = String(value || '').split('-');
    if (parts.length !== 3) {
      return value || '';
    }
    return parts[0] + '년 ' + Number(parts[1]) + '월 ' + Number(parts[2]) + '일';
  }

  function detailUrl(version) {
    return '/release-note.html?v=' + encodeURIComponent(version);
  }

  async function showList() {
    var items;
    try {
      items = await Api.get('/api/release-notes');
    } catch (e) {
      $('releases-empty').textContent = e.message;
      $('releases-empty').hidden = false;
      return;
    }
    var list = $('releases');
    list.replaceChildren.apply(
      list,
      items.map(function (r) {
        var li = el('li', 'release-list__item');
        var a = el('a', 'release-list__link');
        a.href = detailUrl(r.version);
        var head = el('span', 'release-list__head');
        head.append(el('strong', 'release-list__version', r.version), ' — ' + r.title);
        var date = el('time', 'release-list__date', formatDate(r.date));
        date.dateTime = r.date;
        a.append(head, date);
        li.appendChild(a);
        return li;
      })
    );
    $('releases-empty').hidden = items.length > 0;
  }

  async function showDetail(version) {
    if (!version) {
      $('release-empty').hidden = false;
      return;
    }
    var r;
    try {
      r = await Api.get('/api/release-notes/' + encodeURIComponent(version));
    } catch (e) {
      $('release-empty').textContent = e.status === 404 ? '찾는 버전이 없어요.' : e.message;
      $('release-empty').hidden = false;
      return;
    }
    document.title = r.version + ' 릴리즈 노트 - 블로그';
    var title = $('release-title');
    title.replaceChildren(el('span', 'release__version', r.version), ' — ' + r.title);
    $('release-date').textContent = formatDate(r.date);
    var changes = $('release-changes');
    changes.replaceChildren.apply(
      changes,
      r.changes.map(function (text) {
        return el('li', '', text);
      })
    );
    $('release').hidden = false;
  }

  function init() {
    if ($('releases')) {
      showList();
    } else {
      showDetail(new URLSearchParams(window.location.search).get('v'));
    }
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
