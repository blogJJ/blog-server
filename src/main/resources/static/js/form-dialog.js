/*
 * 입력 창 (T083, T084). 신고하기·멤버 제재·블랙리스트 문의처럼 사유를 받는 곳에서 쓴다.
 *
 * 사용: const values = await FormDialog.open({ title, desc, submitLabel, fields: [
 *   { name: 'reason', label: '사유', type: 'textarea', maxLength: 500, required: true },
 *   { name: 'days', label: '기간', type: 'select', options: [['3', '3일'], ['', '영구']] } ] });
 * 취소하면 null. 회원이 쓴 값은 textContent·value로만 다룬다 (SEC-06).
 */
(function () {
  'use strict';

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

  function open(options) {
    return new Promise(function (resolve) {
      var backdrop = el('div', 'dialog-backdrop');
      var form = el('form', 'dialog dialog--form');
      form.noValidate = true;
      form.setAttribute('role', 'dialog');
      form.setAttribute('aria-modal', 'true');
      form.appendChild(el('h2', '', options.title));
      if (options.desc) {
        form.appendChild(el('p', '', options.desc));
      }
      var inputs = {};
      (options.fields || []).forEach(function (f) {
        var label = el('label', 'field');
        label.appendChild(el('span', 'field__label', f.label));
        var input;
        if (f.type === 'select') {
          input = el('select');
          f.options.forEach(function (o) {
            var opt = el('option', '', o[1]);
            opt.value = o[0];
            input.appendChild(opt);
          });
        } else {
          input = el(f.type === 'textarea' ? 'textarea' : 'input');
          if (f.type === 'textarea') {
            input.rows = 3;
          } else {
            input.type = 'text';
          }
          if (f.maxLength) {
            input.maxLength = f.maxLength;
          }
          if (f.placeholder) {
            input.placeholder = f.placeholder;
          }
        }
        input.name = f.name;
        inputs[f.name] = { input: input, field: f };
        label.appendChild(input);
        form.appendChild(label);
      });
      var message = el('p', 'form-message form-message--error');
      message.setAttribute('role', 'alert');
      form.appendChild(message);
      var actions = el('div', 'dialog__actions');
      var cancel = el('button', 'button', '취소');
      cancel.type = 'button';
      var submit = el('button', 'button ' + (options.danger ? 'button--danger' : 'button--primary'), options.submitLabel || '확인');
      submit.type = 'submit';
      actions.appendChild(cancel);
      actions.appendChild(submit);
      form.appendChild(actions);
      backdrop.appendChild(form);
      document.body.appendChild(backdrop);

      function close(value) {
        document.removeEventListener('keydown', onKey);
        backdrop.remove();
        resolve(value);
      }
      function onKey(e) {
        if (e.key === 'Escape') {
          close(null);
        }
      }
      document.addEventListener('keydown', onKey);
      cancel.addEventListener('click', function () {
        close(null);
      });
      form.addEventListener('submit', function (e) {
        e.preventDefault();
        var values = {};
        var problem = null;
        Object.keys(inputs).forEach(function (name) {
          var v = inputs[name].input.value.trim();
          if (inputs[name].field.required && !v && !problem) {
            problem = inputs[name].field.label + '을(를) 입력해 주세요.';
          }
          values[name] = v;
        });
        if (problem) {
          message.textContent = problem;
          return;
        }
        close(values);
      });
      var first = form.querySelector('select, textarea, input');
      if (first) {
        first.focus();
      }
    });
  }

  window.FormDialog = { open: open };
})();
