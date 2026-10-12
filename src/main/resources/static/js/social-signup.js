/*
 * 소셜 로그인 가입 마무리 (T157, D-128). 구글·깃허브 로그인은 됐지만 가입된 계정이 없을 때 온다.
 *
 * - 제공자·이메일·이름은 서버 세션에 있는 값을 읽어 보여 준다(GET /api/auth/social/pending). 10분이 지나면 처음부터 다시.
 * - 이름(미리 채움)·닉네임·필수 동의 두 개만 받는다. 서버가 일반 가입과 같은 규칙으로 다시 검사한다.
 * - 가입되면 로그인된 채로 메인으로 간다.
 */
(function () {
  'use strict';

  var NICKNAME = /^[가-힣A-Za-z0-9]{2,12}$/;

  var $ = function (id) {
    return document.getElementById(id);
  };

  function showMessage(text) {
    $('message').textContent = text || '';
  }

  function showExpired() {
    $('form').hidden = true;
    $('expired').hidden = false;
  }

  async function load() {
    try {
      var pending = await Api.get('/api/auth/social/pending');
      $('intro').textContent = pending.label + ' 계정으로 가입해요. 닉네임을 정하고 약관에 동의해 주세요.';
      $('email').textContent = pending.email;
      $('name').value = pending.name || '';
      $('form').hidden = false;
      ($('name').value ? $('nickname') : $('name')).focus();
    } catch (e) {
      if (e.code === 'SOCIAL_SIGNUP_EXPIRED') {
        showExpired();
      } else {
        showMessage(e.message);
      }
    }
  }

  async function submit(event) {
    event.preventDefault();
    var name = $('name').value.trim();
    var nickname = $('nickname').value.trim();
    if (!name) {
      showMessage('이름을 입력해 주세요.');
      return;
    }
    if (!NICKNAME.test(nickname)) {
      showMessage('닉네임은 2~12자 한글, 영문, 숫자로 입력해 주세요.');
      return;
    }
    if (!$('agree-terms').checked || !$('agree-privacy').checked) {
      showMessage('이용약관과 개인정보 수집·이용에 모두 동의해 주세요.');
      return;
    }
    var button = $('form').querySelector('button[type="submit"]');
    button.disabled = true;
    showMessage('');
    try {
      await Api.post('/api/auth/social/signup', {
        name: name,
        nickname: nickname,
        agreeTerms: true,
        agreePrivacy: true
      });
      window.location.href = '/';
    } catch (e) {
      button.disabled = false;
      if (e.code === 'SOCIAL_SIGNUP_EXPIRED') {
        showExpired();
      }
      showMessage(e.message);
    }
  }

  function syncAgreeAll() {
    $('agree-all').checked = $('agree-terms').checked && $('agree-privacy').checked;
  }

  function init() {
    $('form').addEventListener('submit', submit);
    $('agree-all').addEventListener('change', function () {
      $('agree-terms').checked = this.checked;
      $('agree-privacy').checked = this.checked;
    });
    $('agree-terms').addEventListener('change', syncAgreeAll);
    $('agree-privacy').addEventListener('change', syncAgreeAll);
    load();
  }

  document.addEventListener('layout:user', function (e) {
    if (e.detail) {
      window.location.replace('/'); // 이미 로그인했으면 필요 없다
    }
  });

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
