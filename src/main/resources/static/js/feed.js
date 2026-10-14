/*
 * 메인 화면 (T110): 피드 탭과 블로그 둘러보기.
 * - 최신 글·인기 글(좋아요 수): 공개 블로그의 글. 팔로우·구독 탭은 로그인한 회원에게만 보인다 (BRD-09, D-65).
 * - 블로그 둘러보기: 공개 블로그를 최신순·인기순(멤버 수)으로 (BLG-02, D-89).
 * - 위쪽: 인기 글 큰 카드 3장씩(최대 3쪽)과 추천 블로그(멤버 수 많은 순 3곳), 옆에 로그인 상자와 공지사항 (D-149).
 * 피드는 볼 때마다 서버가 모아온다 (D-73). 주소의 ?tab=, ?sort=, ?page= 로 상태를 둬서 뒤로 가기와 새로 고침이 그대로 동작한다.
 */
(function () {
  'use strict';

  var ui = window.BlogUi;
  var TABS = ['latest', 'popular', 'following', 'subscribed', 'blogs'];
  var EMPTY = {
    latest: '아직 글이 없어요.',
    popular: '아직 글이 없어요.',
    following: '팔로우한 회원의 새 글이 없어요. 프로필에서 회원을 팔로우해 보세요.',
    subscribed: '구독한 블로그의 새 글이 없어요. 블로그에서 구독하기를 눌러 보세요.',
    blogs: '아직 블로그가 없어요. 첫 블로그를 만들어 보세요.'
  };

  function $(id) {
    return document.getElementById(id);
  }

  function state() {
    var p = new URLSearchParams(window.location.search);
    return {
      tab: TABS.indexOf(p.get('tab')) >= 0 ? p.get('tab') : 'latest',
      sort: p.get('sort') === 'popular' ? 'popular' : 'latest',
      page: Math.max(1, parseInt(p.get('page'), 10) || 1)
    };
  }

  function go(next) {
    var p = new URLSearchParams();
    if (next.tab !== 'latest') {
      p.set('tab', next.tab);
    }
    if (next.tab === 'blogs' && next.sort !== 'latest') {
      p.set('sort', next.sort);
    }
    if (next.page > 1) {
      p.set('page', String(next.page));
    }
    var qs = p.toString();
    history.pushState(null, '', qs ? '?' + qs : '/');
    load();
  }

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

  /** 1, 2, 3 쪽 단추. 한 쪽뿐이면 비운다 */
  function dots(container, count, current, onGo) {
    container.replaceChildren();
    if (count <= 1) {
      return;
    }
    for (var i = 0; i < count; i++) {
      var b = el('button', 'dots__item' + (i === current ? ' is-current' : ''), String(i + 1));
      b.type = 'button';
      b.setAttribute('aria-label', (i + 1) + '쪽');
      if (i === current) {
        b.setAttribute('aria-current', 'true');
      }
      b.addEventListener('click', onGo.bind(null, i));
      container.appendChild(b);
    }
  }

  var HOT_PER_PAGE = 3;

  /** 인기 글 큰 카드. 첫 이미지가 있으면 사진 위에 제목, 없으면 색 바탕에 제목과 블로그 이름 */
  function hotCard(post, index) {
    var a = el('a', 'hot-card');
    a.href = ui.blogUrl(post.blogSlug) + '/posts/' + post.id;
    if (post.thumbnail) {
      var img = el('img', 'hot-card__img');
      img.src = post.thumbnail;
      img.alt = '';
      a.appendChild(img);
    } else {
      a.classList.add('hot-card--plain', 'hot-card--c' + (index % 4));
      a.appendChild(el('span', 'hot-card__blog', post.blogName));
    }
    var caption = el('span', 'hot-card__caption');
    caption.appendChild(el('strong', 'hot-card__title', post.title));
    caption.appendChild(el('span', 'hot-card__meta', post.authorNickname + ' · 좋아요 ' + post.likeCount));
    a.appendChild(caption);
    return a;
  }

  async function loadHot() {
    var data;
    try {
      data = await Api.get('/api/feed?tab=popular&size=10&page=1'); // 크기는 10·20·30만 받는다 (D-06)
    } catch (e) {
      return; // 위쪽 꾸밈이라 실패해도 아래 목록은 그대로 둔다
    }
    // 사진 있는 글을 앞으로 (같은 무리 안에서는 인기순 그대로)
    var posts = data.items.filter(function (p) { return p.thumbnail; })
      .concat(data.items.filter(function (p) { return !p.thumbnail; }))
      .slice(0, HOT_PER_PAGE * 3);
    var pages = Math.ceil(posts.length / HOT_PER_PAGE);
    var box = $('hot-cards');
    if (!posts.length) {
      box.replaceChildren(el('p', 'hot__empty', '아직 인기 글이 없어요. 첫 글을 써 보세요.'));
      return;
    }
    function show(page) {
      var slice = posts.slice(page * HOT_PER_PAGE, page * HOT_PER_PAGE + HOT_PER_PAGE);
      box.replaceChildren.apply(box, slice.map(function (p, i) {
        return hotCard(p, page * HOT_PER_PAGE + i);
      }));
      dots($('hot-pages'), pages, page, show);
    }
    show(0);
  }

  async function loadPick() {
    var data;
    try {
      data = await Api.get('/api/blogs?sort=popular&page=1');
    } catch (e) {
      return;
    }
    var blogs = data.items.slice(0, 3);
    var box = $('pick-card');
    if (!blogs.length) {
      box.replaceChildren(el('p', 'hot__empty', '아직 블로그가 없어요.'));
      return;
    }
    function show(i) {
      box.replaceChildren(ui.card(blogs[i]));
      dots($('pick-pages'), blogs.length, i, show);
    }
    show(0);
  }

  /** 오른쪽 로그인 상자. 비회원은 로그인 단추, 회원은 내 블로그로 가는 단추들 */
  function renderLoginBox(user) {
    var box = $('login-box');
    if (!user) {
      var guest = [
        el('p', 'login-box__lead', 'J blog를 더 편하게 이용해 보세요.'),
        Object.assign(el('a', 'button button--primary login-box__main', 'J blog 로그인'), { href: '/login.html' })
      ];
      var links = el('p', 'login-box__links');
      [['/find-email.html', '이메일 찾기'], ['/find-password.html', '비밀번호 찾기'], ['/signup.html', '회원가입']]
        .forEach(function (l) {
          links.appendChild(Object.assign(el('a', '', l[1]), { href: l[0] }));
        });
      guest.push(links);
      box.replaceChildren.apply(box, guest);
      return;
    }
    var me = el('div', 'login-box__me');
    me.appendChild(ui.avatar(user.nickname));
    var hello = el('p', 'login-box__hello');
    var name = el('a', 'login-box__name', user.nickname);
    name.href = ui.profileUrl(user.id);
    hello.append(name, document.createTextNode('님, 반가워요'));
    me.appendChild(hello);
    var actions = el('div', 'login-box__actions');
    [['/my-blogs.html', '내 블로그'], ['/blog-new.html', '블로그 만들기'], ['/me.html', '내 정보']].forEach(function (l) {
      actions.appendChild(Object.assign(el('a', 'button', l[1]), { href: l[0] }));
    });
    box.replaceChildren(me, actions);
  }

  async function loadNotices() {
    var list = $('side-notices');
    try {
      var data = await Api.get('/api/notices?page=1');
      var items = data.items.slice(0, 4);
      if (!items.length) {
        list.replaceChildren(el('li', 'side-list__empty', '아직 공지가 없어요.'));
        return;
      }
      list.replaceChildren.apply(list, items.map(function (n) {
        var li = el('li', 'side-list__item');
        var a = el('a', '', n.title);
        a.href = '/notices.html?id=' + n.id;
        li.append(a, el('span', 'side-list__date', ui.formatDate(n.createdAt)));
        return li;
      }));
    } catch (e) {
      list.closest('.side-box').hidden = true;
    }
  }

  /** 위 메뉴 줄(블로그 홈 / 블로그 둘러보기)의 현재 표시를 탭에 맞춘다 */
  function syncNav(tab) {
    document.querySelectorAll('.site-nav__item').forEach(function (a) {
      var url = new URL(a.href);
      if (url.pathname !== '/') {
        return;
      }
      var current = (url.searchParams.get('tab') === 'blogs') === (tab === 'blogs');
      a.classList.toggle('is-current', current);
      if (current) {
        a.setAttribute('aria-current', 'page');
      } else {
        a.removeAttribute('aria-current');
      }
    });
  }

  function showEmpty(text) {
    $('empty').hidden = !text;
    $('empty').textContent = text || '';
  }

  async function load() {
    var s = state();
    var blogs = s.tab === 'blogs';
    document.querySelectorAll('#tabs .tabs__item').forEach(function (b) {
      b.classList.toggle('is-current', b.dataset.tab === s.tab);
    });
    document.querySelectorAll('#blog-sort [data-sort]').forEach(function (b) {
      b.classList.toggle('is-current', b.dataset.sort === s.sort);
    });
    syncNav(s.tab);
    $('blog-sort').hidden = !blogs;
    $('feed').hidden = blogs;
    $('blogs').hidden = !blogs;
    $('feed').replaceChildren();
    $('blogs').replaceChildren();
    $('pager').replaceChildren();
    showEmpty('');

    var needLogin = s.tab === 'following' || s.tab === 'subscribed';
    if (needLogin && Layout.ready && !Layout.user) {
      showEmpty('로그인하면 팔로우한 회원과 구독한 블로그의 새 글을 볼 수 있어요.');
      return;
    }
    var url = blogs
      ? '/api/blogs?sort=' + s.sort + '&page=' + s.page
      : '/api/feed?tab=' + s.tab + '&page=' + s.page;
    try {
      var data = await Api.get(url);
      if (blogs) {
        $('blogs').replaceChildren.apply($('blogs'), data.items.map(function (b) {
          return ui.card(b);
        }));
      } else {
        $('feed').replaceChildren.apply($('feed'), data.items.map(ui.postCard));
      }
      showEmpty(data.items.length ? '' : EMPTY[s.tab]);
      ui.pager($('pager'), data.page, data.totalPages, function (page) {
        go({ tab: s.tab, sort: s.sort, page: page });
        window.scrollTo(0, 0);
      });
    } catch (e) {
      if (e.status === 401) {
        showEmpty(e.message);
        return;
      }
      Api.showError(e);
    }
  }

  document.addEventListener('DOMContentLoaded', function () {
    // 예전 주소(/?q=)로 온 블로그 검색은 통합 검색으로 보낸다
    var q = new URLSearchParams(window.location.search).get('q');
    if (q) {
      window.location.replace('/search.html?type=blog&q=' + encodeURIComponent(q));
      return;
    }
    document.querySelectorAll('#tabs .tabs__item').forEach(function (b) {
      b.addEventListener('click', function () {
        go({ tab: b.dataset.tab, sort: 'latest', page: 1 });
      });
    });
    document.querySelectorAll('#blog-sort [data-sort]').forEach(function (b) {
      b.addEventListener('click', function () {
        go({ tab: 'blogs', sort: b.dataset.sort, page: 1 });
      });
    });
    window.addEventListener('popstate', load);
    loadHot();
    loadPick();
    loadNotices();
  });

  Layout.onUser(function (user) {
    renderLoginBox(user);
    $('tabs').querySelector('[data-tab="following"]').hidden = !user;
    $('tabs').querySelector('[data-tab="subscribed"]').hidden = !user;
    load(); // 로그인 상태를 안 뒤에 읽어야 팔로우·구독 탭 안내가 맞다
  });
})();
