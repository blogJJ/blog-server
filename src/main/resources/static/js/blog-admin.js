/*
 * 블로그 관리 화면 (T056): 참여 신청 탭(멤버 관리 권한), 정보 수정 탭(정보 수정 권한, T053), 공유 링크 새로 만들기(T046),
 * 카테고리 탭(정보 수정 권한, T066).
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
    document.querySelectorAll('.tabs__item').forEach(function (b) {
      var t = b.dataset.tab;
      $('panel-' + t).hidden = t !== name;
      b.classList.toggle('is-current', t === name);
    });
    // 멤버·신고·블랙리스트 탭은 blog-admin-members.js가 그린다
    document.dispatchEvent(new CustomEvent('admin:tab', { detail: { name: name, blog: blog } }));
    if (name === 'requests') {
      loadRequests();
    }
    if (name === 'categories') {
      loadCategories();
    }
  }

  var categories = [];

  async function loadCategories() {
    try {
      categories = await Api.get('/api/blogs/' + blog.id + '/categories');
      renderCategories();
    } catch (e) {
      Api.showError(e);
    }
  }

  function renderCategories() {
    var list = $('categories');
    list.replaceChildren.apply(list, categories.map(categoryRow));
    $('categories-empty').hidden = categories.length > 0;
  }

  function smallButton(label, onClick, disabled) {
    var b = el('button', 'button', label);
    b.type = 'button';
    b.disabled = !!disabled;
    b.addEventListener('click', onClick);
    return b;
  }

  function categoryRow(c, index) {
    var li = el('li', 'category-list__item');
    var input = el('input');
    input.type = 'text';
    input.maxLength = 20;
    input.value = c.name;
    input.setAttribute('aria-label', '카테고리 이름');
    li.appendChild(input);
    li.appendChild(smallButton('↑', function () {
      move(index, -1);
    }, index === 0));
    li.appendChild(smallButton('↓', function () {
      move(index, 1);
    }, index === categories.length - 1));
    li.appendChild(smallButton('이름 저장', function () {
      renameCategory(c, input.value);
    }));
    var del = smallButton('삭제', function () {
      deleteCategory(c);
    });
    del.classList.add('button--danger');
    li.appendChild(del);
    return li;
  }

  async function addCategory(event) {
    event.preventDefault();
    var name = $('category-name').value.trim();
    if (!name) {
      Api.toast('카테고리 이름을 입력해 주세요.');
      return;
    }
    try {
      await Api.post('/api/blogs/' + blog.id + '/categories', { name: name });
      $('category-name').value = '';
      loadCategories();
    } catch (e) {
      Api.showError(e);
    }
  }

  async function renameCategory(c, name) {
    try {
      await Api.put('/api/blogs/' + blog.id + '/categories/' + c.id, { name: name });
      Api.toast('이름을 바꿨어요.');
      loadCategories();
    } catch (e) {
      Api.showError(e);
    }
  }

  async function move(index, delta) {
    var ids = categories.map(function (c) {
      return c.id;
    });
    var other = index + delta;
    var tmp = ids[index];
    ids[index] = ids[other];
    ids[other] = tmp;
    try {
      await Api.put('/api/blogs/' + blog.id + '/categories', { ids: ids });
    } catch (e) {
      Api.showError(e);
    }
    loadCategories();
  }

  async function deleteCategory(c) {
    if (!window.confirm("'" + c.name + "' 카테고리를 지울까요? 안에 있던 글은 \"카테고리 없음\"이 돼요.")) {
      return;
    }
    try {
      await Api.delete('/api/blogs/' + blog.id + '/categories/' + c.id);
      loadCategories();
    } catch (e) {
      Api.showError(e);
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
    $('tab-categories').hidden = !v.canEditInfo;
    $('tab-members').hidden = !v.canManageMembers;
    $('tab-reports').hidden = !v.canManageMembers;
    $('tab-blacklist').hidden = !v.canManageMembers;
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
    $('category-form').addEventListener('submit', addCategory);
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
