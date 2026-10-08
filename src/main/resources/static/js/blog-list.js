/*
 * 메인 블로그 목록·검색 (T054, BLG-02, BLG-03).
 * 주소의 ?sort=, ?q=, ?page= 로 상태를 둬서 뒤로 가기와 새로 고침이 그대로 동작한다.
 */
(function () {
  'use strict';

  var ui = window.BlogUi;

  function state() {
    var p = new URLSearchParams(window.location.search);
    return {
      sort: p.get('sort') === 'popular' ? 'popular' : 'latest',
      q: (p.get('q') || '').trim(),
      page: Math.max(1, parseInt(p.get('page'), 10) || 1)
    };
  }

  function go(next) {
    var p = new URLSearchParams();
    if (next.q) {
      p.set('q', next.q);
    } else if (next.sort !== 'latest') {
      p.set('sort', next.sort);
    }
    if (next.page > 1) {
      p.set('page', String(next.page));
    }
    var qs = p.toString();
    history.pushState(null, '', qs ? '?' + qs : '/');
    load();
  }

  async function load() {
    var s = state();
    var list = document.getElementById('list');
    var empty = document.getElementById('empty');
    var caption = document.getElementById('caption');
    document.getElementById('q').value = s.q;
    document.getElementById('tabs').hidden = !!s.q;
    document.querySelectorAll('#tabs .tabs__item').forEach(function (b) {
      b.classList.toggle('is-current', b.dataset.sort === s.sort);
    });

    var url = s.q
      ? '/api/blogs/search?q=' + encodeURIComponent(s.q) + '&page=' + s.page
      : '/api/blogs?sort=' + s.sort + '&page=' + s.page;
    try {
      var data = await Api.get(url);
      list.replaceChildren.apply(list, data.items.map(function (b) {
        return ui.card(b);
      }));
      caption.textContent = s.q ? '"' + s.q + '" 검색 결과 ' + data.totalElements + '개' : '';
      empty.hidden = data.items.length > 0;
      empty.textContent = s.q ? '검색 결과가 없습니다.' : '아직 블로그가 없어요. 첫 블로그를 만들어 보세요.';
      ui.pager(document.getElementById('pager'), data.page, data.totalPages, function (page) {
        go({ sort: s.sort, q: s.q, page: page });
        window.scrollTo(0, 0);
      });
    } catch (e) {
      list.replaceChildren();
      Api.showError(e);
    }
  }

  document.addEventListener('DOMContentLoaded', function () {
    document.getElementById('search-form').addEventListener('submit', function (event) {
      event.preventDefault();
      var q = document.getElementById('q').value.trim();
      if (q && q.length < 2) {
        Api.toast('검색어는 2자 이상 입력해 주세요.');
        return;
      }
      go({ sort: 'latest', q: q, page: 1 });
    });
    document.querySelectorAll('#tabs .tabs__item').forEach(function (b) {
      b.addEventListener('click', function () {
        go({ sort: b.dataset.sort, q: '', page: 1 });
      });
    });
    window.addEventListener('popstate', load);
    load();
  });

  document.addEventListener('layout:user', function (event) {
    var user = event.detail;
    // 메인 관리자는 블로그를 만들지 않는다 (D-90)
    document.getElementById('new-blog').hidden = !user || user.role === 'ADMIN';
  });
})();
