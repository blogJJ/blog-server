/*
 * "로그인 유지"를 안 한 로그인의 30분 만료 안내 (T040, SEC-04, D-62, D-72). layout.js 다음에 불러온다.
 *
 * - 로그인 상태가 확인되면(layout:user) 남은 시간을 서버에 묻는다. 이 확인은 X-Auto-Request라 연장으로 세지 않는다.
 * - 만료 5분 전에 "로그인을 연장할까요?" 창을 띄운다. 그 사이 다른 탭에서 활동해 늘어났을 수 있으니 띄우기 직전에 다시 묻는다.
 * - 글쓰기처럼 화면을 옮기지 않고 입력만 하는 동안에는 입력이 있으면 연장 요청을 보낸다(2분에 한 번까지).
 * - 끝나면 로그인 화면으로 보낸다.
 */
(function () {
  'use strict';

  var WARN_BEFORE_MS = 5 * 60 * 1000;
  var TYPING_EXTEND_EVERY_MS = 2 * 60 * 1000;

  var timer = null;
  var dialog = null;
  var lastTypingExtend = 0;
  var active = false;

  function clearTimer() {
    clearTimeout(timer);
    timer = null;
  }

  function schedule(info) {
    clearTimer();
    if (!info || info.rememberMe || info.expiresInSeconds == null) {
      active = false;
      return;
    }
    active = true;
    var leftMs = info.expiresInSeconds * 1000;
    if (leftMs <= 0) {
      expired();
    } else if (leftMs > WARN_BEFORE_MS) {
      timer = setTimeout(recheck, leftMs - WARN_BEFORE_MS);
    } else {
      showDialog(leftMs);
    }
  }

  async function fetchInfo() {
    return Api.get('/api/auth/session', { auto: true });
  }

  async function recheck() {
    try {
      var info = await fetchInfo();
      if (info.expiresInSeconds * 1000 > WARN_BEFORE_MS) {
        schedule(info); // 다른 탭에서 활동해 이미 늘어났다
        return;
      }
      schedule(info);
    } catch (e) {
      if (e.status === 401) {
        expired();
      }
    }
  }

  async function extend() {
    var info = await Api.post('/api/auth/extend', undefined, { auto: true });
    closeDialog();
    schedule(info);
  }

  function expired() {
    clearTimer();
    closeDialog();
    active = false;
    var here = window.location.pathname + window.location.search;
    window.location.href = '/login.html?expired=1&next=' + encodeURIComponent(here);
  }

  function closeDialog() {
    if (dialog) {
      dialog.remove();
      dialog = null;
    }
  }

  function showDialog(leftMs) {
    closeDialog();
    var backdrop = document.createElement('div');
    backdrop.className = 'dialog-backdrop';
    var box = document.createElement('div');
    box.className = 'dialog';
    box.setAttribute('role', 'alertdialog');
    box.setAttribute('aria-modal', 'true');
    box.setAttribute('aria-labelledby', 'session-dialog-title');

    var title = document.createElement('h2');
    title.id = 'session-dialog-title';
    title.textContent = '로그인을 연장할까요?';
    var desc = document.createElement('p');
    var minutes = Math.max(1, Math.ceil(leftMs / 60000));
    desc.textContent = '활동이 없어 약 ' + minutes + '분 뒤 로그아웃돼요.';

    var actions = document.createElement('div');
    actions.className = 'dialog__actions';
    var later = document.createElement('button');
    later.type = 'button';
    later.className = 'button';
    later.textContent = '로그아웃';
    later.addEventListener('click', async function () {
      try {
        await Api.post('/api/auth/logout', undefined, { noRefresh: true });
      } catch (ignored) {
        // 이미 끝났어도 메인으로 간다
      }
      window.location.href = '/';
    });
    var ok = document.createElement('button');
    ok.type = 'button';
    ok.className = 'button button--primary';
    ok.textContent = '연장하기';
    ok.addEventListener('click', function () {
      extend().catch(function (e) {
        if (e.status === 401) {
          expired();
        } else {
          Api.showError(e);
        }
      });
    });
    actions.append(later, ok);
    box.append(title, desc, actions);
    backdrop.appendChild(box);
    document.body.appendChild(backdrop);
    dialog = backdrop;
    ok.focus();

    timer = setTimeout(recheck, leftMs); // 그대로 두면 끝날 때 다시 확인하고 로그인 화면으로
  }

  // 화면을 옮기지 않고 입력만 하는 동안(글쓰기)에도 로그인이 끊기지 않게 한다
  document.addEventListener(
    'input',
    function () {
      var now = Date.now();
      if (!active || dialog || now - lastTypingExtend < TYPING_EXTEND_EVERY_MS) {
        return;
      }
      lastTypingExtend = now;
      Api.post('/api/auth/extend', undefined, { auto: true })
        .then(schedule)
        .catch(function () {
          // 실패해도 입력은 막지 않는다. 만료되면 타이머가 안내한다
        });
    },
    true
  );

  document.addEventListener('layout:user', function (e) {
    if (!e.detail) {
      return;
    }
    fetchInfo().then(schedule).catch(function () {
      // 남은 시간을 모르면 안내만 못 띄우고 화면은 그대로 쓴다
    });
  });

  window.Session = { extend: extend };
})();
