/*
 * 메인 공지사항 화면 (BRD-10, T125). 목록을 보여 주고, ?id=번호로 오면(공지 알림) 그 공지를 위에 펼친다.
 * 본문 HTML은 서버가 글과 같은 방식으로 걸러서 준다.
 */
(function () {
  'use strict';

  var ui = window.BlogUi;
  var el = ui.el;

  function $(id) {
    return document.getElementById(id);
  }

  async function show(id) {
    try {
      var n = await Api.get('/api/notices/' + id);
      $('notice-title').textContent = n.title;
      $('notice-date').textContent = ui.formatDate(n.createdAt);
      $('notice-body').innerHTML = n.html; // 서버가 걸러 낸 HTML (ContentRenderer)
      $('notice').hidden = false;
      history.replaceState(null, '', '/notices.html?id=' + id);
      window.scrollTo(0, 0);
    } catch (e) {
      $('notice').hidden = true;
    }
  }

  async function load(page) {
    var data;
    try {
      data = await Api.get('/api/notices?page=' + page);
    } catch (e) {
      $('notices-empty').hidden = false;
      $('notices-empty').textContent = e.message;
      return;
    }
    var list = $('notices');
    list.replaceChildren.apply(
      list,
      data.items.map(function (n) {
        var li = el('li', 'admin-list__item');
        var a = el('a', '', n.title);
        a.href = '/notices.html?id=' + n.id;
        a.addEventListener('click', function (event) {
          event.preventDefault();
          show(n.id);
        });
        li.append(a, el('p', 'admin-list__sub', ui.formatDate(n.createdAt)));
        return li;
      })
    );
    $('notices-empty').hidden = data.items.length > 0;
    ui.pager($('notices-pager'), data.page, data.totalPages, load);
  }

  function init() {
    var id = new URLSearchParams(window.location.search).get('id');
    if (id && /^\d+$/.test(id)) {
      show(id);
    }
    load(1);
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
