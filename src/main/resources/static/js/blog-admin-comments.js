/*
 * 블로그 관리 화면의 댓글 확인 탭 (T166, D-133).
 * 광고·스팸·욕설·개인정보 노출 같아 잠시 숨겨 둔 댓글을 승인(다시 보이기)하거나 삭제한다.
 * 블로그장·글 관리 권한 부블로그장·메인 관리자에게만 탭이 보이고, 권한은 서버가 요청마다 다시 확인한다.
 */
(function () {
  'use strict';

  var blog = null;
  var REASON = {
    SPAM: '스팸',
    AD: '광고·홍보',
    ABUSE: '욕설·비방',
    PERSONAL_INFO: '개인정보',
    OTHER: '기타'
  };

  function $(id) {
    return document.getElementById(id);
  }

  function el(tag, className, text) {
    return window.BlogUi.el(tag, className, text);
  }

  function button(label, onClick, extra) {
    var b = el('button', 'button' + (extra ? ' ' + extra : ''), label);
    b.type = 'button';
    b.addEventListener('click', onClick);
    return b;
  }

  function base() {
    return '/api/blogs/' + blog.id + '/comment-reviews';
  }

  async function load() {
    try {
      var rows = await Api.get(base());
      var list = $('held-comments');
      list.replaceChildren.apply(list, rows.map(row));
      $('held-comments-empty').hidden = rows.length > 0;
    } catch (e) {
      Api.showError(e);
    }
  }

  function row(c) {
    var li = el('li', 'admin-list__item');
    var head = el('div', 'admin-list__head');
    head.appendChild(el('span', 'badge badge--warn', REASON[c.reason] || REASON.OTHER));
    head.appendChild(el('strong', '', c.authorNickname));
    if (c.parentId) {
      head.appendChild(el('span', 'badge badge--muted', '답글'));
    }
    li.appendChild(head);
    var sub = el('p', 'admin-list__sub');
    var post = el('a', '', c.postTitle);
    post.href = window.BlogUi.blogUrl(blog.slug) + '/posts/' + c.postId;
    sub.appendChild(post);
    sub.appendChild(document.createTextNode(
      ' · ' + window.BlogUi.formatDate(c.createdAt) + ' 작성' + (c.edited ? ' · 수정됨' : '')));
    li.appendChild(sub);
    li.appendChild(el('div', 'admin-list__body', c.content));
    var actions = el('div', 'admin-list__actions');
    actions.appendChild(button('승인', function () {
      approve(c);
    }, 'button--primary'));
    actions.appendChild(button('삭제', function () {
      remove(c);
    }, 'button--danger'));
    li.appendChild(actions);
    return li;
  }

  async function approve(c) {
    try {
      await Api.post(base() + '/' + c.id + '/approve');
      Api.toast('승인했어요. 이제 모두에게 보여요.');
    } catch (e) {
      Api.showError(e);
    }
    load();
  }

  async function remove(c) {
    if (!window.confirm(c.authorNickname + '님의 댓글을 삭제할까요?')) {
      return;
    }
    try {
      await Api.delete(base() + '/' + c.id);
      Api.toast('삭제했어요.');
    } catch (e) {
      Api.showError(e);
    }
    load();
  }

  document.addEventListener('admin:tab', function (e) {
    blog = e.detail.blog;
    if (e.detail.name === 'comments') {
      load();
    }
  });
})();
