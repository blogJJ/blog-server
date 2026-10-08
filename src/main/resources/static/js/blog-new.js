/* 블로그 만들기 (T054, BLG-01, BLG-10). 로그인하지 않았으면 로그인 화면으로 보낸다. */
(function () {
  'use strict';

  var SLUG = /^[a-z0-9](?:[a-z0-9-]{1,28})[a-z0-9]$/;

  function message(text) {
    document.getElementById('message').textContent = text || '';
  }

  function slugValue() {
    var input = document.getElementById('slug');
    input.value = input.value.trim().toLowerCase();
    return input.value;
  }

  async function checkSlug() {
    var hint = document.getElementById('slug-hint');
    var slug = slugValue();
    if (!SLUG.test(slug)) {
      hint.textContent = '영문 소문자, 숫자, -로 3~30자 입력해 주세요. -로 시작하거나 끝날 수 없어요.';
      return false;
    }
    try {
      var res = await Api.get('/api/blogs/slug-check?slug=' + encodeURIComponent(slug));
      hint.textContent = res.message;
      return res.available;
    } catch (e) {
      hint.textContent = e.message;
      return false;
    }
  }

  async function submit(event) {
    event.preventDefault();
    message('');
    var container = document.getElementById('common-fields');
    var form = BlogForm.read(container);
    var problem = BlogForm.check(form);
    if (!SLUG.test(slugValue())) {
      problem = '주소는 영문 소문자, 숫자, -로 3~30자 입력해 주세요.';
    }
    var coverFile = document.getElementById('cover-file').files[0];
    if (!problem && coverFile && coverFile.size > 3 * 1024 * 1024) {
      problem = '대표 이미지는 3MB까지 올릴 수 있어요.';
    }
    if (problem) {
      message(problem);
      return;
    }
    form.slug = slugValue();
    var button = event.submitter || document.querySelector('#blog-form [type=submit]');
    button.disabled = true;
    try {
      var created = await Api.post('/api/blogs', form);
      var cover = document.getElementById('cover-file').files[0];
      if (cover) {
        var data = new FormData();
        data.append('file', cover);
        try {
          await Api.put('/api/blogs/' + created.id + '/cover', data);
        } catch (e) {
          // 블로그는 만들어졌으니 넘어가고, 관리 화면에서 다시 올리게 한다
          alert('블로그는 만들었지만 대표 이미지를 올리지 못했어요. 블로그 관리에서 다시 올려 주세요. (' + e.message + ')');
        }
      }
      var url = BlogUi.blogUrl(created.slug);
      window.location.href = created.shareKey ? url + '?share=' + encodeURIComponent(created.shareKey) : url;
    } catch (e) {
      message(e.message);
      button.disabled = false;
    }
  }

  document.addEventListener('DOMContentLoaded', function () {
    BlogForm.render(document.getElementById('common-fields'));
    document.getElementById('slug-check').addEventListener('click', checkSlug);
    document.getElementById('blog-form').addEventListener('submit', submit);
  });

  Layout.onUser(function (user) {
    if (!user) {
      window.location.href = '/login.html?next=' + encodeURIComponent(location.pathname + location.search);
    } else if (user.role === 'ADMIN') {
      message('관리자 계정은 블로그를 만들 수 없어요.');
      document.querySelector('#blog-form [type=submit]').disabled = true;
    }
  });
})();
