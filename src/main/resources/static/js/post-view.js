/*
 * 글 상세 (T071, BRD-01, BRD-07). 본문은 서버가 jsoup으로 걸러낸 HTML이라 이곳만 innerHTML로 넣고, 나머지는 textContent로 넣는다 (SEC-06).
 * 공유는 1차에 링크 복사만 (BRD-07). 좋아요는 다시 누르면 취소 (BRD-06). 댓글은 comments.js가 'post:loaded'를 받아 그린다.
 */
(function () {
  'use strict';

  var ui = window.BlogUi;
  var post = null;

  function $(id) {
    return document.getElementById(id);
  }

  function route() {
    var m = window.location.pathname.match(/^\/blog\/([a-z0-9-]+)\/posts\/(\d+)/);
    return m ? { slug: m[1], id: m[2] } : null;
  }

  function share() {
    return new URLSearchParams(window.location.search).get('share');
  }

  function withShare(url) {
    return share() ? url + (url.indexOf('?') < 0 ? '?' : '&') + 'share=' + encodeURIComponent(share()) : url;
  }

  function formatDateTime(value) {
    var d = new Date(value);
    function two(n) {
      return n < 10 ? '0' + n : String(n);
    }
    return d.getFullYear() + '.' + two(d.getMonth() + 1) + '.' + two(d.getDate()) + ' ' + two(d.getHours()) + ':' + two(d.getMinutes());
  }

  function blocked(title, desc) {
    $('post').hidden = true;
    $('blocked').hidden = false;
    $('blocked-title').textContent = title;
    $('blocked-desc').textContent = desc || '';
  }

  function render() {
    document.title = post.title + ' - ' + post.blog.name;
    $('blocked').hidden = true;
    $('post').hidden = false;
    $('blog-link').textContent = post.blog.name;
    $('blog-link').href = withShare(ui.blogUrl(post.blog.slug));
    $('back').href = withShare(ui.blogUrl(post.blog.slug));
    $('title').textContent = (post.notice ? '[공지] ' : '') + post.title;
    var meta = [post.authorNickname, formatDateTime(post.createdAt)];
    if (post.categoryName) {
      meta.push(post.categoryName);
    }
    if (post.updatedAt && post.updatedAt !== post.createdAt) {
      meta.push('수정됨');
    }
    $('meta').textContent = meta.join(' · ');
    $('body').innerHTML = post.html;
    $('tags').replaceChildren(ui.tagList(post.tags));
    $('edit').hidden = !post.canEdit;
    $('edit').href = '/post-edit.html?post=' + post.id;
    $('delete').hidden = !post.canDelete;
    $('report').hidden = post.canEdit;
    renderLike();
    document.dispatchEvent(new CustomEvent('post:loaded', { detail: { post: post, share: share() } }));
  }

  function renderLike() {
    $('like').setAttribute('aria-pressed', post.liked ? 'true' : 'false');
    $('like').classList.toggle('is-on', post.liked);
    $('like').textContent = (post.liked ? '♥' : '♡') + ' 좋아요 ' + post.likeCount;
    renderCounts();
  }

  function renderCounts() {
    $('counts').textContent = '조회 ' + post.viewCount + ' · 좋아요 ' + post.likeCount + ' · 댓글 ' + post.commentCount;
  }

  async function onLike() {
    if (!window.Layout.user) {
      window.location.href = '/login.html?next=' + encodeURIComponent(window.location.pathname + window.location.search);
      return;
    }
    try {
      var res = await Api.post(withShare('/api/posts/' + post.id + '/like'));
      post.liked = res.liked;
      post.likeCount = res.likeCount;
      renderLike();
    } catch (e) {
      Api.showError(e);
    }
  }

  async function load() {
    var r = route();
    if (!r) {
      blocked('글을 찾을 수 없어요');
      return;
    }
    try {
      post = await Api.get(withShare('/api/posts/' + r.id));
      render();
    } catch (e) {
      if (e.status === 404) {
        blocked('글을 찾을 수 없어요', '지워졌거나 주소가 잘못되었어요.');
      } else {
        blocked('글을 볼 수 없어요', e.message);
      }
    }
  }

  async function onDelete() {
    if (!window.confirm('이 글을 삭제할까요? 삭제한 글은 되돌릴 수 없어요.')) {
      return;
    }
    try {
      await Api.delete('/api/posts/' + post.id);
      window.location.href = withShare(ui.blogUrl(post.blog.slug));
    } catch (e) {
      Api.showError(e);
    }
  }

  async function onCopy() {
    try {
      await navigator.clipboard.writeText(window.location.href);
      Api.toast('링크를 복사했어요.');
    } catch (e) {
      window.prompt('아래 링크를 복사해 주세요.', window.location.href);
    }
  }

  document.addEventListener('DOMContentLoaded', function () {
    $('delete').addEventListener('click', onDelete);
    $('copy-link').addEventListener('click', onCopy);
    $('like').addEventListener('click', onLike);
    $('report').addEventListener('click', function () {
      Report.open({ targetType: 'POST', targetId: post.id });
    });
    document.addEventListener('comments:count', function (e) {
      if (post) {
        post.commentCount = e.detail;
        renderCounts();
      }
    });
    load();
  });
})();
