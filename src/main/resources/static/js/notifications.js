/*
 * 종 아이콘 알림 사이드바 (T114, SOC-04, D-39, D-56, D-72). 로그인하면 layout.js가 이 파일을 불러온다.
 *
 * - 30초마다 안 읽은 수를 가져와 종 아이콘에 표시한다. 자동 요청이라 X-Auto-Request를 붙여 로그인 30분 연장으로 세지 않는다.
 *   창이 안 보일 때는 건너뛴다.
 * - 종을 누르면 오른쪽 사이드바가 열린다. 탭: 전체·댓글·좋아요·팔로우·구독·블로그·운영. 읽은 알림은 강조 색이 없다.
 * - 알림을 누르면 읽음으로 바꾸고 대상 화면으로 간다.
 */
(function () {
  'use strict';

  var POLL_MS = 30000;
  var TABS = [
    ['ALL', '전체'],
    ['COMMENT', '댓글'],
    ['LIKE', '좋아요'],
    ['FOLLOW', '팔로우·구독'],
    ['BLOG', '블로그'],
    ['OPERATION', '운영']
  ];
  var state = { tab: 'ALL', page: 1, totalPages: 0, open: false };
  var ui = {};

  function el(tag, className, text) {
    var node = document.createElement(tag);
    if (className) {
      node.className = className;
    }
    if (text !== undefined && text !== null) {
      node.textContent = text;
    }
    return node;
  }

  function button(className, text, onClick) {
    var b = el('button', className, text);
    b.type = 'button';
    b.addEventListener('click', onClick);
    return b;
  }

  function timeAgo(value) {
    var diff = (Date.now() - new Date(value).getTime()) / 1000;
    if (diff < 60) {
      return '방금';
    }
    if (diff < 3600) {
      return Math.floor(diff / 60) + '분 전';
    }
    if (diff < 86400) {
      return Math.floor(diff / 3600) + '시간 전';
    }
    if (diff < 86400 * 7) {
      return Math.floor(diff / 86400) + '일 전';
    }
    var d = new Date(value);
    return d.getFullYear() + '.' + String(d.getMonth() + 1).padStart(2, '0') + '.' + String(d.getDate()).padStart(2, '0');
  }

  async function refreshCount() {
    if (document.hidden) {
      return;
    }
    try {
      var res = await Api.get('/api/notifications/unread-count', { auto: true });
      Layout.setUnreadCount(res.count);
    } catch (e) {
      // 다음 확인 때 다시 시도한다
    }
  }

  function build() {
    var backdrop = el('div', 'notif-backdrop');
    backdrop.hidden = true;
    backdrop.addEventListener('click', close);

    var panel = el('aside', 'notif-panel');
    panel.setAttribute('aria-label', '알림');
    panel.hidden = true;

    var head = el('div', 'notif-panel__head');
    head.appendChild(el('h2', '', '알림'));
    var settings = el('a', 'button button--text', '설정');
    settings.href = '/me-notifications.html';
    head.append(
      button('button button--text', '모두 읽음', readAll),
      button('button button--text', '전체 삭제', deleteAll),
      settings,
      button('notif-panel__close', '✕', close)
    );
    head.lastChild.setAttribute('aria-label', '닫기');

    var tabs = el('div', 'notif-tabs');
    tabs.setAttribute('role', 'tablist');
    TABS.forEach(function (t) {
      var b = button('notif-tabs__item', t[1], function () {
        state.tab = t[0];
        load(1);
      });
      b.dataset.tab = t[0];
      b.setAttribute('role', 'tab');
      tabs.appendChild(b);
    });

    var list = el('ul', 'notif-list');
    var empty = el('p', 'notif-empty', '알림이 없어요.');
    empty.hidden = true;
    var more = button('button notif-more', '더 보기', function () {
      load(state.page + 1);
    });
    more.hidden = true;

    panel.append(head, tabs, list, empty, more);
    document.body.append(backdrop, panel);
    ui = { backdrop: backdrop, panel: panel, tabs: tabs, list: list, empty: empty, more: more };
    document.addEventListener('keydown', function (e) {
      if (e.key === 'Escape' && state.open) {
        close();
      }
    });
  }

  function item(n) {
    var li = el('li', 'notif-item' + (n.read ? '' : ' notif-item--unread'));
    var a = el(n.link ? 'a' : 'div', 'notif-item__body');
    if (n.link) {
      a.href = n.link;
    }
    a.appendChild(el('p', 'notif-item__message', n.message));
    a.appendChild(el('span', 'notif-item__time', timeAgo(n.createdAt)));
    a.addEventListener('click', async function (event) {
      if (n.read) {
        return;
      }
      if (n.link) {
        event.preventDefault();
      }
      try {
        await Api.post('/api/notifications/' + n.id + '/read');
      } catch (e) {
        // 읽음 표시가 실패해도 이동은 한다
      }
      n.read = true;
      li.classList.remove('notif-item--unread');
      refreshCount();
      if (n.link) {
        window.location.href = n.link;
      }
    });
    li.appendChild(a);
    return li;
  }

  async function load(page) {
    Array.prototype.forEach.call(ui.tabs.children, function (b) {
      var current = b.dataset.tab === state.tab;
      b.classList.toggle('is-current', current);
      b.setAttribute('aria-selected', current ? 'true' : 'false');
    });
    try {
      var res = await Api.get('/api/notifications?tab=' + state.tab + '&page=' + page);
      state.page = res.page;
      state.totalPages = res.totalPages;
      var rows = res.items.map(item);
      if (page === 1) {
        ui.list.replaceChildren.apply(ui.list, rows);
      } else {
        ui.list.append.apply(ui.list, rows);
      }
      ui.empty.hidden = ui.list.children.length > 0;
      ui.more.hidden = state.page >= state.totalPages;
    } catch (e) {
      Api.showError(e);
    }
  }

  async function readAll() {
    try {
      await Api.post('/api/notifications/read-all');
      Layout.setUnreadCount(0);
      load(1);
    } catch (e) {
      Api.showError(e);
    }
  }

  async function deleteAll() {
    var label = state.tab === 'ALL' ? '모든' : '이 탭의';
    if (!window.confirm(label + ' 알림을 지울까요? 되돌릴 수 없어요.')) {
      return;
    }
    try {
      await Api.delete('/api/notifications?tab=' + state.tab);
      load(1);
      refreshCount();
    } catch (e) {
      Api.showError(e);
    }
  }

  function open() {
    state.open = true;
    ui.backdrop.hidden = false;
    ui.panel.hidden = false;
    load(1);
  }

  function close() {
    state.open = false;
    ui.backdrop.hidden = true;
    ui.panel.hidden = true;
  }

  build();
  document.addEventListener('layout:bell', function () {
    if (state.open) {
      close();
    } else {
      open();
    }
  });
  document.addEventListener('visibilitychange', refreshCount);
  refreshCount();
  setInterval(refreshCount, POLL_MS);
})();
