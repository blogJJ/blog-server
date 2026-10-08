/*
 * 글쓰기·수정 (T070, BRD-01, BRD-04, BRD-05, D-74).
 * - 새 글: /post-edit.html?blog={주소}, 수정: /post-edit.html?post={번호}
 * - 에디터는 Toast UI Editor(마크다운). 이미지는 넣는 즉시 /api/images로 올리고 받은 주소를 본문에 넣는다.
 * - 임시저장은 이 브라우저(localStorage)에만 한다. 올리면 지운다.
 */
(function () {
  'use strict';

  var MAX = 5000;
  var DRAFT_EVERY_MS = 5000;

  var params = new URLSearchParams(window.location.search);
  var editor = null;
  var blog = null; // { id, slug, name }
  var postId = params.get('post');
  var draftKey = null;
  var lastSaved = '';
  var submitting = false;

  function $(id) {
    return document.getElementById(id);
  }

  function message(text) {
    $('message').textContent = text || '';
  }

  function blocked(desc) {
    $('post-form').hidden = true;
    $('blocked').hidden = false;
    $('blocked-desc').textContent = desc;
  }

  function length(text) {
    return Array.from(text || '').length;
  }

  function updateCount() {
    var n = length(editor.getMarkdown());
    $('count').textContent = n.toLocaleString();
    $('count').parentNode.classList.toggle('is-over', n > MAX);
  }

  async function uploadImage(blob, callback) {
    if (blob.size > 3 * 1024 * 1024) {
      Api.toast('이미지는 한 장에 3MB까지 올릴 수 있어요.');
      return;
    }
    var form = new FormData();
    form.append('file', blob, blob.name || 'image.png');
    try {
      var res = await Api.post('/api/images', form);
      callback(res.url, '');
    } catch (e) {
      Api.showError(e);
    }
  }

  function snapshot() {
    return JSON.stringify({
      title: $('title').value,
      content: editor.getMarkdown(),
      tags: $('tags').value,
      categoryId: $('category').value
    });
  }

  function saveDraft() {
    var now = snapshot();
    if (now === lastSaved || submitting) {
      return;
    }
    try {
      localStorage.setItem(draftKey, JSON.stringify({ savedAt: Date.now(), data: now }));
      lastSaved = now;
      var d = new Date();
      $('draft-state').textContent = '이 브라우저에 임시저장 ' + d.getHours() + ':' + String(d.getMinutes()).padStart(2, '0');
    } catch (e) {
      // 저장 공간이 없으면 임시저장만 건너뛴다
    }
  }

  function restoreDraft() {
    var raw;
    try {
      raw = localStorage.getItem(draftKey);
    } catch (e) {
      return;
    }
    if (!raw) {
      return;
    }
    var draft = JSON.parse(raw);
    var when = new Date(draft.savedAt);
    if (!window.confirm(when.toLocaleString() + '에 임시저장한 글이 있어요. 불러올까요?')) {
      localStorage.removeItem(draftKey);
      return;
    }
    var data = JSON.parse(draft.data);
    $('title').value = data.title || '';
    editor.setMarkdown(data.content || '');
    $('tags').value = data.tags || '';
    if (data.categoryId) {
      $('category').value = data.categoryId;
    }
  }

  async function fillCategories(blogId, selected) {
    try {
      var rows = await Api.get('/api/blogs/' + blogId + '/categories');
      rows.forEach(function (c) {
        var opt = document.createElement('option');
        opt.value = String(c.id);
        opt.textContent = c.name;
        $('category').appendChild(opt);
      });
      if (selected) {
        $('category').value = String(selected);
      }
    } catch (e) {
      // 카테고리를 못 읽어도 글은 쓸 수 있다
    }
  }

  function createEditor(initial) {
    editor = new window.toastui.Editor({
      el: $('editor'),
      height: '480px',
      initialEditType: 'markdown',
      previewStyle: window.innerWidth < 768 ? 'tab' : 'vertical',
      language: 'ko-KR',
      initialValue: initial || '',
      usageStatistics: false,
      placeholder: '내용을 입력해 주세요.',
      hooks: { addImageBlobHook: uploadImage },
      events: { change: updateCount }
    });
    updateCount();
  }

  async function init() {
    try {
      if (postId) {
        var post = await Api.get('/api/posts/' + postId);
        if (!post.canEdit) {
          blocked('글은 쓴 사람만 고칠 수 있어요.');
          return;
        }
        blog = post.blog;
        var detail = await Api.get('/api/blogs/by-slug/' + encodeURIComponent(blog.slug));
        $('title').value = post.title;
        $('tags').value = post.tags.join(', ');
        $('notice').checked = post.notice;
        $('notice-wrap').hidden = !detail.viewer.canManagePosts;
        createEditor(post.markdown);
        await fillCategories(blog.id, post.categoryId);
        $('save').textContent = '수정하기';
        document.title = '글 수정 - 블로그';
      } else {
        var slug = params.get('blog') || '';
        var b = await Api.get('/api/blogs/by-slug/' + encodeURIComponent(slug));
        if (!b.viewer.role) {
          blocked('이 블로그 멤버만 글을 쓸 수 있어요.');
          return;
        }
        blog = { id: b.id, slug: b.slug, name: b.name };
        $('notice-wrap').hidden = !b.viewer.canManagePosts;
        createEditor('');
        await fillCategories(blog.id, null);
      }
    } catch (e) {
      blocked(e.message);
      return;
    }
    $('post-form').hidden = false;
    $('blog-name').textContent = blog.name;
    $('cancel').href = postId ? '/blog/' + blog.slug + '/posts/' + postId : '/blog/' + blog.slug;
    draftKey = 'post-draft:' + blog.id + ':' + (postId || 'new');
    lastSaved = snapshot();
    restoreDraft();
    setInterval(saveDraft, DRAFT_EVERY_MS);
  }

  async function submit(event) {
    event.preventDefault();
    message('');
    var form = {
      title: $('title').value.trim(),
      content: editor.getMarkdown(),
      categoryId: $('category').value ? Number($('category').value) : null,
      tags: BlogUi.splitTags($('tags').value),
      notice: $('notice').checked
    };
    if (!form.title || length(form.title) > 30) {
      message('제목은 1~30자로 입력해 주세요.');
      return;
    }
    if (!form.content.trim()) {
      message('본문을 입력해 주세요.');
      return;
    }
    if (length(form.content) > MAX) {
      message('본문은 5,000자까지 쓸 수 있어요.');
      return;
    }
    submitting = true;
    $('save').disabled = true;
    try {
      var res = postId
        ? await Api.put('/api/posts/' + postId, form)
        : await Api.post('/api/blogs/' + blog.id + '/posts', form);
      try {
        localStorage.removeItem(draftKey);
      } catch (ignored) {
        // 임시저장을 못 지워도 글은 올라갔다
      }
      window.location.href = '/blog/' + blog.slug + '/posts/' + res.id;
    } catch (e) {
      submitting = false;
      $('save').disabled = false;
      message(e.message);
    }
  }

  document.addEventListener('DOMContentLoaded', function () {
    $('post-form').addEventListener('submit', submit);
  });

  Layout.onUser(function (current) {
    if (!current) {
      window.location.href = '/login.html?next=' + encodeURIComponent(window.location.pathname + window.location.search);
      return;
    }
    init();
  });

  window.addEventListener('beforeunload', function (event) {
    if (editor && !submitting && snapshot() !== lastSaved) {
      saveDraft();
    }
  });
})();
