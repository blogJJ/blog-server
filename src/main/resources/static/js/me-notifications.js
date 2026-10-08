/* 알림 설정 (T114, SOC-04, 3.6). 끌 수 있는 알림 종류와 보관 기간(30일·7일)을 바꾼다. */
(function () {
  'use strict';

  var TAB_NAMES = { COMMENT: '댓글', LIKE: '좋아요', FOLLOW: '팔로우·구독', BLOG: '블로그', OPERATION: '운영' };

  var $ = function (id) {
    return document.getElementById(id);
  };

  function render(settings) {
    var groups = {};
    var order = [];
    settings.items.forEach(function (s) {
      if (!groups[s.tab]) {
        groups[s.tab] = [];
        order.push(s.tab);
      }
      groups[s.tab].push(s);
    });
    var box = $('groups');
    box.replaceChildren();
    order.forEach(function (tab) {
      var h = document.createElement('h2');
      h.className = 'me-section';
      h.textContent = TAB_NAMES[tab] || tab;
      box.appendChild(h);
      groups[tab].forEach(function (s) {
        var label = document.createElement('label');
        label.className = 'check';
        var input = document.createElement('input');
        input.type = 'checkbox';
        input.name = 'type';
        input.value = s.type;
        input.checked = s.enabled;
        var span = document.createElement('span');
        span.textContent = s.label;
        label.append(input, span);
        box.appendChild(label);
      });
    });
    document.querySelectorAll('input[name=keep]').forEach(function (r) {
      r.checked = Number(r.value) === settings.keepDays;
    });
    $('settings').hidden = false;
  }

  async function save(event) {
    event.preventDefault();
    var map = {};
    document.querySelectorAll('input[name=type]').forEach(function (c) {
      map[c.value] = c.checked;
    });
    var keep = document.querySelector('input[name=keep]:checked');
    try {
      render(await Api.put('/api/me/notification-settings', {
        keepDays: keep ? Number(keep.value) : null,
        settings: map
      }));
      Api.toast('저장했어요.');
    } catch (e) {
      Api.showError(e);
    }
  }

  Layout.onUser(async function (current) {
    if (!current) {
      window.location.href = '/login.html?next=' + encodeURIComponent('/me-notifications.html');
      return;
    }
    try {
      render(await Api.get('/api/me/notification-settings'));
    } catch (e) {
      Api.showError(e);
    }
  });
  $('settings-form').addEventListener('submit', save);
})();
