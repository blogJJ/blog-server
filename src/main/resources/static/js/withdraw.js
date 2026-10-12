/*
 * 회원 탈퇴 (T094, USR-05, D-05, D-15, D-18). 블로그장인 운영 중·폐쇄 예정 블로그가 있으면 그 목록과 위임·폐쇄 방법을 보여 주고,
 * 없으면 비밀번호를 다시 확인해 탈퇴한다. 막는 규칙은 서버가 다시 확인한다.
 */
(function () {
  'use strict';

  var STATUS = { ACTIVE: '운영 중', CLOSING: '폐쇄 예정' };

  function $(id) {
    return document.getElementById(id);
  }

  function el(tag, className, text) {
    var node = document.createElement(tag);
    if (className) {
      node.className = className;
    }
    if (text !== undefined && text !== null) {
      node.textContent = text;
    }
    return node;
  }

  function blogRow(b) {
    var li = el('li', 'admin-list__item');
    var head = el('div', 'admin-list__head');
    head.appendChild(el('strong', '', b.name));
    head.appendChild(el('span', b.status === 'CLOSING' ? 'badge badge--danger' : 'badge', STATUS[b.status] || b.status));
    li.appendChild(head);
    if (b.slug && b.status === 'ACTIVE') {
      var actions = el('div', 'admin-list__actions');
      var link = el('a', 'button', '위임·폐쇄하러 가기');
      link.href = '/blog/' + b.slug + '/admin';
      actions.appendChild(link);
      li.appendChild(actions);
    }
    return li;
  }

  async function check() {
    try {
      var res = await Api.get('/api/me/withdraw');
      $('withdraw').hidden = false;
      $('blocked').hidden = res.allowed;
      $('withdraw-form').hidden = !res.allowed;
      if (!res.allowed) {
        $('guide').textContent = res.guide;
        var list = $('blogs');
        list.replaceChildren.apply(list, res.blogs.map(blogRow));
      }
    } catch (e) {
      Api.showError(e);
    }
  }

  async function submit(event) {
    event.preventDefault();
    var message = $('message');
    message.textContent = '';
    var password = $('password').value;
    if (!password) {
      message.textContent = '비밀번호를 입력해 주세요.';
      return;
    }
    if (!$('agree').checked) {
      message.textContent = '안내를 확인하고 체크해 주세요.';
      return;
    }
    if (!window.confirm('정말 탈퇴할까요? 되돌릴 수 없어요.')) {
      return;
    }
    try {
      await Api.post('/api/me/withdraw', { password: password });
      window.alert('탈퇴했어요. 그동안 이용해 주셔서 고마워요.');
      window.location.href = '/';
    } catch (e) {
      if (e.code === 'WITHDRAW_BLOCKED') {
        check(); // 그 사이 블로그장이 된 블로그가 생겼으면 목록을 다시 보여 준다
        return;
      }
      message.textContent = e.message;
    }
  }

  Layout.onUser(function (current) {
    if (!current) {
      window.location.href = '/login.html?next=' + encodeURIComponent('/withdraw.html');
      return;
    }
    check();
  });
  $('withdraw-form').addEventListener('submit', submit);
})();
