/*
 * 통합 검색 (T110, BRD-08).
 * - 분류바: 글 제목(기본)·블로그(이름·소개)·태그·닉네임. 정렬: 관련도(기본)·최신·인기.
 * - 검색어는 2~20자, 공백·특수문자만이면 보내기 전에 막는다. 서버도 같은 규칙으로 다시 막는다.
 * - 로그인하면 최근 검색어(최대 10개)를 보여 주고 하나씩·전부 지울 수 있다. 자동완성은 없다.
 * 주소의 ?q=, ?type=, ?sort=, ?page= 로 상태를 둔다.
 */
(function () {
  'use strict';

  var ui = window.BlogUi;
  var el = ui.el;
  var TYPES = ['post', 'blog', 'tag', 'nickname'];
  var SORTS = ['relevance', 'latest', 'popular'];

  function $(id) {
    return document.getElementById(id);
  }

  function state() {
    var p = new URLSearchParams(window.location.search);
    return {
      q: (p.get('q') || '').trim(),
      type: TYPES.indexOf(p.get('type')) >= 0 ? p.get('type') : 'post',
      sort: SORTS.indexOf(p.get('sort')) >= 0 ? p.get('sort') : 'relevance',
      page: Math.max(1, parseInt(p.get('page'), 10) || 1)
    };
  }

  /** 서버(BlogQueryService.validateQuery)와 같은 규칙. 맞지 않으면 안내 문구 */
  function invalid(q) {
    if (q.length < 2 || q.length > 20 || !/[\p{L}\p{N}]/u.test(q)) {
      return '검색어는 2~20자로, 글자나 숫자를 넣어 입력해 주세요.';
    }
    return null;
  }

  function go(next) {
    var p = new URLSearchParams();
    p.set('q', next.q);
    if (next.type !== 'post') {
      p.set('type', next.type);
    }
    if (next.sort !== 'relevance') {
      p.set('sort', next.sort);
    }
    if (next.page > 1) {
      p.set('page', String(next.page));
    }
    history.pushState(null, '', '?' + p.toString());
    load();
  }

  function clearResults() {
    $('posts').replaceChildren();
    $('blogs').replaceChildren();
    $('pager').replaceChildren();
    $('user-hit').hidden = true;
    $('caption').textContent = '';
    $('empty').hidden = true;
  }

  function renderUser(user) {
    var box = $('user-hit');
    box.replaceChildren();
    box.hidden = !user;
    if (!user) {
      return;
    }
    var a = el('a', 'user-row');
    a.href = ui.profileUrl(user.id);
    if (user.profileImage) {
      var img = el('img', 'user-row__photo');
      img.src = user.profileImage;
      img.alt = '';
      a.appendChild(img);
    } else {
      a.appendChild(el('span', 'user-row__photo'));
    }
    var who = el('span', 'user-row__who');
    who.appendChild(el('strong', '', user.nickname));
    if (user.bio) {
      who.appendChild(el('span', 'user-row__bio', user.bio));
    }
    a.appendChild(who);
    box.appendChild(a);
  }

  async function load() {
    var s = state();
    $('q').value = s.q;
    document.querySelectorAll('#types .tabs__item').forEach(function (b) {
      b.classList.toggle('is-current', b.dataset.type === s.type);
    });
    document.querySelectorAll('#sorts [data-sort]').forEach(function (b) {
      b.classList.toggle('is-current', b.dataset.sort === s.sort);
    });
    clearResults();
    if (!s.q) {
      return;
    }
    var message = invalid(s.q);
    if (message) {
      $('empty').hidden = false;
      $('empty').textContent = message;
      return;
    }
    var url =
      '/api/search?q=' + encodeURIComponent(s.q) + '&type=' + s.type + '&sort=' + s.sort + '&page=' + s.page;
    try {
      var data = await Api.get(url);
      var blogs = data.type === 'blog';
      $('blogs').hidden = !blogs;
      $('posts').hidden = blogs;
      if (blogs) {
        $('blogs').replaceChildren.apply($('blogs'), data.items.map(function (b) {
          return ui.card(b);
        }));
      } else {
        $('posts').replaceChildren.apply($('posts'), data.items.map(ui.postCard));
      }
      renderUser(data.user);
      $('caption').textContent = data.message ? '' : '"' + data.q + '" 검색 결과 ' + data.totalElements + '개';
      $('empty').hidden = !data.message;
      $('empty').textContent = data.message || '';
      ui.pager($('pager'), data.page, data.totalPages, function (page) {
        go({ q: s.q, type: s.type, sort: s.sort, page: page });
        window.scrollTo(0, 0);
      });
      loadRecent();
    } catch (e) {
      Api.showError(e);
    }
  }

  // ---------- 최근 검색어 (로그인한 회원만) ----------

  async function loadRecent() {
    if (!Layout.user) {
      $('recent').hidden = true;
      return;
    }
    try {
      var rows = await Api.get('/api/me/recent-searches');
      $('recent').hidden = rows.length === 0;
      $('recent-list').replaceChildren.apply($('recent-list'), rows.map(chip));
    } catch (e) {
      $('recent').hidden = true;
    }
  }

  function chip(row) {
    var li = el('li', 'chip');
    var word = el('button', 'chip__word', row.keyword);
    word.type = 'button';
    word.addEventListener('click', function () {
      var s = state();
      go({ q: row.keyword, type: s.type, sort: s.sort, page: 1 });
    });
    var remove = el('button', 'chip__remove', '×');
    remove.type = 'button';
    remove.setAttribute('aria-label', row.keyword + ' 지우기');
    remove.addEventListener('click', async function () {
      try {
        await Api.delete('/api/me/recent-searches/' + row.id);
        loadRecent();
      } catch (e) {
        Api.showError(e);
      }
    });
    li.append(word, remove);
    return li;
  }

  async function clearRecent() {
    if (!window.confirm('최근 검색어를 모두 지울까요?')) {
      return;
    }
    try {
      await Api.delete('/api/me/recent-searches');
      loadRecent();
    } catch (e) {
      Api.showError(e);
    }
  }

  document.addEventListener('DOMContentLoaded', function () {
    $('search-form').addEventListener('submit', function (event) {
      event.preventDefault();
      var q = $('q').value.trim();
      var message = invalid(q);
      if (message) {
        Api.toast(message);
        return;
      }
      var s = state();
      go({ q: q, type: s.type, sort: s.sort, page: 1 });
    });
    document.querySelectorAll('#types .tabs__item').forEach(function (b) {
      b.addEventListener('click', function () {
        var s = state();
        go({ q: s.q, type: b.dataset.type, sort: s.sort, page: 1 });
      });
    });
    document.querySelectorAll('#sorts [data-sort]').forEach(function (b) {
      b.addEventListener('click', function () {
        var s = state();
        go({ q: s.q, type: s.type, sort: b.dataset.sort, page: 1 });
      });
    });
    $('recent-clear').addEventListener('click', clearRecent);
    window.addEventListener('popstate', load);
    load();
  });

  Layout.onUser(function () {
    loadRecent();
  });
})();
