/*
 * 블로그 관리 화면 (T056): 참여 신청 탭(멤버 관리 권한), 정보 수정 탭(정보 수정 권한, T053), 공유 링크 새로 만들기(T046).
 * 탭은 권한이 있는 것만 보인다. 권한은 서버가 요청마다 다시 확인한다.
 */
(function () {
  'use strict';

  var ui = window.BlogUi;
  var el = ui.el;
  var blog = null;

  function $(id) {
    return document.getElementById(id);
  }

  function slugFromPath() {
    var m = window.location.pathname.match(/^\/blog\/([a-z0-9-]+)\/admin/);
    return m ? m[1] : '';
  }

  function blocked(desc) {
    $('admin').hidden = true;
    $('blocked').hidden = false;
    $('blocked-desc').textContent = desc;
  }

  function showTab(name) {
    ['requests', 'info'].forEach(function (t) {
      $('panel-' + t).hidden = t !== name;
      $('tab-' + t).classList.toggle('is-current', t === name);
    });
    if (name === 'requests') {
      loadRequests();
    }
  }

  async function loadRequests() {
    try {
      var rows = await Api.get('/api/blogs/' + blog.id + '/join-requests');
      var list = $('requests');
      list.replaceChildren.apply(list, rows.map(requestRow));
      $('requests-empty').hidden = rows.length > 0;
    } catch (e) {
      Api.showError(e);
    }
  }

  function requestRow(r) {
    var li = el('li', 'request-list__item');
    var who = el('div', 'request-list__who');
    who.appendChild(el('strong', '', r.nickname || '탈퇴한 회원'));
    who.appendChild(el('span', 'request-list__date', ui.formatDate(r.createdAt) + ' 신청'));
    li.appendChild(who);
    var actions = el('div', 'request-list__actions');
    var approve = el('button', 'button button--primary', '승인');
    var reject = el('button', 'button', '거절');
    approve.type = 'button';
    reject.type = 'button';
    approve.addEventListener('click', function () {
      handle(r, 'approve', '승인했어요.');
    });
    reject.addEventListener('click', function () {
      if (window.confirm((r.nickname || '이 회원') + '님의 신청을 거절할까요?')) {
        handle(r, 'reject', '거절했어요.');
      }
    });
    actions.appendChild(approve);
    actions.appendChild(reject);
    li.appendChild(actions);
    return li;
  }

  async function handle(r, action, done) {
    try {
      await Api.post('/api/blogs/' + blog.id + '/join-requests/' + r.id + '/' + action);
      Api.toast(done);
    } catch (e) {
      Api.showError(e);
    }
    loadRequests();
  }

  function renderShare() {
    $('share-box').hidden = !blog.shareKey;
    if (blog.shareKey) {
      $('share-url').value =
        window.location.origin + ui.blogUrl(blog.slug) + '?share=' + encodeURIComponent(blog.shareKey);
    }
  }

  async function saveInfo(event) {
    event.preventDefault();
    $('message').textContent = '';
    var form = BlogForm.read($('common-fields'));
    var problem = BlogForm.check(form);
    if (problem) {
      $('message').textContent = problem;
      return;
    }
    if (blog.visibility !== form.visibility && form.visibility === 'PRIVATE' &&
        !window.confirm('비공개로 바꾸면 멤버만 볼 수 있어요. 바꿀까요?')) {
      return;
    }
    try {
      var res = await Api.put('/api/blogs/' + blog.id, form);
      blog.visibility = form.visibility;
      blog.shareKey = res.shareKey;
      renderShare();
      Api.toast('저장했어요.');
    } catch (e) {
      $('message').textContent = e.message;
    }
  }

  async function regenerate() {
    if (!window.confirm('새 링크를 만들면 지금 링크로는 들어올 수 없어요. 만들까요?')) {
      return;
    }
    try {
      var res = await Api.post('/api/blogs/' + blog.id + '/share-link');
      blog.shareKey = res.shareKey;
      renderShare();
      Api.toast('새 공유 링크를 만들었어요.');
    } catch (e) {
      Api.showError(e);
    }
  }

  function renderCover() {
    $('cover-preview').hidden = !blog.coverImage;
    $('cover-delete').hidden = !blog.coverImage;
    if (blog.coverImage) {
      $('cover-preview').src = blog.coverImage;
    }
  }

  async function uploadCover() {
    var file = $('cover-file').files[0];
    if (!file) {
      return;
    }
    if (file.size > 3 * 1024 * 1024) {
      Api.toast('이미지는 3MB까지 올릴 수 있어요.');
      $('cover-file').value = '';
      return;
    }
    var form = new FormData();
    form.append('file', file);
    try {
      var res = await Api.put('/api/blogs/' + blog.id + '/cover', form);
      blog.coverImage = res.coverImage;
      renderCover();
      Api.toast('대표 이미지를 바꿨어요.');
    } catch (e) {
      Api.showError(e);
    }
    $('cover-file').value = '';
  }

  async function deleteCover() {
    if (!window.confirm('대표 이미지를 지울까요?')) {
      return;
    }
    try {
      await Api.delete('/api/blogs/' + blog.id + '/cover');
      blog.coverImage = null;
      renderCover();
    } catch (e) {
      Api.showError(e);
    }
  }

  async function load() {
    try {
      blog = await Api.get('/api/blogs/by-slug/' + encodeURIComponent(slugFromPath()));
    } catch (e) {
      blocked(e.message);
      return;
    }
    var v = blog.viewer;
    if (!v.canEditInfo && !v.canManageMembers) {
      blocked('이 블로그를 관리할 권한이 없어요.');
      return;
    }
    document.title = blog.name + ' 관리 - 블로그';
    $('admin').hidden = false;
    $('blog-link').textContent = blog.name;
    $('blog-link').href = ui.blogUrl(blog.slug);
    $('tab-requests').hidden = !v.canManageMembers;
    $('tab-info').hidden = !v.canEditInfo;
    if (v.canEditInfo) {
      $('slug').textContent = '/blog/' + blog.slug;
      BlogForm.render($('common-fields'), blog);
      renderShare();
      renderCover();
    }
    showTab(v.canManageMembers ? 'requests' : 'info');
  }

  document.addEventListener('DOMContentLoaded', function () {
    document.querySelectorAll('.tabs__item').forEach(function (b) {
      b.addEventListener('click', function () {
        showTab(b.dataset.tab);
      });
    });
    $('info-form').addEventListener('submit', saveInfo);
    $('regenerate').addEventListener('click', regenerate);
    $('cover-file').addEventListener('change', uploadCover);
    $('cover-delete').addEventListener('click', deleteCover);
  });

  Layout.onUser(function (current) {
    if (!current) {
      window.location.href =
        '/login.html?next=' + encodeURIComponent(window.location.pathname);
      return;
    }
    load();
  });
})();
