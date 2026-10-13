/*
 * 글 아래 "이런 글은 어때요" (T168). 같은 주제의 다른 블로그 글을 카드로 보여 준다.
 * 본문을 다 그린 뒤('post:loaded') 글 끝("목록으로" 버튼)이 화면 아래 600px 안에 들어오면 그때 한 번 부른다. 글 화면을 늦추지 않으려고
 * 처음 불러오기에는 끼지 않는다. 결과가 없거나 실패하면 칸을 숨긴 채로 둔다.
 * 회원이 쓴 값(제목·블로그 이름·발췌)은 모두 textContent로 넣는다 (SEC-06).
 */
(function () {
  'use strict';

  var LIMIT = 4;

  function $(id) {
    return document.getElementById(id);
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

  function blogUrl(slug) {
    return '/blog/' + encodeURIComponent(slug);
  }

  /** 글 첫 이미지 → 블로그 대표 이미지 → 블로그 마스코트 순으로 그림 칸을 채운다. */
  function thumb(item) {
    var box = el('div', 'similar-card__thumb');
    if (item.thumbnail) {
      var img = el('img');
      img.src = item.thumbnail;
      img.alt = '';
      img.loading = 'lazy';
      img.decoding = 'async';
      img.addEventListener('error', function () {
        img.remove();
        box.appendChild(mascot(item));
      });
      box.appendChild(img);
    } else {
      box.appendChild(mascot(item));
    }
    return box;
  }

  function mascot(item) {
    if (window.Mascot) {
      return window.Mascot.element(item.blogMascot, { size: 56, seed: item.blogName });
    }
    return el('span', 'similar-card__initial', (item.blogName || '?').charAt(0));
  }

  function card(item) {
    var li = el('li', 'similar-card');
    var a = el('a', 'similar-card__link');
    a.href = blogUrl(item.blogSlug) + '/posts/' + item.id;
    a.appendChild(thumb(item));
    var body = el('div', 'similar-card__body');
    body.appendChild(el('strong', 'similar-card__title', item.title));
    if (item.excerpt) {
      body.appendChild(el('p', 'similar-card__excerpt', item.excerpt));
    }
    body.appendChild(el('span', 'similar-card__blog', item.blogName));
    a.appendChild(body);
    li.appendChild(a);
    return li;
  }

  async function load(post, share) {
    var url = '/api/posts/' + post.id + '/similar?limit=' + LIMIT;
    if (share) {
      url += '&share=' + encodeURIComponent(share);
    }
    var items;
    try {
      items = await Api.get(url);
    } catch (e) {
      return; // 추천은 덤이라 실패해도 알리지 않는다
    }
    if (!items || !items.length) {
      return;
    }
    $('similar-list').replaceChildren.apply($('similar-list'), items.map(card));
    $('similar').hidden = false;
  }

  /** 글 끝이 가까워지면 한 번만 부른다. IntersectionObserver가 없으면 바로 부른다. */
  function whenNear(target, run) {
    if (!target || !('IntersectionObserver' in window)) {
      run();
      return;
    }
    var observer = new IntersectionObserver(
      function (entries) {
        if (entries.some(function (e) { return e.isIntersecting; })) {
          observer.disconnect();
          run();
        }
      },
      { rootMargin: '0px 0px 600px 0px' }
    );
    observer.observe(target);
  }

  document.addEventListener('post:loaded', function (e) {
    var post = e.detail.post;
    var share = e.detail.share;
    $('similar').hidden = true;
    $('similar-list').replaceChildren();
    whenNear($('back'), function () {
      load(post, share);
    });
  });
})();
