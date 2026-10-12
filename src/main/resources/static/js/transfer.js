/*
 * 받은 블로그장 위임 요청 (T093, BLG-08, D-16, D-31). 알림의 "위임 요청"을 누르면 이 화면으로 온다.
 * 수락·거절은 받는 사람만 할 수 있고, 규칙(폐쇄 예정이면 안 됨, 블로그 개수 제한 등)은 서버가 확인한다.
 */
(function () {
  'use strict';

  var ui = window.BlogUi;
  var el = ui.el;

  function $(id) {
    return document.getElementById(id);
  }

  function dateTime(value) {
    var d = new Date(value);
    return ui.formatDate(value) + ' ' +
      String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0');
  }

  async function load() {
    try {
      var rows = await Api.get('/api/me/transfers');
      var list = $('transfers');
      list.replaceChildren.apply(list, rows.map(row));
      $('transfers-empty').hidden = rows.length > 0;
    } catch (e) {
      Api.showError(e);
    }
  }

  function row(t) {
    var li = el('li', 'admin-list__item');
    var head = el('div', 'admin-list__head');
    var link = el('a', '', t.blogName);
    if (t.blogSlug) {
      link.href = ui.blogUrl(t.blogSlug);
    }
    head.appendChild(link);
    li.appendChild(head);
    li.appendChild(el('p', 'admin-list__sub',
      t.fromNickname + '님이 보낸 요청 · ' + dateTime(t.expiresAt) + '까지 응답할 수 있어요'));
    var actions = el('div', 'admin-list__actions');
    var accept = el('button', 'button button--primary', '수락');
    var reject = el('button', 'button', '거절');
    accept.type = 'button';
    reject.type = 'button';
    accept.addEventListener('click', function () {
      respond(t, 'accept');
    });
    reject.addEventListener('click', function () {
      respond(t, 'reject');
    });
    actions.appendChild(accept);
    actions.appendChild(reject);
    li.appendChild(actions);
    return li;
  }

  async function respond(t, action) {
    var question = action === 'accept'
      ? "'" + t.blogName + "'의 블로그장이 될까요? 수락하면 블로그 관리와 위임·폐쇄를 맡게 돼요."
      : "'" + t.blogName + "' 위임 요청을 거절할까요?";
    if (!window.confirm(question)) {
      return;
    }
    try {
      await Api.post('/api/transfers/' + t.id + '/' + action);
      if (action === 'accept' && t.blogSlug) {
        Api.toast('블로그장이 되었어요.');
        window.location.href = ui.blogUrl(t.blogSlug) + '/admin';
        return;
      }
      Api.toast(action === 'accept' ? '블로그장이 되었어요.' : '거절했어요.');
    } catch (e) {
      Api.showError(e);
    }
    load();
  }

  Layout.onUser(function (current) {
    if (!current) {
      window.location.href = '/login.html?next=' + encodeURIComponent('/transfer.html');
      return;
    }
    load();
  });
})();
