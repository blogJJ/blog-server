/*
 * 가입 화면 (T038, USR-01, USR-02). 서버가 마지막 단계에서 모든 값을 다시 검사하므로 여기 검사는 빨리 알려 주기 위한 것이다.
 *
 * 1단계: 인증번호 받기(1분에 한 번) → 6자리 확인. 메일 발송이 실패하면 바로 다시 받을 수 있다 (OPS-07).
 * 2단계: 비밀번호 두 번 입력.
 * 3단계: 이름·닉네임·전화번호·필수 동의 두 개 → 가입되면 로그인된 채로 메인으로 간다.
 */
(function () {
  'use strict';

  var PASSWORD = /^(?=.*[A-Za-z])(?=.*\d)(?=.*[^A-Za-z\d\s])\S{8,15}$/;
  var NICKNAME = /^[가-힣A-Za-z0-9]{2,12}$/;
  var PHONE = /^01\d{8,9}$/;
  var RESEND_SECONDS = 60;

  var $ = function (id) {
    return document.getElementById(id);
  };
  var state = { email: null, step: 1 };
  var countdown = null;

  function showMessage(text) {
    $('message').textContent = text || '';
  }

  function goTo(step) {
    state.step = step;
    [1, 2, 3].forEach(function (n) {
      $('step' + n).hidden = n !== step;
      var item = document.querySelector('.steps li[data-step="' + n + '"]');
      item.classList.toggle('is-current', n === step);
      item.classList.toggle('is-done', n < step);
    });
    showMessage('');
    var first = $('step' + step).querySelector('input');
    if (first) {
      first.focus();
    }
  }

  function startCountdown() {
    var button = $('send-code');
    var left = RESEND_SECONDS;
    clearInterval(countdown);
    button.disabled = true;
    button.textContent = '다시 받기 (' + left + ')';
    countdown = setInterval(function () {
      left -= 1;
      if (left <= 0) {
        clearInterval(countdown);
        button.disabled = false;
        button.textContent = '다시 받기';
        return;
      }
      button.textContent = '다시 받기 (' + left + ')';
    }, 1000);
  }

  async function sendCode() {
    var email = $('email').value.trim();
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
      showMessage('이메일 주소를 확인해 주세요.');
      return;
    }
    showMessage('');
    $('send-code').disabled = true;
    try {
      var res = await Api.post('/api/auth/signup/code', { email: email });
      state.email = email;
      $('send-hint').textContent = res.message;
      $('code-field').hidden = false;
      $('verify').hidden = false;
      $('code').value = '';
      $('code').focus();
      startCountdown();
    } catch (e) {
      // 메일 실패·요청 많음이면 번호가 저장되지 않았으니 버튼을 바로 다시 쓸 수 있다
      showMessage(e.message);
      if (e.code === 'CODE_RESEND_TOO_SOON') {
        startCountdown();
      } else {
        $('send-code').disabled = false;
      }
    }
  }

  async function verifyCode(event) {
    event.preventDefault();
    var code = $('code').value.trim();
    if (!/^\d{6}$/.test(code)) {
      showMessage('인증번호 6자리를 입력해 주세요.');
      return;
    }
    try {
      await Api.post('/api/auth/signup/verify', { email: state.email, code: code });
      clearInterval(countdown);
      $('email').readOnly = true;
      goTo(2);
    } catch (e) {
      showMessage(e.message);
    }
  }

  function checkPassword(event) {
    event.preventDefault();
    var password = $('password').value;
    if (!PASSWORD.test(password)) {
      showMessage('비밀번호는 8~15자로 영문, 숫자, 특수문자를 모두 넣어 주세요.');
      return;
    }
    if (password !== $('password2').value) {
      showMessage('비밀번호가 서로 달라요.');
      return;
    }
    goTo(3);
  }

  async function submitSignup(event) {
    event.preventDefault();
    var nickname = $('nickname').value.trim();
    var phone = $('phone').value.replace(/[\s-]/g, '');
    var name = $('name').value.trim();
    if (!name) {
      showMessage('이름을 입력해 주세요.');
      return;
    }
    if (!NICKNAME.test(nickname)) {
      showMessage('닉네임은 2~12자 한글, 영문, 숫자로 입력해 주세요.');
      return;
    }
    if (!PHONE.test(phone)) {
      showMessage('휴대폰 번호를 확인해 주세요.');
      return;
    }
    if (!$('agree-terms').checked || !$('agree-privacy').checked) {
      showMessage('이용약관과 개인정보 수집·이용에 모두 동의해 주세요.');
      return;
    }
    var button = $('step3').querySelector('button[type="submit"]');
    button.disabled = true;
    try {
      await Api.post('/api/auth/signup', {
        email: state.email,
        password: $('password').value,
        name: name,
        nickname: nickname,
        phone: phone,
        agreeTerms: true,
        agreePrivacy: true
      });
      window.location.href = '/';
    } catch (e) {
      button.disabled = false;
      if (e.code === 'EMAIL_NOT_VERIFIED' || e.code === 'DUPLICATE_EMAIL') {
        $('email').readOnly = false;
        $('send-code').disabled = false;
        $('send-code').textContent = '인증번호 받기';
        goTo(1);
      }
      showMessage(e.message);
    }
  }

  function syncAgreeAll() {
    $('agree-all').checked = $('agree-terms').checked && $('agree-privacy').checked;
  }

  function init() {
    $('send-code').addEventListener('click', sendCode);
    $('step1').addEventListener('submit', verifyCode);
    $('step2').addEventListener('submit', checkPassword);
    $('step3').addEventListener('submit', submitSignup);
    $('agree-all').addEventListener('change', function () {
      $('agree-terms').checked = this.checked;
      $('agree-privacy').checked = this.checked;
    });
    $('agree-terms').addEventListener('change', syncAgreeAll);
    $('agree-privacy').addEventListener('change', syncAgreeAll);
    // 이메일 칸에서 Enter를 누르면 인증번호를 받는다
    $('email').addEventListener('keydown', function (e) {
      if (e.key === 'Enter' && $('verify').hidden) {
        e.preventDefault();
        sendCode();
      }
    });
    goTo(1);
  }

  document.addEventListener('layout:user', function (e) {
    if (e.detail) {
      window.location.replace('/'); // 이미 로그인했으면 가입 화면이 필요 없다
    }
  });

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
