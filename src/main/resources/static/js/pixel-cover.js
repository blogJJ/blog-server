/*
 * pixel-cover.js - 블로그 대표 이미지가 없을 때 보여 주는 움직이는 픽셀 장면 (BLG-15, D-119).
 *
 * 192x80 논리 해상도 프레임버퍼에 직접 픽셀을 칠한 뒤, 시간대 주변광과
 * 램프/창문 빛을 디더링된 단계로 입히고 정수 배율로 캔버스에 올린다.
 * 같은 spec이면 항상 같은 그림, seed가 다르면 소품·팔레트·구름이 달라진다.
 *
 * 공개 API: window.PixelCover
 */
(function () {
  'use strict';

  var W = 192;
  var H = 80;
  var STEP = 125;
  var PI = Math.PI;
  var WHITE = [255, 255, 255];

  // ---------------------------------------------------------------- math

  var BAYER = [0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5];

  function bay(x, y) {
    return (BAYER[((y & 3) << 2) | (x & 3)] + 0.5) / 16;
  }
  function clamp(v, a, b) {
    return v < a ? a : (v > b ? b : v);
  }
  function mod(a, n) {
    return ((a % n) + n) % n;
  }
  function fl(v) {
    return Math.floor(v);
  }
  function rnd(v) {
    return Math.round(v);
  }
  function smooth(t) {
    t = clamp(t, 0, 1);
    return t * t * (3 - 2 * t);
  }
  function hash(a, b) {
    var h = (Math.imul(a | 0, 374761393) + Math.imul(b | 0, 668265263)) | 0;
    h = Math.imul(h ^ (h >>> 13), 1274126177);
    h = Math.imul(h ^ (h >>> 16), 2246822519);
    h ^= h >>> 15;
    return (h >>> 0) / 4294967296;
  }
  function hashStr(s) {
    var h = 2166136261;
    for (var i = 0; i < s.length; i++) {
      h ^= s.charCodeAt(i);
      h = Math.imul(h, 16777619);
    }
    return h >>> 0;
  }
  function makeRand(seed) {
    var a = seed >>> 0;
    function f() {
      a = (a + 0x6d2b79f5) | 0;
      var t = Math.imul(a ^ (a >>> 15), 1 | a);
      t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
      return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
    }
    return {
      f: f,
      i: function (lo, hi) {
        return lo + fl(f() * (hi - lo + 1));
      },
      r: function (lo, hi) {
        return lo + f() * (hi - lo);
      },
      pick: function (arr) {
        return arr[fl(f() * arr.length)];
      },
      ch: function (p) {
        return f() < p;
      }
    };
  }

  // -------------------------------------------------------------- colour

  var HEX = {};
  function hx(s) {
    var c = HEX[s];
    if (!c) {
      var n = parseInt(s.slice(1), 16);
      c = HEX[s] = [(n >> 16) & 255, (n >> 8) & 255, n & 255];
    }
    return c;
  }
  function C(v) {
    return typeof v === 'string' ? hx(v) : v;
  }
  function mix(a, b, t) {
    a = C(a);
    b = C(b);
    return [
      rnd(a[0] + (b[0] - a[0]) * t),
      rnd(a[1] + (b[1] - a[1]) * t),
      rnd(a[2] + (b[2] - a[2]) * t)
    ];
  }
  function mul(c, m) {
    c = C(c);
    return [
      clamp(rnd(c[0] * m[0]), 0, 255),
      clamp(rnd(c[1] * m[1]), 0, 255),
      clamp(rnd(c[2] * m[2]), 0, 255)
    ];
  }
  function shift(c, dh, ds, dl) {
    var r = c[0] / 255;
    var g = c[1] / 255;
    var b = c[2] / 255;
    var mx = Math.max(r, g, b);
    var mn = Math.min(r, g, b);
    var h = 0;
    var s = 0;
    var l = (mx + mn) / 2;
    if (mx !== mn) {
      var d = mx - mn;
      s = l > 0.5 ? d / (2 - mx - mn) : d / (mx + mn);
      if (mx === r) {
        h = (g - b) / d + (g < b ? 6 : 0);
      } else if (mx === g) {
        h = (b - r) / d + 2;
      } else {
        h = (r - g) / d + 4;
      }
      h /= 6;
    }
    h = mod(h + dh / 360, 1);
    s = clamp(s + ds, 0, 1);
    l = clamp(l + dl, 0, 1);
    if (s === 0) {
      var v = rnd(l * 255);
      return [v, v, v];
    }
    function hue(p, q, t) {
      t = mod(t, 1);
      if (t < 1 / 6) return p + (q - p) * 6 * t;
      if (t < 1 / 2) return q;
      if (t < 2 / 3) return p + (q - p) * (2 / 3 - t) * 6;
      return p;
    }
    var q = l < 0.5 ? l * (1 + s) : l + s - l * s;
    var p = 2 * l - q;
    return [rnd(hue(p, q, h + 1 / 3) * 255), rnd(hue(p, q, h) * 255), rnd(hue(p, q, h - 1 / 3) * 255)];
  }

  // ------------------------------------------------------------- options

  var LABELS = {
    scene: {
      study: '서재',
      desk: '개발 책상',
      kitchen: '부엌',
      cafe: '카페',
      sky: '하늘',
      stage: '음악',
      photo: '사진',
      game: '게임',
      park: '공원·산책',
      bakery: '베이킹',
      sea: '바다·여행',
      snowfield: '운동·겨울',
      garden: '일상·감성',
      'night-city': '기본(도시의 밤)'
    },
    time: { dawn: '새벽', day: '낮', sunset: '노을', night: '밤' },
    weather: { clear: '맑음', cloudy: '흐림', rain: '비', snow: '눈', petals: '벚꽃', leaves: '낙엽' },
    palette: { warm: '따뜻한 나무', mint: '민트', berry: '베리', dusk: '보랏빛 황혼', cream: '크림 모노' },
    mascot: {
      bear: '곰',
      cat: '고양이',
      dog: '강아지',
      rabbit: '토끼',
      fox: '여우',
      penguin: '펭귄',
      bird: '새',
      frog: '개구리',
      hamster: '햄스터',
      owl: '부엉이',
      computer: '컴퓨터',
      camera: '카메라',
      book: '책',
      plane: '비행기',
      headphones: '헤드폰',
      gamepad: '게임패드',
      coffee: '커피',
      cake: '케이크'
    }
  };
  var SCENES = Object.keys(LABELS.scene);
  var TIMES = Object.keys(LABELS.time);
  var WEATHERS = Object.keys(LABELS.weather);
  var PALETTES = Object.keys(LABELS.palette);
  var MASCOT_KEYS = Object.keys(LABELS.mascot);
  var ANIMALS = ['bear', 'cat', 'dog', 'rabbit', 'fox', 'penguin', 'bird', 'frog', 'hamster', 'owl'];

  var PAL = {
    warm: {
      wall: '#f3dcb4', wallD: '#e0bf90', trim: '#b47d56', floor: '#a8673f', floorD: '#87502f',
      wood: '#c48a54', woodD: '#8c5834', woodL: '#e2b077', accent: '#d9594c', accent2: '#4f8a8b',
      metal: '#9a9ca6', metalD: '#5b5d6b', leaf: '#6aa84f', leafD: '#3f7a3a', paper: '#fff6e3',
      ink: '#4a3a36', rug: '#c9604f'
    },
    mint: {
      wall: '#cfe8dc', wallD: '#a9d1bf', trim: '#7fb3a3', floor: '#d7b48a', floorD: '#b38c5c',
      wood: '#e3c193', woodD: '#ad875a', woodL: '#f2dab4', accent: '#f08a7e', accent2: '#f2c65a',
      metal: '#a6b6b9', metalD: '#62777c', leaf: '#5fb87a', leafD: '#3a8a5b', paper: '#fffdf4',
      ink: '#3f4f4e', rug: '#f2a99c'
    },
    berry: {
      wall: '#f2cfd6', wallD: '#dca6b3', trim: '#9c5a73', floor: '#8a5560', floorD: '#6b3f4a',
      wood: '#b06f6a', woodD: '#784247', woodL: '#d49b8e', accent: '#c2456b', accent2: '#6a8fd0',
      metal: '#aaa0ae', metalD: '#6e6276', leaf: '#7aa863', leafD: '#4e7a45', paper: '#fff4f2',
      ink: '#4f3040', rug: '#b84a6a'
    },
    dusk: {
      wall: '#7a74b4', wallD: '#625c9a', trim: '#423c70', floor: '#4b3f5e', floorD: '#372d47',
      wood: '#946460', woodD: '#5e3c45', woodL: '#bb8774', accent: '#f2b84b', accent2: '#e86a8a',
      metal: '#a4a4c0', metalD: '#5c5c80', leaf: '#5aa088', leafD: '#3a6e66', paper: '#f6eedc',
      ink: '#2e2846', rug: '#d8708a'
    },
    cream: {
      wall: '#f4ead6', wallD: '#e0cfb0', trim: '#b8a383', floor: '#cdb48f', floorD: '#a98e69',
      wood: '#d9bf98', woodD: '#9a8060', woodL: '#ecdcbf', accent: '#c98f6b', accent2: '#8fa08a',
      metal: '#aea698', metalD: '#786f64', leaf: '#9aae84', leafD: '#6f8560', paper: '#fffbf2',
      ink: '#5e4e40', rug: '#d4a989'
    }
  };

  var TIME = {
    dawn: {
      sky: ['#3d4070', '#675a94', '#b06e90', '#e89a8a', '#ffcfa6'],
      grey: '#8a8098', amb: [0.8, 0.76, 0.9], lamp: [1, 0.82, 0.62], lampK: 0.55,
      win: [1, 0.82, 0.78], winK: 0.25, cloud: ['#ffd8cf', '#c497b6'], stars: 0.35, lit: 0.25
    },
    day: {
      sky: ['#4f9fe0', '#69b4ee', '#8bcaf3', '#b0dcf7', '#d6effb'],
      grey: '#a8b3be', amb: [1, 1, 1], lamp: [1, 0.95, 0.82], lampK: 0.04,
      win: [1, 0.98, 0.9], winK: 0.32, cloud: ['#ffffff', '#c8dcee'], stars: 0, lit: 0.06
    },
    sunset: {
      sky: ['#33265c', '#6a2f68', '#c0475f', '#ee894c', '#ffc66d'],
      grey: '#8a6f80', amb: [1, 0.83, 0.72], lamp: [1, 0.78, 0.5], lampK: 0.6,
      win: [1, 0.7, 0.45], winK: 0.38, cloud: ['#ffc59c', '#bd6a8a'], stars: 0.12, lit: 0.4
    },
    night: {
      sky: ['#0b0f2a', '#121a3e', '#1a2450', '#232f64', '#2d3a76'],
      grey: '#1d2132', amb: [0.33, 0.36, 0.56], lamp: [1, 0.78, 0.48], lampK: 1.05,
      win: [0.55, 0.65, 1], winK: 0.14, cloud: ['#454d7c', '#2e345c'], stars: 1, lit: 0.6
    }
  };

  // -------------------------------------------------------------- canvas

  function Gfx() {
    this.d = new Uint8ClampedArray(W * H * 4);
    this.e = new Uint8Array(W * H);
    this.em = 0;
    this.lights = [];
    this.noclip();
  }
  Gfx.prototype.clear = function () {
    this.d.fill(0);
    this.e.fill(0);
    this.em = 0;
    this.lights.length = 0;
    this.noclip();
  };
  Gfx.prototype.noclip = function () {
    this.x0 = 0;
    this.y0 = 0;
    this.x1 = W;
    this.y1 = H;
    this.mask = null;
  };
  Gfx.prototype.clip = function (x, y, w, h, mask) {
    this.x0 = Math.max(0, x);
    this.y0 = Math.max(0, y);
    this.x1 = Math.min(W, x + w);
    this.y1 = Math.min(H, y + h);
    this.mask = mask || null;
  };
  Gfx.prototype.p = function (x, y, c) {
    x = fl(x);
    y = fl(y);
    if (!c || x < this.x0 || y < this.y0 || x >= this.x1 || y >= this.y1) return;
    if (this.mask && !this.mask(x, y)) return;
    if (typeof c === 'string') c = hx(c);
    var i = y * W + x;
    var j = i << 2;
    this.d[j] = c[0];
    this.d[j + 1] = c[1];
    this.d[j + 2] = c[2];
    this.d[j + 3] = 255;
    this.e[i] = this.em;
  };
  Gfx.prototype.get = function (x, y) {
    x = clamp(fl(x), 0, W - 1);
    y = clamp(fl(y), 0, H - 1);
    var j = (y * W + x) << 2;
    return [this.d[j], this.d[j + 1], this.d[j + 2]];
  };
  Gfx.prototype.b = function (x, y, c, t) {
    if (t <= 0) return;
    this.p(x, y, mix(this.get(x, y), c, t));
  };
  Gfx.prototype.r = function (x, y, w, h, c) {
    x = fl(x);
    y = fl(y);
    for (var yy = y; yy < y + h; yy++) {
      for (var xx = x; xx < x + w; xx++) this.p(xx, yy, c);
    }
  };
  Gfx.prototype.br = function (x, y, w, h, c, t) {
    x = fl(x);
    y = fl(y);
    for (var yy = y; yy < y + h; yy++) {
      for (var xx = x; xx < x + w; xx++) this.b(xx, yy, c, t);
    }
  };
  // checker dither: paint every other pixel
  Gfx.prototype.dr = function (x, y, w, h, c, ph) {
    x = fl(x);
    y = fl(y);
    for (var yy = y; yy < y + h; yy++) {
      for (var xx = x; xx < x + w; xx++) {
        if (((xx + yy + (ph || 0)) & 1) === 0) this.p(xx, yy, c);
      }
    }
  };
  Gfx.prototype.ell = function (cx, cy, rx, ry, c) {
    cx = rnd(cx);
    cy = rnd(cy);
    for (var dy = -ry; dy <= ry; dy++) {
      var hw = ry === 0 ? rx : rnd(rx * Math.sqrt(Math.max(0, 1 - (dy * dy) / ((ry + 0.5) * (ry + 0.5)))));
      this.r(cx - hw, cy + dy, hw * 2 + 1, 1, c);
    }
  };
  Gfx.prototype.line = function (x0, y0, x1, y1, c) {
    x0 = rnd(x0);
    y0 = rnd(y0);
    x1 = rnd(x1);
    y1 = rnd(y1);
    var dx = Math.abs(x1 - x0);
    var dy = -Math.abs(y1 - y0);
    var sx = x0 < x1 ? 1 : -1;
    var sy = y0 < y1 ? 1 : -1;
    var err = dx + dy;
    for (var n = 0; n < 400; n++) {
      this.p(x0, y0, c);
      if (x0 === x1 && y0 === y1) break;
      var e2 = 2 * err;
      if (e2 >= dy) {
        err += dy;
        x0 += sx;
      }
      if (e2 <= dx) {
        err += dx;
        y0 += sy;
      }
    }
  };
  Gfx.prototype.spr = function (x, y, rows, map, flip) {
    x = fl(x);
    y = fl(y);
    for (var j = 0; j < rows.length; j++) {
      var row = rows[j];
      var n = row.length;
      for (var i = 0; i < n; i++) {
        var ch = row.charAt(i);
        if (ch !== '.' && map[ch]) this.p(flip ? x + n - 1 - i : x + i, y + j, map[ch]);
      }
    }
  };
  // light source: colour c is a 0..1 triplet, k its strength
  Gfx.prototype.light = function (x, y, r, c, k, o) {
    o = o || {};
    this.lights.push({
      x: x, y: y, r: r, r2: r * r, n: o.n || 6, sy: o.sy || 1,
      top: o.top == null ? -999 : o.top, upS: o.upS || 2.6,
      c: [c[0] * k, c[1] * k, c[2] * k]
    });
  };

  // ambient + light sources, banded with ordered dithering; emissive pixels untouched
  function shade(g, amb, out) {
    var d = g.d;
    var e = g.e;
    var Ls = g.lights;
    var nL = Ls.length;
    for (var y = 0; y < H; y++) {
      for (var x = 0; x < W; x++) {
        var i = y * W + x;
        var j = i << 2;
        if (e[i]) {
          out[j] = d[j];
          out[j + 1] = d[j + 1];
          out[j + 2] = d[j + 2];
        } else {
          var lr = amb[0];
          var lg = amb[1];
          var lb = amb[2];
          var bz = bay(x, y);
          for (var k = 0; k < nL; k++) {
            var L = Ls[k];
            var dx = x - L.x;
            var dy = y < L.top ? (L.top - L.y + (y - L.top) * L.upS) * L.sy : (y - L.y) * L.sy;
            var d2 = dx * dx + dy * dy;
            if (d2 < L.r2) {
              var v = (1 - Math.sqrt(d2) / L.r) * L.n;
              var bi = fl(v);
              var fr = v - bi;
              if (fr > 0.7 && (fr > 0.85 || ((x + y) & 1) === 0)) bi++;
              var q = bi / L.n;
              q *= q;
              if (q > 0) {
                lr += L.c[0] * q;
                lg += L.c[1] * q;
                lb += L.c[2] * q;
              }
            }
          }
          out[j] = d[j] * lr;
          out[j + 1] = d[j + 1] * lg;
          out[j + 2] = d[j + 2] * lb;
        }
        out[j + 3] = 255;
      }
    }
  }

  // ----------------------------------------------------------------- env

  function buildPalette(key, R) {
    var src = PAL[key] || PAL.warm;
    var dh = R.r(-9, 9);
    var ds = R.r(-0.05, 0.05);
    var dl = R.r(-0.025, 0.025);
    var P = {};
    Object.keys(src).forEach(function (k) {
      P[k] = shift(hx(src[k]), dh, ds, dl);
    });
    if (R.ch(0.3)) {
      var tmp = P.accent;
      P.accent = P.accent2;
      P.accent2 = tmp;
    }
    P.wallS = mix(P.wall, P.wallD, 0.55);
    P.wallL = mix(P.wall, WHITE, 0.35);
    P.trimL = mix(P.trim, WHITE, 0.3);
    P.woodG = mix(P.wood, P.woodD, 0.3);
    P.woodDD = mix(P.woodD, P.ink, 0.35);
    P.accentL = mix(P.accent, WHITE, 0.35);
    P.accentD = mix(P.accent, P.ink, 0.35);
    P.accent2L = mix(P.accent2, WHITE, 0.35);
    P.accent2D = mix(P.accent2, P.ink, 0.35);
    P.metalL = mix(P.metal, WHITE, 0.4);
    P.paperD = mix(P.paper, P.ink, 0.16);
    P.paperDD = mix(P.paper, P.ink, 0.38);
    P.leafL = mix(P.leaf, '#fff7c0', 0.35);
    P.back = mix(P.wallD, P.ink, 0.45);
    P.pot = mix('#c9764c', P.accent, 0.2);
    P.potD = mix(P.pot, P.ink, 0.35);
    P.potL = mix(P.pot, WHITE, 0.25);
    P.frame = P.woodL;
    P.books = [P.accent, P.accentD, P.accent2, P.accent2D, P.woodL, P.paperD, P.leaf, P.metal, mix(P.ink, P.accent2, 0.4)];
    return P;
  }

  function Env(spec) {
    var R = makeRand(spec.seed);
    this.spec = spec;
    this.seed = spec.seed % 1000003;
    this.time = spec.time;
    this.weather = spec.weather;
    var T = (this.T = TIME[spec.time]);
    this.P = buildPalette(spec.palette, R);
    var wk = { cloudy: 0.4, rain: 0.58, snow: 0.38 }[spec.weather] || 0;
    var grey = hx(T.grey);
    this.sky = T.sky.map(function (s) {
      return mix(hx(s), grey, wk);
    });
    this.skyLow = this.sky[this.sky.length - 1];
    this.skyMid = this.sky[2];
    var wa = { cloudy: [0.9, 0.92, 0.96], rain: [0.8, 0.83, 0.92], snow: [0.95, 0.96, 1] }[spec.weather] || [1, 1, 1];
    this.amb = [T.amb[0] * wa[0], T.amb[1] * wa[1], T.amb[2] * wa[2]];
    this.cloudL = mix(hx(T.cloud[0]), grey, wk * 0.7);
    this.cloudD = mix(hx(T.cloud[1]), grey, wk * 0.7);
    this.lampK = T.lampK * (spec.weather === 'rain' && spec.time === 'day' ? 2 : 1);
    this.winK = T.winK * (wk ? 0.6 : 1);
    this.litC = spec.time === 'day' ? hx('#fff3cf') : (spec.time === 'night' ? hx('#ffd36e') : hx('#ffc982'));
    this.stars = [];
    for (var i = 0; i < 48; i++) {
      this.stars.push({ fx: R.f(), fy: R.f(), b: R.f(), ph: R.i(0, 80) });
    }
    var nC = { clear: 3, cloudy: 7, rain: 8, snow: 6, petals: 3, leaves: 3 }[spec.weather] || 3;
    this.clouds = [];
    for (i = 0; i < nC; i++) {
      var puffs = [{ dx: 0, dy: -1, r: 4 }, { dx: -4.5, dy: 0.5, r: 3 }, { dx: 4.5, dy: 0.5, r: 3 }];
      var extra = R.i(0, 2);
      for (var k = 0; k < extra; k++) puffs.push({ dx: R.r(-7, 7), dy: R.r(-1, 1), r: R.r(2, 3.2) });
      this.clouds.push({ fx: R.f(), fy: R.r(0.08, 0.5), s: R.r(0.75, 1.3), v: R.r(0.04, 0.12), puffs: puffs });
    }
    this.sunX = R.r(0.2, 0.8);
    this.moonFull = R.ch(0.3);
  }

  function tone(env, c) {
    return mul(c, env.amb);
  }

  // ------------------------------------------------------------- outside

  function skyAt(env, f, x, y) {
    var s = env.sky;
    var n = s.length - 1;
    var v = clamp(f, 0, 0.9999) * n;
    var i = fl(v);
    var k = clamp((v - i - 0.5) / 0.5, 0, 1);
    return k > bay(x, y) ? s[i + 1] : s[i];
  }

  function skyBg(g, env, x, y, w, h) {
    for (var yy = 0; yy < h; yy++) {
      var f = yy / Math.max(1, h - 1);
      for (var xx = 0; xx < w; xx++) g.p(x + xx, y + yy, skyAt(env, f, x + xx, y + yy));
    }
  }

  function stars(g, env, x, y, w, h, t) {
    var amt = env.T.stars * ({ cloudy: 0.4, rain: 0, snow: 0 }[env.weather] === undefined ? 1 : { cloudy: 0.4, rain: 0, snow: 0 }[env.weather]);
    var n = fl(env.stars.length * amt * Math.min(1, (w * h) / 3000 + 0.25));
    for (var i = 0; i < n; i++) {
      var s = env.stars[i];
      var px = x + fl(s.fx * w);
      var py = y + fl(s.fy * h * 0.7);
      var tw = mod(t + s.ph, 56);
      var c = s.b > 0.55 ? '#fff6dc' : '#aeb9ea';
      if (s.b > 0.75 && tw < 3) {
        g.p(px, py, '#ffffff');
        g.p(px - 1, py, '#c8d0ff');
        g.p(px + 1, py, '#c8d0ff');
        g.p(px, py - 1, '#c8d0ff');
        g.p(px, py + 1, '#c8d0ff');
      } else if (!(s.b < 0.4 && tw > 40)) {
        g.p(px, py, c);
      }
    }
  }

  function halo(g, cx, cy, r, w, c, a) {
    for (var dy = -r - w; dy <= r + w; dy++) {
      for (var dx = -r - w; dx <= r + w; dx++) {
        var d = Math.sqrt(dx * dx + dy * dy);
        if (d > r && d < r + w) {
          var al = (1 - (d - r) / w) * a;
          if (bay(cx + dx, cy + dy) < al) g.b(cx + dx, cy + dy, c, 0.5);
        }
      }
    }
  }

  function disc(g, cx, cy, r, c) {
    for (var dy = -r; dy <= r; dy++) {
      for (var dx = -r; dx <= r; dx++) {
        if (dx * dx + dy * dy <= r * r + r * 0.8) g.p(cx + dx, cy + dy, c);
      }
    }
  }

  function celestial(g, env, x, y, w, h) {
    if (env.weather === 'rain' || env.weather === 'snow') return;
    var tm = env.time;
    var sx = x + fl(env.sunX * w);
    var r = clamp(rnd(h * 0.09), 2, 6);
    var sy;
    if (tm === 'night') {
      sy = y + fl(h * 0.26);
      r = Math.max(3, r);
      halo(g, sx, sy, r, 4, '#8e9ad8', 0.5);
      for (var dy = -r; dy <= r; dy++) {
        for (var dx = -r; dx <= r; dx++) {
          if (dx * dx + dy * dy > r * r + r * 0.8) continue;
          var cx = dx - r * 0.55;
          var cy = dy + r * 0.35;
          if (!env.moonFull && cx * cx + cy * cy <= r * r * 0.8) continue;
          var edge = dx * dx + dy * dy > (r - 1) * (r - 1);
          g.p(sx + dx, sy + dy, edge && dx > 0 ? '#e9d59e' : '#fff1c6');
        }
      }
      if (env.moonFull && r >= 3) {
        g.p(sx - 1, sy, '#ead9a6');
        g.p(sx + 1, sy + 1, '#ead9a6');
        g.p(sx, sy - 2, '#ead9a6');
      }
    } else if (tm === 'day') {
      sy = y + fl(h * 0.24);
      halo(g, sx, sy, r, 5, '#fffbe8', 0.55);
      disc(g, sx, sy, r, '#fff6c8');
      disc(g, sx - 1, sy - 1, Math.max(1, r - 2), '#fffdf0');
    } else {
      var sunset = tm === 'sunset';
      r += 1;
      sy = y + fl(h * (sunset ? 0.7 : 0.8));
      halo(g, sx, sy, r, 6, sunset ? '#ffd49a' : '#ffe0c0', 0.6);
      for (var yy = -r; yy <= r; yy++) {
        var band = sunset && yy > 0 && (yy % 3 === 1);
        if (band) continue;
        for (var xx = -r; xx <= r; xx++) {
          if (xx * xx + yy * yy <= r * r + r * 0.8) {
            g.p(sx + xx, sy + yy, yy < -r / 3 ? (sunset ? '#ffe28a' : '#fff0c8') : (sunset ? '#ffb257' : '#ffd2a0'));
          }
        }
      }
    }
  }

  function cloud(g, env, cx, cy, sc, puffs, cl, cd) {
    var minX = 999;
    var maxX = -999;
    var minY = 999;
    for (var i = 0; i < puffs.length; i++) {
      var p = puffs[i];
      minX = Math.min(minX, p.dx * sc - p.r * sc);
      maxX = Math.max(maxX, p.dx * sc + p.r * sc);
      minY = Math.min(minY, p.dy * sc - p.r * sc);
    }
    var base = cy + rnd(1.5 * sc);
    function ins(px, py) {
      if (py > base) return false;
      for (var k = 0; k < puffs.length; k++) {
        var q = puffs[k];
        var dx = px - (cx + q.dx * sc);
        var dy = py - (cy + q.dy * sc);
        var rr = q.r * sc;
        if (dx * dx + dy * dy <= rr * rr) return true;
      }
      return false;
    }
    var hi = mix(cl, WHITE, 0.45);
    for (var py = fl(cy + minY); py <= base; py++) {
      for (var px = fl(cx + minX); px <= cx + maxX; px++) {
        if (!ins(px, py)) continue;
        var c = cl;
        if (!ins(px, py + 2)) {
          c = cd;
        } else if (!ins(px, py + 3) || !ins(px + 2, py)) {
          c = bay(px, py) < 0.5 ? cd : cl;
        } else if (!ins(px, py - 1) || !ins(px - 1, py - 1)) {
          c = hi;
        }
        g.p(px, py, c);
      }
    }
  }

  function clouds(g, env, x, y, w, h, t, scale, filter) {
    var sc0 = scale || clamp(h / 36, 0.6, 1.5);
    var span = w + 40;
    for (var i = 0; i < env.clouds.length; i++) {
      if (filter && !filter(i)) continue;
      var c = env.clouds[i];
      var cx = x + mod(c.fx * span + t * c.v * (scale ? 1.6 : 1), span) - 20;
      var cy = y + fl(c.fy * h);
      cloud(g, env, rnd(cx), cy, sc0 * c.s, c.puffs, env.cloudL, env.cloudD);
    }
  }

  function distant(g, env, x, y, w, h, kind, t) {
    var low = env.skyLow;
    var sd = env.seed;
    var snow = env.weather === 'snow';
    var xx;
    var yy;
    var i;
    if (kind === 'hills' || kind === 'trees') {
      var far = tone(env, mix(low, snow ? '#e8eef6' : '#9cc79a', 0.55));
      var near = tone(env, snow ? '#f4f7fb' : '#6aa862');
      var nearD = tone(env, snow ? '#c8d4e6' : '#4f8c4e');
      var tree = tone(env, snow ? '#5f7f78' : '#3f7a48');
      var treeL = tone(env, snow ? '#e8f0f6' : '#5f9e55');
      for (xx = 0; xx < w; xx++) {
        var gx = x + xx;
        var f1 = y + h * 0.62 + Math.sin((gx + (sd % 50)) * 0.09) * h * 0.08 + Math.sin(gx * 0.23);
        var f2 = y + h * 0.8 + Math.sin((gx + (sd % 70)) * 0.05 + 1.7) * h * 0.08;
        for (yy = fl(f1); yy < y + h; yy++) g.p(gx, yy, far);
        for (yy = fl(f2); yy < y + h; yy++) g.p(gx, yy, yy - f2 > 3 && bay(gx, yy) < 0.5 ? nearD : near);
      }
      var nt = kind === 'trees' ? fl(w / 6) : fl(w / 14);
      for (i = 0; i < nt; i++) {
        var tx = x + fl(hash(sd + 31, i) * w);
        var ty = y + h * 0.8 + Math.sin((tx + (sd % 70)) * 0.05 + 1.7) * h * 0.08;
        var tr = kind === 'trees' ? 2 + fl(hash(sd + 32, i) * 3) : 1 + fl(hash(sd + 32, i) * 2);
        g.ell(tx, ty - tr, tr, tr + 1, tree);
        g.ell(tx - 1, ty - tr - 1, Math.max(1, tr - 1), Math.max(1, tr - 1), treeL);
      }
    } else if (kind === 'city' || kind === 'street') {
      var big = kind === 'street';
      var bc = tone(env, mix(low, '#4a536f', 0.6));
      var bc2 = tone(env, mix(low, '#5d6788', 0.45));
      var lit = env.T.lit;
      xx = x - fl(hash(sd, 9) * 6);
      i = 0;
      while (xx < x + w) {
        var bw = big ? 14 + fl(hash(sd + 3, i) * 14) : 5 + fl(hash(sd + 3, i) * 9);
        var bh = rnd(h * (big ? 0.5 + hash(sd + 5, i) * 0.4 : 0.2 + hash(sd + 5, i) * 0.4));
        var by = y + h - bh;
        var col = big ? tone(env, mix(low, [env.P.wallD, env.P.trim, '#b98e7a', '#8a98b0'][i % 4], 0.65)) : (i & 1 ? bc : bc2);
        g.r(xx, by, bw, bh, col);
        if (!big && hash(sd + 7, i) < 0.3) g.r(xx + fl(bw / 2), by - 2, 1, 2, col);
        var dk = mix(col, '#20243a', 0.35);
        var refl = mix(col, low, 0.5);
        var stepY = big ? 6 : 3;
        var stepX = big ? 5 : 2;
        for (var wy = by + 2; wy < y + h - (big ? 9 : 1); wy += stepY) {
          for (var wx = xx + (big ? 2 : 1); wx < xx + bw - (big ? 3 : 1); wx += stepX) {
            var on = hash(i * 131 + wx + sd, wy * 7 + fl((t + hash(wx, wy) * 300) / 160)) < lit;
            if (big) {
              g.r(wx, wy, 3, 4, on ? env.litC : dk);
              if (!on) g.p(wx, wy, refl);
            } else {
              g.p(wx, wy, on ? env.litC : refl);
            }
          }
        }
        if (big) {
          // shop front with striped awning
          var aw = y + h - 9;
          for (var ax = xx; ax < xx + bw; ax++) {
            g.p(ax, aw, (ax >> 1) & 1 ? tone(env, env.P.accent) : tone(env, env.P.paper));
            g.p(ax, aw + 1, (ax >> 1) & 1 ? tone(env, env.P.accent) : tone(env, env.P.paper));
          }
          for (ax = xx + 1; ax < xx + bw - 1; ax += 2) g.p(ax, aw + 2, tone(env, env.P.accentD));
          g.r(xx + 2, aw + 3, bw - 4, 4, env.time === 'day' ? refl : env.litC);
        }
        xx += bw + (hash(sd + 9, i) < 0.3 ? 1 : 0);
        i++;
      }
      if (big) {
        g.r(x, y + h - 2, w, 2, tone(env, '#8b8f9c'));
        var lx = x + fl(w * 0.62);
        g.r(lx, y + h - 16, 1, 14, tone(env, '#3a3f52'));
        g.r(lx - 1, y + h - 17, 3, 2, env.time === 'day' ? tone(env, '#d8dccc') : '#ffe08a');
        if (env.time !== 'day') halo(g, lx, y + h - 16, 1, 4, '#ffe9a8', 0.6);
      }
    }
  }

  function weather(g, env, x, y, w, h, t, dens) {
    var wt = env.weather;
    var sd = env.seed;
    var i;
    var n;
    var xx;
    var yy;
    dens = dens || 1;
    var night = env.time === 'night';
    if (wt === 'rain') {
      n = rnd(((w * h) / 42) * dens);
      var rc = night ? hx('#8a9ad0') : hx('#e2efff');
      for (i = 0; i < n; i++) {
        var sp = 4 + hash(sd + i, 13) * 2;
        yy = mod(hash(sd + i, 12) * (h + 8) + t * sp, h + 8) - 4;
        xx = mod(hash(sd + i, 11) * w - yy * 0.25, w);
        g.b(x + xx, y + yy, rc, 0.75);
        g.b(x + xx, y + yy + 1, rc, 0.55);
        g.b(x + xx - 1, y + yy + 2, rc, 0.35);
      }
    } else if (wt === 'snow') {
      n = rnd(((w * h) / 55) * dens);
      for (i = 0; i < n; i++) {
        var v = 0.45 + hash(sd + i, 23) * 0.6;
        yy = mod(hash(sd + i, 22) * (h + 4) + t * v, h + 4) - 2;
        xx = mod(hash(sd + i, 21) * w + Math.sin(t * 0.13 + i) * 2 + t * 0.12, w);
        g.p(x + xx, y + yy, '#ffffff');
        if (hash(sd + i, 24) < 0.22) {
          g.p(x + xx + 1, y + yy, '#e8f0ff');
          g.p(x + xx, y + yy + 1, '#e8f0ff');
          g.p(x + xx + 1, y + yy + 1, '#ffffff');
        }
      }
    } else if (wt === 'petals' || wt === 'leaves') {
      var leaf = wt === 'leaves';
      n = rnd(((w * h) / (leaf ? 170 : 130)) * dens);
      var cols = leaf ? ['#e9873b', '#d0533a', '#f2b648', '#b8672e'] : ['#ffc4d6', '#ff9fbf', '#ffe3ec', '#f7b0c8'];
      for (i = 0; i < n; i++) {
        var spd = leaf ? 0.6 : 0.85;
        xx = mod(hash(sd + i, 31) * w + t * spd, w);
        yy = mod(hash(sd + i, 32) * h + t * (leaf ? 0.4 : 0.45) + Math.sin(t * 0.25 + i * 1.7) * 2, h);
        var c = cols[i % cols.length];
        var fr = mod(t + i, 4);
        var px = x + fl(xx);
        var py = y + fl(yy);
        g.p(px, py, c);
        if (leaf) {
          if (fr < 2) g.p(px + 1, py, c);
          else g.p(px, py + 1, c);
          if (fr === 0) g.p(px + 1, py + 1, mix(c, '#5a2a10', 0.3));
        } else {
          if (fr === 0 || fr === 2) g.p(px + 1, py, c);
          if (fr === 1) g.p(px, py + 1, c);
        }
      }
    }
  }

  function outside(g, env, x, y, w, h, t, view) {
    skyBg(g, env, x, y, w, h);
    stars(g, env, x, y, w, h, t);
    celestial(g, env, x, y, w, h);
    clouds(g, env, x, y, w, h, t);
    if (view) distant(g, env, x, y, w, h, view, t);
    weather(g, env, x, y, w, h, t);
  }

  // -------------------------------------------------------------- props

  function room(g, env, L, floorY, o) {
    var P = env.P;
    var x;
    var y;
    o = o || {};
    g.r(0, 0, W, floorY, P.wall);
    var st = L.wallStyle || 0;
    if (st === 1) {
      for (x = 4; x < W; x += 12) g.r(x, 0, 3, floorY, P.wallS);
    } else if (st === 2) {
      for (y = 5; y < floorY - 6; y += 8) {
        for (x = ((y >> 3) & 1) * 6 + 3; x < W; x += 12) {
          g.p(x, y - 1, P.wallS);
          g.p(x - 1, y, P.wallS);
          g.p(x + 1, y, P.wallS);
          g.p(x, y + 1, P.wallS);
        }
      }
    }
    if (st === 3 || o.wainscot) {
      var wy = o.wainY || floorY - 20;
      g.r(0, wy, W, floorY - wy, P.wallD);
      for (x = 5; x < W; x += 18) {
        g.r(x, wy + 4, 13, floorY - wy - 9, P.wallS);
        g.r(x, wy + 4, 13, 1, mix(P.wallD, P.ink, 0.15));
        g.r(x, wy + 4, 1, floorY - wy - 9, mix(P.wallD, P.ink, 0.15));
      }
      g.r(0, wy - 2, W, 2, P.trim);
      g.r(0, wy - 2, W, 1, P.trimL);
      g.dr(0, wy, W, 1, mix(P.wallD, P.ink, 0.2), 0);
    }
    g.r(0, 0, W, 1, P.wallD);
    g.dr(0, 1, W, 1, P.wallD, 0);
    if (floorY >= H) return;
    g.r(0, floorY - 3, W, 3, P.trim);
    g.r(0, floorY - 3, W, 1, P.trimL);
    g.r(0, floorY, W, H - floorY, P.floor);
    g.r(0, floorY, W, 1, P.floorD);
    if (o.tiles) {
      for (y = floorY + 1; y < H; y++) {
        var row = fl((y - floorY - 1) / 3);
        for (x = 0; x < W; x++) {
          if (((fl(x / 6) + row) & 1) === 0) g.p(x, y, o.tiles);
        }
      }
      return;
    }
    var gap = 2;
    var ly = floorY + 1;
    var k = 0;
    while (ly < H) {
      var off = fl(hash(env.seed, k) * 30);
      for (x = off; x < W; x += 30 + k * 5) g.r(x, ly, 1, Math.min(gap, H - ly), P.floorD);
      ly += gap;
      if (ly < H) g.r(0, ly, W, 1, P.floorD);
      ly += 1;
      gap++;
      k++;
    }
  }

  function win(g, env, x, y, w, h, t, o) {
    var P = env.P;
    o = o || {};
    var shape = o.shape || 'rect';
    var fr = o.frame || P.frame;
    var frD = mix(fr, P.ink, 0.35);
    var cx = x + (w - 1) / 2;
    var rad = w / 2;
    var px;
    var py;
    function inside(qx, qy, ins) {
      if (shape === 'round') {
        var dx = qx - cx;
        var dy = qy - (y + (h - 1) / 2);
        return dx * dx + dy * dy <= (rad - ins) * (rad - ins);
      }
      if (qx < x + ins || qx >= x + w - ins || qy >= y + h - ins) return false;
      if (shape === 'arch' && qy < y + rad) {
        var ax = qx - cx;
        var ay = qy - (y + rad);
        return ax * ax + ay * ay <= (rad - ins) * (rad - ins);
      }
      return qy >= y + ins;
    }
    // recess shadow on wall
    for (py = y + 1; py < y + h + 1; py++) {
      for (px = x + 1; px < x + w + 1; px++) {
        if (inside(px - 1, py - 1, 0) && !inside(px, py, 0)) g.b(px, py, P.ink, 0.15);
      }
    }
    g.em = 1;
    g.clip(x, y, w, h, function (qx, qy) {
      return inside(qx, qy, 2);
    });
    outside(g, env, x + 2, y + 2, w - 4, h - 4, t, o.view);
    if (o.inner) o.inner(x + 2, y + 2, w - 4, h - 4);
    if (!o.open) {
      for (var k = 0; k < h + w; k++) {
        g.b(x + 5 + k, y + h - 3 - k, WHITE, 0.2);
        g.b(x + 6 + k, y + h - 3 - k, WHITE, 0.2);
        g.b(x + 10 + k, y + h - 3 - k, WHITE, 0.12);
      }
    }
    g.noclip();
    g.em = 0;
    for (py = y; py < y + h; py++) {
      for (px = x; px < x + w; px++) {
        if (inside(px, py, 0) && !inside(px, py, 2)) g.p(px, py, inside(px, py, 1) ? frD : fr);
      }
    }
    if (!o.open && o.panes !== 1) {
      g.clip(x, y, w, h, function (qx, qy) {
        return inside(qx, qy, 1);
      });
      var mx = fl(cx);
      g.r(mx, y, 2, h, fr);
      g.r(mx + 1, y + 1, 1, h - 2, frD);
      if (o.panes === 4 || shape === 'arch' || shape === 'round') {
        var hy = shape === 'arch' ? y + fl(rad) : y + fl(h * 0.46);
        g.r(x, hy, w, 2, fr);
        g.r(x, hy + 1, w, 1, frD);
      }
      g.noclip();
    }
    if (o.open) {
      // casement leaves swung into the room, swaying in the breeze
      var sway = Math.sin(t * 0.35) * 0.8 + (o.flutter || 0) * 0.5;
      [-1, 1].forEach(function (side) {
        var lw = clamp(rnd(5 + sway), 4, 8);
        for (var c = 0; c < lw; c++) {
          var ex = rnd((c * 3) / lw);
          var colx = side < 0 ? x - 1 - c : x + w + c;
          for (var yy = y - ex; yy < y + h + ex; yy++) {
            var edge = c === 0 || c === lw - 1 || yy < y - ex + 2 || yy >= y + h + ex - 2;
            g.p(colx, yy, edge ? (c === lw - 1 ? fr : frD) : mix(env.skyLow, P.wall, 0.45));
          }
        }
        for (var s = 0; s < h; s += 3) g.b(side < 0 ? x - 3 : x + w + 2, y + 4 + s, WHITE, 0.25);
      });
    }
    if (o.sill !== false) {
      g.r(x - 3, y + h, w + 6, 2, P.woodL);
      g.r(x - 3, y + h + 2, w + 6, 1, P.woodD);
      g.br(x - 2, y + h + 3, w + 4, 1, P.ink, 0.15);
    }
    if (o.curtain) curtains(g, env, x, y, w, h, t, o);
    g.light(x + w / 2, y + h * 0.7, o.lightR || 70, env.T.win, env.winK, { sy: 1.3 });
  }

  function curtains(g, env, x, y, w, h, t, o) {
    var P = env.P;
    var cc = o.curtain;
    var cL = mix(cc, WHITE, 0.28);
    var cD = mix(cc, P.ink, 0.28);
    var cDD = mix(cc, P.ink, 0.45);
    var cw = o.cw || 11;
    var top = y - 3;
    var len = h + 7;
    var amp = o.flutter || 0;
    g.r(x - cw - 1, top - 1, w + cw * 2 + 2, 1, P.metalD);
    g.r(x - cw - 2, top - 2, 2, 3, P.metalD);
    g.r(x + w + cw, top - 2, 2, 3, P.metalD);
    [-1, 1].forEach(function (side) {
      var tieR = rnd(len * 0.62);
      var outer = side < 0 ? x - cw + 4 : x + w + cw - 5;
      for (var r = 0; r < len; r++) {
        var wr = cw;
        if (o.tie) {
          wr = r < tieR ? cw - cw * 0.5 * Math.pow(r / tieR, 1.6) : cw * 0.5 + cw * 0.35 * ((r - tieR) / (len - tieR));
        }
        var fr = r / len;
        if (amp) wr += amp * (0.5 + 0.5 * Math.sin(r * 0.28 - t * 0.8 + (side > 0 ? 1.4 : 0))) * fr * 1.6;
        wr = Math.max(3, rnd(wr));
        var phase = amp ? Math.sin(r * 0.22 - t * 0.6 + side) * amp * 0.5 * fr : 0;
        for (var c = 0; c < wr; c++) {
          var u = (c + phase) / wr;
          var fold = mod(u * 3.2 + 0.15, 1);
          var col = fold < 0.22 ? cL : (fold > 0.72 ? cD : cc);
          if (c === wr - 1) col = cDD;
          if (r === len - 1) col = cD;
          g.p(side < 0 ? outer + c : outer - c, top + r, col);
        }
        if (o.tie && Math.abs(r - tieR) < 1) {
          var tx = side < 0 ? outer : outer - wr + 1;
          g.r(tx, top + r, wr, 2, P.accentD);
        }
      }
    });
  }

  function desk(g, env, x, y, w, depth) {
    var P = env.P;
    g.br(x, y - 1, w, 1, P.ink, 0.15);
    g.r(x, y, w, depth, P.wood);
    g.r(x, y, w, 1, P.woodG);
    for (var gy = y + 2; gy < y + depth - 1; gy += 3) {
      for (var gx = x + mod(gy * 7, 13); gx < x + w - 6; gx += 19) g.r(gx, gy, 7, 1, P.woodG);
    }
    g.r(x, y + depth, w, 1, P.woodL);
    g.r(x, y + depth + 1, w, 2, P.woodD);
    var ay = y + depth + 3;
    g.r(x + 3, ay, w - 6, 6, P.woodDD);
    g.r(x + fl(w / 2) - 12, ay + 1, 24, 4, P.woodD);
    g.r(x + fl(w / 2) - 1, ay + 2, 3, 1, P.metalL);
    g.r(x + 3, ay, 3, H - ay, P.woodDD);
    g.r(x + w - 6, ay, 3, H - ay, P.woodDD);
    g.r(x + 4, ay + 6, 1, H - ay - 6, P.woodD);
    g.r(x + w - 5, ay + 6, 1, H - ay - 6, P.woodD);
  }

  function steam(g, x, y, t, len, salt) {
    for (var s = 0; s < 2; s++) {
      for (var k = 0; k < len; k++) {
        if (mod(k + fl(t * 0.9) + s * 3, 7) > 3) continue;
        var px = x + s * 2 - 1 + rnd(Math.sin((k + t * 0.9) * 0.45 + salt + s * 2) * (0.6 + k / len) * 1.4);
        var a = 0.75 * (1 - k / len);
        if (bay(px, y - k) < a + 0.15) g.b(px, y - k, WHITE, 0.6 * (1 - k / len) + 0.2);
      }
    }
  }

  function puffs(g, x, y, t, n, life, salt) {
    for (var i = 0; i < n; i++) {
      var a = mod(t + i * (life / n) + salt, life);
      var f = a / life;
      var px = x + Math.sin(i * 2.1 + a * 0.2) * 3 * f + (hash(salt, i) - 0.5) * 5;
      var py = y - a * 1.1;
      var r = 1.5 + f * 3;
      var al = 1 - f;
      for (var dy = -5; dy <= 5; dy++) {
        for (var dx = -5; dx <= 5; dx++) {
          var d2 = dx * dx + dy * dy;
          if (d2 > r * r) continue;
          if (d2 < (r - 1) * (r - 1)) g.b(px + dx, py + dy, WHITE, 0.55 * al);
          else if (((fl(px + dx) + fl(py + dy)) & 1) === 0) g.b(px + dx, py + dy, '#c4d0de', 0.5 * al);
        }
      }
    }
  }

  function mug(g, env, x, yb, c, t, hot) {
    var P = env.P;
    var cD = mix(c, P.ink, 0.3);
    g.r(x, yb - 6, 6, 6, c);
    g.r(x + 5, yb - 6, 1, 6, cD);
    g.r(x, yb - 1, 6, 1, cD);
    g.r(x, yb - 6, 6, 1, mix(c, WHITE, 0.35));
    g.r(x + 1, yb - 6, 4, 1, '#6b4532');
    g.p(x + 6, yb - 5, c);
    g.p(x + 7, yb - 4, c);
    g.p(x + 7, yb - 3, c);
    g.p(x + 6, yb - 2, cD);
    g.br(x - 1, yb, 9, 1, P.ink, 0.2);
    if (hot) steam(g, x + 3, yb - 7, t, 10, x);
  }

  function plant(g, env, x, yb, kind, t, salt) {
    var P = env.P;
    var sw = Math.sin(t * 0.25 + x) > 0.6 ? 1 : 0;
    var r;
    if (kind === 'trail') {
      g.r(x - 3, yb - 5, 7, 5, P.pot);
      g.r(x - 3, yb - 5, 7, 1, P.potL);
      g.r(x + 3, yb - 4, 1, 4, P.potD);
      g.ell(x, yb - 7, 4, 2, P.leafD);
      g.ell(x - 1, yb - 8, 2, 1, P.leaf);
      for (var v = 0; v < 3; v++) {
        var vx = x - 3 + v * 3;
        var vl = 6 + fl(hash(salt || 1, v) * 10);
        for (var k = 0; k < vl; k++) {
          var px = vx + rnd(Math.sin(k * 0.5 + v + t * 0.15) * 0.8);
          g.p(px, yb - 3 + k, P.leafD);
          if (k % 3 === 1) g.p(px + (k % 2 ? 1 : -1), yb - 3 + k, P.leaf);
        }
      }
      return;
    }
    // pot
    g.r(x - 4, yb - 7, 9, 2, P.potL);
    g.r(x - 4, yb - 7, 9, 1, mix(P.potL, WHITE, 0.2));
    for (r = 0; r < 5; r++) {
      var ins = r > 3 ? 1 : 0;
      g.r(x - 3 + ins, yb - 5 + r, 7 - ins * 2, 1, P.pot);
      g.p(x + 3 - ins, yb - 5 + r, P.potD);
    }
    g.br(x - 4, yb, 9, 1, P.ink, 0.2);
    if (kind === 'cactus') {
      g.r(x - 1, yb - 15, 3, 8, P.leaf);
      g.r(x + 1, yb - 15, 1, 8, P.leafD);
      g.r(x - 3, yb - 12, 2, 1, P.leaf);
      g.r(x - 3, yb - 14, 1, 2, P.leaf);
      g.r(x + 2, yb - 11, 2, 1, P.leafD);
      g.r(x + 3, yb - 13, 1, 2, P.leafD);
      g.p(x, yb - 16, '#ff8fb0');
      g.p(x - 1, yb - 16, '#ffc0d4');
    } else if (kind === 'tall') {
      for (var b = 0; b < 5; b++) {
        var bx = x - 3 + b * 1.5;
        var bh = 8 + fl(hash(salt || 3, b) * 6);
        for (var k2 = 0; k2 < bh; k2++) {
          g.p(rnd(bx + (b - 2) * k2 * 0.12), yb - 7 - k2, b % 2 ? P.leafD : P.leaf);
        }
      }
    } else if (kind === 'flower') {
      var fc = [P.accent, P.accent2, '#ffffff', '#ffd25a'];
      for (var f = 0; f < 3; f++) {
        var fx = x - 3 + f * 3;
        var fh = 6 + fl(hash(salt || 5, f) * 5);
        g.r(fx, yb - 6 - fh, 1, fh, P.leafD);
        g.p(fx + 1, yb - 9, P.leaf);
        var c = fc[(f + (salt || 0)) % fc.length];
        g.p(fx, yb - 7 - fh, c);
        g.p(fx - 1, yb - 6 - fh, c);
        g.p(fx + 1, yb - 6 - fh, c);
        g.p(fx, yb - 5 - fh, c);
        g.p(fx, yb - 6 - fh, '#ffe27a');
      }
    } else {
      g.ell(x, yb - 11, 5, 4, P.leafD);
      g.ell(x - 2 + sw, yb - 12, 3, 3, P.leaf);
      g.ell(x + 2, yb - 13, 2, 2, P.leaf);
      g.p(x - 3 + sw, yb - 14, P.leafL);
      g.p(x + 1, yb - 15, P.leafL);
      g.p(x - 5, yb - 9, P.leaf);
      g.p(x + 5, yb - 10, P.leafD);
    }
  }

  function bookRow(g, env, x, yb, w, hmax, salt) {
    var P = env.P;
    var xx = x;
    var i = 0;
    while (xx < x + w) {
      var bw = 2 + fl(hash(env.seed + salt, i * 3 + 1) * 3);
      if (xx + bw > x + w) break;
      if (hash(env.seed + salt, i * 3 + 2) < 0.07) {
        xx += 2;
        i++;
        continue;
      }
      var bh = hmax - fl(hash(env.seed + salt, i * 3) * 5);
      var c = P.books[fl(hash(salt, i + env.seed) * P.books.length)];
      g.r(xx, yb - bh, bw, bh, c);
      g.r(xx + bw - 1, yb - bh, 1, bh, mix(c, P.ink, 0.3));
      g.r(xx, yb - bh + 2, bw, 1, mix(c, P.ink, 0.35));
      if (bh > 9 && bw > 2 && hash(salt + 5, i) < 0.4) g.r(xx, yb - bh + 4, bw - 1, 2, mix(c, WHITE, 0.45));
      xx += bw;
      i++;
    }
    return xx;
  }

  function bookshelf(g, env, x, y, w, yb, t, opt) {
    var P = env.P;
    var h = yb - y;
    opt = opt || {};
    g.r(x, y, w, h, P.woodD);
    g.r(x + 2, y + 2, w - 4, h - 2, P.back);
    g.r(x - 1, y, w + 2, 2, P.woodL);
    g.r(x - 1, y + 2, w + 2, 1, P.woodDD);
    g.r(x, y, 1, h, mix(P.woodD, WHITE, 0.15));
    var row = 0;
    for (var sy = y + 18; sy <= yb; sy += 18) {
      var end = x + w - 2;
      var item = fl(hash(env.seed + 77, row) * 5);
      if (opt.free === row) end = x + 3 + fl((w - 6) * 0.45);
      else if (item === 1 || item === 2) end = x + w - 14;
      bookRow(g, env, x + 3, sy, end - x - 3, 13, 40 + row);
      if (opt.free !== row) {
        var ix = x + w - 9;
        if (item === 1) plant(g, env, ix, sy, ['bush', 'cactus', 'tall'][row % 3], t, row);
        if (item === 2) {
          // globe on stand
          g.r(ix - 1, sy - 2, 4, 2, P.woodD);
          g.r(ix, sy - 3, 2, 1, P.metalD);
          g.ell(ix + 0.5, sy - 8, 3, 3, '#5ea3d6');
          g.p(ix - 1, sy - 9, '#7fc07a');
          g.p(ix, sy - 9, '#7fc07a');
          g.p(ix + 1, sy - 7, '#7fc07a');
          g.p(ix + 2, sy - 8, '#7fc07a');
          g.p(ix - 1, sy - 10, '#a8d6f2');
        }
      }
      if (sy + 2 <= yb) {
        g.r(x + 2, sy, w - 4, 2, P.woodL);
        g.r(x + 2, sy + 2, w - 4, 1, mix(P.back, P.ink, 0.4));
      }
      row++;
    }
  }

  function bookStack(g, env, x, yb, n, salt) {
    var P = env.P;
    var y = yb;
    for (var i = 0; i < n; i++) {
      var bw = 12 + fl(hash(salt, i) * 5);
      var bh = 2 + (hash(salt, i + 9) < 0.5 ? 1 : 0);
      var off = fl(hash(salt, i + 4) * 3) - 1;
      var c = P.books[fl(hash(salt + env.seed, i) * P.books.length)];
      y -= bh;
      g.r(x + off, y, bw, bh, c);
      g.r(x + off + bw - 2, y, 2, bh, P.paperD);
      g.r(x + off, y, bw, 1, mix(c, WHITE, 0.25));
    }
    g.br(x - 1, yb, 18, 1, P.ink, 0.2);
  }

  function clock(g, env, cx, cy, r, t) {
    var P = env.P;
    disc(g, cx, cy, r, P.woodD);
    disc(g, cx, cy, r - 1, P.paper);
    g.p(cx, cy - r + 2, P.ink);
    g.p(cx, cy + r - 2, P.ink);
    g.p(cx - r + 2, cy, P.ink);
    g.p(cx + r - 2, cy, P.ink);
    var hr = { dawn: 5.5, day: 13, sunset: 18.5, night: 23 }[env.time];
    var ha = (hr / 12) * 2 * PI;
    var ma = (t / 480) * 2 * PI + hr * 2 * PI;
    g.line(cx, cy, cx + Math.sin(ha) * (r - 3), cy - Math.cos(ha) * (r - 3), P.ink);
    g.line(cx, cy, cx + Math.sin(ma) * (r - 2), cy - Math.cos(ma) * (r - 2), P.accentD);
    g.p(cx, cy, P.accent);
  }

  function picture(g, env, x, y, w, h) {
    var P = env.P;
    g.r(x, y, w, h, P.woodD);
    g.r(x + 1, y + 1, w - 2, h - 2, P.paper);
    var ix = x + 2;
    var iy = y + 2;
    var iw = w - 4;
    var ih = h - 4;
    g.r(ix, iy, iw, ih, '#a8d4ea');
    for (var xx = 0; xx < iw; xx++) {
      var hy = rnd(ih * 0.55 + Math.sin(xx * 0.5) * 1.5);
      g.r(ix + xx, iy + hy, 1, ih - hy, xx < iw / 2 ? '#7fb87a' : '#6aa86a');
    }
    g.p(ix + iw - 3, iy + 1, '#fff2a8');
    g.br(x + 1, y + h, w, 1, P.ink, 0.15);
  }

  function lamp(g, env, x, yb, o) {
    var P = env.P;
    o = o || {};
    var c = o.color || P.accent2;
    var cD = mix(c, P.ink, 0.35);
    g.r(x - 4, yb - 2, 9, 2, P.metalD);
    g.r(x - 3, yb - 3, 7, 1, P.metal);
    var ph = o.h || 13;
    g.r(x, yb - 3 - ph, 1, ph, P.metalD);
    var ty = yb - 9 - ph;
    for (var r = 0; r < 6; r++) {
      var hw = 2 + r;
      g.r(x - hw, ty + r, hw * 2 + 1, 1, r === 5 ? cD : c);
      g.p(x - hw, ty + r, mix(c, WHITE, 0.35));
      g.p(x + hw, ty + r, cD);
    }
    g.em = 1;
    g.r(x - 3, ty + 6, 7, 1, env.time === 'day' ? '#f6efd8' : '#fff1c2');
    g.em = 0;
    g.light(x, ty + 7, o.r || 46, env.T.lamp, env.lampK, { top: ty + 4 });
  }

  // ------------------------------------------------------------ mascots

  var MASCOTS = {
    cat: {
      rows: [
        '.o.........o....',
        'obo.......obo...',
        'obio.....oibo...',
        'obbbooooobbbo...',
        'obbbbbbbbbbbo...',
        'obEbbbbbbbEbo...',
        'obebbbnbbbebo...',
        'obcbbbbbbbcbo...',
        '.obbbbbbbbbo..o.',
        '.obbllllllbo.obo',
        'obbllllllllboobo',
        'obbllllllllbbbo.',
        '.oooooooooooo...'
      ],
      c: { o: '#4a3434', b: '#f2b46a', l: '#fff3e2', e: '#2a2228', n: '#e9877f', c: '#f7a3a0', i: '#f7a3a0' }
    },
    bear: {
      rows: [
        '.ooo.....ooo.',
        'obibooooobibo',
        'obbbbbbbbbbbo',
        'obbbbbbbbbbbo',
        'obEbbbbbbbEbo',
        'obebblllbbebo',
        'obcbllnllbcbo',
        '.obbblllbbbo.',
        '.obbbbbbbbbo.',
        'obbllllllllbo',
        'obbllllllllbo',
        'obbblllllbbbo',
        '.ooooooooooo.'
      ],
      c: { o: '#3e2a22', b: '#b07a4f', l: '#ecd0a8', e: '#21161a', n: '#3e2a22', c: '#e99a8a', i: '#e3a98a' }
    },
    dog: {
      rows: [
        '...oooooooo...',
        '..obbbbbbbbo..',
        '.odbbbbbbbbdo.',
        'oddbbbbbbbbddo',
        'oddbEbbbbEbddo',
        'oddbebllbebddo',
        'oddbcllnlcbddo',
        '.odobllllbodo.',
        '..obbbbbbbbo..',
        '.obbllllllbbo.',
        '.obllllllllbo.',
        '.obllllllllbo.',
        '..oooooooooo..'
      ],
      c: { o: '#4a3326', b: '#e8ad6c', d: '#a8673e', l: '#fff4e2', e: '#24181a', n: '#2e2020', c: '#f4a29a' }
    },
    rabbit: {
      rows: [
        '..oo.....oo..',
        '.obio...oibo.',
        '.obio...oibo.',
        '.obio...oibo.',
        '.obbooooobbo.',
        'obbbbbbbbbbbo',
        'obEbbbbbbbEbo',
        'obebbbnbbbebo',
        'obcbbbbbbbcbo',
        '.obbbbbbbbbo.',
        'obbllllllllbo',
        'obbllllllllbo',
        '.ooooooooooo.'
      ],
      c: { o: '#7d6a72', b: '#fbf7f2', l: '#f1e6dc', e: '#2a2228', n: '#f08a9a', c: '#ffb3c0', i: '#ffb3c0' }
    },
    fox: {
      rows: [
        '.o.........o....',
        'odo.......odo...',
        'obdo.....odbo...',
        'obbbooooobbbo...',
        'obbbbbbbbbbbo...',
        'obEbbbbbbbEbo...',
        'obebllnllbebo...',
        'olllllllllllo...',
        '.obbbbbbbbbo.oo.',
        '.obllllllbo.owwo',
        'obbllllllbbobbbo',
        'obbllllllbbbbbo.',
        '.ooooooooooooo..'
      ],
      c: { o: '#4a2a1e', b: '#ee8236', d: '#3a2420', l: '#fff4e8', w: '#fff8f0', e: '#24181a', n: '#2a1a1a' }
    },
    penguin: {
      rows: [
        '...ooooooo...',
        '..obbbbbbbo..',
        '.obbbbbbbbbo.',
        '.obwwwbwwwbo.',
        'obwEwwwwwEwbo',
        'obwewwkwwewbo',
        'obcwwkkkwwcbo',
        '.obwwwwwwwbo.',
        'obbwwwwwwwbbo',
        'obbwwwwwwwbbo',
        '.obwwwwwwwbo.',
        '..obwwwwwbo..',
        '..kko...okk..'
      ],
      c: { o: '#1c1e2c', b: '#353b52', w: '#f6f6f2', e: '#16161e', k: '#f2a53a', c: '#f6a3b0' }
    },
    bird: {
      rows: [
        '.....oo.....',
        '....obbo....',
        '..oobbbboo..',
        '.obbbbbbbbo.',
        '.obEbbbbEbo.',
        'obbebkkbebbo',
        'obcbbkkbbcbo',
        'obdllllllbdo',
        '.odllllllbo.',
        '..obllllbo..',
        '...oooooo...',
        '....k..k....'
      ],
      c: { o: '#5a4020', b: '#ffd75a', d: '#f0b43a', l: '#fff2b8', e: '#2a2018', k: '#f08a3a', c: '#ffa98a' }
    },
    frog: {
      rows: [
        '.oooo....oooo.',
        'owwwwo..owwwwo',
        'owwEwoooowEwwo',
        'obwewbbbbwewbo',
        'obbbbbbbbbbbbo',
        'obcbbbbbbbbcbo',
        'obbboooooobbbo',
        '.obbbbbbbbbbo.',
        'obbllllllllbbo',
        'obbllllllllbbo',
        '.oobbo..obboo.'
      ],
      c: { o: '#2f5a33', b: '#7cc46a', l: '#dff2b0', w: '#ffffff', e: '#1a2418', c: '#f6a0a0' }
    },
    hamster: {
      rows: [
        '..oo......oo..',
        '.oiio....oiio.',
        '.obboooooobbo.',
        'obbbbbbbbbbbbo',
        'obbEbbbbbbEbbo',
        'obbebbnnbbebbo',
        'ocllllllllllco',
        '.ollllllllllo.',
        'obllllllllllbo',
        'obllllllllllbo',
        '.obbllllllbbo.',
        '..oooooooooo..'
      ],
      c: { o: '#6a4428', b: '#eaa865', l: '#fff6ea', e: '#24181a', n: '#e98a8a', c: '#f7a3a0', i: '#f7b3a8' }
    },
    owl: {
      rows: [
        '.oo........oo.',
        '.obo......obo.',
        '.obboooooobbo.',
        'obbbbbbbbbbbbo',
        'obwwwbbbbwwwbo',
        'obwEwbbbbwEwbo',
        'obwewbkkbwewbo',
        'obbwbbkkbbwbbo',
        '.obldlldldlbo.',
        'obbllllllllbbo',
        '.obbldlldlbbo.',
        '..okkooookko..'
      ],
      c: { o: '#3e2c22', b: '#8f6c4f', d: '#a8875f', l: '#ecd9b6', w: '#fff8e6', e: '#1e1614', k: '#f0a23a' }
    }
  };
  var ZGLYPH = ['####', '..#.', '.#..', '####'];

  (function prep() {
    Object.keys(MASCOTS).forEach(function (k) {
      var m = MASCOTS[k];
      var open = {};
      Object.keys(m.c).forEach(function (ch) {
        open[ch] = hx(m.c[ch]);
      });
      open.E = open.e;
      var shut = {};
      Object.keys(open).forEach(function (ch) {
        shut[ch] = open[ch];
      });
      shut.E = shut.w && k !== 'penguin' ? shut.w : shut.b;
      shut.e = shut.o;
      m.open = open;
      m.shut = shut;
      m.w = m.rows[0].length;
      m.h = m.rows.length;
    });
  })();

  function mascot(g, env, x, yb, t, o) {
    var m = MASCOTS[env.spec.mascot];
    if (!m) return null;
    o = o || {};
    var sleep = o.sleep != null ? o.sleep : env.time === 'night';
    var blink = !sleep && mod(t + (env.seed % 23), 37) === 0;
    var y = yb - m.h;
    g.br(x + 1, yb, m.w - 2, 1, env.P.ink, 0.28);
    var src = sleep || blink ? m.shut : m.open;
    var lift = [(1 + env.amb[0] * 1.5) / 2.5, (1 + env.amb[1] * 1.5) / 2.5, (1 + env.amb[2] * 1.5) / 2.5];
    var map = {};
    Object.keys(src).forEach(function (ch) {
      map[ch] = mul(src[ch], lift);
    });
    g.em = 1;
    g.spr(x, y, m.rows, map, !!o.flip);
    g.em = 0;
    if (sleep) {
      var ph = mod(t, 24);
      var zx = o.flip ? x - 4 : x + m.w - 2;
      var dir = o.flip ? -1 : 1;
      var zc = mix(WHITE, '#c8d4ff', 0.3);
      g.spr(zx + dir * fl(ph / 8), y - 4 - fl(ph / 4), ZGLYPH, { '#': zc });
      if (ph > 10) g.p(zx + dir * (3 + fl(ph / 6)), y - 8 - fl(ph / 3), zc);
    }
    return m;
  }

  // ---------------------------------------------------------- open book

  function textRow(g, x, y, w, seed, row, c) {
    var xx = 0;
    var k = 0;
    while (xx < w) {
      var wl = 2 + fl(hash(seed * 31 + row, k) * 4);
      for (var i = 0; i < wl && xx + i < w; i++) g.p(x + xx + i, y, c);
      xx += wl + 1;
      k++;
    }
  }

  function openBook(g, env, L, sx, top, t) {
    var P = env.P;
    var PW = 20;
    var PH = 10;
    var bottom = top + PH;
    var cover = P[L.cover] || P.accent;
    var ink = mix(P.paper, P.ink, 0.42);
    var CYC = 44;
    var TURN = 12;
    var ph = mod(t, CYC);
    var k = fl(t / CYC);
    var turning = ph < TURN;
    var leftSeed = turning ? k * 2 : k * 2 + 2;
    var rightSeed = k * 2 + 3;
    // shadow + cover + page block
    g.br(sx - PW - 2, bottom + 3, PW * 2 + 6, 1, P.ink, 0.25);
    g.r(sx - PW - 2, top + 2, PW * 2 + 5, PH + 1, cover);
    g.r(sx - PW - 2, bottom + 2, PW * 2 + 5, 1, mix(cover, P.ink, 0.35));
    g.r(sx - PW - 1, bottom, PW + 1, 2, P.paperD);
    g.r(sx + 1, bottom, PW + 1, 2, P.paperD);
    g.r(sx - PW - 1, bottom + 1, PW + 1, 1, P.paperDD);
    g.r(sx + 1, bottom + 1, PW + 1, 1, P.paperDD);
    function page(px0, dir, seed) {
      for (var c = 0; c < PW; c++) {
        var px = dir > 0 ? sx + 1 + c : sx - 1 - c;
        var dip = c < 2 ? 1 : 0;
        g.r(px, top + dip, 1, PH - dip, c < 2 ? P.paperD : P.paper);
      }
      for (var r = 0; r < 4; r++) {
        var lx = dir > 0 ? sx + 3 : sx - PW + 1;
        textRow(g, lx, top + 2 + r * 2, PW - 4 - (r === 3 ? 6 : 0), seed, r, ink);
      }
    }
    page(0, -1, leftSeed);
    page(0, 1, rightSeed);
    g.r(sx, top + 1, 1, PH, P.paperDD);
    // bookmark ribbon
    g.r(sx + 6, bottom + 1, 1, 3, P.accent2);
    g.p(sx + 7, bottom + 4, P.accent2);
    if (!turning) return;
    // the sheet flips over the spine, curling as it goes
    var a = PI * smooth(ph / (TURN - 1));
    var reach = rnd(PW * Math.abs(Math.cos(a * 0.92)) + 3);
    for (var sc2 = 1; sc2 < Math.min(PW, reach); sc2++) {
      var sxp = a < PI / 2 ? sx + sc2 : sx - sc2;
      g.br(sxp, top + 1, 1, PH - 1, P.ink, 0.12 * Math.sin(a));
    }
    var steps = PW * 4;
    var X = sx + 0.5;
    var lift = 0;
    var outline = mix(P.paper, P.ink, 0.3);
    var tops = [];
    for (var s = 0; s <= steps; s++) {
      var u = s / steps;
      var ang = clamp(a - 0.6 * Math.sin(a) * u, 0, PI);
      X += (PW / steps) * Math.cos(ang);
      lift += (PW / steps) * Math.sin(ang) * 0.82;
      var px = rnd(X);
      var ly = rnd(lift);
      var front = ang < PI / 2;
      var sh = 1 - Math.abs(Math.cos(ang));
      var col = mix(P.paper, P.paperDD, sh * 0.55 + (front ? 0 : 0.08));
      var cidx = fl(u * PW);
      for (var r = 0; r < PH; r++) {
        var py = top + r - ly;
        var cc = col;
        if (r === 2 || r === 4 || r === 6 || r === 8) {
          if (cidx > 2 && cidx < PW - 2) {
            var seed = front ? rightSeed - 2 : leftSeed + 2;
            var wl = hash(seed * 31 + (r >> 1) - 1, fl(cidx / 4));
            if (mod(cidx, 4) !== 3 && wl > 0.15 && sh < 0.8) cc = mix(col, P.ink, 0.38);
          }
        }
        g.p(px, py, cc);
      }
      tops.push([px, top - ly]);
    }
    for (var i = 0; i < tops.length; i++) g.p(tops[i][0], tops[i][1] - 1, outline);
    var last = tops[tops.length - 1];
    g.r(last[0], last[1], 1, PH, outline);
  }

  // -------------------------------------------------------------- scenes

  var SC = {};

  SC.study = {
    still: 6,
    setup: function (env, R) {
      return {
        wallStyle: R.i(0, 3),
        view: R.pick(['trees', 'hills', 'trees', 'city']),
        shape: R.pick(['rect', 'arch', 'rect']),
        wx: R.i(10, 16),
        ww: R.pick([42, 46, 48]),
        cur: R.pick(['accent2', 'accent', 'rug']),
        cover: R.pick(['accent', 'accent2', 'leaf', 'woodD']),
        wallItem: R.pick(['clock', 'picture', 'clock']),
        stack: R.ch(0.75),
        spot: R.pick(['desk', 'desk', 'shelf']),
        lampC: R.pick(['accent2', 'accent', 'leaf'])
      };
    },
    draw: function (g, env, L, t) {
      var P = env.P;
      room(g, env, L, 66);
      var ph = mod(t, 44);
      var gust = ph >= 38 || ph < 15 ? 1 : 0;
      var wx = L.wx;
      var ww = L.ww;
      win(g, env, wx, 5, ww, 38, t, { open: true, shape: L.shape, view: L.view, curtain: P[L.cur], flutter: 1 + gust * 2.5, cw: 10 });
      if (L.wallItem === 'clock') clock(g, env, 102, 19, 6, t);
      else picture(g, env, 92, 11, 20, 15);
      bookshelf(g, env, 128, 3, 58, 77, t, { free: L.spot === 'shelf' ? 2 : -1 });
      desk(g, env, 2, 44, 120, 9);
      // patch of daylight from the window onto the desk
      if (env.time !== 'night' && env.weather !== 'rain') {
        for (var py = 44; py < 53; py++) {
          for (var px = wx + 2 + (py - 44); px < wx + ww - 2 + (py - 44); px++) {
            if (bay(px, py) < 0.5) g.b(px, py, env.time === 'day' ? '#fff8dc' : '#ffc890', 0.3);
          }
        }
      }
      var bx = wx + fl(ww / 2) + 8;
      if (L.stack) bookStack(g, env, 100, 50, 3, 7);
      openBook(g, env, L, bx, 44, t);
      lamp(g, env, bx + 33, 50, { color: P[L.lampC], r: 40 });
      mug(g, env, bx + 43, 51, P.paper, t, env.time !== 'day');
      if (L.spot === 'shelf') mascot(g, env, 160, 57, t, { flip: true });
      else mascot(g, env, 2, 51, t);
      if (env.weather === 'petals' || env.weather === 'leaves') {
        var cols = env.weather === 'leaves' ? ['#e9873b', '#d0533a', '#f2b648'] : ['#ffc4d6', '#ff9fbf', '#ffe3ec'];
        for (var i = 0; i < 5; i++) {
          var tt = mod(t * 1.1 + i * 31, 150);
          var qx = wx + ww - 4 + tt;
          var qy = 14 + i * 5 + tt * 0.22 + Math.sin(t * 0.3 + i) * 2;
          g.p(qx, qy, cols[i % 3]);
          g.p(qx + (mod(t + i, 3) === 0 ? 0 : 1), qy + (mod(t + i, 3) === 0 ? 1 : 0), cols[i % 3]);
        }
      }
    }
  };

  function codeScreen(g, env, x, y, w, h, t) {
    var P = env.P;
    g.em = 1;
    g.r(x, y, w, h, '#1d2035');
    g.r(x, y, w, 2, '#2b304d');
    g.r(x + 2, y, 12, 2, '#3c4470');
    g.r(x + 15, y, 10, 2, '#262b44');
    g.r(x, y + h - 1, w, 1, P.accent);
    g.r(x, y + 2, 11, h - 3, '#252a42');
    for (var fi = 0; fi < 9; fi++) {
      var fw = 3 + fl(hash(env.seed + 3, fi) * 5);
      var fx0 = x + 2 + (fi > 0 && hash(env.seed, fi) < 0.6 ? 2 : 0);
      g.r(fx0, y + 4 + fi * 3, fw, 1, fi === 2 ? '#c8d0ff' : '#5f688f');
    }
    x += 11;
    w -= 11;
    var syn = ['#ff7eb0', '#7fd8ff', '#ffd47a', '#a6f0a0', '#c7a8ff', '#e8e8f0'];
    var CL = 96;
    var cyc = fl(t / CL);
    var reveal = mod(t, CL) * 4;
    var lines = fl((h - 4) / 2);
    var used = 0;
    var cur = null;
    var ind = 0;
    for (var li = 0; li < lines; li++) {
      var ly = y + 3 + li * 2;
      g.p(x + 1, ly, '#3a4166');
      var r0 = hash(cyc * 97 + li, 5);
      if (li > 0 && r0 < 0.3 && ind < 3) ind++;
      else if (r0 > 0.78 && ind > 0) ind--;
      if (hash(cyc * 97 + li, 9) < 0.1) continue;
      var lx = x + 4 + ind * 3;
      var maxW = w - 6 - ind * 3;
      var len = 6 + fl(hash(cyc * 97 + li, 7) * (maxW - 6));
      var xx = 0;
      var k = 0;
      var comment = hash(cyc * 97 + li, 11) < 0.12;
      while (xx < len) {
        var tl = 2 + fl(hash(cyc * 131 + li, k) * 5);
        var col = comment ? '#5d6690' : syn[fl(hash(cyc * 17 + li, k * 3) * syn.length)];
        for (var i = 0; i < tl && xx + i < len; i++) {
          if (used >= reveal) {
            if (!cur) cur = [lx + xx + i, ly];
          } else {
            g.p(lx + xx + i, ly, col);
          }
          used++;
        }
        xx += tl + 1;
        used++;
        k++;
      }
      if (!cur && used >= reveal) cur = [lx + len + 1, ly];
    }
    if (!cur) cur = [x + 4, y + 3 + (lines - 1) * 2];
    if (mod(t, 4) < 2) g.r(cur[0], cur[1] - 1, 2, 2, '#ffffff');
    g.em = 0;
  }

  SC.desk = {
    still: 40,
    setup: function (env, R) {
      return {
        wallStyle: R.i(0, 3),
        view: R.pick(['city', 'city', 'hills', 'trees']),
        shape: R.pick(['rect', 'rect', 'arch']),
        cur: R.pick(['accent2', 'paper', 'accent']),
        deskPlant: R.pick(['bush', 'cactus', 'tall']),
        mugC: R.pick(['accent', 'accent2', 'paper']),
        notes: R.i(1, 3),
        spot: R.pick(['desk', 'shelf', 'desk']),
        lampC: R.pick(['accent', 'accent2'])
      };
    },
    draw: function (g, env, L, t) {
      var P = env.P;
      room(g, env, L, 66);
      win(g, env, 8, 6, 40, 34, t, { shape: L.shape, panes: 4, view: L.view, curtain: P[L.cur], tie: true, cw: 9 });
      // sticky notes
      var nc = ['#ffe27a', '#ffb3c6', '#a8e6cf'];
      for (var i = 0; i < L.notes; i++) {
        g.r(126 + i * 6, 12 + (i % 2) * 3, 5, 5, nc[i]);
        g.r(126 + i * 6, 16 + (i % 2) * 3, 5, 1, mix(nc[i], P.ink, 0.2));
        g.p(127 + i * 6, 14 + (i % 2) * 3, mix(nc[i], P.ink, 0.4));
        g.p(128 + i * 6, 14 + (i % 2) * 3, mix(nc[i], P.ink, 0.4));
      }
      // wall shelf
      g.r(144, 26, 44, 2, P.woodL);
      g.r(144, 28, 44, 1, P.woodD);
      g.r(148, 29, 2, 3, P.metalD);
      g.r(182, 29, 2, 3, P.metalD);
      var end = bookRow(g, env, 146, 26, L.spot === 'shelf' ? 14 : 24, 10, 11);
      plant(g, env, L.spot === 'shelf' ? 182 : 178, 26, 'trail', t, 4);
      if (L.spot === 'shelf') mascot(g, env, end + 2, 26, t, { flip: true });
      desk(g, env, 2, 46, 188, 8);
      // monitor
      var mx = 60;
      var my = 6;
      g.r(mx, my, 62, 37, '#2a2c3a');
      g.r(mx, my, 62, 1, '#45485c');
      g.r(mx, my, 1, 37, '#45485c');
      g.r(mx + 61, my, 1, 37, '#1c1d28');
      g.r(mx + 28, my + 34, 6, 1, '#45485c');
      codeScreen(g, env, mx + 3, my + 3, 56, 30, t);
      g.r(mx + 27, my + 37, 8, 4, '#3a3c4c');
      g.r(mx + 21, my + 41, 20, 2, '#4a4c5e');
      g.r(mx + 21, my + 41, 20, 1, '#5e6174');
      g.light(mx + 31, my + 30, 46, [0.55, 0.72, 1], env.time === 'night' ? 0.9 : 0.15, { top: my + 34, upS: 1.4 });
      // keyboard + mouse
      g.r(70, 50, 42, 4, P.metalD);
      g.r(70, 50, 42, 3, P.metal);
      for (var ky = 50; ky < 53; ky++) {
        for (var kx = 71 + (ky & 1); kx < 111; kx += 2) g.p(kx, ky, P.metalL);
      }
      g.r(118, 51, 4, 3, P.metal);
      g.p(119, 51, P.metalL);
      mug(g, env, 132, 53, P[L.mugC], t, true);
      plant(g, env, 22, 52, L.deskPlant, t, 2);
      lamp(g, env, 170, 52, { color: P[L.lampC] });
      if (L.spot === 'desk') mascot(g, env, 36, 52, t);
    }
  };

  SC.kitchen = {
    still: 3,
    setup: function (env, R) {
      return {
        wallStyle: R.pick([0, 2]),
        view: R.pick(['hills', 'trees', 'city']),
        shape: R.pick(['rect', 'arch', 'rect']),
        cur: R.pick(['accent', 'paper', 'accent2']),
        potC: R.pick(['accent', 'accent2']),
        kettle: R.ch(0.6),
        jars: R.i(3, 5),
        tile: R.pick(['accent2', 'paper', 'accent'])
      };
    },
    draw: function (g, env, L, t) {
      var P = env.P;
      var x;
      var y;
      room(g, env, L, H);
      // backsplash tiles
      var tc = mix(P[L.tile], WHITE, 0.55);
      var grout = mix(tc, P.ink, 0.15);
      g.r(0, 34, W, 18, tc);
      for (y = 34; y < 52; y += 4) g.r(0, y, W, 1, grout);
      for (y = 34; y < 52; y += 4) {
        for (x = ((y / 4) & 1) * 3; x < W; x += 6) g.r(x, y, 1, 4, grout);
      }
      // range hood
      for (y = 0; y < 16; y++) {
        var hw = y < 10 ? 10 : 10 + (y - 10) * 2.5;
        g.r(40 - hw, y, hw * 2, 1, y < 10 ? P.metalD : P.metal);
      }
      g.r(25, 15, 30, 2, P.metalD);
      g.em = 1;
      g.r(30, 17, 20, 1, env.time === 'day' ? '#e8e2cc' : '#fff1c0');
      g.em = 0;
      g.light(40, 19, 44, env.T.lamp, env.lampK * 0.9, { top: 17, upS: 6 });
      // shelf with jars
      g.r(70, 14, 36, 2, P.woodL);
      g.r(70, 16, 36, 1, P.woodD);
      var jc = [P.accent, P.accent2, P.leaf, P.woodL, P.paperD];
      for (var j = 0; j < L.jars; j++) {
        var jx = 72 + j * 7;
        var jh = 6 + fl(hash(env.seed, j) * 4);
        g.r(jx, 14 - jh, 5, jh, mix('#e8f4f8', jc[(j + env.seed) % jc.length], 0.25));
        g.r(jx + 1, 14 - jh + 2, 3, jh - 3, jc[(j + env.seed) % jc.length]);
        g.r(jx, 14 - jh - 1, 5, 1, P.woodD);
        g.p(jx, 14 - jh + 1, WHITE);
      }
      // utensil rail
      g.r(70, 21, 36, 1, P.metalD);
      var ux = [74, 81, 88, 97];
      for (var u = 0; u < 4; u++) {
        var sx = ux[u];
        var sw = fl(Math.sin(t * 0.2 + u) * 0.8);
        g.p(sx, 22, P.metalD);
        if (u === 0) {
          g.r(sx, 23, 1, 7, P.metal);
          g.ell(sx + sw, 31, 2, 1, P.metal);
          g.p(sx - 1 + sw, 31, P.metalD);
        } else if (u === 1) {
          g.r(sx, 23, 1, 6, P.woodD);
          g.r(sx - 1 + sw, 29, 3, 4, P.metalL);
        } else if (u === 2) {
          g.r(sx, 23, 1, 4, P.woodD);
          g.r(sx - 1, 27, 3, 1, P.metal);
          g.r(sx - 2 + sw, 28, 1, 4, P.metal);
          g.r(sx + 2 + sw, 28, 1, 4, P.metal);
          g.r(sx - 1 + sw, 32, 3, 1, P.metal);
        } else {
          g.r(sx, 23, 1, 4, P.woodD);
          g.ell(sx + sw, 31, 4, 4, P.metalD);
          g.ell(sx + sw, 31, 3, 3, mix(P.metalD, P.ink, 0.3));
        }
      }
      win(g, env, 120, 6, 46, 32, t, { shape: L.shape, panes: 2, view: L.view, curtain: P[L.cur], tie: true, cw: 9 });
      // counter + cabinets
      g.r(0, 51, W, 3, mix(P.paper, P.metal, 0.4));
      g.r(0, 51, W, 1, mix(P.paper, WHITE, 0.4));
      g.r(0, 54, W, 2, mix(P.metal, P.ink, 0.2));
      g.r(0, 56, W, H - 56, P.wood);
      for (x = 2; x < W; x += 24) {
        g.r(x, 59, 21, H - 59, P.woodL);
        g.r(x + 1, 60, 19, H - 61, P.wood);
        g.r(x + 1, 60, 19, 1, P.woodD);
        g.r(x + 9, 63, 3, 1, P.metalL);
      }
      // stove top + oven front
      g.r(14, 49, 52, 3, '#3a3a44');
      g.r(14, 49, 52, 1, '#55555f');
      g.r(14, 56, 52, H - 56, mix(P.metal, P.ink, 0.15));
      g.r(18, 62, 44, 14, '#2a2a33');
      g.r(20, 64, 40, 10, '#3c3a44');
      g.r(18, 58, 44, 1, P.metalL);
      for (var kn = 0; kn < 4; kn++) g.r(22 + kn * 11, 57, 2, 2, P.metalD);
      // flames under the pot
      g.em = 1;
      for (var fx = 25; fx < 48; fx += 3) {
        var fh = mod(fx + t, 3) === 0 ? 2 : 1;
        g.r(fx, 48 - fh + 1, 1, fh, mod(fx + t, 2) ? '#6fb7ff' : '#ffb347');
      }
      g.em = 0;
      g.light(36, 48, 22, [1, 0.6, 0.3], 0.35, { n: 3 });
      // pot
      var pc = P[L.potC];
      var pD = mix(pc, P.ink, 0.35);
      var pL = mix(pc, WHITE, 0.35);
      g.r(25, 37, 22, 11, pc);
      g.r(25, 37, 1, 11, pL);
      g.r(26, 38, 1, 9, pL);
      g.r(45, 37, 2, 11, pD);
      g.r(25, 47, 22, 1, pD);
      g.r(25, 37, 22, 1, P.paper);
      g.r(22, 39, 3, 2, P.metalD);
      g.r(47, 39, 3, 2, P.metalD);
      var jig = mod(t, 9) < 2 ? 1 : 0;
      g.r(26, 36 - jig, 20, 1, P.metal);
      g.r(28, 35 - jig, 16, 1, P.metal);
      g.r(31, 34 - jig, 10, 1, P.metalL);
      g.r(34, 32 - jig, 4, 2, P.metalD);
      if (jig) {
        g.em = 0;
        g.p(25, 36, WHITE);
        g.p(46, 36, WHITE);
      }
      puffs(g, 36, 30, t, 4, 20, 3);
      // kettle on the back burner
      if (L.kettle) {
        var kc = P[L.potC === 'accent' ? 'accent2' : 'accent'];
        g.ell(58, 44, 5, 4, kc);
        g.r(53, 47, 11, 1, mix(kc, P.ink, 0.35));
        g.ell(56, 42, 1, 1, mix(kc, WHITE, 0.4));
        g.r(57, 39, 3, 1, P.metalD);
        g.line(54, 40, 57, 37, P.metalD);
        g.line(57, 37, 62, 40, P.metalD);
        g.p(64, 43, kc);
        g.p(65, 42, kc);
        steam(g, 66, 40, t, 8, 9);
      }
      // cutting board with veggies
      g.r(76, 48, 26, 3, P.woodL);
      g.r(76, 50, 26, 1, P.woodD);
      g.r(80, 46, 9, 2, '#f08a3a');
      g.r(81, 46, 7, 1, '#f6a65a');
      g.p(89, 46, '#5fb05a');
      g.p(90, 45, '#5fb05a');
      g.ell(95, 46, 2, 2, '#e8483a');
      g.p(94, 45, '#ff8070');
      g.p(95, 43, '#4f9a4a');
      // utensil crock
      g.r(176, 43, 8, 8, P.accent2);
      g.r(176, 43, 8, 1, mix(P.accent2, WHITE, 0.3));
      g.r(183, 44, 1, 7, mix(P.accent2, P.ink, 0.3));
      g.line(178, 42, 176, 34, P.woodD);
      g.line(181, 42, 182, 33, P.woodD);
      g.ell(176, 33, 1, 1, P.woodD);
      g.r(181, 31, 3, 2, P.woodD);
      mascot(g, env, 150, 51, t, { flip: true });
    }
  };

  SC.cafe = {
    still: 10,
    setup: function (env, R) {
      return {
        lampC: R.pick(['accent', 'accent2', 'metal']),
        cup: R.pick(['paper', 'accent', 'accent2']),
        panes: R.pick([2, 3, 3, 4]),
        transom: R.ch(0.7),
        item: R.pick(['cake', 'book', 'croissant']),
        tiles: R.pick(['paper', 'accent2', 'wood']),
        spot: R.pick(['sill', 'sill', 'chair'])
      };
    },
    draw: function (g, env, L, t) {
      var P = env.P;
      var x;
      room(g, env, { wallStyle: 0 }, 64, { wainscot: true, wainY: 50, tiles: mix(P.floor, P[L.tiles], 0.6) });
      var rain = env.weather === 'rain';
      win(g, env, 6, 5, 116, 44, t, {
        panes: 1,
        view: 'street',
        sill: true,
        lightR: 90,
        inner: function (ix, iy, iw, ih) {
          if (rain) {
            for (var d = 0; d < 24; d++) {
              var dx = ix + fl(hash(env.seed + d, 41) * iw);
              var sp = 0.25 + hash(env.seed + d, 42) * 0.5;
              var dy = iy + mod(hash(env.seed + d, 43) * ih + t * sp, ih + 6) - 3;
              g.b(dx, dy - 3, WHITE, 0.15);
              g.b(dx, dy - 2, WHITE, 0.2);
              g.b(dx, dy - 1, WHITE, 0.3);
              g.b(dx, dy, WHITE, 0.55);
              g.b(dx + 1, dy, WHITE, 0.25);
            }
          }
          // window frame divisions (cafe style)
          g.em = 0;
        }
      });
      // mullions + transom
      for (var mi = 1; mi < L.panes; mi++) {
        var mx = 6 + rnd((116 * mi) / L.panes) - 1;
        g.r(mx, 5, 2, 44, P.frame);
        g.r(mx + 1, 6, 1, 42, mix(P.frame, P.ink, 0.35));
      }
      if (L.transom) {
        g.r(6, 15, 116, 2, P.frame);
        g.r(6, 16, 116, 1, mix(P.frame, P.ink, 0.35));
      }
      // pendant lamps
      [34, 94].forEach(function (lx) {
        g.r(lx, 0, 1, 10, P.ink);
        var c = P[L.lampC];
        g.ell(lx, 13, 5, 3, c);
        g.r(lx - 6, 15, 13, 1, mix(c, P.ink, 0.3));
        g.p(lx - 3, 12, mix(c, WHITE, 0.4));
        g.em = 1;
        g.r(lx - 2, 16, 5, 1, env.time === 'day' ? '#f6efd8' : '#fff1c2');
        g.em = 0;
        g.light(lx, 17, 44, env.T.lamp, env.lampK, { top: 14 });
      });
      // chalkboard menu
      g.r(134, 6, 46, 24, P.woodD);
      g.r(136, 8, 42, 20, '#2f3d3a');
      for (var r = 0; r < 4; r++) {
        textRow(g, 139, 11 + r * 4, 22 + fl(hash(env.seed, r) * 10), env.seed + 5, r, '#dfe8e0');
      }
      g.r(168, 10, 6, 5, '#dfe8e0');
      g.r(169, 11, 4, 3, '#2f3d3a');
      g.p(174, 12, '#dfe8e0');
      // counter + espresso machine
      g.r(128, 44, 64, 3, P.woodL);
      g.r(128, 47, 64, 1, P.woodD);
      g.r(128, 48, 64, 32, P.accent2D);
      for (x = 130; x < W; x += 4) g.r(x, 49, 1, 31, mix(P.accent2D, P.ink, 0.3));
      g.r(140, 30, 22, 14, P.metal);
      g.r(140, 30, 22, 2, P.metalL);
      g.r(142, 34, 18, 1, P.metalD);
      g.r(146, 36, 10, 2, P.metalD);
      g.r(148, 38, 2, 2, P.metalD);
      g.r(153, 38, 2, 2, P.metalD);
      g.em = 1;
      g.p(158, 33, mod(t, 16) < 8 ? '#ff6a5a' : '#6aff8a');
      g.em = 0;
      for (var cu = 0; cu < 3; cu++) g.r(166 + cu * 5, 40, 4, 4, P.paper);
      plant(g, env, 184, 44, 'bush', t, 8);
      // table
      g.r(30, 54, 66, 2, P.woodL);
      g.r(30, 56, 66, 2, P.woodD);
      g.r(61, 58, 4, 14, P.woodDD);
      g.r(53, 72, 20, 2, P.woodDD);
      g.br(50, 74, 26, 1, P.ink, 0.3);
      // chairs
      g.r(18, 46, 2, 30, P.woodDD);
      g.r(18, 46, 8, 2, P.woodDD);
      g.r(18, 60, 12, 2, P.woodD);
      g.r(28, 62, 2, 14, P.woodDD);
      g.r(106, 46, 2, 30, P.woodDD);
      g.r(100, 46, 8, 2, P.woodDD);
      g.r(96, 60, 12, 2, P.woodD);
      g.r(96, 62, 2, 14, P.woodDD);
      // cup + saucer + cake
      var cc = P[L.cup];
      g.r(40, 53, 12, 1, P.paperD);
      g.r(42, 52, 8, 1, P.paper);
      g.r(42, 47, 7, 5, cc);
      g.r(42, 47, 7, 1, '#8a5a3a');
      g.p(44, 47, '#e8c9a0');
      g.p(45, 47, '#e8c9a0');
      g.p(49, 48, cc);
      g.p(50, 49, cc);
      g.p(49, 50, cc);
      steam(g, 45, 45, t, 11, 2);
      if (L.item === 'cake') {
        g.r(68, 53, 12, 1, P.paperD);
        g.r(70, 49, 7, 4, '#f6e2b8');
        g.r(70, 49, 7, 1, P.accentL);
        g.r(70, 51, 7, 1, P.accent);
        g.p(73, 48, '#e8483a');
      } else if (L.item === 'croissant') {
        g.r(67, 53, 13, 1, P.paperD);
        pastry(g, env, 0, 69, 51);
      } else {
        g.r(64, 52, 16, 2, P.accent2);
        g.r(65, 51, 7, 1, P.paper);
        g.r(73, 51, 6, 1, P.paper);
        g.r(72, 51, 1, 2, P.accent2D);
      }
      // small vase
      g.r(85, 49, 3, 5, P.accent2L);
      g.r(86, 45, 1, 4, P.leafD);
      g.p(85, 44, P.accent);
      g.p(87, 44, P.accent);
      g.p(86, 43, P.accent);
      g.p(86, 44, '#ffe27a');
      if (L.spot === 'chair') mascot(g, env, 15, 60, t, { flip: false });
      else mascot(g, env, 100, 49, t, { flip: true });
    }
  };

  function plane(g, env, x, y, t) {
    var P = env.P;
    var night = env.time === 'night';
    var body = hx('#f7f7fb');
    var shadeC = hx('#c3cbe0');
    var r;
    for (var k = 0; k < 60; k++) {
      var a = 0.65 * (1 - k / 60);
      var cy = y + rnd(Math.sin((k + t) * 0.25) * 0.5);
      if (bay(x - k, cy) < a) g.b(x - k, cy, WHITE, 0.75);
      if (bay(x - k, cy + 1) < a * 0.6) g.b(x - k, cy + 1, WHITE, 0.6);
    }
    for (r = 0; r < 8; r++) g.r(x + rnd(r * 0.7), y - 10 + r, 5 - (r < 2 ? 1 : 0), 1, r === 0 ? mix(P.accent, WHITE, 0.3) : P.accent);
    g.r(x - 2, y + 1, 9, 2, shadeC);
    g.r(x + 3, y - 3, 27, 1, body);
    g.r(x + 1, y - 2, 30, 5, body);
    g.r(x + 31, y - 2, 1, 5, body);
    g.r(x + 32, y - 1, 1, 4, body);
    g.r(x + 33, y, 1, 3, body);
    g.r(x + 2, y + 3, 30, 1, shadeC);
    g.r(x + 3, y + 1, 30, 1, P.accent2);
    g.r(x + 29, y - 1, 3, 1, '#34405e');
    g.r(x + 30, y - 2, 2, 1, '#4a5a80');
    g.em = night ? 1 : 0;
    for (var wx = x + 8; wx < x + 27; wx += 2) g.p(wx, y - 1, night ? '#ffe08a' : '#4f6f9f');
    g.em = 0;
    for (r = 0; r < 5; r++) g.r(x + 17 - r * 2, y + 2 + r, 10, 1, r === 4 ? shadeC : (r === 0 ? body : mix(body, shadeC, 0.3)));
    g.r(x + 12, y + 5, 5, 2, P.metal);
    g.r(x + 12, y + 6, 5, 1, P.metalD);
    if (night || env.time === 'dawn' || env.time === 'sunset') {
      g.em = 1;
      if (mod(t, 6) < 2) {
        g.p(x + 6, y + 7, '#ff4a4a');
        g.p(x + 1, y - 10, '#ff4a4a');
      }
      g.em = 0;
    }
  }

  SC.sky = {
    still: 50,
    outdoor: true,
    setup: function (env, R) {
      function layer(n, y0, y1, s0, s1, v) {
        var out = [];
        for (var i = 0; i < n; i++) {
          var puffs = [{ dx: 0, dy: -1, r: 4 }, { dx: -4.5, dy: 0.5, r: 3 }, { dx: 4.5, dy: 0.5, r: 3 }];
          var ex = R.i(1, 3);
          for (var k = 0; k < ex; k++) puffs.push({ dx: R.r(-8, 8), dy: R.r(-1.5, 1), r: R.r(2, 3.4) });
          out.push({ x: R.r(0, W + 60), y: R.i(y0, y1), s: R.r(s0, s1), v: v * R.r(0.8, 1.2), puffs: puffs });
        }
        return out;
      }
      return {
        far: layer(5, 8, 34, 0.55, 0.8, 0.08),
        near: layer(3, 44, 58, 1.3, 1.7, 0.4),
        balloon: R.ch(0.55),
        bx: R.i(20, 70),
        birds: R.ch(0.6),
        py: R.i(26, 34)
      };
    },
    draw: function (g, env, L, t) {
      var P = env.P;
      g.em = 1;
      skyBg(g, env, 0, 0, W, H);
      stars(g, env, 0, 0, W, H, t);
      celestial(g, env, 0, 0, W, H);
      g.em = 0;
      var farL = mix(env.cloudL, env.skyMid, 0.3);
      var farD = mix(env.cloudD, env.skyMid, 0.3);
      function drawLayer(arr, cl, cd) {
        for (var i = 0; i < arr.length; i++) {
          var c = arr[i];
          var cx = mod(c.x - t * c.v, W + 60) - 30;
          cloud(g, env, rnd(cx), c.y, c.s, c.puffs, cl, cd);
        }
      }
      drawLayer(L.far, farL, farD);
      if (L.balloon) {
        var bx = L.bx + rnd(Math.sin(t * 0.05) * 3);
        var by = 22 + rnd(Math.sin(t * 0.08) * 2);
        for (var dy = -7; dy <= 7; dy++) {
          var hw = rnd(6 * Math.sqrt(1 - (dy * dy) / 64) * (dy > 3 ? 1 - (dy - 3) * 0.15 : 1));
          for (var dx = -hw; dx <= hw; dx++) {
            var stripe = fl((dx + 7) / 3) % 2;
            var c = stripe ? P.accent : P.paper;
            if (dx > hw - 2 || dy > 5) c = mix(c, P.ink, 0.2);
            g.p(bx + dx, by + dy, c);
          }
        }
        g.p(bx - 3, by - 4, WHITE);
        g.p(bx - 4, by - 3, WHITE);
        g.line(bx - 3, by + 7, bx - 2, by + 11, P.woodD);
        g.line(bx + 3, by + 7, bx + 2, by + 11, P.woodD);
        g.r(bx - 2, by + 11, 5, 3, P.woodD);
        g.r(bx - 2, by + 11, 5, 1, P.woodL);
      }
      if (L.birds) {
        for (var b = 0; b < 4; b++) {
          var bxx = mod(t * 0.5 + b * 7 + 30, W + 20) - 10;
          var byy = 14 + b * 2 + (b % 2) * 2;
          var up = mod(t + b, 4) < 2;
          var bc = env.time === 'night' ? '#c8ccee' : '#4a4060';
          g.p(bxx, byy, bc);
          g.p(bxx - 1, byy + (up ? -1 : 1), bc);
          g.p(bxx + 1, byy + (up ? -1 : 1), bc);
        }
      }
      var px = mod(t * 1.3 + (env.seed % 97) * 3, W + 90) - 40;
      plane(g, env, px, L.py + rnd(Math.sin(t * 0.15) * 1.2), t);
      drawLayer(L.near, env.cloudL, env.cloudD);
      for (var c2 = 0; c2 < 9; c2++) {
        var cx2 = mod(c2 * 26 - t * 0.2, W + 30) - 15;
        cloud(g, env, rnd(cx2), 72 + (c2 % 2) * 2, 1.25 + (c2 % 3) * 0.15, env.clouds[c2 % env.clouds.length].puffs, env.cloudL, env.cloudD);
      }
      g.r(0, 77, W, 3, env.cloudL);
      if (MASCOTS[env.spec.mascot]) mascot(g, env, 146, 72, t);
      weather(g, env, 0, 0, W, H, t);
    }
  };

  SC['night-city'] = {
    still: 8,
    outdoor: true,
    setup: function (env, R) {
      var b = [];
      var x = -2;
      while (x < W) {
        var w = R.i(16, 30);
        b.push({ x: x, w: w, h: R.i(26, 50), roof: R.i(0, 4), col: R.i(0, 3), ant: R.ch(0.35) });
        x += w + R.i(0, 3);
      }
      return { b: b, lights: R.ch(0.7) };
    },
    draw: function (g, env, L, t) {
      var P = env.P;
      var i;
      g.em = 1;
      skyBg(g, env, 0, 0, W, 70);
      stars(g, env, 0, 0, W, 60, t);
      celestial(g, env, 0, 0, W, 60);
      g.em = 0;
      clouds(g, env, 0, 0, W, 40, t, 0.9);
      distant(g, env, 0, 30, W, 36, 'city', t);
      var cols = [mix(P.wallD, '#6a6f8a', 0.5), mix(P.trim, '#6a5a6a', 0.4), mix(P.accent2, '#5a6378', 0.6), mix(P.woodD, '#5a5060', 0.4)];
      var night = env.time !== 'day';
      for (i = 0; i < L.b.length; i++) {
        var b = L.b[i];
        var top = H - b.h;
        var c = cols[b.col];
        var cD = mix(c, P.ink, 0.3);
        g.r(b.x, top, b.w, b.h, c);
        g.r(b.x + b.w - 2, top, 2, b.h, cD);
        g.r(b.x - 1, top - 1, b.w + 2, 2, mix(c, WHITE, 0.2));
        if (b.roof === 1) {
          // water tower
          var tx = b.x + 3;
          g.r(tx, top - 9, 7, 6, P.woodD);
          g.r(tx, top - 9, 7, 1, P.woodL);
          g.r(tx - 1, top - 10, 9, 1, cD);
          g.r(tx + 1, top - 3, 1, 3, cD);
          g.r(tx + 5, top - 3, 1, 3, cD);
        } else if (b.roof === 2) {
          for (var r = 0; r < 5; r++) g.r(b.x + 2 + r, top - 1 - r, b.w - 4 - r * 2, 1, mix(P.accent, P.ink, 0.2));
        } else if (b.roof === 3) {
          g.r(b.x + b.w - 8, top - 5, 5, 4, cD);
          g.r(b.x + b.w - 7, top - 4, 2, 3, night ? env.litC : mix(c, WHITE, 0.3));
        }
        if (b.ant) {
          g.r(b.x + 4, top - 12, 1, 11, cD);
          g.em = 1;
          if (mod(t + i * 3, 10) < 5) g.p(b.x + 4, top - 13, '#ff4a4a');
          g.em = 0;
        }
        for (var wy = top + 4; wy < H - 4; wy += 5) {
          g.r(b.x, wy + 4, b.w - 2, 1, mix(c, P.ink, 0.12));
          for (var wx = b.x + 3; wx < b.x + b.w - 4; wx += 5) {
            var on = hash(i * 997 + wx * 13 + env.seed, wy + fl((t + hash(wx, wy) * 400) / 100) * 101) < env.T.lit;
            if (on) {
              g.em = 1;
              g.r(wx, wy, 3, 3, env.litC);
              if (hash(wx, wy) < 0.4) g.r(wx, wy, 1, 3, mix(env.litC, P.accent, 0.4));
              g.em = 0;
            } else {
              g.r(wx, wy, 3, 3, mix(c, env.skyMid, 0.55));
              g.p(wx, wy, mix(c, WHITE, 0.4));
            }
          }
        }
      }
      // rooftop parapet in front
      g.r(0, 72, W, 8, mix(P.metalD, P.ink, 0.2));
      g.r(0, 71, W, 2, P.metal);
      for (var x = 6; x < W; x += 14) g.r(x, 73, 1, 7, P.ink);
      if (L.lights) {
        for (x = 0; x < W; x++) {
          var sag = rnd(3 - Math.abs(Math.sin((x / 48) * PI)) * 4);
          g.p(x, 60 + sag, P.ink);
          if (x % 8 === 4) {
            g.em = 1;
            var lc = ['#ffd36e', '#ff8a9a', '#8ad8ff'][fl(x / 8) % 3];
            g.p(x, 61 + sag, mod(fl(x / 8) + fl(t / 4), 5) === 0 ? mix(lc, WHITE, 0.6) : lc);
            g.em = 0;
            if (env.time !== 'day') g.light(x, 61 + sag, 10, [1, 0.8, 0.5], 0.25, { n: 2 });
          }
        }
      }
      plant(g, env, 170, 71, 'bush', t, 3);
      plant(g, env, 182, 71, 'tall', t, 6);
      mascot(g, env, 30, 71, t, { sleep: false });
      weather(g, env, 0, 0, W, H, t);
    }
  };

  // ---- secondary scenes

  SC.stage = {
    still: 6,
    setup: function (env, R) {
      return { beam: R.pick(['accent', 'accent2']), guitar: R.pick(['accent', 'woodL', 'accent2']), drum: R.pick(['accent', 'accent2']) };
    },
    draw: function (g, env, L, t) {
      var P = env.P;
      var wall = mix(P.wallD, P.ink, 0.55);
      g.r(0, 0, W, 60, wall);
      for (var x = 0; x < W; x += 8) g.r(x, 0, 1, 60, mix(wall, P.ink, 0.25));
      win(g, env, 12, 8, 18, 18, t, { shape: 'round', sill: false });
      // stage + floor
      g.r(0, 56, W, 4, P.woodL);
      g.r(0, 59, W, 1, P.woodD);
      g.r(0, 60, W, 20, mix(P.woodD, P.ink, 0.3));
      for (var px = 0; px < W; px += 12) g.r(px, 60, 1, 20, mix(P.woodD, P.ink, 0.5));
      g.r(0, 60, W, 1, P.woodDD);
      g.r(0, 74, W, 6, mix(P.floorD, P.ink, 0.4));
      for (px = 8; px < W; px += 24) {
        g.r(px, 61, 6, 3, '#2a2630');
        g.em = 1;
        g.r(px + 1, 61, 4, 1, env.time === 'day' ? '#e8dcc0' : '#ffe6a0');
        g.em = 0;
        g.light(px + 3, 60, 16, [1, 0.85, 0.6], 0.35, { n: 3 });
      }
      // speakers pulsing on the beat
      var beat = mod(t, 4) === 0 ? 1 : 0;
      [[22, 28], [156, 28]].forEach(function (sp) {
        var sx = sp[0];
        var sy = sp[1];
        g.r(sx, sy, 16, 28, '#25222e');
        g.r(sx, sy, 16, 1, '#3e3a4c');
        g.ell(sx + 8, sy + 19, 5 + beat, 5 + beat, '#3c3848');
        g.ell(sx + 8, sy + 19, 3, 3, '#1a1820');
        g.ell(sx + 8, sy + 19, 1, 1, '#55506a');
        g.ell(sx + 8, sy + 7, 3, 3, '#3c3848');
        g.ell(sx + 8, sy + 7, 1, 1, '#1a1820');
      });
      // amp
      g.r(136, 44, 16, 12, '#2e2a36');
      g.r(137, 45, 14, 6, '#4a4458');
      for (var gx = 138; gx < 150; gx += 2) g.r(gx, 46, 1, 4, '#3a3546');
      g.em = 1;
      g.p(149, 53, '#ff6a5a');
      g.em = 0;
      g.r(138, 53, 2, 1, P.metalL);
      g.r(142, 53, 2, 1, P.metalL);
      // drum kit
      var dc = P[L.drum];
      g.r(52, 41, 1, 15, P.metalD);
      g.r(48, 40, 9, 1, '#e8c060');
      g.r(48, 41, 9, 1, '#b89040');
      g.r(84, 36, 1, 20, P.metalD);
      var tilt = mod(t, 4) === 0 ? 1 : 0;
      g.r(79, 35 - tilt, 11, 1, '#f0cc6a');
      g.r(80, 36 - tilt, 9, 1, '#b89040');
      g.ell(70, 49, 7, 7, dc);
      g.ell(70, 49, 6, 6, P.paper);
      g.ell(70, 49, 2, 2, dc);
      g.r(63, 55, 15, 1, mix(dc, P.ink, 0.4));
      g.r(66, 39, 7, 4, dc);
      g.r(66, 39, 7, 1, P.paper);
      g.r(56, 44, 7, 4, P.metalL);
      g.r(56, 44, 7, 1, WHITE);
      g.r(59, 48, 1, 8, P.metalD);

      // mic stand
      g.r(96, 34, 1, 22, P.metalD);
      g.r(92, 55, 9, 1, P.metalD);
      g.ell(96, 32, 1, 2, P.metal);
      // guitar on stand
      var gc = P[L.guitar];
      g.ell(124, 50, 5, 4, gc);
      g.ell(124, 44, 4, 3, gc);
      g.ell(124, 47, 1, 1, '#2a1a14');
      g.r(123, 28, 2, 14, P.woodD);
      g.r(122, 26, 4, 3, P.woodDD);
      g.line(119, 55, 124, 51, P.metalD);
      g.line(129, 55, 124, 51, P.metalD);
      // beams
      [[64, P[L.beam]], [132, P.accent]].forEach(function (bm, bi) {
        var bx0 = bm[0];
        var sw = Math.sin(t * 0.12 + bi * 2) * 0.35;
        var bc = mix(bm[1], WHITE, 0.55);
        for (var y = 3; y < 57; y++) {
          var hw = 2 + y * 0.3;
          var cx = bx0 + sw * y;
          for (var xx = fl(cx - hw); xx <= cx + hw; xx++) {
            var dd = Math.abs(xx - cx) / hw;
            g.b(xx, y, bc, dd > 0.85 ? 0.1 : (dd > 0.45 ? 0.18 : 0.26));
          }
        }
        var fx = rnd(bx0 + sw * 56);
        for (var ey = -2; ey <= 2; ey++) {
          var ew = rnd(18 * Math.sqrt(1 - (ey * ey) / 6.25));
          for (var ex = -ew; ex <= ew; ex++) g.b(fx + ex, 57 + ey, bc, 0.3);
        }
        g.r(bx0 - 3, 0, 7, 3, '#1a1820');
        g.light(rnd(bx0 + sw * 56), 54, 26, [1, 0.9, 0.8], 0.5, { sy: 2.2 });
      });
      // string lights
      for (x = 0; x < W; x++) {
        var sag = rnd(Math.abs(Math.sin((x / 48) * PI)) * 4);
        g.p(x, 4 + sag, P.ink);
        if (x % 8 === 4) {
          g.em = 1;
          g.p(x, 5 + sag, mod(fl(x / 8) + fl(t / 3), 4) === 0 ? '#fff6c0' : ['#ffd36e', '#ff8a9a', '#8ad8ff'][fl(x / 8) % 3]);
          g.em = 0;
        }
      }
      // floating notes
      var N1 = ['..#..', '..##.', '..#.#', '..#..', '.##..', '###..', '.#...'];
      var N2 = ['.####', '.#..#', '.#..#', '##.##', '##.##'];
      var srcs = [30, 164, 124, 70, 144];
      for (var n = 0; n < 6; n++) {
        var life = 40;
        var a = mod(t + n * 7, life);
        var nx = srcs[n % srcs.length] + rnd(Math.sin(a * 0.25 + n) * 3);
        var ny = 40 - a;
        if (a > 34 && mod(t, 2) === 0) continue;
        g.em = 1;
        g.spr(nx, ny, n % 2 ? N1 : N2, { '#': [P.accent, P.accent2, '#ffffff', P.accentL][n % 4] });
        g.em = 0;
      }
      mascot(g, env, 100, 56, t, { sleep: false });
      g.light(96, 30, 70, [1, 0.85, 0.7], 0.3);
    }
  };

  SC.photo = {
    still: 1,
    outdoor: true,
    setup: function (env, R) {
      return { peaks: R.i(2, 4), cam: R.pick(['accent', 'accent2']) };
    },
    draw: function (g, env, L, t) {
      var P = env.P;
      var x;
      var y;
      g.em = 1;
      skyBg(g, env, 0, 0, W, 50);
      stars(g, env, 0, 0, W, 46, t);
      celestial(g, env, 0, 0, W, 50);
      g.em = 0;
      clouds(g, env, 0, 0, W, 40, t);
      // mountains with a lit and a shaded face
      var mc = mix(env.skyLow, '#7686ad', 0.5);
      var mcD = mix(mc, '#2e3656', 0.3);
      var snowC = hx('#f4f6fb');
      var snowD = hx('#c9d3ea');
      for (x = 0; x < W; x++) {
        var my = 999;
        var pk = 0;
        for (var k = 0; k < L.peaks; k++) {
          var pxk = 30 + k * (140 / L.peaks) + (env.seed % 20);
          var hk = 14 + k * 4 + Math.abs(x - pxk) * 0.72;
          if (hk < my) {
            my = hk;
            pk = pxk;
          }
        }
        var shadow = x > pk;
        for (y = fl(my); y < 50; y++) {
          var sn = y < my + 3 + ((x * 7) % 3);
          g.p(x, y, sn ? (shadow ? snowD : snowC) : (shadow ? mcD : mc));
        }
      }
      distant(g, env, 0, 30, W, 22, 'trees', t);
      // lake mirrors whatever is above it
      var water = hx('#3a6aa0');
      for (y = 50; y < 66; y++) {
        var src = 49 - fl((y - 50) * 1.6);
        for (x = 0; x < W; x++) {
          var sx = x + rnd(Math.sin(y * 0.9 + t * 0.35) * (y - 48) * 0.12);
          var cs = g.get(sx, src);
          var si = clamp(src, 0, H - 1) * W + clamp(sx, 0, W - 1);
          if (!g.e[si]) cs = tone(env, cs);
          var c = mix(cs, tone(env, water), 0.4);
          if (mod(x + fl(t * 0.6) * (y & 1 ? 1 : -1) + y * 9, 31) < 3 && (y & 1)) c = mix(c, WHITE, 0.3);
          g.em = 1;
          g.p(x, y, c);
          g.em = 0;
        }
      }
      // shore
      for (x = 0; x < W; x++) {
        var sy = 62 + rnd(Math.sin(x * 0.07) * 2);
        for (y = sy; y < H; y++) g.p(x, y, y === sy ? P.leafL : (y > sy + 3 && hash(x, y) < 0.06 ? P.leafD : (y > 72 && ((x + y) & 1) ? mix(P.leaf, P.leafD, 0.5) : P.leaf)));
      }
      for (var f = 0; f < 20; f++) {
        var fx = fl(hash(env.seed, f) * W);
        var fy = 68 + fl(hash(env.seed, f + 50) * 11);
        g.p(fx, fy, [P.accent, '#ffffff', '#ffd25a', P.accent2][f % 4]);
        g.p(fx, fy + 1, P.leafD);
      }
      // tripod + camera
      var cx = 150;
      g.line(cx, 50, cx - 8, 77, P.ink);
      g.line(cx + 1, 50, cx + 1, 77, P.ink);
      g.line(cx + 2, 50, cx + 10, 77, P.ink);
      var cb = mix(P[L.cam], P.ink, 0.15);
      g.r(cx - 5, 50, 12, 2, P.metalD);
      g.r(cx - 8, 39, 20, 12, '#34343f');
      g.r(cx - 8, 39, 20, 1, '#55556a');
      g.r(cx - 8, 43, 20, 5, cb);
      g.r(cx - 8, 43, 20, 1, mix(cb, WHITE, 0.3));
      g.r(cx - 3, 35, 8, 4, '#34343f');
      g.r(cx - 3, 35, 8, 1, '#55556a');
      g.r(cx + 7, 37, 3, 2, P.metalL);
      g.r(cx - 15, 40, 7, 10, '#26262f');
      g.r(cx - 15, 40, 7, 1, '#44445a');
      g.r(cx - 16, 41, 1, 8, '#4a6ea8');
      g.p(cx - 16, 42, '#bcd6ff');
      g.r(cx - 12, 44, 3, 1, P.metalL);
      var flash = mod(t, 40) < 2;
      g.em = 1;
      g.r(cx + 1, 31, 6, 4, flash ? '#ffffff' : '#c8c8d0');
      g.r(cx + 2, 32, 4, 2, flash ? '#ffffff' : '#e8e8f0');
      if (flash) {
        for (var ray = 1; ray < 8; ray++) {
          var al = 1 - ray / 8;
          g.b(cx + 4, 32 - ray - 1, WHITE, al);
          g.b(cx + 4 + ray + 2, 32, WHITE, al);
          g.b(cx + 4 - ray - 2, 32, WHITE, al);
          g.b(cx + 4 + ray + 1, 32 - ray, WHITE, al * 0.8);
          g.b(cx + 4 - ray - 1, 32 - ray, WHITE, al * 0.8);
        }
      }
      g.em = 0;
      if (flash) g.light(cx, 36, 110, [1, 1, 1], 0.7);
      mascot(g, env, cx + 14, 77, t, { sleep: false, flip: true });
      weather(g, env, 0, 0, W, H, t);
    }
  };

  SC.game = {
    still: 5,
    setup: function (env, R) {
      return { wallStyle: R.i(0, 2), tv: R.pick(['paper', 'accent2', 'woodL']), view: R.pick(['city', 'hills']), cush: R.pick(['accent', 'accent2']) };
    },
    draw: function (g, env, L, t) {
      var P = env.P;
      room(g, env, L, 60);
      g.ell(96, 72, 70, 6, P.rug);
      g.ell(96, 72, 66, 5, mix(P.rug, WHITE, 0.2));
      win(g, env, 10, 6, 34, 30, t, { panes: 4, view: L.view, curtain: P.accent2, tie: true, cw: 8 });
      // poster
      g.r(150, 10, 18, 22, P.paper);
      g.r(151, 11, 16, 20, P.accent2);
      g.spr(154, 16, ['.##.##.', '#######', '#######', '.#####.', '..###..', '...#...'], { '#': P.accent });
      // cabinet + TV
      g.r(58, 48, 76, 14, P.woodD);
      g.r(58, 48, 76, 2, P.woodL);
      g.r(62, 52, 32, 8, P.woodDD);
      g.r(98, 52, 32, 8, P.woodDD);
      var tc = P[L.tv];
      g.r(68, 16, 56, 32, tc);
      g.r(68, 16, 56, 1, mix(tc, WHITE, 0.4));
      g.r(123, 16, 1, 32, mix(tc, P.ink, 0.35));
      g.line(84, 15, 78, 6, P.metalD);
      g.line(100, 15, 108, 5, P.metalD);
      g.r(114, 22, 6, 3, P.metalD);
      g.r(114, 28, 6, 3, P.metalD);
      g.r(115, 36, 4, 8, mix(tc, P.ink, 0.2));
      // screen with tiny platformer
      var sx = 72;
      var sy = 19;
      var sw = 38;
      var sh = 26;
      g.em = 1;
      g.r(sx, sy, sw, sh, '#7ec8f6');
      g.r(sx, sy + sh - 5, sw, 5, '#b86a3a');
      g.r(sx, sy + sh - 5, sw, 1, '#5fc05a');
      var scroll = fl(t * 1);
      for (var bx = 0; bx < sw; bx++) {
        if (mod(bx + scroll, 6) === 0) g.p(sx + bx, sy + sh - 3, '#8a4a2a');
      }
      var cl = mod(-t * 0.5, sw + 10) - 5;
      g.r(sx + cl, sy + 4, 6, 2, '#ffffff');
      g.r(sx + cl + 1, sy + 3, 3, 1, '#ffffff');
      var px2 = mod(30 - scroll, sw + 8) - 4;
      g.r(sx + px2, sy + sh - 11, 4, 6, '#4ab84a');
      g.r(sx + px2 - 1, sy + sh - 12, 6, 2, '#5fd05a');
      var qb = mod(18 - scroll, sw + 8) - 4;
      g.r(sx + qb, sy + 8, 4, 4, '#f6c03a');
      g.p(sx + qb + 1, sy + 9, '#a86a1a');
      var jp = mod(t, 14);
      var jy = jp < 10 ? rnd(Math.sin((jp / 10) * PI) * 9) : 0;
      var hx0 = sx + 12;
      var hy0 = sy + sh - 11 - jy;
      g.r(hx0, hy0, 4, 2, '#e8483a');
      g.r(hx0, hy0 + 2, 4, 2, '#ffd0a0');
      g.r(hx0, hy0 + 4, 4, 2, '#3a5ad0');
      if (jp > 2 && jp < 8) {
        g.p(sx + 14, sy + 8 - (jp - 3), '#ffe27a');
      }
      for (var yy = sy; yy < sy + sh; yy += 2) g.br(sx, yy, sw, 1, '#000000', 0.12);
      g.em = 0;
      g.light(91, 32, 60, [0.6, 0.8, 1], env.time === 'night' ? 0.7 : 0.25, { n: 3 });
      // console + controller
      g.r(80, 64, 22, 5, P.metalD);
      g.r(80, 64, 22, 1, P.metal);
      g.em = 1;
      g.p(99, 66, '#6aff8a');
      g.em = 0;
      g.line(102, 67, 112, 71, P.ink);
      g.r(110, 70, 10, 4, P.accent2D);
      g.p(112, 71, P.paper);
      g.p(117, 71, P.accent);
      // beanbag
      var cc = P[L.cush];
      g.ell(30, 66, 15, 9, mix(cc, P.ink, 0.3));
      g.ell(29, 64, 13, 8, cc);
      g.ell(24, 60, 5, 3, mix(cc, WHITE, 0.3));
      plant(g, env, 170, 60, 'tall', t, 2);
      mascot(g, env, 23, 64, t, { sleep: false });
    }
  };

  function tree(g, env, x, yb, r, t) {
    var P = env.P;
    var snow = env.weather === 'snow';
    var fall = env.weather === 'leaves';
    var c = fall ? hx('#e08a3a') : P.leaf;
    var cD = fall ? hx('#b45a2a') : P.leafD;
    var cL = fall ? hx('#f6c05a') : P.leafL;
    var blossom = env.weather === 'petals';
    if (blossom) {
      c = hx('#ffc4d6');
      cD = hx('#e890b0');
      cL = hx('#fff0f4');
    }
    g.r(x - 1, yb - r - 4, 3, r + 4, P.woodD);
    g.r(x + 1, yb - r - 4, 1, r + 4, P.woodDD);
    var cy = yb - r - 4 - r;
    for (var dy = -r - 1; dy <= r; dy++) {
      for (var dx = -r - 2; dx <= r + 2; dx++) {
        var d = (dx * dx) / ((r + 2) * (r + 2)) + (dy * dy) / ((r + 1) * (r + 1));
        var bump = Math.sin(Math.atan2(dy, dx) * 5 + x) * 0.08;
        if (d > 1 + bump) continue;
        var lit = dx + dy * 1.3;
        var col = lit < -r * 0.6 ? cL : (lit > r * 0.5 ? cD : (lit > r * 0.1 && bay(x + dx, cy + dy) < 0.5 ? cD : c));
        if (snow && dy < -r * 0.4 && d < 1 + bump) col = '#f4f8ff';
        g.p(x + dx, cy + dy, col);
      }
    }
  }

  SC.park = {
    still: 4,
    outdoor: true,
    setup: function (env, R) {
      return { tl: R.i(16, 30), tr: R.i(160, 178), lamp: R.ch(0.7) };
    },
    draw: function (g, env, L, t) {
      var P = env.P;
      var x;
      var y;
      var snow = env.weather === 'snow';
      g.em = 1;
      skyBg(g, env, 0, 0, W, 48);
      stars(g, env, 0, 0, W, 44, t);
      celestial(g, env, 0, 0, W, 48);
      g.em = 0;
      clouds(g, env, 0, 0, W, 34, t);
      distant(g, env, 0, 22, W, 26, 'trees', t);
      var grass = snow ? hx('#eef3fa') : P.leaf;
      var grassD = snow ? hx('#ccd8ea') : P.leafD;
      for (y = 46; y < H; y++) {
        for (x = 0; x < W; x++) g.p(x, y, (x * 7 + y * 13) % 17 === 0 ? grassD : grass);
      }
      // path in perspective
      var path = mix(P.paper, P.woodL, 0.5);
      for (y = 46; y < H; y++) {
        var f = (y - 46) / (H - 46);
        var cx = 96 + Math.sin(f * 2.2) * 14;
        var hw = 3 + f * 26;
        for (x = fl(cx - hw); x <= cx + hw; x++) g.p(x, y, Math.abs(x - cx) > hw - 1 ? mix(path, P.ink, 0.2) : path);
      }
      tree(g, env, L.tl, 66, 11, t);
      tree(g, env, L.tr, 70, 12, t);
      tree(g, env, 60, 50, 6, t);
      tree(g, env, 140, 50, 5, t);
      // bench
      g.r(124, 56, 26, 2, P.woodL);
      g.r(124, 52, 26, 2, P.woodL);
      g.r(124, 58, 26, 1, P.woodD);
      g.r(126, 58, 2, 6, P.metalD);
      g.r(146, 58, 2, 6, P.metalD);
      g.r(126, 50, 1, 8, P.metalD);
      g.r(147, 50, 1, 8, P.metalD);
      if (L.lamp) {
        g.r(108, 30, 2, 30, P.metalD);
        g.r(105, 27, 8, 3, P.metalD);
        g.em = 1;
        g.r(106, 30, 6, 2, env.time === 'day' ? '#e8e8dc' : '#fff1c2');
        g.em = 0;
        g.light(109, 32, 40, env.T.lamp, env.lampK * 0.9, { top: 30 });
      }
      // pigeons hopping on the path
      for (var b = 0; b < 2; b++) {
        var hop = mod(t + b * 5, 10) < 2 ? 1 : 0;
        var bx = 86 + b * 14 + (mod(fl(t / 20) + b, 2) ? 1 : 0);
        var by = 66 - hop;
        g.r(bx, by, 4, 2, '#8a8aa0');
        g.p(bx + 4, by - 1, '#8a8aa0');
        g.p(bx + 5, by - 1, '#f0a23a');
        g.p(bx + 4, by - 2, '#8a8aa0');
        g.p(bx - 1, by, '#6a6a80');
        g.p(bx + 1, by + 2, '#f0a23a');
      }
      for (var k = 0; k < 6; k++) {
        var fx = fl(hash(env.seed, k) * W);
        var fy = 50 + fl(hash(env.seed, k + 9) * 28);
        if (Math.abs(fx - 96) < 20) continue;
        g.p(fx, fy, snow ? '#ffffff' : [P.accent, '#ffffff', '#ffd25a'][k % 3]);
      }
      // drifting leaves even on clear days
      if (env.weather === 'clear' || env.weather === 'cloudy') {
        for (var l = 0; l < 4; l++) {
          var lx = mod(l * 50 + t * 0.7, W);
          var ly = mod(l * 17 + t * 0.35, 60) + 10;
          g.p(lx, ly, P.leafL);
          g.p(lx + (mod(t + l, 4) < 2 ? 1 : 0), ly + (mod(t + l, 4) < 2 ? 0 : 1), P.leaf);
        }
      }
      mascot(g, env, 131, 56, t);
      weather(g, env, 0, 0, W, H, t);
    }
  };

  function pastry(g, env, kind, x, y) {
    var P = env.P;
    if (kind === 0) {
      // croissant
      g.ell(x + 4, y, 4, 2, '#d8943e');
      g.r(x + 1, y - 1, 7, 1, '#f0bf6a');
      g.p(x, y + 1, '#c07a2e');
      g.p(x + 8, y + 1, '#c07a2e');
      g.r(x + 2, y - 1, 1, 3, '#b8702a');
      g.r(x + 4, y - 2, 1, 4, '#b8702a');
      g.r(x + 6, y - 1, 1, 3, '#b8702a');
    } else if (kind === 1) {
      // muffin
      g.r(x + 1, y, 6, 3, P.accent);
      g.p(x + 2, y + 1, P.accentL);
      g.p(x + 4, y + 1, P.accentL);
      g.ell(x + 4, y - 1, 3, 2, '#9a5a3a');
      g.p(x + 3, y - 2, '#c88a5a');
      g.p(x + 5, y - 1, '#5a2e1e');
    } else if (kind === 2) {
      // macaron stack
      var mc = [P.accentL, '#c8e6b0', '#f6e08a'][(x >> 3) % 3];
      g.r(x + 1, y - 2, 6, 2, mc);
      g.r(x + 1, y, 6, 1, P.paper);
      g.r(x + 1, y + 1, 6, 2, mc);
      g.p(x + 2, y - 2, WHITE);
    } else {
      // donut
      g.ell(x + 4, y, 4, 2, '#d8a060');
      g.ell(x + 4, y - 1, 3, 1, '#f6a0c0');
      g.p(x + 4, y - 1, '#7a4a3a');
      g.p(x + 2, y - 1, WHITE);
      g.p(x + 6, y - 2, '#7fd0ff');
    }
  }

  SC.bakery = {
    still: 2,
    setup: function (env, R) {
      return {
        wallStyle: R.pick([1, 2, 0]),
        view: R.pick(['street', 'hills']),
        cake: R.pick(['#f6b8c8', '#f6e2c8', '#a8684a', '#c8e6b0']),
        berry: R.pick(['#e8483a', '#6a5ad0'])
      };
    },
    draw: function (g, env, L, t) {
      var P = env.P;
      var x;
      room(g, env, L, H);
      // bunting
      for (x = 0; x < W; x++) {
        var sag = rnd(Math.sin((x / 64) * PI) * 3);
        g.p(x, 3 + Math.abs(sag), P.ink);
        if (x % 10 === 2) {
          var bc = [P.accent, P.accent2, P.paper][fl(x / 10) % 3];
          for (var r = 0; r < 4; r++) g.r(x - 3 + r, 4 + Math.abs(sag) + r, 7 - r * 2, 1, bc);
        }
      }
      // oven with bread inside
      g.r(8, 14, 38, 36, P.metal);
      g.r(8, 14, 38, 2, P.metalL);
      g.r(45, 14, 1, 36, P.metalD);
      g.r(12, 24, 30, 18, '#2a2a33');
      g.em = 1;
      var hot = mod(t, 6) < 3 ? 0.1 : 0;
      g.r(14, 26, 26, 14, mix('#ff8a3a', '#ffcf7a', 0.25 + hot));
      g.r(14, 26, 26, 3, mix('#ffb04a', '#ffe0a0', 0.3 + hot));
      g.r(14, 38, 26, 1, '#a85a2a');
      g.ell(20, 35, 4, 2, '#b8682a');
      g.ell(31, 35, 5, 2, '#b8682a');
      g.r(17, 34, 6, 1, '#e8a050');
      g.r(28, 34, 7, 1, '#e8a050');
      g.em = 0;
      g.r(12, 20, 30, 2, P.metalD);
      for (var k = 0; k < 3; k++) g.r(16 + k * 9, 17, 3, 2, P.metalD);
      g.r(14, 44, 26, 2, P.metalL);
      for (var hz = 0; hz < 3; hz++) {
        var hx0 = 18 + hz * 8 + rnd(Math.sin(t * 0.5 + hz) * 1);
        g.b(hx0, 11 - mod(t + hz * 2, 4), WHITE, 0.25);
      }
      g.light(27, 34, 46, [1, 0.6, 0.3], env.time === 'day' ? 0.3 : 0.6, { n: 5 });
      // wall shelf with jars and a rolling pin
      g.r(54, 22, 26, 2, P.woodL);
      g.r(54, 24, 26, 1, P.woodD);
      g.r(56, 14, 6, 8, mix('#eef6f8', P.paper, 0.5));
      g.r(56, 17, 6, 5, '#f6f0e0');
      g.r(56, 13, 6, 1, P.woodD);
      g.r(64, 16, 5, 6, mix('#eef6f8', P.paper, 0.5));
      g.r(64, 18, 5, 4, '#e8c890');
      g.r(64, 15, 5, 1, P.accent);
      g.r(71, 20, 8, 2, P.woodL);
      g.r(70, 20, 1, 2, P.woodD);
      g.r(79, 20, 1, 2, P.woodD);
      win(g, env, 136, 6, 40, 30, t, { panes: 4, view: L.view, curtain: P.accent, tie: true, cw: 8 });
      // counter top + glass pastry case
      g.r(0, 52, W, 3, P.woodL);
      g.r(0, 52, W, 1, mix(P.woodL, WHITE, 0.3));
      g.r(0, 55, W, 1, P.woodD);
      g.r(0, 56, W, 24, P.wood);
      g.r(4, 57, 184, 21, P.woodD);
      g.r(6, 58, 180, 18, mix('#d8eef6', P.wall, 0.25));
      g.r(6, 67, 180, 1, P.metal);
      g.r(6, 75, 180, 1, P.metal);
      for (var b = 0; b < 16; b++) {
        pastry(g, env, b % 2, 9 + b * 11, 64);
        pastry(g, env, 2 + (b % 2), 9 + b * 11, 72);
      }
      for (var sr = 0; sr < 18; sr++) {
        g.b(14 + sr, 75 - sr, WHITE, 0.22);
        g.b(15 + sr, 75 - sr, WHITE, 0.22);
        g.b(100 + sr, 75 - sr, WHITE, 0.15);
      }
      // cake on a stand
      var cx = 96;
      var cc = hx(L.cake);
      var cD = mix(cc, P.ink, 0.25);
      var cream = hx('#fffaf0');
      g.br(cx - 12, 52, 25, 1, P.ink, 0.25);
      g.r(cx - 9, 50, 19, 2, P.paperD);
      g.r(cx - 2, 46, 5, 4, P.paperD);
      g.r(cx - 13, 45, 27, 1, P.paper);
      g.r(cx - 13, 46, 27, 1, P.paperD);
      g.r(cx - 11, 35, 23, 10, cc);
      g.r(cx + 9, 35, 3, 10, cD);
      g.r(cx - 11, 35, 23, 2, cream);
      for (var d = 0; d < 23; d += 2) g.r(cx - 11 + d, 37, 1, 1 + ((d * 7) % 3), cream);
      g.r(cx - 11, 41, 23, 1, cream);
      for (d = 0; d < 23; d += 3) g.p(cx - 11 + d, 42, mix(cream, cc, 0.4));
      g.r(cx - 7, 28, 15, 7, cc);
      g.r(cx + 6, 28, 2, 7, cD);
      g.r(cx - 7, 28, 15, 2, cream);
      for (d = 0; d < 15; d += 2) g.r(cx - 7 + d, 30, 1, 1 + ((d * 5) % 2), cream);
      [-5, 0, 5].forEach(function (o, i) {
        if (o === 0) return;
        g.r(cx + o - 1, 26, 3, 2, hx(L.berry));
        g.p(cx + o - 1, 26, mix(hx(L.berry), WHITE, 0.4));
        g.p(cx + o, 25, P.leaf);
      });
      // candle + flame
      g.r(cx, 21, 1, 7, P.accent2L);
      g.p(cx, 23, P.accent2);
      g.p(cx, 26, P.accent2);
      var f1 = mod(t, 3);
      g.em = 1;
      g.p(cx, 20, '#ffd34a');
      g.p(cx, 19, f1 === 0 ? '#fff4c0' : '#ffb03a');
      g.p(cx - 1, 20, f1 === 1 ? '#ff9a3a' : '#ffc04a');
      g.p(cx + 1, 20, f1 === 2 ? '#ff9a3a' : '#ffc04a');
      if (f1 !== 1) g.p(cx + (f1 === 2 ? 1 : 0), 18, '#ff9a3a');
      g.em = 0;
      if (env.time !== 'day') {
        g.em = 1;
        halo(g, cx, 19, 1, 4, '#ffd890', 0.6);
        g.em = 0;
      }
      g.light(cx, 20, 40, [1, 0.75, 0.4], (env.time === 'day' ? 0.2 : 0.8) * (f1 === 1 ? 0.88 : 1), { n: 5 });
      // bread basket
      g.r(152, 46, 24, 6, P.woodL);
      for (var bb = 0; bb < 24; bb += 2) g.p(152 + bb, 47 + (bb % 4 ? 1 : 0), P.woodD);
      g.r(152, 51, 24, 1, P.woodD);
      g.line(155, 45, 162, 37, '#c8843a');
      g.line(156, 45, 163, 37, '#e8b86a');
      g.line(162, 45, 170, 38, '#c8843a');
      g.line(163, 45, 171, 38, '#e8b86a');
      g.ell(146, 49, 4, 2, '#c8843a');
      g.r(143, 48, 6, 1, '#e8b86a');
      mascot(g, env, 116, 52, t, { sleep: false });
    }
  };

  SC.sea = {
    still: 3,
    outdoor: true,
    setup: function (env, R) {
      return { boatX: R.i(30, 120), umb: R.pick(['accent', 'accent2']) };
    },
    draw: function (g, env, L, t) {
      var P = env.P;
      var x;
      var y;
      g.em = 1;
      skyBg(g, env, 0, 0, W, 40);
      stars(g, env, 0, 0, W, 38, t);
      celestial(g, env, 0, 0, W, 44);
      g.em = 0;
      clouds(g, env, 0, 0, W, 30, t);
      var deep = mix(env.skyMid, '#1a4a8a', 0.6);
      var shallow = hx('#4ab8c8');
      for (y = 40; y < 67; y++) {
        var f = clamp((y - 40) / 22, 0, 1);
        var c = mix(deep, shallow, f * f);
        g.r(0, y, W, 1, c);
        if (y & 1 && y < 62) {
          var nd = 2 + fl(f * 4);
          for (var k = 0; k < nd; k++) {
            var dl = 2 + fl(hash(y, k) * (2 + f * 4));
            var dx = mod(hash(k, y) * W + t * (0.2 + f * 0.6) * (k & 1 ? 1 : -1), W);
            g.r(dx, y, dl, 1, mix(c, WHITE, 0.35 + f * 0.2));
          }
        }
      }
      g.r(0, 40, W, 1, mix(env.skyLow, WHITE, 0.3));
      // sun / moon reflection
      if (env.weather !== 'rain' && env.weather !== 'snow') {
        var sx = fl(env.sunX * W);
        g.em = 1;
        for (y = 41; y < 60; y += 2) {
          var hw = 1 + (y - 41) * 0.25;
          var off = rnd(Math.sin(t * 0.4 + y) * 2);
          var rc = env.time === 'night' ? '#fff1c6' : (env.time === 'day' ? '#ffffff' : '#ffd27a');
          if (mod(y + fl(t / 2), 4) < 3) g.r(sx - hw + off, y, hw * 2, 1, rc);
        }
        g.em = 0;
      }
      // sailboat
      var bx = L.boatX + mod(t * 0.15, 40);
      var bob = mod(t, 8) < 4 ? 0 : 1;
      var by = 44 + bob;
      g.r(bx, by, 14, 2, P.woodD);
      g.r(bx + 1, by + 2, 12, 1, P.woodDD);
      g.r(bx + 6, by - 14, 1, 14, P.woodDD);
      for (var r = 0; r < 12; r++) g.r(bx + 7, by - 13 + r, rnd(r * 0.55) + 1, 1, r % 4 === 2 ? P.accent : P.paper);
      for (r = 0; r < 9; r++) g.r(bx + 5 - rnd(r * 0.45), by - 10 + r, rnd(r * 0.45) + 1, 1, P.paperD);
      // sand + waves
      var sand = mix(P.paper, '#e8c98a', 0.6);
      var wash = rnd(Math.sin(t * 0.18) * 2);
      for (x = 0; x < W; x++) {
        var sy = 62 + rnd(Math.sin(x * 0.05) * 1.5) + wash;
        for (y = sy; y < H; y++) g.p(x, y, y < sy + 2 ? mix(sand, '#a8885a', 0.35) : (bay(x, y) < 0.08 ? mix(sand, '#a8885a', 0.4) : sand));
        if (mod(x + fl(t * 0.5), 9) < 6) g.p(x, sy - 1, WHITE);
        if (mod(x * 2 + fl(t), 13) < 4) g.p(x, sy - 4 + rnd(Math.sin(x * 0.3 + t * 0.3)), mix(WHITE, shallow, 0.3));
      }
      // umbrella + towel
      var uc = P[L.umb];
      g.r(172, 47, 1, 28, P.woodD);
      for (r = 0; r < 7; r++) {
        var uw = rnd(15 * Math.sqrt(1 - Math.pow((6 - r) / 7, 2)));
        for (var ux = -uw; ux <= uw; ux++) {
          var seg = fl(((ux / Math.max(1, uw)) + 1) * 3);
          var col = seg % 2 ? uc : P.paper;
          if (r === 6) col = mix(col, P.ink, 0.25);
          g.p(172 + ux, 42 + r, col);
        }
      }
      for (ux = -15; ux <= 15; ux += 5) g.p(172 + ux, 49, mix(uc, P.ink, 0.25));
      g.p(172, 41, P.woodD);
      g.r(140, 72, 24, 5, P.accent2);
      for (var tx = 140; tx < 164; tx += 4) g.r(tx, 72, 2, 5, P.paper);
      g.r(140, 77, 24, 1, mix(P.accent2, P.ink, 0.3));
      g.p(60, 74, '#f08a6a');
      g.p(59, 75, '#f08a6a');
      g.p(61, 75, '#f08a6a');
      g.p(60, 76, '#f08a6a');
      // gulls
      for (var b = 0; b < 2; b++) {
        var gx = mod(t * 0.6 + b * 60, W + 20) - 10;
        var gy = 16 + b * 6;
        var up = mod(t + b * 2, 6) < 3;
        g.p(gx, gy, '#f6f6f6');
        g.p(gx - 1, gy + (up ? -1 : 0), '#f6f6f6');
        g.p(gx + 1, gy + (up ? -1 : 0), '#f6f6f6');
        g.p(gx - 2, gy + (up ? -1 : 1), '#d6d6e0');
        g.p(gx + 2, gy + (up ? -1 : 1), '#d6d6e0');
      }
      mascot(g, env, 145, 74, t, { sleep: false });
      weather(g, env, 0, 0, W, H, t);
    }
  };

  function pine(g, env, x, yb, h) {
    var P = env.P;
    var d = mix(P.leafD, '#1f4a40', 0.4);
    g.r(x, yb - 3, 1, 3, P.woodDD);
    for (var r = 0; r < h; r++) {
      var tier = r % 4;
      var hw = rnd((r / h) * (h * 0.35) + tier * 0.4);
      g.r(x - hw, yb - 3 - h + r, hw * 2 + 1, 1, d);
      if (tier === 0) g.r(x - hw, yb - 3 - h + r, hw * 2 + 1, 1, '#f4f8ff');
    }
  }

  SC.snowfield = {
    still: 12,
    outdoor: true,
    setup: function (env, R) {
      return { snowman: R.ch(0.6), sx: R.i(20, 40) };
    },
    draw: function (g, env, L, t) {
      var P = env.P;
      var x;
      var y;
      g.em = 1;
      skyBg(g, env, 0, 0, W, 46);
      stars(g, env, 0, 0, W, 40, t);
      celestial(g, env, 0, 0, W, 46);
      g.em = 0;
      clouds(g, env, 0, 0, W, 30, t);
      var mc = mix(env.skyLow, '#8a9ac0', 0.45);
      for (x = 0; x < W; x++) {
        var my = 22 + Math.abs(mod(x + env.seed % 40, 70) - 35) * 0.5;
        for (y = fl(my); y < 46; y++) g.p(x, y, y < my + 4 ? '#f6f8fc' : mc);
      }
      var s1 = hx('#eef3fa');
      var s2 = hx('#d8e2f0');
      var s3 = hx('#c0cee4');
      for (x = 0; x < W; x++) {
        var h1 = 40 + Math.sin(x * 0.04 + 1) * 4;
        var h2 = 50 + Math.sin(x * 0.03 + 3) * 5;
        for (y = fl(h1); y < H; y++) g.p(x, y, y < h1 + 1 ? WHITE : (y < h1 + 5 && ((x + y) & 1) ? s2 : (y >= h1 + 5 ? s2 : s1)));
        for (y = fl(h2); y < H; y++) g.p(x, y, y < h2 + 1 ? WHITE : (y > h2 + 14 && ((x + y) & 1) ? s2 : s1));
      }
      for (var p = 0; p < 7; p++) pine(g, env, 8 + p * 28 + (p % 2) * 9, 46 + (p % 3) * 3, 10 + (p % 3) * 3);
      // frozen pond
      var pcx = 100;
      var pcy = 66;
      for (y = -7; y <= 7; y++) {
        var hw = rnd(46 * Math.sqrt(1 - (y * y) / 56));
        for (x = -hw; x <= hw; x++) {
          var ice = mix('#a8d8f0', '#e0f4fc', (y + 7) / 14);
          if (mod(x - y * 2 + 60, 17) === 0) ice = mix(ice, WHITE, 0.6);
          g.p(pcx + x, pcy + y, Math.abs(x) >= hw - 1 ? '#8ab8d8' : ice);
        }
      }
      var ang = t * 0.12;
      var skx = pcx + rnd(Math.cos(ang) * 30);
      var sky2 = pcy + rnd(Math.sin(ang) * 4);
      var flip = Math.sin(ang) < 0;
      if (MASCOTS[env.spec.mascot]) {
        mascot(g, env, skx - 7, sky2 + 2, t, { sleep: false, flip: flip });
        g.r(skx - 4, sky2 + 2, 8, 1, '#5a6a8a');
      } else {
        g.r(skx, sky2 - 7, 3, 3, '#ffd0a0');
        g.r(skx - 1, sky2 - 8, 5, 1, P.accent);
        g.r(skx, sky2 - 4, 3, 4, P.accent2);
        g.r(skx - 1, sky2 - 4, 5, 1, P.accent);
        g.p(skx + (flip ? -2 : 4), sky2 - 3, P.accent);
        g.r(skx, sky2, 1, 2, P.ink);
        g.r(skx + 2, sky2, 1, 2, P.ink);
        g.r(skx - 1, sky2 + 2, 5, 1, '#8a9ab0');
      }
      if (L.snowman) {
        var sx = L.sx;
        g.ell(sx, 70, 5, 4, s3);
        g.ell(sx - 1, 69, 4, 3, WHITE);
        g.ell(sx, 63, 4, 3, s3);
        g.ell(sx - 1, 62, 3, 2, WHITE);
        g.p(sx, 68, P.ink);
        g.p(sx, 71, P.ink);
        g.p(sx - 1, 62, P.ink);
        g.p(sx + 1, 62, P.ink);
        g.p(sx + 2, 63, '#f08a3a');
        g.r(sx - 4, 60 + 5, 9, 1, P.accent);
        g.r(sx - 3, 57, 7, 1, P.ink);
        g.r(sx - 2, 54, 5, 3, P.ink);
      }
      weather(g, env, 0, 0, W, H, t, env.weather === 'clear' ? 0 : 1);
    }
  };

  SC.garden = {
    still: 4,
    setup: function (env, R) {
      var pots = [];
      for (var i = 0; i < 5; i++) pots.push(R.pick(['flower', 'flower', 'bush', 'cactus', 'tall']));
      return { pots: pots, view: R.pick(['hills', 'trees']), shape: R.pick(['rect', 'arch']), cur: R.pick(['paper', 'accent2']) };
    },
    draw: function (g, env, L, t) {
      var P = env.P;
      room(g, env, { wallStyle: 0 }, H, { wainscot: true, wainY: 60 });
      win(g, env, 44, 4, 104, 46, t, { shape: L.shape, panes: 4, view: L.view, curtain: P[L.cur], tie: true, cw: 12, sill: false });
      g.r(36, 50, 120, 3, P.woodL);
      g.r(36, 53, 120, 2, P.woodD);
      g.br(38, 55, 116, 1, P.ink, 0.25);
      var xs = [54, 70, 112, 128, 144];
      for (var i = 0; i < 5; i++) plant(g, env, xs[i], 50, L.pots[i], t, i + (env.seed % 7));
      // watering can
      g.r(84, 44, 9, 6, P.accent2);
      g.r(84, 44, 9, 1, P.accent2L);
      g.line(93, 46, 97, 42, P.accent2D);
      g.line(85, 43, 89, 40, P.accent2D);
      g.line(89, 40, 92, 43, P.accent2D);
      // hanging macrame planter
      g.line(20, 0, 16, 18, P.paperD);
      g.line(20, 0, 24, 18, P.paperD);
      plant(g, env, 20, 26, 'trail', t, 7);
      // butterflies
      for (var b = 0; b < 3; b++) {
        var ph = t * 0.08 + b * 2.1;
        var bx = 96 + Math.sin(ph) * 70 + Math.sin(ph * 2.3) * 8;
        var by = 30 + Math.sin(ph * 1.7 + b) * 12;
        var open = mod(t + b, 2) === 0;
        var bc = [P.accent, '#ffd25a', P.accent2L][b];
        g.p(bx, by, P.ink);
        g.p(bx, by + 1, P.ink);
        if (open) {
          g.r(bx - 2, by - 1, 2, 2, bc);
          g.r(bx + 1, by - 1, 2, 2, bc);
          g.p(bx - 1, by + 1, bc);
          g.p(bx + 1, by + 1, bc);
        } else {
          g.r(bx - 1, by - 1, 1, 2, bc);
          g.r(bx + 1, by - 1, 1, 2, bc);
        }
      }
      mascot(g, env, 91, 50, t);
    }
  };

  // ---------------------------------------------------------------- API

  function pick(arr, v, def) {
    return arr.indexOf(v) >= 0 ? v : def;
  }

  function specFor(seedString) {
    var seed = hashStr(String(seedString == null ? '' : seedString));
    var R = makeRand(seed);
    var scene = R.pick(SCENES);
    var time = R.pick(['day', 'day', 'sunset', 'night', 'night', 'dawn']);
    var weather;
    if (scene === 'cafe' && R.ch(0.5)) weather = 'rain';
    else if (scene === 'snowfield') weather = R.ch(0.7) ? 'snow' : 'clear';
    else weather = R.pick(['clear', 'clear', 'clear', 'cloudy', 'rain', 'snow', 'petals', 'leaves']);
    var spec = { scene: scene, time: time, weather: weather, palette: R.pick(PALETTES), seed: seed };
    if (R.ch(0.7)) spec.mascot = R.pick(ANIMALS);
    return spec;
  }

  function normalize(spec) {
    spec = spec || {};
    var seed = spec.seed;
    if (typeof seed === 'string') seed = hashStr(seed);
    seed = Number(seed);
    if (!isFinite(seed)) seed = 1;
    seed = Math.abs(fl(seed)) >>> 0;
    var def = specFor(String(seed));
    var out = {
      scene: pick(SCENES, spec.scene, def.scene),
      time: pick(TIMES, spec.time, def.time),
      weather: pick(WEATHERS, spec.weather, def.weather),
      palette: pick(PALETTES, spec.palette, def.palette),
      seed: seed
    };
    if (MASCOT_KEYS.indexOf(spec.mascot) >= 0) out.mascot = spec.mascot;
    return out;
  }

  function render(canvas, spec, opts) {
    if (!canvas || !canvas.getContext) throw new Error('PixelCover.render: canvas element required');
    if (canvas.__pixelCover) canvas.__pixelCover.stop();
    opts = opts || {};
    spec = normalize(spec);
    var env = new Env(spec);
    var scene = SC[spec.scene];
    var L = scene.setup(env, makeRand((spec.seed ^ 0x9e3779b9) >>> 0));
    var g = new Gfx();
    var off = document.createElement('canvas');
    off.width = W;
    off.height = H;
    var octx = off.getContext('2d');
    var img = octx.createImageData(W, H);
    var ctx = canvas.getContext('2d');
    canvas.style.width = '100%';
    canvas.style.height = 'auto';
    canvas.style.imageRendering = 'pixelated';
    var scale = 0;
    var frame = 0;
    var last = 0;
    var raf = 0;
    var stopped = false;
    var onscreen = true;
    var visible = typeof document.hidden === 'boolean' ? !document.hidden : true;
    var reduce = !!(window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches);
    var animate = opts.animate !== false && !reduce;
    if (opts.frame != null) frame = opts.frame | 0;
    else if (!animate) frame = scene.still || 0;

    function fit() {
      var cw = canvas.clientWidth || (canvas.parentNode && canvas.parentNode.clientWidth) || W * 3;
      var s = Math.max(1, rnd((cw * (window.devicePixelRatio || 1)) / W));
      if (s === scale) return false;
      scale = s;
      canvas.width = W * s;
      canvas.height = H * s;
      return true;
    }
    function draw() {
      g.clear();
      scene.draw(g, env, L, frame);
      shade(g, env.amb, img.data);
      octx.putImageData(img, 0, 0);
      ctx.imageSmoothingEnabled = false;
      ctx.drawImage(off, 0, 0, W * scale, H * scale);
    }
    function tick(now) {
      raf = 0;
      if (stopped || !animate || !visible || !onscreen) return;
      if (!last) last = now;
      if (now - last >= STEP) {
        last = now - last > STEP * 4 ? now : last + STEP;
        frame++;
        draw();
      }
      schedule();
    }
    function schedule() {
      if (!stopped && animate && visible && onscreen && !raf) raf = requestAnimationFrame(tick);
    }
    function onVis() {
      visible = !document.hidden;
      last = 0;
      schedule();
    }
    fit();
    draw();
    var io = null;
    var ro = null;
    if (animate) {
      if (typeof IntersectionObserver === 'function') {
        io = new IntersectionObserver(function (es) {
          onscreen = es[es.length - 1].isIntersecting;
          last = 0;
          schedule();
        });
        io.observe(canvas);
      }
      document.addEventListener('visibilitychange', onVis);
    }
    if (typeof ResizeObserver === 'function') {
      ro = new ResizeObserver(function () {
        if (!stopped && fit()) draw();
      });
      ro.observe(canvas);
    }
    schedule();
    var handle = {
      spec: spec,
      stop: function () {
        stopped = true;
        if (raf) cancelAnimationFrame(raf);
        raf = 0;
        if (io) io.disconnect();
        if (ro) ro.disconnect();
        document.removeEventListener('visibilitychange', onVis);
        if (canvas.__pixelCover === handle) canvas.__pixelCover = null;
      },
      frame: function () {
        return frame;
      },
      seek: function (f) {
        frame = f | 0;
        draw();
      }
    };
    canvas.__pixelCover = handle;
    return handle;
  }

  // 서버가 저장한 값(study.night.rain.mint.4821)을 spec으로 바꾼다. 값이 없으면 fallbackSeed(블로그 주소)로 정한다.
  // mascot은 블로그 마스코트 값(cat.mint.beret.heart)이고 바탕이 동물이면 장면 안에 그린다
  function fromValue(value, fallbackSeed, mascot) {
    var spec;
    var p = typeof value === 'string' ? value.split('.') : [];
    if (SCENES.indexOf(p[0]) >= 0) {
      spec = normalize({ scene: p[0], time: p[1], weather: p[2], palette: p[3], seed: Number(p[4]) || 0 });
    } else {
      spec = normalize({ seed: String(fallbackSeed || '') });
      delete spec.mascot;
    }
    var base = typeof mascot === 'string' ? mascot.split('.')[0] : null;
    if (ANIMALS.indexOf(base) >= 0) spec.mascot = base;
    return spec;
  }

  window.PixelCover = {
    WIDTH: W,
    HEIGHT: H,
    SCENES: SCENES.slice(),
    TIMES: TIMES.slice(),
    WEATHERS: WEATHERS.slice(),
    PALETTES: PALETTES.slice(),
    MASCOTS: MASCOT_KEYS.slice(),
    label: function (kind, key) {
      var m = LABELS[kind];
      return (m && m[key]) || '';
    },
    render: render,
    specFor: specFor,
    normalize: normalize,
    fromValue: fromValue
  };
})();
