/*
 * 블로그 첫 화면의 글 목록 (T071, BRD-02, BRD-03, D-76). blog.js가 블로그를 읽으면 보내는 'blog:loaded'를 받아 시작한다.
 * 최신순, 번호 페이지, 10·20·30개씩. 첫 페이지 위에 공지를 따로 보여 준다. 제목에 마우스를 올리면 첫 사진을 보여 준다 (BRD-05).
 */
(function () {
  'use strict';

  var ui = window.BlogUi;
  var el = ui.el;
  var blog = null;
  var state = { page: 1, size: 10, category: '' };

  function $(id) {
    return document.getElementById(id);
  }

  function share() {
    return new URLSearchParams(window.location.search).get('share');
  }

  function postUrl(id) {
    var url = ui.blogUrl(blog.slug) + '/posts/' + id;
    return share() ? url + '?share=' + encodeURIComponent(share()) : url;
  }

  function formatDate(value) {
    var d = new Date(value);
    return d.getFullYear() + '.' + String(d.getMonth() + 1).padStart(2, '0') + '.' + String(d.getDate()).padStart(2, '0');
  }

  function row(p) {
    var li = el('li', 'post-row' + (p.notice ? ' post-row--notice' : ''));
    var a = el('a', 'post-row__title');
    a.href = postUrl(p.id);
    if (p.notice) {
      a.appendChild(el('span', 'badge', '공지'));
    }
    a.appendChild(el('span', '', p.title));
    if (p.commentCount > 0) {
      a.appendChild(el('span', 'post-row__comments', '[' + p.commentCount + ']'));
    }
    if (p.thumbnail) {
      var img = el('img', 'post-row__thumb');
      img.src = p.thumbnail;
      img.alt = '';
      img.loading = 'lazy';
      a.appendChild(img);
    }
    li.appendChild(a);
    var meta = [p.authorNickname, formatDate(p.createdAt), '조회 ' + p.viewCount];
    if (p.categoryName && !state.category) {
      meta.unshift(p.categoryName);
    }
    li.appendChild(el('span', 'post-row__meta', meta.join(' · ')));
    return li;
  }

  async function load() {
    var url =
      '/api/blogs/' + blog.id + '/posts?page=' + state.page + '&size=' + state.size +
      (state.category ? '&category=' + state.category : '') +
      (share() ? '&share=' + encodeURIComponent(share()) : '');
    try {
      var data = await Api.get(url);
      $('notices').replaceChildren.apply($('notices'), data.notices.map(row));
      $('posts').replaceChildren.apply($('posts'), data.page.items.map(row));
      $('posts-empty').hidden = data.page.items.length > 0 || data.notices.length > 0;
      ui.pager($('pager'), data.page.page, data.page.totalPages, function (page) {
        state.page = page;
        load();
        $('posts').scrollIntoView({ behavior: 'smooth', block: 'start' });
      });
    } catch (e) {
      Api.showError(e);
    }
  }

  async function loadCategories() {
    try {
      var url = '/api/blogs/' + blog.id + '/categories' + (share() ? '?share=' + encodeURIComponent(share()) : '');
      var rows = await Api.get(url);
      rows.forEach(function (c) {
        var opt = document.createElement('option');
        opt.value = String(c.id);
        opt.textContent = c.name;
        $('category').appendChild(opt);
      });
      $('category').hidden = rows.length === 0;
    } catch (e) {
      $('category').hidden = true;
    }
  }

  document.addEventListener('blog:loaded', function (event) {
    var first = blog === null;
    blog = event.detail;
    var v = blog.viewer;
    $('write').hidden = !v.role;
    $('write').href = '/post-edit.html?blog=' + encodeURIComponent(blog.slug);
    if (first) {
      $('category').addEventListener('change', function () {
        state.category = $('category').value;
        state.page = 1;
        load();
      });
      $('size').addEventListener('change', function () {
        state.size = Number($('size').value);
        state.page = 1;
        load();
      });
      loadCategories();
      load();
    }
  });
})();
