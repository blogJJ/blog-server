/*
 * 내 정보 (T099, USR-07, D-21, D-32). 닉네임·휴대폰 번호·소개·프로필 사진·비밀번호를 바꾼다. 이메일과 이름은 보여 주기만 한다.
 * 차단한 회원 목록에서 차단을 푼다 (T104, SOC-05).
 * 구글·깃허브 연결·해제 (T157, D-128). 소셜 로그인으로만 가입해 비밀번호가 없으면 "현재 비밀번호" 없이 비밀번호를 만든다.
 */
(function () {
  'use strict';

  var $ = function (id) {
    return document.getElementById(id);
  };
  var hasPassword = true;

  function formatDate(value) {
    var d = new Date(value);
    return d.getFullYear() + '년 ' + (d.getMonth() + 1) + '월 ' + d.getDate() + '일';
  }

  function showPhoto(url) {
    $('photo').hidden = !url;
    $('photo-empty').hidden = !!url;
    $('photo-delete').hidden = !url;
    if (url) {
      $('photo').src = url;
    } else {
      $('photo').removeAttribute('src');
    }
  }

  function fill(me) {
    $('email').textContent = me.email;
    $('name').textContent = me.name;
    $('joined').textContent = formatDate(me.createdAt);
    $('nickname').value = me.nickname || '';
    $('phone').value = me.phone || '';
    $('bio').value = me.bio || '';
    showPhoto(me.profileImage);
    setHasPassword(me.hasPassword !== false);
    $('my-profile').href = '/profile.html?id=' + encodeURIComponent(me.id);
    $('my-profile').hidden = false;
    $('me').hidden = false;
  }

  async function loadBlocks() {
    try {
      var rows = await Api.get('/api/me/blocks');
      $('blocks').replaceChildren.apply($('blocks'), rows.map(blockRow));
      $('blocks-empty').hidden = rows.length > 0;
    } catch (e) {
      Api.showError(e);
    }
  }

  function blockRow(row) {
    var li = document.createElement('li');
    li.className = 'request-list__item';
    var who = document.createElement('a');
    who.href = '/profile.html?id=' + encodeURIComponent(row.userId);
    who.textContent = row.nickname;
    var unblock = document.createElement('button');
    unblock.type = 'button';
    unblock.className = 'button';
    unblock.textContent = '차단 해제';
    unblock.addEventListener('click', async function () {
      try {
        await Api.delete('/api/users/' + row.userId + '/block');
        Api.toast('차단을 풀었어요.');
        loadBlocks();
      } catch (e) {
        Api.showError(e);
      }
    });
    li.append(who, unblock);
    return li;
  }

  function setHasPassword(value) {
    hasPassword = value;
    $('password-title').textContent = value ? '비밀번호 변경' : '비밀번호 만들기';
    $('password-none').hidden = value;
    $('current-password-field').hidden = !value;
    $('password-form').querySelector('button[type="submit"]').textContent = value
      ? '비밀번호 바꾸기'
      : '비밀번호 만들기';
  }

  async function loadSocial() {
    try {
      var res = await Api.get('/api/me/social');
      var list = $('social-accounts');
      list.replaceChildren.apply(list, res.accounts.map(socialRow));
      $('social-section').hidden = res.accounts.length === 0;
      var linked = res.accounts.filter(function (a) {
        return a.linked;
      }).length;
      $('social-hint').hidden = res.hasPassword || linked !== 1;
    } catch (e) {
      Api.showError(e);
    }
  }

  function socialRow(account) {
    var li = document.createElement('li');
    li.className = 'request-list__item';
    var who = document.createElement('div');
    who.className = 'request-list__who';
    var name = document.createElement('strong');
    name.textContent = account.label;
    var status = document.createElement('span');
    status.className = 'social-accounts__status';
    status.textContent = account.linked
      ? '연결됨' + (account.email ? ' · ' + account.email : '')
      : '연결 안 됨';
    who.append(name, status);
    var button = document.createElement('button');
    button.type = 'button';
    button.className = 'button';
    if (account.linked) {
      button.textContent = '해제';
      button.addEventListener('click', function () {
        unlinkSocial(account);
      });
    } else {
      button.textContent = '연결';
      button.disabled = !account.enabled;
      button.addEventListener('click', function () {
        linkSocial(account);
      });
    }
    li.append(who, button);
    return li;
  }

  async function linkSocial(account) {
    try {
      var res = await Api.post('/api/me/social/' + account.provider.toLowerCase() + '/link');
      window.location.href = res.url;
    } catch (e) {
      Api.showError(e);
    }
  }

  async function unlinkSocial(account) {
    if (!window.confirm(account.label + ' 연결을 해제할까요?')) {
      return;
    }
    try {
      await Api.delete('/api/me/social/' + account.provider.toLowerCase());
      Api.toast(account.label + ' 연결을 해제했어요.');
      loadSocial();
    } catch (e) {
      Api.showError(e);
    }
  }

  // 연결하고 돌아왔을 때 결과를 알려 주고 주소에서 지운다
  function showSocialResult() {
    var params = new URLSearchParams(window.location.search);
    var error = params.get('social_error');
    if (params.get('social_linked')) {
      Api.toast('소셜 계정을 연결했어요.');
    } else if (error) {
      Api.toast(SocialLogin.errorMessage(error));
    } else {
      return;
    }
    window.history.replaceState(null, '', window.location.pathname);
  }

  async function saveProfile(event) {
    event.preventDefault();
    $('profile-message').textContent = '';
    try {
      var me = await Api.put('/api/me', {
        nickname: $('nickname').value.trim(),
        phone: $('phone').value.replace(/[\s-]/g, ''),
        bio: $('bio').value
      });
      fill(me);
      Api.toast('저장했어요.');
    } catch (e) {
      $('profile-message').textContent = e.message;
    }
  }

  async function changePassword(event) {
    event.preventDefault();
    var msg = $('password-message');
    msg.textContent = '';
    var current = hasPassword ? $('current-password').value : null;
    var next = $('new-password').value;
    if ((hasPassword && !current) || !next) {
      msg.textContent = hasPassword
        ? '현재 비밀번호와 새 비밀번호를 입력해 주세요.'
        : '새 비밀번호를 입력해 주세요.';
      return;
    }
    if (next !== $('new-password2').value) {
      msg.textContent = '새 비밀번호가 서로 달라요.';
      return;
    }
    try {
      await Api.put('/api/me/password', { currentPassword: current, newPassword: next });
      $('password-form').reset();
      Api.toast(
        hasPassword
          ? '비밀번호를 바꿨어요. 다른 기기에서는 로그아웃됐어요.'
          : '비밀번호를 만들었어요. 다른 기기에서는 로그아웃됐어요.'
      );
      setHasPassword(true);
      loadSocial();
    } catch (e) {
      msg.textContent = e.message;
    }
  }

  async function uploadPhoto() {
    var file = $('photo-file').files[0];
    $('photo-file').value = '';
    if (!file) {
      return;
    }
    if (file.size > 3 * 1024 * 1024) {
      Api.toast('사진은 3MB까지 올릴 수 있어요.');
      return;
    }
    var form = new FormData();
    form.append('file', file);
    try {
      var res = await Api.put('/api/me/profile-image', form);
      showPhoto(res.profileImage);
    } catch (e) {
      Api.showError(e);
    }
  }

  async function deletePhoto() {
    try {
      await Api.delete('/api/me/profile-image');
      showPhoto(null);
    } catch (e) {
      Api.showError(e);
    }
  }

  Layout.onUser(async function (current) {
    if (!current) {
      window.location.href = '/login.html?next=' + encodeURIComponent('/me.html');
      return;
    }
    try {
      fill(await Api.get('/api/me'));
      loadBlocks();
      loadSocial();
      showSocialResult();
    } catch (e) {
      Api.showError(e);
    }
  });
  $('profile-form').addEventListener('submit', saveProfile);
  $('password-form').addEventListener('submit', changePassword);
  $('photo-file').addEventListener('change', uploadPhoto);
  $('photo-delete').addEventListener('click', deletePhoto);
})();
