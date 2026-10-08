/* 내 블로그 목록 (T055, BLG-06). 만든 블로그·참여한 블로그·승인 대기로 나눠 보여 준다. */
(function () {
  'use strict';

  var ui = window.BlogUi;

  function fill(id, cards) {
    var box = document.getElementById(id);
    box.replaceChildren.apply(box, cards);
    var empty = document.getElementById(id + '-empty');
    if (empty) {
      empty.hidden = cards.length > 0;
    }
  }

  async function load() {
    try {
      var data = await Api.get('/api/me/blogs');
      fill('owned', data.owned.map(function (b) {
        return ui.card(b);
      }));
      fill('joined', data.joined.map(function (j) {
        return ui.card(j.blog, j.role === 'MANAGER' ? '부블로그장' : null);
      }));
      fill('pending', data.pending.map(function (b) {
        return ui.card(b, '승인 대기');
      }));
      document.getElementById('pending-section').hidden = data.pending.length === 0;
    } catch (e) {
      Api.showError(e);
    }
  }

  Layout.onUser(function (current) {
    if (!current) {
      window.location.href = '/login.html?next=' + encodeURIComponent('/my-blogs.html');
      return;
    }
    load();
  });
})();
