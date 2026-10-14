/*
 * 모든 화면의 공통 틀 (T027): 메인으로 가는 상단 메뉴, 로그인 상태, 종 아이콘, 아래쪽 개인정보 처리방침 링크(4.6).
 * api.js 다음에 불러온다. <body>에 이 틀이 앞뒤로 붙는다.
 *
 * 로그인한 회원 정보는 Layout.user에 두고, 다 읽으면 document에 'layout:user' 이벤트(detail: 회원 또는 null)를 보낸다.
 * 화면 스크립트는 Layout.onUser(fn)로 받는다(이벤트를 놓쳐도 부른다).
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

  /** 상단 메뉴 줄을 보이는 화면: 메인, 검색, 공지사항 (D-149) */
  var NAV_PAGES = ['/', '/index.html', '/search.html', '/notices.html'];

  var SEARCH_TYPES = [
    ['post', '글'],
    ['blog', '블로그'],
    ['tag', '태그'],
    ['nickname', '닉네임']
  ];

  /** J blog 로고: 동그라미 J + blog (D-149) */
  function logo() {
    var a = link('/', 'site-logo');
    a.setAttribute('aria-label', 'J blog 홈');
    a.appendChild(el('span', 'site-logo__mark', 'J'));
    a.appendChild(el('span', 'site-logo__text', 'blog'));
    return a;
  }

  /** 모든 화면 위의 통합 검색 칸. 검색 화면은 자기 검색 칸이 있어 넣지 않는다 */
  function searchForm() {
    var form = el('form', 'site-search');
    form.action = '/search.html';
    form.method = 'get';
    form.setAttribute('role', 'search');
    var select = el('select', 'site-search__type');
    select.name = 'type';
    select.setAttribute('aria-label', '검색 대상');
    SEARCH_TYPES.forEach(function (t) {
      var option = el('option', '', t[1]);
      option.value = t[0];
      select.appendChild(option);
    });
    var input = el('input', 'site-search__input');
    input.type = 'search';
    input.name = 'q';
    input.minLength = 2;
    input.maxLength = 20;
    input.required = true;
    input.placeholder = '검색어를 입력하세요';
    input.setAttribute('aria-label', '통합 검색');
    var button = el('button', 'site-search__button', '검색');
    button.type = 'submit';
    form.append(select, input, button);
    return form;
  }

  /** 메인·검색·공지 화면의 두 번째 줄 메뉴 */
  function navRow() {
    var path = window.location.pathname;
    var params = new URLSearchParams(window.location.search);
    var nav = el('nav', 'site-nav');
    nav.setAttribute('aria-label', '블로그 메뉴');
    var inner = el('div', 'site-nav__inner');
    var browse = params.get('tab') === 'blogs';
    [
      ['/', '블로그 홈', (path === '/' || path === '/index.html') && !browse],
      ['/?tab=blogs', '블로그 둘러보기', browse],
      ['/notices.html', '공지사항', path === '/notices.html']
    ].forEach(function (item) {
      var a = link(item[0], 'site-nav__item' + (item[2] ? ' is-current' : ''), item[1]);
      if (item[2]) {
        a.setAttribute('aria-current', 'page');
      }
      inner.appendChild(a);
    });
    var actions = el('div', 'site-nav__actions');
    inner.appendChild(actions);
    nav.appendChild(inner);
    return { nav: nav, actions: actions };
  }

  function buildHeader() {
    var header = el('header', 'site-header');
    var inner = el('div', 'site-header__inner');
    inner.appendChild(logo());
    if (window.location.pathname !== '/search.html') {
      inner.appendChild(searchForm());
    }
    var menu = el('nav', 'site-header__menu');
    menu.setAttribute('aria-label', '상단 메뉴');
    inner.appendChild(menu);
    inner.appendChild(themeButton());
    header.appendChild(inner);
    var navActions = null;
    if (NAV_PAGES.indexOf(window.location.pathname) >= 0) {
      var row = navRow();
      header.appendChild(row.nav);
      navActions = row.actions;
    }
    return { header: header, menu: menu, navActions: navActions };
  }

  /** 밝은/어두운 모드 버튼 (D-117). 바꾸는 일은 theme.js가 한다 */
  function themeButton() {
    var button = el('button', 'theme-toggle');
    button.type = 'button';
    function sync() {
      var dark = window.Theme && window.Theme.current() === 'dark';
      button.textContent = dark ? '☀️' : '🌙';
      button.setAttribute('aria-label', dark ? '밝은 모드로 바꾸기' : '어두운 모드로 바꾸기');
      button.title = button.getAttribute('aria-label');
    }
    button.addEventListener('click', function () {
      if (window.Theme) {
        window.Theme.toggle();
      }
    });
    document.addEventListener('theme:change', sync);
    sync();
    return button;
  }

  function buildFooter() {
    var footer = el('footer', 'site-footer');
    footer.appendChild(link('/notices.html', '', '공지사항'));
    footer.appendChild(link('/release-notes.html', '', '릴리즈 노트')); // T159, D-130
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

    var nickname = link('/profile.html?id=' + encodeURIComponent(user.id), 'site-header__nickname', user.nickname || '');
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

    var items = [bell, nickname];
    if (user.role === 'ADMIN') {
      items.push(link('/admin/', 'button', '관리')); // 메인 관리자 화면 (T126)
    }
    items.push(link('/my-blogs.html', 'button', '내 블로그'));
    items.push(link('/me.html', 'button', '내 정보'), logout);
    menu.replaceChildren.apply(menu, items);
    loadNotifications();
  }

  /** 알림 사이드바와 30초 확인(notifications.js)을 한 번만 불러온다 */
  function loadNotifications() {
    if (document.querySelector('script[data-notifications]')) {
      return;
    }
    var script = document.createElement('script');
    script.src = '/js/notifications.js';
    script.dataset.notifications = 'true';
    document.head.appendChild(script);
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
    window.Layout.ready = true;
    if (parts.navActions && user) {
      parts.navActions.appendChild(link('/blog-new.html', 'site-nav__action', '블로그 만들기'));
      parts.navActions.appendChild(link('/my-blogs.html', 'site-nav__action', '내 블로그'));
    }
    if (user) {
      renderMember(parts.menu, user);
    } else {
      renderGuest(parts.menu);
    }
    document.dispatchEvent(new CustomEvent('layout:user', { detail: user }));
  }

  /**
   * 로그인 상태를 안 뒤에 fn(회원 또는 null)을 부른다. 이미 알면 바로 부른다.
   * 큰 스크립트를 함께 불러오는 화면은 'layout:user' 이벤트가 먼저 지나갈 수 있어 이것을 쓴다.
   */
  function onUser(fn) {
    if (window.Layout.ready) {
      fn(window.Layout.user);
    } else {
      document.addEventListener('layout:user', function (event) {
        fn(event.detail);
      });
    }
  }

  window.Layout = { user: null, ready: false, setUnreadCount: setUnreadCount, onUser: onUser };

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
