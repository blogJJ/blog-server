/*
 * 비밀번호 찾기·재설정 (T099, USR-06, SEC-05, D-25).
 * 1) 이메일(또는 이메일 찾기에서 넘어온 임시 토큰)로 인증번호를 받는다. 가입 여부와 관계없이 같은 문구가 나온다.
 * 2) 같은 화면에서 인증번호와 새 비밀번호를 두 번 넣는다. 바뀌면 로그인 화면으로 보낸다.
 */
(function () {
  'use strict';

  var $ = function (id) {
    return document.getElementById(id);
  };
  var found = null;

  function readFound() {
    try {
      var raw = sessionStorage.getItem('findToken');
      return raw ? JSON.parse(raw) : null;
    } catch (e) {
      return null;
    }
  }

  function target() {
    return found ? { findToken: found.token } : { email: $('email').value.trim() };
  }

  function message(text) {
    $('message').textContent = text || '';
  }

  async function sendCode(event) {
    event.preventDefault();
    message('');
    if (!found && !$('email').value.trim()) {
      message('이메일을 입력해 주세요.');
      return;
    }
    var button = $('send-code');
    button.disabled = true;
    try {
      var res = await Api.post('/api/auth/password/code', target());
      $('send-hint').textContent = res.message;
      $('send-hint').hidden = false;
      button.textContent = '인증번호 다시 받기';
      $('reset-form').hidden = false;
      $('code').focus();
    } catch (e) {
      message(e.message);
    } finally {
      button.disabled = false;
    }
  }

  async function reset(event) {
    event.preventDefault();
    message('');
    var code = $('code').value.trim();
    var password = $('password').value;
    if (!/^\d{6}$/.test(code)) {
      message('인증번호 6자리를 입력해 주세요.');
      return;
    }
    if (password !== $('password2').value) {
      message('새 비밀번호가 서로 달라요.');
      return;
    }
    var body = target();
    body.code = code;
    body.newPassword = password;
    try {
      await Api.post('/api/auth/password/reset', body);
      try {
        sessionStorage.removeItem('findToken');
      } catch (e) {
        // 무시
      }
      window.location.href = '/login.html?reset=1';
    } catch (e) {
      message(e.message);
    }
  }

  found = readFound();
  if (found) {
    $('email-field').hidden = true;
    $('found-target').hidden = false;
    $('found-target').textContent = found.email + ' 계정의 비밀번호를 다시 정해요.';
  }
  $('code-form').addEventListener('submit', sendCode);
  $('reset-form').addEventListener('submit', reset);
})();
