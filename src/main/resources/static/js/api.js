/*
 * 모든 화면의 서버 요청 (T027). fetch를 직접 쓰지 않고 이것을 쓴다.
 *
 * - 쓰기 요청(POST·PUT·PATCH·DELETE)에 XSRF-TOKEN 쿠키 값을 X-XSRF-TOKEN 헤더로 싣는다 (SEC-10).
 * - 5초가 지나면 요청을 끊고 "다시 시도" 안내 오류를 던진다 (D-48).
 * - 401이면 토큰 재발급(POST /api/auth/refresh)을 한 번 해 보고 원래 요청을 다시 보낸다 (SEC-04).
 * - 알림 폴링처럼 사용자가 직접 하지 않은 요청은 { auto: true }로 보낸다. 로그인 30분 연장에 세지 않는다 (D-72).
 * - 실패하면 Api.ApiError(code, message, status, errorId)를 던진다. message는 서버가 준 안내 문구다.
 *
 * 사용: const blogs = await Api.get('/api/blogs');  await Api.post('/api/posts', { title });
 */
(function () {
  'use strict';

  var TIMEOUT_MS = 5000;
  var REFRESH_URL = '/api/auth/refresh';
  // 로그인·가입·재발급·로그아웃의 401은 토큰 만료가 아니라 그 요청의 실패라서 재발급하지 않는다
  var NO_REFRESH = ['/api/auth/login', '/api/auth/signup', REFRESH_URL, '/api/auth/logout'];

  function shouldRefresh(url) {
    return !NO_REFRESH.some(function (prefix) {
      return url.indexOf(prefix) === 0;
    });
  }
  var MESSAGES = {
    REQUEST_TIMEOUT: '응답이 늦어지고 있어요. 잠시 뒤 다시 시도해 주세요.',
    NETWORK_ERROR: '서버에 연결하지 못했어요. 인터넷 연결을 확인하고 다시 시도해 주세요.',
    INTERNAL_ERROR: '일시적인 오류가 생겼어요. 잠시 뒤 다시 시도해 주세요.'
  };

  function ApiError(code, message, status, errorId) {
    this.name = 'ApiError';
    this.code = code;
    this.message = message;
    this.status = status;
    this.errorId = errorId || null;
  }
  ApiError.prototype = Object.create(Error.prototype);
  ApiError.prototype.constructor = ApiError;

  function readCookie(name) {
    var prefix = name + '=';
    var parts = document.cookie ? document.cookie.split('; ') : [];
    for (var i = 0; i < parts.length; i++) {
      if (parts[i].indexOf(prefix) === 0) {
        return decodeURIComponent(parts[i].substring(prefix.length));
      }
    }
    return null;
  }

  async function send(method, url, body, options) {
    var headers = { Accept: 'application/json' };
    var payload;
    if (body instanceof FormData) {
      payload = body; // 파일 업로드: Content-Type은 브라우저가 정한다
    } else if (body !== undefined) {
      headers['Content-Type'] = 'application/json';
      payload = JSON.stringify(body);
    }
    if (method !== 'GET') {
      var token = readCookie('XSRF-TOKEN');
      if (token) {
        headers['X-XSRF-TOKEN'] = token;
      }
    }
    if (options.auto) {
      headers['X-Auto-Request'] = 'true';
    }

    var controller = new AbortController();
    var timer = setTimeout(function () {
      controller.abort();
    }, TIMEOUT_MS);
    try {
      return await fetch(url, {
        method: method,
        headers: headers,
        body: payload,
        credentials: 'same-origin',
        signal: controller.signal
      });
    } catch (e) {
      if (e && e.name === 'AbortError') {
        throw new ApiError('REQUEST_TIMEOUT', MESSAGES.REQUEST_TIMEOUT, 0);
      }
      throw new ApiError('NETWORK_ERROR', MESSAGES.NETWORK_ERROR, 0);
    } finally {
      clearTimeout(timer);
    }
  }

  // 여러 요청이 동시에 401을 받아도 재발급은 한 번만 한다
  var refreshing = null;

  function refreshToken() {
    if (!refreshing) {
      refreshing = send('POST', REFRESH_URL, undefined, {})
        .then(function (response) {
          return response.ok;
        })
        .catch(function () {
          return false;
        })
        .finally(function () {
          refreshing = null;
        });
    }
    return refreshing;
  }

  async function toError(response) {
    var data = null;
    try {
      data = await response.json();
    } catch (ignored) {
      // 본문이 JSON이 아닌 경우(프록시 오류 화면 등)
    }
    var code = (data && data.code) || (response.status >= 500 ? 'INTERNAL_ERROR' : 'ERROR');
    var message = (data && data.message) || MESSAGES.INTERNAL_ERROR;
    return new ApiError(code, message, response.status, data && data.errorId);
  }

  async function request(method, url, body, options) {
    options = options || {};
    var response = await send(method, url, body, options);
    if (response.status === 401 && !options.noRefresh && shouldRefresh(url)) {
      if (await refreshToken()) {
        response = await send(method, url, body, options);
      }
    }
    if (!response.ok) {
      throw await toError(response);
    }
    if (response.status === 204) {
      return null;
    }
    var type = response.headers.get('Content-Type') || '';
    return type.indexOf('application/json') !== -1 ? response.json() : response.text();
  }

  // 화면 아래에 잠깐 안내를 띄운다. 500 오류면 문의할 때 쓸 오류 번호를 붙인다
  var toastTimer = null;

  function toast(message) {
    var el = document.querySelector('.toast');
    if (!el) {
      el = document.createElement('div');
      el.className = 'toast';
      el.setAttribute('role', 'status');
      el.setAttribute('aria-live', 'polite');
      document.body.appendChild(el);
    }
    el.textContent = message;
    el.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () {
      el.hidden = true;
    }, 4000);
  }

  function showError(error) {
    if (error instanceof ApiError) {
      toast(error.errorId ? error.message + ' (오류 번호 ' + error.errorId + ')' : error.message);
    } else {
      toast(MESSAGES.INTERNAL_ERROR);
    }
  }

  window.Api = {
    ApiError: ApiError,
    get: function (url, options) {
      return request('GET', url, undefined, options);
    },
    post: function (url, body, options) {
      return request('POST', url, body, options);
    },
    put: function (url, body, options) {
      return request('PUT', url, body, options);
    },
    patch: function (url, body, options) {
      return request('PATCH', url, body, options);
    },
    delete: function (url, options) {
      return request('DELETE', url, undefined, options);
    },
    toast: toast,
    showError: showError
  };
})();
