/*
 * 메인 화면 (T110): 피드 탭과 블로그 둘러보기.
 * - 최신 글·인기 글(좋아요 수): 공개 블로그의 글. 팔로우·구독 탭은 로그인한 회원에게만 보인다 (BRD-09, D-65).
 * - 블로그 둘러보기: 공개 블로그를 최신순·인기순(멤버 수)으로 (BLG-02, D-89).
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
  });

  Layout.onUser(function (user) {
    $('new-blog').hidden = !user;
    $('tabs').querySelector('[data-tab="following"]').hidden = !user;
    $('tabs').querySelector('[data-tab="subscribed"]').hidden = !user;
    load(); // 로그인 상태를 안 뒤에 읽어야 팔로우·구독 탭 안내가 맞다
  });
})();
