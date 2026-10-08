/*
 * 블로그 첫 화면 /blog/{주소} (T055, BLG-01, BLG-04).
 * 일부 공개 블로그는 공유 링크의 ?share= 값을 API에 그대로 넘긴다. 참여 버튼은 보는 사람의 상태(viewer)로 정한다.
 */
(function () {
  'use strict';

  var ui = window.BlogUi;
  var blog = null;
  var user = undefined; // layout:user 전에는 undefined

  function $(id) {
    return document.getElementById(id);
  }

  function slugFromPath() {
    var m = window.location.pathname.match(/^\/blog\/([a-z0-9-]+)/);
    return m ? m[1] : '';
  }

  function shareKey() {
    return new URLSearchParams(window.location.search).get('share');
  }

  function blocked(title, desc) {
    $('blog').hidden = true;
    $('blocked').hidden = false;
    $('blocked-title').textContent = title;
    $('blocked-desc').textContent = desc || '';
    document.title = title + ' - 블로그';
  }

  function render() {
    var v = blog.viewer;
    document.title = blog.name + ' - 블로그';
    $('blocked').hidden = true;
    $('blog').hidden = false;
    $('name').textContent = blog.name;
    $('cover').hidden = !blog.coverImage;
    if (blog.coverImage) {
      $('cover').src = blog.coverImage;
    }
    $('description').textContent = blog.description || '';
    $('tags').replaceChildren(ui.tagList(blog.tags));
    $('visibility').hidden = blog.visibility === 'PUBLIC';
    $('visibility').textContent = ui.VISIBILITY[blog.visibility];
    $('meta').textContent =
      '블로그장 ' + (blog.ownerNickname || '') +
      ' · 멤버 ' + blog.memberCount +
      ' · 글 ' + blog.postCount +
      ' · ' + ui.JOIN_POLICY[blog.joinPolicy];

    $('notice-closing').hidden = blog.status !== 'CLOSING';
    if (blog.status === 'CLOSING') {
      $('notice-closing').textContent =
        '이 블로그는 ' + ui.formatDate(blog.closeScheduledAt) + '에 폐쇄될 예정이에요.';
    }
    $('notice-owner').hidden = !blog.ownerSuspendedUntil;
    if (blog.ownerSuspendedUntil) {
      $('notice-owner').textContent =
        '블로그장 정지 중이에요 (' + ui.formatDate(blog.ownerSuspendedUntil) + '까지).';
    }

    var canManage = v.canEditInfo || v.canManageMembers;
    $('manage').hidden = !canManage;
    $('manage').href = ui.blogUrl(blog.slug) + '/admin';
    $('cover-wrap').hidden = !blog.coverImage && !canManage;
    $('report-blog').hidden = !v.loggedIn || v.role === 'OWNER';
    $('cover-wrap').classList.toggle('has-cover', !!blog.coverImage);

    $('share-box').hidden = !blog.shareKey;
    if (blog.shareKey) {
      $('share-url').value =
        window.location.origin + ui.blogUrl(blog.slug) + '?share=' + encodeURIComponent(blog.shareKey);
    }
    renderJoin();
    document.dispatchEvent(new CustomEvent('blog:loaded', { detail: blog }));
  }

  function renderJoin() {
    var v = blog.viewer;
    var join = $('join');
    var cancel = $('cancel-join');
    var state = $('join-state');
    join.hidden = true;
    join.disabled = false;
    cancel.hidden = true;
    state.textContent = '';

    if (user && user.role === 'ADMIN') {
      return; // 메인 관리자는 참여하지 않는다 (D-90)
    }
    if (v.role) {
      state.textContent =
        v.role === 'OWNER' ? '내가 만든 블로그예요.' : ui.ROLE[v.role] + (v.role === 'MANAGER' ? '으로' : '로') + ' 참여 중';
      return;
    }
    if (v.joinRequestStatus === 'PENDING') {
      state.textContent = '참여 승인을 기다리고 있어요.';
      cancel.hidden = false;
      return;
    }
    join.hidden = false;
    join.textContent = blog.joinPolicy === 'APPROVAL' ? '참여 신청' : '참여하기';
    if (v.reapplyAt) {
      join.disabled = true;
      state.textContent = ui.formatDate(v.reapplyAt) + ' 이후 다시 신청할 수 있어요.';
    }
  }

  async function load() {
    var url = '/api/blogs/by-slug/' + encodeURIComponent(slugFromPath());
    if (shareKey()) {
      url += '?share=' + encodeURIComponent(shareKey());
    }
    try {
      blog = await Api.get(url);
      render();
    } catch (e) {
      if (e.status === 404) {
        blocked('블로그를 찾을 수 없어요', '주소가 맞는지 확인해 주세요.');
      } else if (e.status === 403) {
        blocked('블로그를 볼 수 없어요', e.message);
      } else {
        blocked('블로그를 불러오지 못했어요', e.message);
      }
    }
  }

  async function onJoin() {
    if (!blog.viewer.loggedIn) {
      window.location.href =
        '/login.html?next=' + encodeURIComponent(window.location.pathname + window.location.search);
      return;
    }
    $('join').disabled = true;
    var url = '/api/blogs/' + blog.id + '/join';
    if (shareKey()) {
      url += '?share=' + encodeURIComponent(shareKey());
    }
    try {
      var res = await Api.post(url);
      Api.toast(res.message);
      await load();
    } catch (e) {
      $('join').disabled = false;
      if (e.code === 'BLACKLISTED') {
        inquire(e.message);
        return;
      }
      Api.showError(e);
    }
  }

  /** 블랙리스트에 걸렸을 때 해제 문의를 남긴다 (BLG-12) */
  async function inquire(message) {
    var values = await FormDialog.open({
      title: '참여 신청을 할 수 없어요',
      desc: message + ' 전화번호 주인이 바뀌었다면 문의를 남겨 주세요. 블로그장이 확인하면 알림으로 알려 드려요.',
      submitLabel: '문의하기',
      fields: [{ name: 'message', label: '문의 내용 (선택)', type: 'textarea', maxLength: 500 }]
    });
    if (!values) {
      return;
    }
    try {
      await Api.post('/api/blogs/' + blog.id + '/blacklist-inquiries', { message: values.message || null });
      Api.toast('문의를 남겼어요.');
    } catch (e) {
      Api.showError(e);
    }
  }

  async function onCancel() {
    if (!window.confirm('참여 신청을 취소할까요?')) {
      return;
    }
    try {
      await Api.delete('/api/blogs/' + blog.id + '/join-requests/' + blog.viewer.joinRequestId);
      Api.toast('참여 신청을 취소했어요.');
      await load();
    } catch (e) {
      Api.showError(e);
    }
  }

  async function onCopy() {
    try {
      await navigator.clipboard.writeText($('share-url').value);
      Api.toast('링크를 복사했어요.');
    } catch (e) {
      $('share-url').select();
      Api.toast('링크를 선택했어요. 복사해 주세요.');
    }
  }

  document.addEventListener('DOMContentLoaded', function () {
    $('report-blog').addEventListener('click', function () {
      Report.open({ targetType: 'BLOG', targetId: blog.id });
    });
    $('join').addEventListener('click', onJoin);
    $('cancel-join').addEventListener('click', onCancel);
    $('copy-share').addEventListener('click', onCopy);
    load();
  });

  Layout.onUser(function (current) {
    user = current;
    if (blog) {
      renderJoin();
    }
  });
})();
