/*
 * 프로필 /profile.html?id= (T110, SOC-01~03, SOC-05).
 * 닉네임·사진·소개·팔로워 수, 운영하는 블로그·참여한 블로그(공개 블로그만), 팔로워·팔로잉 목록.
 * 팔로우는 누르면 하고 다시 누르면 취소한다. 차단하면 서로의 팔로우가 풀린다.
 * 회원이 쓴 값은 모두 textContent로 넣는다(innerHTML 금지, SEC-06).
 */
(function () {
  'use strict';

  var ui = window.BlogUi;
  var el = ui.el;
  var profile = null;
  var list = { name: 'followers', page: 1 };

  function $(id) {
    return document.getElementById(id);
  }

  function userId() {
    return new URLSearchParams(window.location.search).get('id');
  }

  function loginFirst() {
    window.location.href =
      '/login.html?next=' + encodeURIComponent(window.location.pathname + window.location.search);
  }

  function render() {
    document.title = profile.nickname + ' - 블로그';
    $('blocked').hidden = true;
    $('profile').hidden = false;
    $('nickname').textContent = profile.nickname;
    $('bio').textContent = profile.bio || '';
    $('photo').hidden = !profile.profileImage;
    $('photo-empty').hidden = !!profile.profileImage;
    if (profile.profileImage) {
      $('photo').src = profile.profileImage;
    }
    $('follower-count').textContent = String(profile.followerCount);
    renderButtons();
    blogs($('owned'), $('owned-empty'), profile.ownedBlogs);
    blogs($('joined'), $('joined-empty'), profile.joinedBlogs);
  }

  function renderButtons() {
    var v = profile.viewer;
    var user = Layout.user;
    $('edit').hidden = !v.me;
    // 비회원에게도 팔로우 버튼을 보여 주고, 누르면 로그인으로 보낸다. 메인 관리자도 팔로우한다 (D-122)
    $('follow').hidden = v.me || (!!user && !v.canFollow);
    $('follow').textContent = v.following ? '팔로잉' : '팔로우';
    $('follow').classList.toggle('button--primary', !v.following);
    $('follow').setAttribute('aria-pressed', v.following ? 'true' : 'false');
    $('block').hidden = !user || v.me;
    $('block').textContent = v.blocking ? '차단 해제' : '차단';
    $('report').hidden = !user || v.me;
  }

  function blogs(container, empty, items) {
    container.replaceChildren.apply(container, items.map(function (b) {
      return ui.card(b);
    }));
    empty.hidden = items.length > 0;
  }

  async function load() {
    if (!userId()) {
      $('blocked').hidden = false;
      return;
    }
    try {
      profile = await Api.get('/api/users/' + encodeURIComponent(userId()) + '/profile');
      render();
      loadPeople();
    } catch (e) {
      if (e.status === 404 || e.status === 400) {
        $('profile').hidden = true;
        $('blocked').hidden = false;
      } else {
        Api.showError(e);
      }
    }
  }

  async function onFollow() {
    if (!Layout.user) {
      loginFirst();
      return;
    }
    $('follow').disabled = true;
    try {
      var res = await Api.post('/api/users/' + profile.id + '/follow');
      profile.viewer.following = res.following;
      profile.followerCount = res.followerCount;
      $('follower-count').textContent = String(res.followerCount);
      renderButtons();
      if (list.name === 'followers') {
        loadPeople();
      }
    } catch (e) {
      Api.showError(e);
    } finally {
      $('follow').disabled = false;
    }
  }

  async function onBlock() {
    var blocking = profile.viewer.blocking;
    var question = blocking
      ? profile.nickname + '님의 차단을 풀까요?'
      : profile.nickname + '님을 차단할까요? 서로의 팔로우가 풀리고, 이 회원의 글과 댓글이 보이지 않아요. 같은 블로그 안에서는 보여요.';
    if (!window.confirm(question)) {
      return;
    }
    try {
      if (blocking) {
        await Api.delete('/api/users/' + profile.id + '/block');
        Api.toast('차단을 풀었어요.');
      } else {
        await Api.post('/api/users/' + profile.id + '/block');
        Api.toast('차단했어요.');
      }
      await load();
    } catch (e) {
      Api.showError(e);
    }
  }

  // ---------- 팔로워·팔로잉 목록 (SOC-02) ----------

  function person(row) {
    var li = el('li', 'request-list__item');
    var a = el('a', 'user-row');
    a.href = ui.profileUrl(row.id);
    if (row.profileImage) {
      var img = el('img', 'user-row__photo');
      img.src = row.profileImage;
      img.alt = '';
      a.appendChild(img);
    } else {
      a.appendChild(el('span', 'user-row__photo'));
    }
    var who = el('span', 'user-row__who');
    who.appendChild(el('strong', '', row.nickname));
    if (row.bio) {
      who.appendChild(el('span', 'user-row__bio', row.bio));
    }
    a.appendChild(who);
    li.appendChild(a);
    return li;
  }

  async function loadPeople() {
    document.querySelectorAll('#people-tabs .tabs__item').forEach(function (b) {
      b.classList.toggle('is-current', b.dataset.list === list.name);
    });
    try {
      var data = await Api.get('/api/users/' + profile.id + '/' + list.name + '?page=' + list.page);
      $('people').replaceChildren.apply($('people'), data.items.map(person));
      $('people-empty').hidden = data.items.length > 0;
      $('people-empty').textContent =
        list.name === 'followers' ? '아직 팔로워가 없어요.' : '아직 팔로우한 회원이 없어요.';
      ui.pager($('people-pager'), data.page, data.totalPages, function (page) {
        list.page = page;
        loadPeople();
      });
    } catch (e) {
      Api.showError(e);
    }
  }

  document.addEventListener('DOMContentLoaded', function () {
    $('follow').addEventListener('click', onFollow);
    $('block').addEventListener('click', onBlock);
    $('report').addEventListener('click', function () {
      Report.open({ targetType: 'USER', targetId: profile.id });
    });
    document.querySelectorAll('#people-tabs .tabs__item').forEach(function (b) {
      b.addEventListener('click', function () {
        list = { name: b.dataset.list, page: 1 };
        loadPeople();
      });
    });
  });

  Layout.onUser(function () {
    load(); // 로그인 상태를 안 뒤에 읽어야 버튼(팔로우·차단)이 맞다
  });
})();
