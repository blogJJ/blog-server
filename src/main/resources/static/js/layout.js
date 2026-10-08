/*
 * 모든 화면의 공통 틀 (T027): 메인으로 가는 상단 메뉴, 로그인 상태, 종 아이콘, 아래쪽 개인정보 처리방침 링크(4.6).
 * api.js 다음에 불러온다. <body>에 이 틀이 앞뒤로 붙는다.
 *
 * 로그인한 회원 정보는 Layout.user에 두고, 다 읽으면 document에 'layout:user' 이벤트(detail: 회원 또는 null)를 보낸다.
 * 종 아이콘을 누르면 'layout:bell' 이벤트를 보낸다. 알림 사이드바와 30초 확인은 notifications.js(T114)가 맡는다.
 *
 * 화면에 넣는 회원 값은 모두 textContent로 넣는다(innerHTML 금지, SEC-06).
 */
(function () {
  'use strict';

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

  function link(href, className, text) {
    var a = el('a', className, text);
    a.href = href;
    return a;
  }

  function buildHeader() {
    var header = el('header', 'site-header');
    var inner = el('div', 'site-header__inner');
    inner.appendChild(link('/', 'site-header__logo', '블로그'));
    var menu = el('nav', 'site-header__menu');
    menu.setAttribute('aria-label', '상단 메뉴');
    inner.appendChild(menu);
    header.appendChild(inner);
    return { header: header, menu: menu };
  }

  function buildFooter() {
    var footer = el('footer', 'site-footer');
    footer.appendChild(link('/privacy.html', '', '개인정보 처리방침'));
    return footer;
  }

  function renderGuest(menu) {
    menu.replaceChildren(
      link('/login.html', 'button', '로그인'),
      link('/signup.html', 'button button--primary', '회원가입')
    );
  }

  function renderMember(menu, user) {
    var bell = el('button', 'bell');
    bell.type = 'button';
    bell.setAttribute('aria-label', '알림');
    bell.appendChild(el('span', '', '🔔'));
    var badge = el('span', 'bell__badge');
    badge.hidden = true;
    bell.appendChild(badge);
    bell.addEventListener('click', function () {
      document.dispatchEvent(new CustomEvent('layout:bell'));
    });

    var nickname = el('span', 'site-header__nickname', user.nickname || '');
    var logout = el('button', 'button', '로그아웃');
    logout.type = 'button';
    logout.addEventListener('click', async function () {
      try {
        await Api.post('/api/auth/logout', undefined, { noRefresh: true });
      } catch (ignored) {
        // 이미 로그아웃된 상태여도 메인으로 보낸다
      }
      window.location.href = '/';
    });

    menu.replaceChildren(bell, nickname, link('/me.html', 'button', '내 정보'), logout);
  }

  /** 안 읽은 알림 수를 종 아이콘에 표시한다. 0이면 숨긴다. notifications.js가 30초마다 부른다. */
  function setUnreadCount(count) {
    var badge = document.querySelector('.bell__badge');
    if (!badge) {
      return;
    }
    badge.hidden = !count;
    badge.textContent = count > 99 ? '99+' : String(count || '');
  }

  async function loadUser() {
    try {
      return await Api.get('/api/me');
    } catch (e) {
      return null; // 로그인하지 않았거나 확인할 수 없으면 비회원 메뉴
    }
  }

  async function init() {
    var parts = buildHeader();
    document.body.prepend(parts.header);
    document.body.appendChild(buildFooter());

    var user = await loadUser();
    window.Layout.user = user;
    if (user) {
      renderMember(parts.menu, user);
    } else {
      renderGuest(parts.menu);
    }
    document.dispatchEvent(new CustomEvent('layout:user', { detail: user }));
  }

  window.Layout = { user: null, setUnreadCount: setUnreadCount };

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
