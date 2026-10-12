/*
 * 밝은/어두운 모드 (D-117). 처음에는 기기 설정을 따르고, 상단 메뉴의 버튼으로 바꾸면 이 브라우저에 기억한다.
 * 화면이 한 번 밝게 번쩍이지 않도록 <head>에서 defer 없이 가장 먼저 불러온다. 버튼은 layout.js가 만든다.
 */
(function () {
  'use strict';

  var KEY = 'theme';
  var root = document.documentElement;
  var media = window.matchMedia ? window.matchMedia('(prefers-color-scheme: dark)') : null;

  function saved() {
    try {
      var v = window.localStorage.getItem(KEY);
      return v === 'light' || v === 'dark' ? v : null;
    } catch (ignored) {
      return null; // 사생활 보호 모드 등에서 저장소를 못 쓰면 기기 설정만 따른다
    }
  }

  function current() {
    return saved() || (media && media.matches ? 'dark' : 'light');
  }

  function apply() {
    var s = saved();
    if (s) {
      root.setAttribute('data-theme', s);
    } else {
      root.removeAttribute('data-theme');
    }
    document.dispatchEvent(new CustomEvent('theme:change', { detail: current() }));
  }

  function toggle() {
    var next = current() === 'dark' ? 'light' : 'dark';
    try {
      window.localStorage.setItem(KEY, next);
    } catch (ignored) {
      // 저장이 안 되면 이번 화면에서만 바뀐다
    }
    root.setAttribute('data-theme', next);
    document.dispatchEvent(new CustomEvent('theme:change', { detail: next }));
  }

  if (media && media.addEventListener) {
    media.addEventListener('change', apply);
  }
  apply();

  window.Theme = { current: current, toggle: toggle };
})();
