/*
 * 블로그 관리 화면의 위임·폐쇄 탭 (T093, BLG-08, BLG-09, D-12, D-16, D-19, D-20).
 * 블로그장과 메인 관리자에게 탭이 보이고, 위임은 진짜 블로그장에게만 보인다 (D-122). 위임할 멤버가 없으면 폐쇄 버튼만, 폐쇄 예정이면 철회 버튼만 보인다.
 * 권한과 규칙(폐쇄 예정 중 위임 불가, 대기 요청은 하나)은 서버가 다시 확인한다.
 */
(function () {
  'use strict';

  var blog = null;

  function $(id) {
    return document.getElementById(id);
  }

  function el(tag, className, text) {
    return window.BlogUi.el(tag, className, text);
  }

  function base() {
    return '/api/blogs/' + blog.id;
  }

  function dateTime(value) {
    var d = new Date(value);
    return d.getFullYear() + '년 ' + (d.getMonth() + 1) + '월 ' + d.getDate() + '일 ' +
      String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0');
  }

  /** 지금 누르면 폐쇄될 시각: 오늘 + 7일 04:00 (서버가 같은 규칙으로 정한다) */
  function closeAtIfNow() {
    var d = new Date();
    d.setDate(d.getDate() + 7);
    d.setHours(4, 0, 0, 0);
    return d;
  }

  function isSuspended(m) {
    return !!m.suspendedUntil;
  }

  async function load() {
    $('closing-notice').hidden = true;
    $('transfer-box').hidden = true;
    $('close-box').hidden = true;
    $('revoke-box').hidden = true;

    if (blog.status === 'CLOSING') {
      $('closing-notice').hidden = false;
      $('closing-notice').textContent =
        '이 블로그는 ' + dateTime(blog.closeScheduledAt) + '에 폐쇄될 예정이에요. 그때까지 글쓰기와 댓글은 평소처럼 돼요.';
      $('revoke-box').hidden = false;
      return;
    }

    var pending = null;
    // 위임은 진짜 블로그장만. 메인 관리자는 폐쇄·철회만 한다 (D-122)
    if (blog.viewer.canTransfer) {
      var members;
      try {
        members = await Api.get(base() + '/members');
        pending = await Api.get(base() + '/transfers/pending');
      } catch (e) {
        Api.showError(e);
        return;
      }
      var candidates = members.filter(function (m) {
        return m.role !== 'OWNER' && !isSuspended(m);
      });
      renderTransfer(candidates, pending);
    }
    $('close-desc').textContent =
      '지금 누르면 ' + dateTime(closeAtIfNow()) + '에 폐쇄돼요. 그 전까지 철회할 수 있고, 멤버와 구독자에게 바로, 3일 전, 1일 전에 알림이 가요.' +
      (pending ? ' 대기 중인 위임 요청은 취소돼요.' : '') +
      ' 폐쇄 30일 뒤 글·사진·카테고리는 지워지고 주소는 다른 사람이 쓸 수 있게 돼요.';
    $('close-box').hidden = false;
  }

  function renderTransfer(candidates, pending) {
    // 위임할 멤버가 없으면 폐쇄 버튼만 보인다 (BLG-08)
    if (candidates.length === 0 && !pending) {
      return;
    }
    $('transfer-box').hidden = false;
    $('transfer-pending').hidden = !pending;
    $('transfer-form').hidden = !!pending;
    if (pending) {
      $('transfer-pending-text').textContent =
        pending.toNickname + '님에게 보낸 위임 요청이 응답을 기다리고 있어요 (' + dateTime(pending.expiresAt) + '까지).';
      return;
    }
    var select = $('transfer-target');
    select.replaceChildren.apply(
      select,
      candidates.map(function (m) {
        var option = el('option', '', m.nickname + (m.role === 'MANAGER' ? ' (부블로그장)' : ''));
        option.value = m.userId;
        return option;
      })
    );
  }

  async function requestTransfer(event) {
    event.preventDefault();
    var select = $('transfer-target');
    var name = select.options[select.selectedIndex] ? select.options[select.selectedIndex].textContent : '';
    if (!select.value || !window.confirm(name + '님에게 블로그장 위임을 요청할까요? 상대가 수락하면 바로 넘어가요.')) {
      return;
    }
    try {
      await Api.post(base() + '/transfers', { toUserId: Number(select.value) });
      Api.toast('위임 요청을 보냈어요. 상대가 수락하면 알림으로 알려 드려요.');
    } catch (e) {
      Api.showError(e);
    }
    load();
  }

  async function cancelTransfer() {
    if (!window.confirm('보낸 위임 요청을 취소할까요? 받는 멤버에게 알림이 가요.')) {
      return;
    }
    try {
      await Api.post(base() + '/transfers/pending/cancel');
      Api.toast('위임 요청을 취소했어요.');
    } catch (e) {
      Api.showError(e);
    }
    load();
  }

  async function closeBlog() {
    var values = await FormDialog.open({
      title: '블로그 폐쇄',
      desc: "'" + blog.name + "' 블로그를 " + dateTime(closeAtIfNow()) + '에 폐쇄해요. 7일 안에는 철회할 수 있어요. 확인하려면 블로그 이름을 입력해 주세요.',
      submitLabel: '폐쇄',
      danger: true,
      fields: [{ name: 'name', label: '블로그 이름', type: 'text', required: true }]
    });
    if (!values) {
      return;
    }
    if (values.name.trim() !== blog.name) {
      Api.toast('블로그 이름이 맞지 않아요.');
      return;
    }
    try {
      var res = await Api.post(base() + '/close');
      blog.status = res.status;
      blog.closeScheduledAt = res.closeScheduledAt;
      Api.toast('폐쇄 예정으로 바꿨어요. ' + dateTime(res.closeScheduledAt) + '에 폐쇄돼요.');
    } catch (e) {
      Api.showError(e);
    }
    load();
  }

  async function revokeClose() {
    if (!window.confirm('폐쇄를 철회할까요? 운영 중으로 돌아가요.')) {
      return;
    }
    try {
      var res = await Api.post(base() + '/close/revoke');
      blog.status = res.status;
      blog.closeScheduledAt = res.closeScheduledAt;
      Api.toast('폐쇄를 철회했어요.');
    } catch (e) {
      Api.showError(e);
    }
    load();
  }

  document.addEventListener('DOMContentLoaded', function () {
    $('transfer-form').addEventListener('submit', requestTransfer);
    $('transfer-cancel').addEventListener('click', cancelTransfer);
    $('close-blog').addEventListener('click', closeBlog);
    $('revoke-close').addEventListener('click', revokeClose);
  });

  document.addEventListener('admin:tab', function (e) {
    blog = e.detail.blog;
    if (e.detail.name === 'close') {
      load();
    }
  });
})();
