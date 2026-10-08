/*
 * 블로그 관리 화면의 멤버·신고·블랙리스트 탭 (T083, BLG-11~13, D-03, D-71).
 * 블로그장·멤버 관리 권한 부블로그장에게만 탭이 보이고, 부블로그장 지정과 권한 체크박스는 블로그장에게만 보인다. 권한은 서버가 요청마다 다시 확인한다.
 */
(function () {
  'use strict';

  var blog = null;
  var ROLE = { OWNER: '블로그장', MANAGER: '부블로그장', MEMBER: '멤버' };
  var PERMISSIONS = [
    ['EDIT_INFO', '블로그 정보 수정'],
    ['MANAGE_MEMBERS', '멤버 관리'],
    ['MANAGE_POSTS', '글 관리']
  ];
  var DAYS = [['3', '3일'], ['14', '14일'], ['30', '30일'], ['', '영구']];
  var REASON = { SPAM: '스팸·홍보', ABUSE: '욕설·비방', ADULT: '음란물', ILLEGAL: '불법 정보', ETC: '기타' };
  var TARGET = { USER: '회원', POST: '글', COMMENT: '댓글', BLOG: '블로그' };
  var SANCTION = { WARNING: '경고', SUSPENSION: '정지', KICK: '강제 퇴장' };

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

  function date(value) {
    return window.BlogUi.formatDate(value);
  }

  function isPermanent(value) {
    return value && value.indexOf('9999') === 0;
  }

  function base() {
    return '/api/blogs/' + blog.id;
  }

  function me() {
    return window.Layout.user ? window.Layout.user.id : null;
  }

  // ---------- 제재 ----------

  async function sanction(kind, userId, nickname, reportId) {
    var titles = { warn: '경고', suspend: '정지', kick: '강제 퇴장' };
    var fields = [];
    if (kind === 'suspend') {
      fields.push({ name: 'days', label: '기간', type: 'select', options: DAYS });
    }
    fields.push({ name: 'reason', label: '사유', type: 'textarea', maxLength: 500, required: true });
    var desc = {
      warn: nickname + '님에게 경고를 보내요.',
      suspend: nickname + '님은 기간 동안 이 블로그에 들어올 수 없어요. 사유는 본인에게 보여요.',
      kick: nickname + '님을 멤버에서 빼고 블랙리스트에 올려요. 쓴 글은 "탈퇴한 계정"으로 바뀌어요. 되돌릴 수 없어요.'
    }[kind];
    var values = await FormDialog.open({
      title: titles[kind],
      desc: desc,
      submitLabel: titles[kind],
      danger: kind !== 'warn',
      fields: fields
    });
    if (!values) {
      return false;
    }
    var days = values.days ? Number(values.days) : null;
    try {
      if (reportId) {
        var resolution = { warn: 'WARN', suspend: 'SUSPEND', kick: 'KICK' }[kind];
        await Api.post(base() + '/reports/' + reportId + '/resolve', {
          resolution: resolution,
          days: days,
          reason: values.reason
        });
      } else {
        await Api.post(base() + '/members/' + userId + '/' + kind, { reason: values.reason, days: days });
      }
      Api.toast(titles[kind] + ' 처리했어요.');
      return true;
    } catch (e) {
      Api.showError(e);
      return false;
    }
  }

  // ---------- 멤버 ----------

  async function loadMembers() {
    try {
      var rows = await Api.get(base() + '/members');
      var list = $('members');
      list.replaceChildren.apply(list, rows.map(memberRow));
    } catch (e) {
      Api.showError(e);
    }
  }

  function memberRow(m) {
    var li = el('li', 'admin-list__item');
    var head = el('div', 'admin-list__head');
    head.appendChild(el('strong', '', m.nickname));
    head.appendChild(el('span', 'badge badge--muted', ROLE[m.role]));
    if (m.suspendedUntil) {
      head.appendChild(
        el('span', 'badge badge--danger', isPermanent(m.suspendedUntil) ? '영구 정지' : date(m.suspendedUntil) + '까지 정지'));
    }
    if (m.kickSuggested) {
      head.appendChild(el('span', 'badge badge--warn', '정지 ' + m.suspensionCount + '번 · 강제 퇴장 권유'));
    }
    li.appendChild(head);
    var sub = [m.email, m.phone, date(m.joinedAt) + ' 참여'];
    if (m.suspensionCount > 0 && !m.kickSuggested) {
      sub.push('정지 ' + m.suspensionCount + '번');
    }
    li.appendChild(el('p', 'admin-list__sub', sub.filter(Boolean).join(' · ')));

    var self = m.userId === me();
    var canSanction = m.role !== 'OWNER' && !self && (m.role !== 'MANAGER' || blog.viewer.owner);
    var actions = el('div', 'admin-list__actions');
    if (canSanction) {
      actions.appendChild(button('경고', async function () {
        if (await sanction('warn', m.userId, m.nickname)) {
          loadMembers();
        }
      }));
      if (m.suspendedUntil) {
        actions.appendChild(button('정지 해제', function () {
          release(m);
        }));
      } else {
        actions.appendChild(button('정지', async function () {
          if (await sanction('suspend', m.userId, m.nickname)) {
            loadMembers();
          }
        }));
      }
      actions.appendChild(button('강제 퇴장', async function () {
        if (await sanction('kick', m.userId, m.nickname)) {
          loadMembers();
        }
      }, 'button--danger'));
    }
    if (m.role !== 'OWNER') {
      actions.appendChild(button('제재 이력', function () {
        history(m, li);
      }, 'button--text'));
    }
    if (blog.viewer.owner && m.role !== 'OWNER') {
      actions.appendChild(button(m.role === 'MANAGER' ? '멤버로 내리기' : '부블로그장으로', function () {
        setManager(m, m.role !== 'MANAGER', []);
      }));
    }
    if (actions.children.length) {
      li.appendChild(actions);
    }
    if (blog.viewer.owner && m.role === 'MANAGER') {
      li.appendChild(permissionBoxes(m));
    }
    return li;
  }

  function permissionBoxes(m) {
    var box = el('div', 'admin-list__perms');
    var checks = PERMISSIONS.map(function (p) {
      var label = el('label');
      var input = el('input');
      input.type = 'checkbox';
      input.value = p[0];
      input.checked = m.permissions.indexOf(p[0]) >= 0;
      label.appendChild(input);
      label.appendChild(document.createTextNode(' ' + p[1]));
      box.appendChild(label);
      return input;
    });
    box.appendChild(button('권한 저장', function () {
      setManager(m, true, checks.filter(function (c) {
        return c.checked;
      }).map(function (c) {
        return c.value;
      }));
    }));
    return box;
  }

  async function setManager(m, manager, permissions) {
    if (!manager && !window.confirm(m.nickname + '님을 멤버로 내릴까요? 받은 권한도 모두 없어져요.')) {
      return;
    }
    try {
      await Api.put(base() + '/managers/' + m.userId, { manager: manager, permissions: permissions });
      Api.toast(manager ? '저장했어요.' : '멤버로 내렸어요.');
      loadMembers();
    } catch (e) {
      Api.showError(e);
    }
  }

  async function release(m) {
    if (!window.confirm(m.nickname + '님의 정지를 풀까요?')) {
      return;
    }
    try {
      await Api.post(base() + '/members/' + m.userId + '/release');
      Api.toast('정지를 풀었어요.');
      loadMembers();
    } catch (e) {
      Api.showError(e);
    }
  }

  async function history(m, li) {
    var old = li.querySelector('.admin-list__history');
    if (old) {
      old.remove();
      return;
    }
    try {
      var rows = await Api.get(base() + '/members/' + m.userId + '/sanctions');
      var list = el('ul', 'admin-list admin-list__history');
      if (!rows.length) {
        list.appendChild(el('li', 'admin-list__sub', '제재 이력이 없어요.'));
      }
      rows.forEach(function (s) {
        var text = date(s.createdAt) + ' ' + SANCTION[s.type];
        if (s.type === 'SUSPENSION') {
          text += s.suspendDays ? ' ' + s.suspendDays + '일' : ' 영구';
          if (s.releasedAt) {
            text += ' (해제됨)';
          }
        }
        text += ' · ' + (s.issuedBy || '') + ' · ' + s.reason;
        list.appendChild(el('li', 'admin-list__sub', text));
      });
      li.appendChild(list);
    } catch (e) {
      Api.showError(e);
    }
  }

  // ---------- 신고 ----------

  async function loadReports() {
    try {
      var rows = await Api.get(base() + '/reports');
      var list = $('reports');
      list.replaceChildren.apply(list, rows.map(reportRow));
      $('reports-empty').hidden = rows.length > 0;
    } catch (e) {
      Api.showError(e);
    }
  }

  function reportRow(r) {
    var li = el('li', 'admin-list__item');
    var head = el('div', 'admin-list__head');
    head.appendChild(el('span', 'badge badge--muted', TARGET[r.targetType]));
    head.appendChild(el('strong', '', REASON[r.reason]));
    li.appendChild(head);
    li.appendChild(el('p', 'admin-list__sub', (r.reporter || '탈퇴한 회원') + ' 신고 · ' + date(r.createdAt)));
    li.appendChild(el('div', 'admin-list__body', r.snapshot));
    if (r.detail) {
      li.appendChild(el('p', 'admin-list__sub', '신고 내용: ' + r.detail));
    }
    var actions = el('div', 'admin-list__actions');
    actions.appendChild(button('문제 없음', function () {
      noIssue(r);
    }));
    if (r.targetUserId && r.targetUserId !== me()) {
      ['warn', 'suspend', 'kick'].forEach(function (kind) {
        var label = { warn: '경고', suspend: '정지', kick: '강제 퇴장' }[kind];
        actions.appendChild(button(label, async function () {
          if (await sanction(kind, r.targetUserId, '대상', r.id)) {
            loadReports();
          }
        }, kind === 'kick' ? 'button--danger' : ''));
      });
    }
    li.appendChild(actions);
    return li;
  }

  async function noIssue(r) {
    try {
      await Api.post(base() + '/reports/' + r.id + '/resolve', { resolution: 'NO_ISSUE' });
      Api.toast('문제 없음으로 처리했어요.');
      loadReports();
    } catch (e) {
      Api.showError(e);
    }
  }

  // ---------- 블랙리스트 ----------

  async function loadBlacklist() {
    try {
      var view = await Api.get(base() + '/blacklist');
      var inquiries = $('inquiries');
      inquiries.replaceChildren.apply(inquiries, view.inquiries.map(inquiryRow));
      $('inquiries-empty').hidden = view.inquiries.length > 0;
      var records = $('blacklist');
      records.replaceChildren.apply(records, view.records.map(recordRow));
      $('blacklist-empty').hidden = view.records.length > 0;
    } catch (e) {
      Api.showError(e);
    }
  }

  function match(label, ok) {
    return el('span', 'badge ' + (ok ? 'badge--muted' : 'badge--warn'), label + (ok ? ' 일치' : ' 다름'));
  }

  function inquiryRow(i) {
    var li = el('li', 'admin-list__item');
    var head = el('div', 'admin-list__head');
    head.appendChild(el('strong', '', i.nickname || '회원'));
    head.appendChild(match('이름', i.nameMatch));
    head.appendChild(match('전화번호', i.phoneMatch));
    li.appendChild(head);
    li.appendChild(el('p', 'admin-list__sub', date(i.createdAt) + ' 문의 · 강제 퇴장된 사람과 비교한 결과예요.'));
    if (i.message) {
      li.appendChild(el('div', 'admin-list__body', i.message));
    }
    var actions = el('div', 'admin-list__actions');
    actions.appendChild(button('해제', function () {
      handleInquiry(i, 'release', '해제했어요.');
    }, 'button--primary'));
    actions.appendChild(button('거절', function () {
      handleInquiry(i, 'reject', '거절했어요.');
    }));
    li.appendChild(actions);
    return li;
  }

  async function handleInquiry(i, action, done) {
    if (action === 'release' && !window.confirm('블랙리스트에서 풀면 이 사람이 다시 참여 신청을 할 수 있어요. 풀까요?')) {
      return;
    }
    try {
      await Api.post(base() + '/blacklist-inquiries/' + i.id + '/' + action);
      Api.toast(done);
      loadBlacklist();
    } catch (e) {
      Api.showError(e);
    }
  }

  function recordRow(b) {
    var li = el('li', 'admin-list__item');
    var head = el('div', 'admin-list__head');
    head.appendChild(el('strong', '', b.nickname));
    if (b.releasedAt) {
      head.appendChild(el('span', 'badge badge--muted', date(b.releasedAt) + ' 해제'));
    }
    li.appendChild(head);
    li.appendChild(el('p', 'admin-list__sub', date(b.createdAt) + ' 등록 · ' + (b.registeredBy || '')));
    return li;
  }

  document.addEventListener('admin:tab', function (e) {
    blog = e.detail.blog;
    if (e.detail.name === 'members') {
      loadMembers();
    } else if (e.detail.name === 'reports') {
      loadReports();
    } else if (e.detail.name === 'blacklist') {
      loadBlacklist();
    }
  });
})();
