/*
 * 이메일 찾기 (T099, USR-08, D-23, D-26). 이름과 휴대폰 번호로 찾고, 가린 이메일과 가입일을 보여 준다.
 * [비밀번호 재설정]은 서버가 준 10분짜리 임시 토큰을 sessionStorage에 담아 비밀번호 찾기 화면으로 넘긴다(주소에 남기지 않는다).
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

  function row(found) {
    var li = document.createElement('li');
    li.className = 'found-list__item';
    var text = document.createElement('div');
    var email = document.createElement('strong');
    email.textContent = found.email;
    var joined = document.createElement('span');
    joined.className = 'found-list__date';
    joined.textContent = formatDate(found.joinedAt) + ' 가입';
    text.append(email, joined);
    var reset = document.createElement('button');
    reset.type = 'button';
    reset.className = 'button';
    reset.textContent = '비밀번호 재설정';
    reset.addEventListener('click', function () {
      try {
        sessionStorage.setItem('findToken', JSON.stringify({ token: found.token, email: found.email }));
      } catch (e) {
        // 저장할 수 없으면 이메일을 직접 입력하게 한다
      }
      window.location.href = '/find-password.html';
    });
    li.append(text, reset);
    return li;
  }

  async function submit(event) {
    event.preventDefault();
    $('message').textContent = '';
    var name = $('name').value.trim();
    var phone = $('phone').value.replace(/[\s-]/g, '');
    if (!name || !phone) {
      $('message').textContent = '이름과 휴대폰 번호를 입력해 주세요.';
      return;
    }
    try {
      var found = await Api.post('/api/auth/find-email', { name: name, phone: phone });
      $('result').hidden = false;
      $('result-hint').textContent = found.length
        ? '이 정보로 가입한 계정이에요.'
        : '일치하는 계정이 없어요. 이름과 번호를 다시 확인해 주세요.';
      $('found').replaceChildren.apply($('found'), found.map(row));
    } catch (e) {
      $('message').textContent = e.message;
    }
  }

  $('find-form').addEventListener('submit', submit);
})();
