/*
 * 로그인 화면 (T039, USR-03, SEC-03, SEC-13, D-79, D-80).
 *
 * - "로그인 유지"를 체크하면 14일, 안 하면 마지막 활동부터 30분 (D-61, D-62).
 * - 서버가 CAPTCHA_REQUIRED를 주면(3번째 실패부터) Cloudflare Turnstile 위젯을 띄우고, 그 토큰을 함께 보낸다.
 *   토큰은 한 번만 쓸 수 있어서 실패할 때마다 위젯을 다시 그린다.
 * - 로그인하면 ?next=로 받은 같은 사이트 주소로, 없으면 메인으로 간다.
 */
(function () {
  'use strict';

  var TURNSTILE_SRC = 'https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit';

  var $ = function (id) {
    return document.getElementById(id);
  };
  var captcha = { widgetId: null, token: null, loading: null };

  function showMessage(text) {
    $('message').textContent = text || '';
  }

  // 다른 사이트로 보내는 주소(//evil.com, https://...)는 받지 않는다
  function nextUrl() {
    var next = new URLSearchParams(window.location.search).get('next');
    if (next && next.charAt(0) === '/' && next.charAt(1) !== '/' && next.charAt(1) !== '\\') {
      return next;
    }
    return '/';
  }

  function loadTurnstile() {
    if (window.turnstile) {
      return Promise.resolve(window.turnstile);
    }
    if (!captcha.loading) {
      captcha.loading = new Promise(function (resolve, reject) {
        var script = document.createElement('script');
        script.src = TURNSTILE_SRC;
        script.async = true;
        script.onload = function () {
          resolve(window.turnstile);
        };
        script.onerror = function () {
          captcha.loading = null;
          reject(new Error('turnstile'));
        };
        document.head.appendChild(script);
      });
    }
    return captcha.loading;
  }

  async function showCaptcha() {
    var box = $('captcha');
    box.hidden = false;
    captcha.token = null;
    try {
      var turnstile = await loadTurnstile();
      if (captcha.widgetId !== null) {
        turnstile.reset(captcha.widgetId);
        return;
      }
      var config = await Api.get('/api/auth/config');
      captcha.widgetId = turnstile.render(box, {
        sitekey: config.turnstileSiteKey,
        language: 'ko',
        callback: function (token) {
          captcha.token = token;
        },
        'expired-callback': function () {
          captcha.token = null;
        }
      });
    } catch (e) {
      showMessage('사람 확인을 불러오지 못했어요. 새로고침한 뒤 다시 시도해 주세요.');
    }
  }

  async function submit(event) {
    event.preventDefault();
    var email = $('email').value.trim();
    var password = $('password').value;
    if (!email || !password) {
      showMessage('이메일과 비밀번호를 입력해 주세요.');
      return;
    }
    if (!$('captcha').hidden && !captcha.token) {
      showMessage('사람 확인을 먼저 해 주세요.');
      return;
    }
    var button = $('login-form').querySelector('button[type="submit"]');
    button.disabled = true;
    showMessage('');
    try {
      await Api.post('/api/auth/login', {
        email: email,
        password: password,
        rememberMe: $('remember-me').checked,
        turnstileToken: captcha.token
      });
      window.location.href = nextUrl();
    } catch (e) {
      button.disabled = false;
      showMessage(e.message);
      $('password').value = '';
      $('password').focus();
      if (e.code === 'CAPTCHA_REQUIRED' || !$('captcha').hidden) {
        showCaptcha();
      }
    }
  }

  function init() {
    $('login-form').addEventListener('submit', submit);
    if (new URLSearchParams(window.location.search).get('expired')) {
      showMessage('30분 동안 활동이 없어 로그아웃됐어요. 다시 로그인해 주세요.');
    }
    $('email').focus();
  }

  document.addEventListener('layout:user', function (e) {
    if (e.detail) {
      window.location.replace(nextUrl());
    }
  });

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
