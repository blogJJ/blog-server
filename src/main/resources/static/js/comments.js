/*
 * 글 상세의 댓글 (T071, BRD-06, D-78, D-87). 대댓글은 1단계: 답글에 답해도 같은 첫 댓글 아래에 붙고 앞에 @닉네임이 보인다.
 * 지운 첫 댓글에 답글이 남아 있으면 "삭제된 댓글입니다"로 보인다. 회원이 쓴 값은 모두 textContent로 넣는다 (SEC-06).
 */
(function () {
  'use strict';

  var MAX = 500;
  var post = null;
  var share = null;
  var user;
  var userKnown = false;
  var page = 1;

  function $(id) {
    return document.getElementById(id);
  }

  function el(tag, className, text) {
    return window.BlogUi.el(tag, className, text);
  }

  function withShare(url) {
    return share ? url + (url.indexOf('?') < 0 ? '?' : '&') + 'share=' + encodeURIComponent(share) : url;
  }

  function length(text) {
    return Array.from(text.trim()).length;
  }

  function formatDateTime(value) {
    var d = new Date(value);
    function two(n) {
      return n < 10 ? '0' + n : String(n);
    }
    return d.getFullYear() + '.' + two(d.getMonth() + 1) + '.' + two(d.getDate()) + ' ' + two(d.getHours()) + ':' + two(d.getMinutes());
  }

  function canWrite() {
    return user && user.role !== 'ADMIN';
  }

  function start() {
    if (!post || !userKnown) {
      return;
    }
    $('comments').hidden = false;
    $('comment-form').hidden = !canWrite();
    $('comment-login').hidden = !!user;
    $('comment-login-link').href =
      '/login.html?next=' + encodeURIComponent(window.location.pathname + window.location.search);
    $('comment-count').textContent = post.commentCount;
    load(1, false);
  }

  async function load(target, append) {
    try {
      var res = await Api.get(withShare('/api/posts/' + post.id + '/comments?page=' + target));
      page = res.page;
      var list = $('comment-list');
      var rows = res.items.map(rootItem);
      if (append) {
        rows.forEach(function (r) {
          list.appendChild(r);
        });
      } else {
        list.replaceChildren.apply(list, rows);
      }
      $('comment-empty').hidden = list.children.length > 0;
      $('comment-more').hidden = res.page >= res.totalPages;
    } catch (e) {
      Api.showError(e);
    }
  }

  function reload() {
    load(1, false);
  }

  function setCount(delta) {
    post.commentCount += delta;
    $('comment-count').textContent = post.commentCount;
    document.dispatchEvent(new CustomEvent('comments:count', { detail: post.commentCount }));
  }

  function rootItem(c) {
    var li = commentItem(c, c);
    var replies = el('ul', 'comment-list comment-list--replies');
    c.replies.forEach(function (r) {
      replies.appendChild(commentItem(r, c));
    });
    li.appendChild(replies);
    return li;
  }

  /** root는 이 댓글이 붙은 첫 댓글 (대댓글 폼을 그 아래에 연다) */
  function commentItem(c, root) {
    var li = el('li', 'comment');
    li.dataset.id = c.id;
    if (c.deleted) {
      li.classList.add('comment--deleted');
      li.appendChild(el('p', 'comment__text', '삭제된 댓글입니다'));
      return li;
    }
    var head = el('div', 'comment__head');
    head.appendChild(el('strong', 'comment__author', c.authorNickname));
    var meta = formatDateTime(c.createdAt) + (c.edited ? ' · 수정됨' : '');
    head.appendChild(el('span', 'comment__meta', meta));
    li.appendChild(head);

    var text = el('p', 'comment__text');
    if (c.replyToNickname) {
      text.appendChild(el('span', 'comment__mention', '@' + c.replyToNickname + ' '));
    }
    text.appendChild(document.createTextNode(c.content));
    li.appendChild(text);

    var actions = el('div', 'comment__actions');
    if (canWrite()) {
      actions.appendChild(linkButton('답글', function () {
        openForm(li, root, c, null);
      }));
    }
    if (c.canEdit) {
      actions.appendChild(linkButton('수정', function () {
        openForm(li, root, c, c.content);
      }));
    }
    if (c.canDelete) {
      actions.appendChild(linkButton('삭제', function () {
        remove(c);
      }));
    }
    li.appendChild(actions);
    return li;
  }

  function linkButton(label, onClick) {
    var b = el('button', 'comment__action', label);
    b.type = 'button';
    b.addEventListener('click', onClick);
    return b;
  }

  /** 답글 쓰기(editText가 null) 또는 수정 폼을 그 댓글 바로 아래에 연다 */
  function openForm(li, root, target, editText) {
    document.querySelectorAll('.comment-form--inline').forEach(function (f) {
      f.remove();
    });
    var form = el('form', 'comment-form comment-form--inline');
    form.noValidate = true;
    var input = el('textarea');
    input.maxLength = MAX;
    input.rows = 2;
    input.value = editText || '';
    input.setAttribute('aria-label', editText === null ? '답글' : '댓글 수정');
    input.placeholder = editText === null ? '@' + target.authorNickname + '님에게 답글' : '';
    var foot = el('div', 'comment-form__foot');
    var count = el('span', 'field__hint', length(input.value) + '/' + MAX);
    var cancel = el('button', 'button', '취소');
    cancel.type = 'button';
    cancel.addEventListener('click', function () {
      form.remove();
    });
    var submit = el('button', 'button button--primary', editText === null ? '등록' : '저장');
    submit.type = 'submit';
    foot.appendChild(count);
    foot.appendChild(cancel);
    foot.appendChild(submit);
    form.appendChild(input);
    form.appendChild(foot);
    input.addEventListener('input', function () {
      count.textContent = length(input.value) + '/' + MAX;
    });
    form.addEventListener('submit', async function (event) {
      event.preventDefault();
      if (!check(input.value)) {
        return;
      }
      try {
        if (editText === null) {
          await Api.post(withShare('/api/posts/' + post.id + '/comments'), {
            content: input.value,
            parentId: target.id
          });
          setCount(1);
        } else {
          await Api.put('/api/comments/' + target.id, { content: input.value });
        }
        reload();
      } catch (e) {
        Api.showError(e);
      }
    });
    li.appendChild(form);
    input.focus();
  }

  function check(text) {
    var n = length(text);
    if (n < 1 || n > MAX) {
      Api.toast('댓글은 1~500자로 입력해 주세요.');
      return false;
    }
    return true;
  }

  async function remove(c) {
    if (!window.confirm('이 댓글을 삭제할까요?')) {
      return;
    }
    try {
      await Api.delete('/api/comments/' + c.id);
      setCount(-1);
      reload();
    } catch (e) {
      Api.showError(e);
    }
  }

  async function submitNew(event) {
    event.preventDefault();
    var input = $('comment-input');
    if (!check(input.value)) {
      return;
    }
    try {
      await Api.post(withShare('/api/posts/' + post.id + '/comments'), { content: input.value });
      input.value = '';
      $('comment-length').textContent = '0/' + MAX;
      setCount(1);
      reload();
    } catch (e) {
      Api.showError(e);
    }
  }

  document.addEventListener('post:loaded', function (e) {
    post = e.detail.post;
    share = e.detail.share;
    start();
  });

  document.addEventListener('DOMContentLoaded', function () {
    $('comment-form').addEventListener('submit', submitNew);
    $('comment-input').addEventListener('input', function () {
      $('comment-length').textContent = length($('comment-input').value) + '/' + MAX;
    });
    $('comment-more').addEventListener('click', function () {
      load(page + 1, true);
    });
  });

  Layout.onUser(function (current) {
    user = current;
    userKnown = true;
    start();
  });
})();
