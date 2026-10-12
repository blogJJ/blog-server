/*
 * 메인 관리자 화면 (T126, ADM-01~08, BRD-10): 신고·회원·블로그·글·댓글·통계·공지·활동 기록 탭.
 * 주소(/admin/**)와 API(/api/admin/**)는 서버가 ADMIN만 열어 준다. 회원이 쓴 값은 textContent로만 넣는다 (SEC-06).
 */
(function () {
  'use strict';

  var ui = window.BlogUi;
  var el = ui.el;

  var DAYS = [['3', '3일'], ['14', '14일'], ['30', '30일'], ['', '영구']];
  var REASON = { SPAM: '스팸·홍보', ABUSE: '욕설·비방', ADULT: '음란물', ILLEGAL: '불법 정보', ETC: '기타' };
  var TARGET = { USER: '회원', POST: '글', COMMENT: '댓글', BLOG: '블로그', REPORT: '신고' };
  var KIND = {
    PROFILE: '메인 프로필 신고',
    OWNER: '블로그·블로그장 신고',
    IN_BLOG: '블로그장 정지 중인 블로그의 신고'
  };
  var STATUS = {
    PENDING: '대기 중',
    NO_ISSUE: '문제 없음',
    WARNED: '경고',
    SUSPENDED: '정지',
    KICKED: '강제 퇴장',
    OWNER_DEMOTED: '블로그장 강퇴',
    BLOG_CLOSED: '블로그 폐쇄',
    PROFILE_RESET: '프로필 초기화',
    ACCOUNT_SUSPENDED: '계정 정지'
  };
  // 신고 종류마다 같은 WARN이라도 뜻이 다르다
  var RESOLUTION = {
    PROFILE: { NO_ISSUE: '문제 없음', WARN: '계정 경고', PROFILE_RESET: '프로필 초기화', ACCOUNT_SUSPEND: '계정 정지' },
    OWNER: { NO_ISSUE: '문제 없음', WARN: '블로그장 경고', OWNER_DEMOTE: '블로그장 강퇴', BLOG_CLOSE: '블로그 폐쇄' },
    IN_BLOG: { NO_ISSUE: '문제 없음', WARN: '경고', SUSPEND: '정지', KICK: '강제 퇴장' }
  };
  var DANGER = ['ACCOUNT_SUSPEND', 'OWNER_DEMOTE', 'BLOG_CLOSE', 'KICK', 'SUSPEND', 'PROFILE_RESET'];
  var ACTION = {
    VIEW_PRIVATE_INFO: '개인정보 전체 보기',
    HIDE_BLOG: '블로그 숨김',
    UNHIDE_BLOG: '블로그 숨김 해제',
    CLOSE_BLOG: '블로그 강제 폐쇄',
    REVOKE_CLOSE_BLOG: '블로그 폐쇄 철회',
    HIDE_POST: '글 숨김',
    DELETE_POST: '글 삭제',
    RESTORE_POST: '글 복구',
    HIDE_COMMENT: '댓글 숨김',
    DELETE_COMMENT: '댓글 삭제',
    OWNER_WARN: '블로그장 경고',
    OWNER_DEMOTE: '블로그장 강퇴',
    ACCOUNT_WARN: '계정 경고',
    PROFILE_RESET: '프로필 초기화',
    ACCOUNT_SUSPEND: '계정 정지',
    ACCOUNT_RELEASE: '계정 정지 해제',
    MEMBER_WARN: '멤버 경고(대신 처리)',
    MEMBER_SUSPEND: '멤버 정지(대신 처리)',
    MEMBER_KICK: '멤버 강제 퇴장(대신 처리)',
    RESOLVE_REPORT: '신고 처리',
    POST_NOTICE: '공지 올림'
  };
  var BLOG_STATUS = { ACTIVE: '운영 중', CLOSING: '폐쇄 예정', CLOSED: '폐쇄' };
  var POST_STATUS = { PUBLISHED: '게시', HIDDEN: '숨김', DELETED: '삭제' };
  var COMMENT_STATUS = { ACTIVE: '게시', HIDDEN: '숨김', DELETED: '삭제' };
  var ROLE = { OWNER: '블로그장', MANAGER: '부블로그장', MEMBER: '멤버' };
  var SANCTION = { WARNING: '경고', PROFILE_RESET: '프로필 초기화', SUSPENSION: '정지', DEMOTION: '권한 박탈' };

  var state = { reports: 1, users: 1, blogs: 1, posts: 1, notices: 1, logs: 1 };

  function $(id) {
    return document.getElementById(id);
  }

  function button(label, onClick, extra) {
    var b = el('button', 'button' + (extra ? ' ' + extra : ''), label);
    b.type = 'button';
    b.addEventListener('click', onClick);
    return b;
  }

  function badge(text, extra) {
    return el('span', 'badge' + (extra ? ' ' + extra : ''), text);
  }

  function dateTime(value) {
    if (!value) {
      return '';
    }
    if (value.indexOf('9999') === 0) {
      return '영구';
    }
    var d = new Date(value);
    var hh = String(d.getHours()).padStart(2, '0');
    var mm = String(d.getMinutes()).padStart(2, '0');
    return ui.formatDate(value) + ' ' + hh + ':' + mm;
  }

  function errorText(e) {
    return e && e.message ? e.message : '처리하지 못했어요. 다시 시도해 주세요.';
  }

  /** 목록을 그린다. rows가 비면 빈 안내를 보인다 */
  function fill(name, data, render, reload) {
    var list = $(name);
    list.replaceChildren.apply(list, data.items.map(render));
    $(name + '-empty').hidden = data.items.length > 0;
    ui.pager($(name + '-pager'), data.page, data.totalPages, function (p) {
      state[name] = p;
      reload();
    });
  }

  /** 사유를 받아 POST한다. required면 사유가 있어야 한다 */
  async function act(options) {
    var fields = [];
    if (options.days) {
      fields.push({ name: 'days', label: '기간', type: 'select', options: DAYS });
    }
    if (options.reason !== false) {
      fields.push({
        name: 'reason',
        label: options.required ? '사유' : '사유 (선택)',
        type: 'textarea',
        maxLength: 500,
        required: !!options.required
      });
    }
    var values = await FormDialog.open({
      title: options.title,
      desc: options.desc,
      submitLabel: options.submitLabel || options.title,
      danger: !!options.danger,
      fields: fields
    });
    if (!values) {
      return false;
    }
    var body = Object.assign({}, options.body || {}, { reason: values.reason || null });
    if (options.days) {
      body.days = values.days ? Number(values.days) : null;
    }
    try {
      await Api.post(options.url, body);
      return true;
    } catch (e) {
      window.alert(errorText(e));
      return false;
    }
  }

  // ---------- 신고 ----------

  async function loadReports() {
    var status = $('report-status').value;
    try {
      var data = await Api.get('/api/admin/reports?status=' + status + '&page=' + state.reports);
      fill('reports', data, reportItem, loadReports);
    } catch (e) {
      $('reports-empty').hidden = false;
      $('reports-empty').textContent = errorText(e);
    }
  }

  function reportItem(r) {
    var li = el('li', 'admin-list__item');
    var head = el('div', 'admin-list__head');
    head.append(
      badge(KIND[r.kind] || r.kind),
      badge(TARGET[r.targetType] || r.targetType, 'badge--muted'),
      el('strong', '', REASON[r.reason] || r.reason)
    );
    if (r.status !== 'PENDING') {
      head.appendChild(badge(STATUS[r.status] || r.status, 'badge--warn'));
    }
    li.appendChild(head);
    var sub = '신고한 사람 ' + (r.reporter || '탈퇴한 계정') + ' · ' + dateTime(r.createdAt);
    if (r.blogName) {
      sub += ' · 블로그 ' + r.blogName;
    }
    if (r.handledBy) {
      sub += ' · 처리 ' + r.handledBy + ' ' + dateTime(r.handledAt);
    }
    li.appendChild(el('p', 'admin-list__sub', sub));
    li.appendChild(el('div', 'admin-list__body', r.snapshot));
    if (r.detail) {
      li.appendChild(el('p', 'admin-list__sub', '자세한 내용: ' + r.detail));
    }
    var links = el('div', 'admin-list__actions');
    if (r.blogSlug) {
      var a = el('a', 'button', '블로그 보기');
      a.href = ui.blogUrl(r.blogSlug);
      a.target = '_blank';
      a.rel = 'noopener';
      links.appendChild(a);
    }
    if (r.targetType === 'POST' && r.blogSlug) {
      var p = el('a', 'button', '글 보기');
      p.href = ui.blogUrl(r.blogSlug) + '/posts/' + r.targetId;
      p.target = '_blank';
      p.rel = 'noopener';
      links.appendChild(p);
    }
    if (r.targetUserId) {
      links.appendChild(
        button('대상 회원 보기', function () {
          showTab('users');
          openUser(r.targetUserId);
        })
      );
    }
    li.appendChild(links);

    if (r.resolutions && r.resolutions.length) {
      var actions = el('div', 'admin-list__actions');
      var labels = RESOLUTION[r.kind] || {};
      r.resolutions.forEach(function (res) {
        var danger = DANGER.indexOf(res) >= 0;
        actions.appendChild(
          button(
            labels[res] || res,
            async function () {
              var ok = await act({
                title: labels[res] || res,
                desc: resolutionDesc(r.kind, res),
                url: '/api/admin/reports/' + r.id + '/resolve',
                body: { resolution: res },
                days: res === 'SUSPEND' || res === 'ACCOUNT_SUSPEND',
                required: res !== 'NO_ISSUE',
                reason: res !== 'NO_ISSUE',
                danger: danger
              });
              if (ok) {
                loadReports();
              }
            },
            danger ? 'button--danger' : ''
          )
        );
      });
      li.appendChild(actions);
    }
    return li;
  }

  function resolutionDesc(kind, res) {
    var desc = {
      NO_ISSUE: '문제 없음으로 처리해요. 신고한 사람에게 결과가 가요.',
      WARN: {
        PROFILE: '대상 회원에게 계정 경고를 보내요.',
        OWNER: '블로그장에게 경고를 보내요. 이 블로그에서 최근 1년 안에 3번째면 블로그장 권한이 박탈돼요.',
        IN_BLOG: '블로그장 대신 그 블로그의 경고를 보내요.'
      }[kind],
      SUSPEND: '블로그장 대신 그 블로그에서 정지해요. 그 블로그에만 들어갈 수 없어요.',
      KICK: '블로그장 대신 그 블로그에서 강제 퇴장하고 블랙리스트에 올려요. 되돌릴 수 없어요.',
      OWNER_DEMOTE: '블로그장 권한을 박탈해요. 가장 먼저 부블로그장이 된 사람이 블로그장이 되고, 없으면 7일 뒤 폐쇄돼요.',
      BLOG_CLOSE: '블로그를 7일 뒤 04:00에 폐쇄해요. 멤버·구독자에게 알림이 가고, 그 안에 블로그장이 철회할 수 있어요.',
      PROFILE_RESET: '닉네임을 "회원"+회원 번호로 바꾸고 프로필 사진·소개를 지워요.',
      ACCOUNT_SUSPEND: '기간 동안 로그인과 읽기만 돼요. 영구 정지면 그 사람이 블로그장인 블로그마다 블로그장 권한이 박탈돼요.'
    }[res];
    return desc + (res === 'NO_ISSUE' ? '' : ' 사유는 대상에게 보여요.');
  }

  // ---------- 회원 ----------

  async function loadUsers() {
    var q = $('user-q').value.trim();
    try {
      var data = await Api.get(
        '/api/admin/users?q=' + encodeURIComponent(q) + '&page=' + state.users
      );
      fill('users', data, userItem, loadUsers);
    } catch (e) {
      window.alert(errorText(e));
    }
  }

  function userItem(u) {
    var li = el('li', 'admin-list__item');
    var head = el('div', 'admin-list__head');
    head.append(el('strong', '', u.nickname || '(닉네임 없음)'), el('span', '', '#' + u.id));
    if (u.role === 'ADMIN') {
      head.appendChild(badge('관리자'));
    }
    if (u.status === 'WITHDRAWN') {
      head.appendChild(badge('탈퇴', 'badge--muted'));
    }
    if (u.suspendedUntil && new Date(u.suspendedUntil) > new Date()) {
      head.appendChild(badge('정지 ' + dateTime(u.suspendedUntil), 'badge--warn'));
    }
    li.appendChild(head);
    li.appendChild(
      el(
        'p',
        'admin-list__sub',
        [u.name, u.email, u.phone, '가입 ' + ui.formatDate(u.createdAt)]
          .filter(Boolean)
          .join(' · ')
      )
    );
    var actions = el('div', 'admin-list__actions');
    actions.appendChild(
      button('상세', function () {
        openUser(u.id);
      })
    );
    li.appendChild(actions);
    return li;
  }

  async function openUser(id) {
    var box = $('user-detail');
    box.hidden = false;
    box.className = 'admin-detail';
    box.replaceChildren(el('p', '', '불러오는 중…'));
    var d;
    try {
      d = await Api.get('/api/admin/users/' + id);
    } catch (e) {
      box.replaceChildren(el('p', '', errorText(e)));
      return;
    }
    var u = d.user;
    var nodes = [];
    nodes.push(el('h2', '', (u.nickname || '(닉네임 없음)') + ' #' + u.id));
    var contact = el('p', 'admin-list__sub', '이메일 ' + (u.email || '-') + ' · 전화번호 ' + (u.phone || '-'));
    nodes.push(contact);
    var actions = el('div', 'admin-list__actions');
    actions.appendChild(
      button('전체 보기', async function () {
        if (!window.confirm('이메일·전화번호 원문을 봐요. 활동 기록에 남아요.')) {
          return;
        }
        try {
          var info = await Api.post('/api/admin/users/' + u.id + '/reveal');
          contact.textContent = '이메일 ' + (info.email || '-') + ' · 전화번호 ' + (info.phone || '-');
        } catch (e) {
          window.alert(errorText(e));
        }
      })
    );
    if (u.suspendedUntil && new Date(u.suspendedUntil) > new Date()) {
      actions.appendChild(
        button('계정 정지 해제', async function () {
          if (!window.confirm('계정 정지를 풀어요. 본인에게 알림이 가요.')) {
            return;
          }
          try {
            await Api.post('/api/admin/users/' + u.id + '/release');
            openUser(u.id);
            loadUsers();
          } catch (e) {
            window.alert(errorText(e));
          }
        })
      );
    }
    nodes.push(actions);
    var info = [
      '이름 ' + (u.name || '-'),
      '상태 ' + (u.status === 'WITHDRAWN' ? '탈퇴' : '정상'),
      '가입 ' + dateTime(u.createdAt)
    ];
    if (u.suspendedUntil) {
      info.push('계정 정지 ' + dateTime(u.suspendedUntil) + '까지');
    }
    nodes.push(el('p', 'admin-list__sub', info.join(' · ')));
    if (d.bio) {
      nodes.push(el('div', 'admin-list__body', d.bio));
    }

    nodes.push(el('h3', 'admin-subhead', '블로그'));
    var blogs = el('ul', 'admin-list');
    d.blogs.forEach(function (b) {
      var item = el('li', 'admin-list__item');
      item.appendChild(
        el('p', '', b.name + ' (' + (ROLE[b.role] || b.role) + ', ' + (BLOG_STATUS[b.status] || b.status) + ')')
      );
      blogs.appendChild(item);
    });
    nodes.push(d.blogs.length ? blogs : el('p', 'empty', '참여한 블로그가 없어요.'));

    nodes.push(el('h3', 'admin-subhead', '계정 제재 이력'));
    var account = el('ul', 'admin-list');
    d.accountSanctions.forEach(function (s) {
      var text = (SANCTION[s.type] || s.type) + ' · ' + dateTime(s.createdAt) + ' · ' + s.admin;
      if (s.type === 'SUSPENSION') {
        text += ' · ' + (s.suspendDays ? s.suspendDays + '일' : '영구');
      }
      if (s.releasedAt) {
        text += ' · 해제 ' + dateTime(s.releasedAt);
      }
      var item = el('li', 'admin-list__item');
      item.append(el('p', '', text), el('p', 'admin-list__sub', '사유: ' + s.reason));
      account.appendChild(item);
    });
    nodes.push(d.accountSanctions.length ? account : el('p', 'empty', '계정 제재 이력이 없어요.'));

    nodes.push(el('h3', 'admin-subhead', '블로그장 제재 이력'));
    var owner = el('ul', 'admin-list');
    d.ownerSanctions.forEach(function (s) {
      var item = el('li', 'admin-list__item');
      item.append(
        el('p', '', (SANCTION[s.type] || s.type) + ' · ' + s.blogName + ' · ' + dateTime(s.createdAt) + ' · ' + s.admin),
        el('p', 'admin-list__sub', '사유: ' + s.reason)
      );
      owner.appendChild(item);
    });
    nodes.push(d.ownerSanctions.length ? owner : el('p', 'empty', '블로그장 제재 이력이 없어요.'));
    box.replaceChildren.apply(box, nodes);
    box.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }

  // ---------- 블로그 ----------

  async function loadBlogs() {
    var q = $('blog-q').value.trim();
    try {
      var data = await Api.get(
        '/api/admin/blogs?q=' + encodeURIComponent(q) + '&page=' + state.blogs
      );
      fill('blogs', data, blogItem, loadBlogs);
    } catch (e) {
      window.alert(errorText(e));
    }
  }

  function blogItem(b) {
    var li = el('li', 'admin-list__item');
    var head = el('div', 'admin-list__head');
    var name = el('a', '', b.name);
    if (b.slug) {
      name.href = ui.blogUrl(b.slug);
      name.target = '_blank';
      name.rel = 'noopener';
    }
    head.append(el('strong', '', ''), badge(BLOG_STATUS[b.status] || b.status, 'badge--muted'));
    head.firstChild.appendChild(name);
    if (b.hidden) {
      head.appendChild(badge('숨김', 'badge--warn'));
    }
    if (b.ownerSuspendedUntil) {
      head.appendChild(badge('블로그장 정지 중', 'badge--warn'));
    }
    li.appendChild(head);
    var sub = [
      '/' + (b.slug || '-'),
      '블로그장 ' + (b.ownerNickname || '-'),
      '멤버 ' + b.memberCount,
      '글 ' + b.postCount,
      '만든 날 ' + ui.formatDate(b.createdAt)
    ];
    if (b.status === 'CLOSING') {
      sub.push('폐쇄 예정 ' + dateTime(b.closeScheduledAt));
    }
    li.appendChild(el('p', 'admin-list__sub', sub.join(' · ')));
    if (b.status === 'CLOSED') {
      return li;
    }
    var actions = el('div', 'admin-list__actions');
    var url = '/api/admin/blogs/' + b.id;
    function run(options) {
      return async function () {
        if (await act(options)) {
          loadBlogs();
        }
      };
    }
    actions.appendChild(
      b.hidden
        ? button('숨김 해제', run({ title: '숨김 해제', url: url + '/unhide' }))
        : button('숨기기', run({ title: '블로그 숨기기', desc: '목록·검색에서 빠지고 주소로 들어와도 보이지 않아요.', url: url + '/hide' }))
    );
    actions.appendChild(
      button('블로그장 정보', function () {
        showTab('users');
        openUser(b.ownerId);
      })
    );
    actions.appendChild(
      button(
        '블로그장 경고',
        run({
          title: '블로그장 경고',
          desc: b.ownerNickname + '님에게 경고를 보내요. 이 블로그에서 최근 1년 안에 3번째면 블로그장 권한이 박탈돼요.',
          url: url + '/owner/warn',
          required: true
        })
      )
    );
    actions.appendChild(
      button(
        '블로그장 강퇴',
        run({
          title: '블로그장 강퇴',
          desc: '블로그장 권한을 박탈해요. 가장 먼저 부블로그장이 된 사람이 블로그장이 되고, 없으면 7일 뒤 폐쇄돼요.',
          url: url + '/owner/demote',
          required: true,
          danger: true
        }),
        'button--danger'
      )
    );
    if (b.status === 'ACTIVE') {
      actions.appendChild(
        button(
          '강제 폐쇄',
          run({
            title: '강제 폐쇄',
            desc: '7일 뒤 04:00에 폐쇄해요. 멤버·구독자에게 알림이 가고, 그 안에 블로그장이 철회할 수 있어요.',
            url: url + '/close',
            required: true,
            danger: true
          }),
          'button--danger'
        )
      );
    }
    li.appendChild(actions);
    return li;
  }

  // ---------- 글·댓글 ----------

  async function loadPosts() {
    var q = $('post-q').value.trim();
    try {
      var data = await Api.get(
        '/api/admin/posts?q=' + encodeURIComponent(q) + '&page=' + state.posts
      );
      fill('posts', data, postItem, loadPosts);
    } catch (e) {
      window.alert(errorText(e));
    }
  }

  function postItem(p) {
    var li = el('li', 'admin-list__item');
    var head = el('div', 'admin-list__head');
    var title = el('a', '', p.title);
    if (p.blogSlug && p.status === 'PUBLISHED') {
      title.href = ui.blogUrl(p.blogSlug) + '/posts/' + p.id;
      title.target = '_blank';
      title.rel = 'noopener';
    }
    head.append(el('strong', '', ''), badge(POST_STATUS[p.status] || p.status, p.status === 'PUBLISHED' ? 'badge--muted' : 'badge--warn'));
    head.firstChild.appendChild(title);
    if (p.notice) {
      head.appendChild(badge('공지'));
    }
    li.appendChild(head);
    var sub = [p.blogName, p.authorNickname || '탈퇴한 계정', dateTime(p.createdAt), '댓글 ' + p.commentCount];
    if (p.deletedAt) {
      sub.push('삭제 ' + dateTime(p.deletedAt));
    }
    li.appendChild(el('p', 'admin-list__sub', sub.join(' · ')));
    var actions = el('div', 'admin-list__actions');
    var url = '/api/admin/posts/' + p.id;
    function run(options) {
      return async function () {
        if (await act(options)) {
          loadPosts();
        }
      };
    }
    if (p.status === 'PUBLISHED') {
      actions.appendChild(button('숨기기', run({ title: '글 숨기기', url: url + '/hide' })));
    }
    if (p.status !== 'DELETED') {
      actions.appendChild(
        button('삭제', run({ title: '글 삭제', desc: '작성자에게 알림이 가요. 30일 안에는 복구할 수 있어요.', url: url + '/delete', danger: true }), 'button--danger')
      );
    }
    if (p.status !== 'PUBLISHED') {
      actions.appendChild(button('복구', run({ title: '글 복구', desc: '다시 게시해요.', url: url + '/restore' })));
    }
    var comments = el('ul', 'admin-list');
    actions.appendChild(
      button('댓글 보기', function () {
        loadComments(p.id, comments);
      })
    );
    li.append(actions, comments);
    return li;
  }

  async function loadComments(postId, container) {
    var list;
    try {
      list = await Api.get('/api/admin/posts/' + postId + '/comments');
    } catch (e) {
      window.alert(errorText(e));
      return;
    }
    if (!list.length) {
      container.replaceChildren(el('li', 'empty', '댓글이 없어요.'));
      return;
    }
    container.replaceChildren.apply(
      container,
      list.map(function (c) {
        var li = el('li', 'admin-list__item');
        var head = el('div', 'admin-list__head');
        head.append(
          el('strong', '', (c.parentId ? '↳ ' : '') + (c.authorNickname || '탈퇴한 계정')),
          badge(COMMENT_STATUS[c.status] || c.status, c.status === 'ACTIVE' ? 'badge--muted' : 'badge--warn'),
          el('span', 'admin-list__sub', dateTime(c.createdAt))
        );
        li.append(head, el('div', 'admin-list__body', c.content));
        var actions = el('div', 'admin-list__actions');
        var url = '/api/admin/comments/' + c.id;
        function run(options) {
          return async function () {
            if (await act(options)) {
              loadComments(postId, container);
            }
          };
        }
        if (c.status === 'ACTIVE') {
          actions.appendChild(button('숨기기', run({ title: '댓글 숨기기', url: url + '/hide' })));
        }
        if (c.status !== 'DELETED') {
          actions.appendChild(
            button('삭제', run({ title: '댓글 삭제', desc: '작성자에게 알림이 가요.', url: url + '/delete', danger: true }), 'button--danger')
          );
        }
        li.appendChild(actions);
        return li;
      })
    );
  }

  // ---------- 통계 ----------

  async function loadStats() {
    var s;
    try {
      s = await Api.get('/api/admin/stats?days=' + $('stats-days').value);
    } catch (e) {
      $('stats-summary').textContent = errorText(e);
      return;
    }
    $('stats-summary').textContent = '회원 ' + s.userCount + '명 · 블로그 ' + s.blogCount + '개';
    var max = 1;
    s.daily.forEach(function (d) {
      max = Math.max(max, d.signups, d.posts);
    });
    function cell(value) {
      var td = el('td', '', String(value));
      var bar = el('span', 'admin-bar');
      bar.style.width = Math.round((value / max) * 120) + 'px';
      td.appendChild(bar);
      return td;
    }
    var body = $('stats-table').querySelector('tbody');
    body.replaceChildren.apply(
      body,
      s.daily
        .slice()
        .reverse()
        .map(function (d) {
          var tr = el('tr');
          tr.append(el('td', '', d.date), cell(d.signups), cell(d.posts));
          return tr;
        })
    );
  }

  // ---------- 공지 ----------

  async function loadNotices() {
    try {
      var data = await Api.get('/api/notices?page=' + state.notices);
      fill(
        'notices',
        data,
        function (n) {
          var li = el('li', 'admin-list__item');
          var a = el('a', '', n.title);
          a.href = '/notices.html?id=' + n.id;
          li.append(a, el('p', 'admin-list__sub', dateTime(n.createdAt)));
          return li;
        },
        loadNotices
      );
    } catch (e) {
      window.alert(errorText(e));
    }
  }

  async function submitNotice(event) {
    event.preventDefault();
    var message = $('notice-message');
    message.textContent = '';
    var title = $('notice-title').value.trim();
    var content = $('notice-content').value;
    if (!title || !content.trim()) {
      message.textContent = '제목과 본문을 입력해 주세요.';
      return;
    }
    if (!window.confirm('전체 회원에게 공지 알림이 가요. 올릴까요?')) {
      return;
    }
    try {
      await Api.post('/api/admin/notices', { title: title, content: content });
      $('notice-form').reset();
      state.notices = 1;
      loadNotices();
    } catch (e) {
      message.textContent = errorText(e);
    }
  }

  // ---------- 활동 기록 ----------

  async function loadLogs() {
    try {
      var data = await Api.get('/api/admin/logs?page=' + state.logs);
      fill(
        'logs',
        data,
        function (l) {
          var li = el('li', 'admin-list__item');
          var head = el('div', 'admin-list__head');
          head.append(
            el('strong', '', ACTION[l.action] || l.action),
            badge((TARGET[l.targetType] || l.targetType) + ' #' + l.targetId, 'badge--muted')
          );
          li.append(head, el('p', 'admin-list__sub', (l.admin || '') + ' · ' + dateTime(l.createdAt)));
          if (l.reason) {
            li.appendChild(el('div', 'admin-list__body', l.reason));
          }
          return li;
        },
        loadLogs
      );
    } catch (e) {
      window.alert(errorText(e));
    }
  }

  // ---------- 탭 ----------

  var LOADERS = {
    reports: loadReports,
    users: loadUsers,
    blogs: loadBlogs,
    posts: loadPosts,
    stats: loadStats,
    notices: loadNotices,
    logs: loadLogs
  };

  function showTab(name) {
    document.querySelectorAll('.tabs__item').forEach(function (b) {
      var t = b.dataset.tab;
      $('panel-' + t).hidden = t !== name;
      b.classList.toggle('is-current', t === name);
    });
    if (window.location.hash !== '#' + name) {
      history.replaceState(null, '', '#' + name);
    }
    LOADERS[name]();
  }

  function onSearch(formId, name, loader) {
    $(formId).addEventListener('submit', function (event) {
      event.preventDefault();
      state[name] = 1;
      loader();
    });
  }

  function init() {
    document.querySelectorAll('.tabs__item').forEach(function (b) {
      b.addEventListener('click', function () {
        showTab(b.dataset.tab);
      });
    });
    $('report-status').addEventListener('change', function () {
      state.reports = 1;
      loadReports();
    });
    $('stats-days').addEventListener('change', loadStats);
    $('notice-form').addEventListener('submit', submitNotice);
    onSearch('user-search', 'users', loadUsers);
    onSearch('blog-search', 'blogs', loadBlogs);
    onSearch('post-search', 'posts', loadPosts);
    var first = window.location.hash.replace('#', '');
    showTab(LOADERS[first] ? first : 'reports');
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
