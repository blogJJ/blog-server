/*
 * 내 정보 (T099, USR-07, D-21, D-32). 닉네임·휴대폰 번호·소개·프로필 사진·비밀번호를 바꾼다. 이메일과 이름은 보여 주기만 한다.
 */
(function () {
  'use strict';

  var $ = function (id) {
    return document.getElementById(id);
  };

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
    $('me').hidden = false;
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
    var current = $('current-password').value;
    var next = $('new-password').value;
    if (!current || !next) {
      msg.textContent = '현재 비밀번호와 새 비밀번호를 입력해 주세요.';
      return;
    }
    if (next !== $('new-password2').value) {
      msg.textContent = '새 비밀번호가 서로 달라요.';
      return;
    }
    try {
      await Api.put('/api/me/password', { currentPassword: current, newPassword: next });
      $('password-form').reset();
      Api.toast('비밀번호를 바꿨어요. 다른 기기에서는 로그아웃됐어요.');
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
    } catch (e) {
      Api.showError(e);
    }
  });
  $('profile-form').addEventListener('submit', saveProfile);
  $('password-form').addEventListener('submit', changePassword);
  $('photo-file').addEventListener('change', uploadPhoto);
  $('photo-delete').addEventListener('click', deletePhoto);
})();
