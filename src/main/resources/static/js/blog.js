/*
 * 블로그 첫 화면 /blog/{주소} (T055, BLG-01, BLG-04). 멤버는 블로그를 떠날 수 있다 (T091, BLG-07).
 * 일부 공개 블로그는 공유 링크의 ?share= 값을 API에 그대로 넘긴다. 참여 버튼은 보는 사람의 상태(viewer)로 정한다.
 * 구독하기 버튼(T110, SOC-01, D-50): 누르면 구독, 다시 누르면 취소. 일부 공개는 링크로 들어온 회원만 구독할 수 있어 share 값을 같이 보낸다.
 */
(function () {
  'use strict';

  var ui = window.BlogUi;
  var blog = null;
  var user = undefined; // layout:user 전에는 undefined
  // 폐쇄 예정 공지 앞에 붙는 사유 (ADM-02, ADM-07)
  var CLOSE_REASON = {
    OWNER_DEMOTED: '블로그장 권한 박탈로 인한 폐쇄 조치예요. ',
    ADMIN: '운영 정책에 따라 관리자가 폐쇄하기로 했어요. '
  };

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
      ' · 구독자 ' + blog.subscriberCount +
      ' · ' + ui.JOIN_POLICY[blog.joinPolicy];

    $('notice-closing').hidden = blog.status !== 'CLOSING';
    if (blog.status === 'CLOSING') {
      $('notice-closing').textContent =
        (CLOSE_REASON[blog.closeReason] || '') +
        '이 블로그는 ' + ui.formatDate(blog.closeScheduledAt) + '에 폐쇄될 예정이에요.';
    }
    // 블로그장 계정 정지 중 공지는 서버가 문구를 만든다 (T122, ADM-08, D-100)
    $('notice-owner').hidden = !blog.ownerNotice;
    $('notice-owner').textContent = blog.ownerNotice || '';

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
    renderSubscribe();
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
    $('leave').hidden = true;
    state.textContent = '';

    if (user && user.role === 'ADMIN') {
      return; // 메인 관리자는 참여하지 않는다 (D-90)
    }
    if (v.role) {
      state.textContent =
        v.role === 'OWNER' ? '내가 만든 블로그예요.' : ui.ROLE[v.role] + (v.role === 'MANAGER' ? '으로' : '로') + ' 참여 중';
      $('leave').hidden = v.role === 'OWNER'; // 블로그장은 위임·폐쇄해야 떠날 수 있다 (BLG-07)
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

  function renderSubscribe() {
    var v = blog.viewer;
    var button = $('subscribe');
    // 비회원에게도 보여 주고 누르면 로그인으로 보낸다. 내가 만든 블로그는 구독하지 않는다
    button.hidden = v.role === 'OWNER';
    button.textContent = v.subscribed ? '구독 중' : '구독하기';
    button.setAttribute('aria-pressed', v.subscribed ? 'true' : 'false');
  }

  async function onSubscribe() {
    if (!blog.viewer.loggedIn) {
      window.location.href =
        '/login.html?next=' + encodeURIComponent(window.location.pathname + window.location.search);
      return;
    }
    var url = '/api/blogs/' + blog.id + '/subscribe';
    if (shareKey()) {
      url += '?share=' + encodeURIComponent(shareKey());
    }
    $('subscribe').disabled = true;
    try {
      var res = await Api.post(url);
      blog.viewer.subscribed = res.subscribed;
      blog.subscriberCount = res.subscriberCount;
      Api.toast(res.subscribed ? '구독했어요. 새 글은 메인의 구독 탭에서 볼 수 있어요.' : '구독을 취소했어요.');
      render();
    } catch (e) {
      Api.showError(e);
    } finally {
      $('subscribe').disabled = false;
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

  /** 블로그 떠나기 (T091, BLG-07). 쓴 글은 "탈퇴한 계정"으로 바뀐다 (D-33) */
  async function onLeave() {
    if (!window.confirm("'" + blog.name + "' 블로그를 떠날까요? 이 블로그에 쓴 글은 작성자가 \"탈퇴한 계정\"으로 바뀌어요.")) {
      return;
    }
    try {
      await Api.post('/api/blogs/' + blog.id + '/leave');
      Api.toast('블로그를 떠났어요.');
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
    $('subscribe').addEventListener('click', onSubscribe);
    $('cancel-join').addEventListener('click', onCancel);
    $('leave').addEventListener('click', onLeave);
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
