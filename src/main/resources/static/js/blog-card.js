/*
 * 블로그 화면들이 같이 쓰는 조각 (T054~T056): 블로그 카드, 페이지 번호, 공개 범위 이름.
 * 회원이 쓴 값(이름·소개·태그·닉네임)은 모두 textContent로 넣는다(innerHTML 금지, SEC-06).
 */
(function () {
  'use strict';

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

  var VISIBILITY = { PUBLIC: '공개', LINK_ONLY: '일부 공개', PRIVATE: '비공개' };
  var JOIN_POLICY = { OPEN: '자유 참여', APPROVAL: '승인제' };
  var ROLE = { OWNER: '블로그장', MANAGER: '부블로그장', MEMBER: '멤버' };

  function blogUrl(slug) {
    return '/blog/' + encodeURIComponent(slug);
  }

  function tagList(tags) {
    var list = el('ul', 'tags');
    (tags || []).forEach(function (tag) {
      list.appendChild(el('li', 'tag', '#' + tag));
    });
    return list;
  }

  /** 목록 카드. badge가 있으면 이름 옆에 붙인다(내 블로그의 역할 등). */
  function card(blog, badge) {
    var a = el('a', 'blog-card');
    a.href = blogUrl(blog.slug);
    var title = el('div', 'blog-card__title');
    title.appendChild(el('strong', '', blog.name));
    if (badge) {
      title.appendChild(el('span', 'badge', badge));
    }
    if (blog.visibility && blog.visibility !== 'PUBLIC') {
      title.appendChild(el('span', 'badge badge--muted', VISIBILITY[blog.visibility]));
    }
    if (blog.status === 'CLOSING') {
      title.appendChild(el('span', 'badge badge--danger', '폐쇄 예정'));
    }
    a.appendChild(title);
    a.appendChild(el('p', 'blog-card__desc', blog.description || '소개가 없어요.'));
    a.appendChild(tagList(blog.tags));
    a.appendChild(
      el(
        'p',
        'blog-card__meta',
        (blog.ownerNickname || '') + ' · 멤버 ' + blog.memberCount + ' · 글 ' + blog.postCount
      )
    );
    return a;
  }

  /** 1부터 세는 페이지 번호. totalPages가 1 이하면 비운다. */
  function pager(container, page, totalPages, onGo) {
    container.replaceChildren();
    if (totalPages <= 1) {
      return;
    }
    var start = Math.max(1, page - 4);
    var end = Math.min(totalPages, start + 9);
    function add(label, target, current) {
      var b = el('button', 'pager__item' + (current ? ' is-current' : ''), label);
      b.type = 'button';
      b.disabled = current || target < 1 || target > totalPages;
      b.addEventListener('click', function () {
        onGo(target);
      });
      container.appendChild(b);
    }
    add('이전', page - 1, false);
    for (var p = start; p <= end; p++) {
      add(String(p), p, p === page);
    }
    add('다음', page + 1, false);
  }

  function formatDate(value) {
    if (!value) {
      return '';
    }
    var d = new Date(value);
    return d.getFullYear() + '년 ' + (d.getMonth() + 1) + '월 ' + d.getDate() + '일';
  }

  /** "태그1, #태그2 태그3" 같은 입력을 나눈다. 정리 규칙은 서버(TagNormalizer)가 다시 적용한다. */
  function splitTags(text) {
    return (text || '')
      .split(/[,\n]/)
      .map(function (t) {
        return t.trim();
      })
      .filter(function (t) {
        return t.length > 0;
      });
  }

  window.BlogUi = {
    el: el,
    card: card,
    tagList: tagList,
    pager: pager,
    blogUrl: blogUrl,
    formatDate: formatDate,
    splitTags: splitTags,
    VISIBILITY: VISIBILITY,
    JOIN_POLICY: JOIN_POLICY,
    ROLE: ROLE
  };
})();
