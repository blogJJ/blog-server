/*
 * 소셜 로그인 버튼과 안내 문구 (T157, D-128). 로그인·가입·내 정보 화면이 함께 쓴다.
 *
 * - 서버 설정(/api/auth/config의 socialProviders)에 있는 제공자 버튼만 보여 준다. 키를 넣지 않은 제공자는 숨는다.
 * - 버튼은 /oauth2/authorization/{google|github}로 가는 링크다. 로그인 화면이면 ?next=(같은 사이트 주소만)와 "로그인 유지" 체크를 함께 넘긴다.
 * - 실패해서 돌아오면 주소의 ?social_error= 값으로 문구를 고른다(SocialLogin.errorMessage).
 */
(function () {
  'use strict';

  var MESSAGES = {
    email_unverified:
      '소셜 계정의 이메일이 인증되지 않았거나 알 수 없어요. 그 서비스에서 이메일을 인증한 뒤 다시 시도하거나, 이메일로 가입해 주세요.',
    locked: '로그인을 5번 잘못해서 잠겼어요. 5분 뒤 다시 시도해 주세요.',
    already_linked: '이 소셜 계정은 이미 다른 회원에게 연결돼 있어요.',
    provider_taken: '이 계정에는 같은 서비스의 다른 계정이 이미 연결돼 있어요. 내 정보에서 연결을 해제한 뒤 다시 시도해 주세요.',
    rate_limited: '요청이 너무 많아요. 잠시 뒤 다시 시도해 주세요.',
    cancelled: '소셜 로그인을 취소했어요.',
    failed: '소셜 로그인을 하지 못했어요. 잠시 뒤 다시 시도해 주세요.'
  };

  var config = null;

  function enabledProviders() {
    if (!config) {
      config = Api.get('/api/auth/config')
        .then(function (c) {
          return (c && c.socialProviders) || [];
        })
        .catch(function () {
          return [];
        });
    }
    return config;
  }

  // login.js의 nextUrl과 같은 규칙: 다른 사이트 주소(//evil.com, https://...)는 받지 않는다
  function nextUrl() {
    var next = new URLSearchParams(window.location.search).get('next');
    if (next && next.charAt(0) === '/' && next.charAt(1) !== '/' && next.charAt(1) !== '\\') {
      return next;
    }
    return null;
  }

  function startUrl(provider) {
    var params = new URLSearchParams();
    var next = nextUrl();
    if (next) {
      params.set('next', next);
    }
    var remember = document.getElementById('remember-me');
    if (remember && remember.checked) {
      params.set('remember', '1');
    }
    var query = params.toString();
    return '/oauth2/authorization/' + provider + (query ? '?' + query : '');
  }

  async function initButtons() {
    var boxes = document.querySelectorAll('.social-login');
    if (!boxes.length) {
      return;
    }
    var enabled = await enabledProviders();
    boxes.forEach(function (box) {
      var any = false;
      box.querySelectorAll('[data-provider]').forEach(function (button) {
        var on = enabled.indexOf(button.getAttribute('data-provider')) !== -1;
        button.hidden = !on;
        any = any || on;
        button.addEventListener('click', function (event) {
          event.preventDefault();
          window.location.href = startUrl(button.getAttribute('data-provider'));
        });
      });
      box.hidden = !any;
    });
  }

  window.SocialLogin = {
    enabledProviders: enabledProviders,
    errorMessage: function (code) {
      return MESSAGES[code] || MESSAGES.failed;
    }
  };

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initButtons);
  } else {
    initButtons();
  }
})();
