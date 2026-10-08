/*
 * 블로그 만들기와 정보 수정이 같이 쓰는 입력 칸 (T054, T056): 이름·소개·태그·공개 범위·참여 방식.
 * BlogForm.render(container, blog)로 칸을 만들고 BlogForm.read(container)로 값을 읽는다.
 */
(function () {
  'use strict';

  var ui = window.BlogUi;
  var el = ui.el;

  function field(id, label, input, hint) {
    var wrap = el('div', 'field');
    var l = el('label', '', label);
    l.htmlFor = id;
    input.id = id;
    wrap.appendChild(l);
    wrap.appendChild(input);
    if (hint) {
      wrap.appendChild(el('p', 'field__hint', hint));
    }
    return wrap;
  }

  function radios(name, legend, options, current, hints) {
    var set = el('fieldset', 'field radio-group');
    set.appendChild(el('legend', '', legend));
    Object.keys(options).forEach(function (value) {
      var label = el('label', 'check');
      var input = document.createElement('input');
      input.type = 'radio';
      input.name = name;
      input.value = value;
      input.checked = value === current;
      label.appendChild(input);
      var text = el('span', '', options[value]);
      if (hints && hints[value]) {
        text.appendChild(el('small', 'radio-group__hint', ' ' + hints[value]));
      }
      label.appendChild(text);
      set.appendChild(label);
    });
    return set;
  }

  function render(container, blog) {
    blog = blog || {};
    var name = document.createElement('input');
    name.type = 'text';
    name.maxLength = 50;
    name.required = true;
    name.value = blog.name || '';

    var description = document.createElement('textarea');
    description.maxLength = 500;
    description.rows = 4;
    description.value = blog.description || '';

    var tags = document.createElement('input');
    tags.type = 'text';
    tags.value = (blog.tags || []).join(', ');

    container.replaceChildren(
      field('name', '이름', name, '1~50자. 다른 블로그와 같아도 돼요.'),
      field('description', '소개', description, '500자까지'),
      field('tags', '태그', tags, '쉼표로 나눠 10개까지. 한글·영문·숫자·_ 20자까지, 띄어쓰기는 _로 바뀌어요.'),
      radios('visibility', '공개 범위', ui.VISIBILITY, blog.visibility || 'PUBLIC', {
        PUBLIC: '목록·검색에 나와요',
        LINK_ONLY: '공유 링크를 받은 사람만 봐요',
        PRIVATE: '멤버만 봐요'
      }),
      radios('joinPolicy', '참여 방식', ui.JOIN_POLICY, blog.joinPolicy || 'OPEN', {
        OPEN: '신청하면 바로 멤버',
        APPROVAL: '블로그장이 승인'
      })
    );
  }

  function checked(container, name) {
    var input = container.querySelector('input[name="' + name + '"]:checked');
    return input ? input.value : null;
  }

  function read(container) {
    return {
      name: container.querySelector('#name').value.trim(),
      description: container.querySelector('#description').value.trim(),
      tags: ui.splitTags(container.querySelector('#tags').value),
      visibility: checked(container, 'visibility'),
      joinPolicy: checked(container, 'joinPolicy')
    };
  }

  /** 서버에 보내기 전 간단한 확인. 문제가 있으면 문구, 없으면 null */
  function check(form) {
    if (!form.name || form.name.length > 50) {
      return '블로그 이름은 1~50자로 입력해 주세요.';
    }
    if (form.tags.length > 10) {
      return '태그는 10개까지 달 수 있어요.';
    }
    return null;
  }

  window.BlogForm = { render: render, read: read, check: check };
})();
