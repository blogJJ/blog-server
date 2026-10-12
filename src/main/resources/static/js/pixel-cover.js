/*
 * pixel-cover.js - 블로그 대표 이미지가 없을 때 보여 주는 움직이는 픽셀 장면 (BLG-15, D-119).
 *
 * 192x80 논리 해상도 프레임버퍼에 직접 픽셀을 칠한 뒤, 시간대 주변광과
 * 램프/창문 빛을 디더링된 단계로 입히고 정수 배율로 캔버스에 올린다.
 * 같은 spec이면 항상 같은 그림, seed가 다르면 소품·팔레트·구름이 달라진다.
 * 장면 40개, 색 8개. 장면마다 소품 칸(벽 장식, 선반 소품, 바닥 소품, 작은 동물 등)을 따로 골라
 * 경우의 수를 곱으로 늘리고, spec.prop(태그에서 고른 소품 주제)이 있으면 그 주제의 소품을 먼저 고른다 (T158, D-129).
 * 섞기 모드(spec.mix, 저장값 일곱째 칸)는 장면 위에 배경 20개(창밖·뒤 풍경), 계절·행사 9개, 벽·바닥 재질,
 * 가구 색, 마스코트 자세, 좌우 뒤집기를 따로 겹친다. 일곱째 칸이 없는 예전 값은 그림이 그대로다.
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
      'night-city': '기본(도시의 밤)',
      library: '도서관',
      rooftop: '옥상 정원',
      campsite: '캠핑',
      mountain: '등산·산',
      train: '기차 여행',
      aquarium: '수족관',
      atelier: '그림·화실',
      workshop: '공방·만들기',
      'flower-shop': '꽃집',
      observatory: '별·천문대',
      bedroom: '아늑한 방',
      'street-market': '시장·길거리 음식',
      riverside: '강변',
      greenhouse: '온실·식물',
      laundromat: '빨래방',
      classroom: '교실·공부',
      gym: '헬스장',
      'record-shop': '레코드 가게',
      convenience: '편의점',
      office: '사무실',
      subway: '지하철',
      'bus-stop': '버스 정류장',
      hanok: '한옥',
      farm: '농장·텃밭',
      'space-station': '우주정거장',
      pojangmacha: '포장마차'
    },
    time: { dawn: '새벽', day: '낮', sunset: '노을', night: '밤' },
    weather: { clear: '맑음', cloudy: '흐림', rain: '비', snow: '눈', petals: '벚꽃', leaves: '낙엽' },
    palette: {
      warm: '따뜻한 나무',
      mint: '민트',
      berry: '베리',
      dusk: '보랏빛 황혼',
      cream: '크림 모노',
      ocean: '바다빛',
      forest: '숲',
      pastel: '파스텔'
    },
    // 태그에서 고른 소품 주제. 저장값 여섯째 칸(study.night.rain.mint.4821.cat)이고 없으면 배치 번호만으로 고른다 (D-129)
    prop: {
      cat: '고양이',
      dog: '강아지',
      bird: '새',
      plant: '식물',
      flower: '꽃',
      book: '책',
      coffee: '커피·차',
      music: '음악',
      art: '그림',
      sport: '운동',
      food: '음식',
      star: '별',
      travel: '여행',
      game: '게임',
      code: '개발'
    },
    // 섞기 모드의 배경(창밖·뒤 풍경)과 계절·행사. 저장값 일곱째 칸(study.night.rain.mint.4821.cat.sea~xmas)이다 (D-129)
    backdrop: {
      hills: '언덕',
      trees: '숲길',
      city: '도시',
      street: '거리',
      sea: '바다',
      mount: '설산',
      snow: '눈밭',
      desert: '사막',
      space: '우주',
      autumn: '단풍 숲',
      sakura: '벚꽃길',
      rice: '논밭 시골',
      harbor: '항구',
      under: '바닷속',
      towers: '야경 빌딩',
      aurora: '오로라',
      jungle: '정글',
      tulip: '튤립 꽃밭',
      canyon: '협곡',
      lake: '호수'
    },
    event: {
      bloom: '봄꽃',
      sun: '여름',
      fall: '가을',
      frost: '겨울',
      xmas: '크리스마스',
      boo: '핼러윈',
      bday: '생일',
      wet: '비 웅덩이',
      flies: '반딧불이'
    },
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
  var PROPS = Object.keys(LABELS.prop);
  var BACKDROPS = Object.keys(LABELS.backdrop);
  var EVENTS = Object.keys(LABELS.event);
  // 처음 14개 장면과 5개 색. 저장값이 없는 블로그는 주소로 장면을 정하는데(specFor), 목록이 늘어도 그 그림이 바뀌지 않게 이 목록에서만 고른다
  var LEGACY_SCENES = SCENES.slice(0, 14);
  var LEGACY_PALETTES = PALETTES.slice(0, 5);
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
    },
    ocean: {
      wall: '#d3e6f2', wallD: '#a9cbe2', trim: '#5f8fb4', floor: '#c9a97e', floorD: '#a3845c',
      wood: '#d2b48a', woodD: '#8f7254', woodL: '#ead3ad', accent: '#2f7fb8', accent2: '#f2a65a',
      metal: '#9fb0bf', metalD: '#5a6b7c', leaf: '#4fa88a', leafD: '#2f7a66', paper: '#f7fbff',
      ink: '#2c3e50', rug: '#4a90c2'
    },
    forest: {
      wall: '#dfe6cf', wallD: '#bcc9a4', trim: '#6f8a55', floor: '#8a6a48', floorD: '#6a4e34',
      wood: '#a87c50', woodD: '#6e4e32', woodL: '#c9a374', accent: '#c8643c', accent2: '#e0b84a',
      metal: '#9aa092', metalD: '#5c6256', leaf: '#5e9a48', leafD: '#3a6a34', paper: '#fbf8ea',
      ink: '#33402c', rug: '#a85a3a'
    },
    pastel: {
      wall: '#f6eef8', wallD: '#e2d4ea', trim: '#b9a3cf', floor: '#e8d2c4', floorD: '#ccb0a0',
      wood: '#f0dcc8', woodD: '#c4a690', woodL: '#fbeee0', accent: '#f4a3b8', accent2: '#8fcbe4',
      metal: '#c4c0d0', metalD: '#8e8aa2', leaf: '#9fd4a8', leafD: '#6fae84', paper: '#fffcfd',
      ink: '#6a5a78', rug: '#f0b8c8'
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
    this.wins = [];
    this.noclip();
  }
  Gfx.prototype.clear = function () {
    this.d.fill(0);
    this.e.fill(0);
    this.em = 0;
    this.lights.length = 0;
    this.wins.length = 0;
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
    this.hint = spec.prop || null;
    // 일곱째 칸이 있을 때만 섞는다. 없으면 아래 값이 모두 비어 예전 그림 그대로다 (D-129)
    this.mix = !!spec.mix;
    this.bd = null;
    this.ev = null;
    if (this.mix) {
      this.bd = fitBd(spec.scene, spec.backdrop);
      this.ev = spec.event || null;
      mixLayers(this);
    }
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
    var amt = env.starK != null ? env.starK : env.T.stars * ({ cloudy: 0.4, rain: 0, snow: 0 }[env.weather] === undefined ? 1 : { cloudy: 0.4, rain: 0, snow: 0 }[env.weather]);
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
    if (env.bd === 'space') return planet(g, env, x, y, w, h);
    if (env.bd === 'under') return;
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
    if (env.bd === 'space' || env.bd === 'under') return;
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
    var snow = snowy(env);
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
    } else if (kind) {
      scenery(g, env, x, y, w, h, kind, t);
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
    if (env.bd === 'space' || env.bd === 'under') return;
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
    if (env.wallX) wallTex(g, env, floorY, env.wallX);
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
    if (env.floorX) {
      floorTex(g, env, floorY, env.floorX);
      return;
    }
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
    outside(g, env, x + 2, y + 2, w - 4, h - 4, t, env.bd || o.view);
    g.wins.push([x + 2, y + 2, w - 4, h - 4, function (qx, qy) {
      return inside(qx, qy, 2);
    }]);
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
    } else if (PLANTS2.indexOf(kind) >= 0) {
      plant2(g, env, x, yb, kind, t, salt);
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
    var pose = env.pose;
    if (pose) {
      // 섞기 모드의 자세: 반대로 보기, 깡충, 낮잠, 밤에도 깨어 있기
      if (o.sleep == null && pose === 'nap') sleep = true;
      if (o.sleep == null && pose === 'awake') sleep = false;
      if (pose === 'flip') o = { flip: !o.flip, sleep: o.sleep };
      if (pose === 'hop' && !sleep) {
        var hp0 = mod(t + env.seed, 18);
        if (hp0 < 3) yb -= hp0 === 1 ? 2 : 1;
      }
    }
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
      moreOf(g, env, L, t, 'study');
      if (L.spot === 'shelf') mascot(g, env, 160, 57, t, { flip: true });
      else mascot(g, env, 2, 51, t);
      if (blossomy(env) || autumnal(env)) {
        var cols = autumnal(env) ? ['#e9873b', '#d0533a', '#f2b648'] : ['#ffc4d6', '#ff9fbf', '#ffe3ec'];
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
      moreOf(g, env, L, t, 'desk');
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
      moreOf(g, env, L, t, 'kitchen');
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
      moreOf(g, env, L, t, 'cafe');
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
      if (env.bd) distant(g, env, 0, 54, W, 26, env.bd, t);
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
      moreOf(g, env, L, t, 'sky');
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
      distant(g, env, 0, 30, W, 36, bk(env, 'city'), t);
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
      moreOf(g, env, L, t, 'night-city');
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
      moreOf(g, env, L, t, 'stage');
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
      distant(g, env, 0, 30, W, 22, bk(env, 'trees'), t);
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
      moreOf(g, env, L, t, 'photo');
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
      moreOf(g, env, L, t, 'game');
      plant(g, env, 170, 60, 'tall', t, 2);
      mascot(g, env, 23, 64, t, { sleep: false });
    }
  };

  function tree(g, env, x, yb, r, t) {
    var P = env.P;
    var snow = snowy(env);
    var fall = autumnal(env);
    var c = fall ? hx('#e08a3a') : P.leaf;
    var cD = fall ? hx('#b45a2a') : P.leafD;
    var cL = fall ? hx('#f6c05a') : P.leafL;
    var blossom = blossomy(env);
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
      var snow = snowy(env);
      g.em = 1;
      skyBg(g, env, 0, 0, W, 48);
      stars(g, env, 0, 0, W, 44, t);
      celestial(g, env, 0, 0, W, 48);
      g.em = 0;
      clouds(g, env, 0, 0, W, 34, t);
      distant(g, env, 0, 22, W, 26, bk(env, 'trees'), t);
      moreOf(g, env, L, t, 'park', 0);
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
      moreOf(g, env, L, t, 'park');
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
      moreOf(g, env, L, t, 'bakery');
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
      if (env.bd) distant(g, env, 0, 22, W, 18, env.bd, t);
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
      moreOf(g, env, L, t, 'sea', 0);
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
      moreOf(g, env, L, t, 'sea');
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
      if (env.bd) distant(g, env, 0, 14, W, 32, env.bd, t);
      else {
        for (x = 0; x < W; x++) {
          var my = 22 + Math.abs(mod(x + env.seed % 40, 70) - 35) * 0.5;
          for (y = fl(my); y < 46; y++) g.p(x, y, y < my + 4 ? '#f6f8fc' : mc);
        }
      }
      moreOf(g, env, L, t, 'snowfield', 0);
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
      moreOf(g, env, L, t, 'snowfield');
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
      moreOf(g, env, L, t, 'garden');
      mascot(g, env, 91, 50, t);
    }
  };

  // ------------------------------------------------- more props (T158, D-129)
  //
  // 장면마다 따로 고르는 칸(벽 장식, 선반 소품, 바닥 소품, 작은 동물, 앞쪽 소품 등)을 두고 칸마다 여러 그림 중
  // 하나를 고른다. 칸끼리 따로 고르므로 경우의 수가 곱으로 늘어난다. 원래 있던 장면은 setup의 난수 순서를
  // 건드리지 않도록 xr()로 따로 뽑은 난수를 쓰고, 칸마다 '비움'도 고를 수 있어 예전 그림이 그대로 남는 배치도 있다.

  function xr(env, salt) {
    return makeRand((Math.imul(env.spec.seed ^ 0x5bd1e995, 2654435761) + Math.imul(salt + 1, 40503)) >>> 0);
  }

  var CUPS = ['mug', 'tea', 'iced', 'bottle', 'teapot', 'glass'];
  var TRINKETS = ['vase', 'candle', 'frame', 'figure', 'radio', 'hourglass', 'snowglobe', 'trophy', 'pencils', 'alarm', 'books', 'fruit', 'succulent', 'speaker'];
  var WALL_ART = ['poster', 'pennant', 'wreath', 'calendar', 'mirror', 'cork', 'photos', 'clock', 'picture', 'map', 'plates', 'neon', 'shelf', 'banner'];
  var FLOOR_THINGS = ['bigplant', 'basket', 'stool', 'boxes', 'guitar', 'umbrella', 'catbed', 'floorlamp', 'books', 'pouf', 'bag', 'skate'];
  var CRITTERS = ['kitty', 'pup', 'bird', 'snail', 'squirrel', 'duck', 'bunny', 'hedgehog', 'butterfly', 'ladybug', 'chick', 'mouse'];
  var PLANTS2 = ['monstera', 'succulent', 'sunflower', 'tulip', 'snake', 'fern', 'herb', 'bonsai'];
  var MOTIFS = ['heart', 'star', 'mountain', 'wave', 'note', 'planet', 'flower', 'moon', 'cat', 'sun'];

  // 태그 소품 주제(spec.prop)마다 칸별로 먼저 고를 그림. 주제가 있으면 칸마다 3분의 2쯤 이 목록에서 고른다
  var HINT = {
    cat: { critter: ['kitty'], trinket: ['figure'], floor: ['catbed', 'basket'], motif: ['cat', 'heart'], wall: ['poster', 'photos'] },
    dog: { critter: ['pup'], floor: ['basket', 'bag'], motif: ['heart', 'sun'], wall: ['photos', 'poster'] },
    bird: { critter: ['bird', 'duck', 'chick'], motif: ['sun', 'heart'], fly: ['flock', 'kite'], wall: ['poster', 'wreath'] },
    plant: { plant: ['monstera', 'snake', 'fern', 'succulent', 'bonsai', 'herb'], trinket: ['succulent', 'vase'], floor: ['bigplant'], critter: ['snail', 'ladybug', 'butterfly'], wall: ['wreath'], motif: ['flower'] },
    flower: { plant: ['tulip', 'sunflower', 'flower'], trinket: ['vase'], critter: ['butterfly', 'ladybug'], motif: ['flower'], wall: ['wreath', 'poster'], floor: ['bigplant'] },
    book: { trinket: ['books', 'pencils', 'hourglass', 'frame'], floor: ['books', 'stool'], wall: ['calendar', 'map', 'shelf'], motif: ['moon', 'star'] },
    coffee: { cup: ['mug', 'tea', 'iced', 'teapot'], trinket: ['candle', 'books'], wall: ['plates', 'poster'], motif: ['heart'] },
    music: { trinket: ['radio', 'speaker'], floor: ['guitar'], motif: ['note'], wall: ['poster', 'neon'] },
    art: { trinket: ['frame', 'pencils', 'vase'], wall: ['picture', 'photos', 'poster'], motif: ['flower', 'moon', 'mountain'], floor: ['stool'] },
    sport: { trinket: ['trophy', 'alarm'], cup: ['bottle'], floor: ['skate', 'bag'], motif: ['star', 'sun'], wall: ['pennant', 'banner'] },
    food: { trinket: ['fruit'], cup: ['teapot', 'glass', 'tea'], wall: ['plates', 'calendar'], motif: ['heart', 'sun'], floor: ['basket'] },
    star: { trinket: ['snowglobe', 'hourglass', 'candle'], motif: ['star', 'moon', 'planet'], wall: ['neon', 'poster'], fly: ['lanterns'] },
    travel: { floor: ['bag', 'umbrella'], wall: ['map', 'photos'], fly: ['plane', 'balloon'], motif: ['mountain', 'wave'], trinket: ['frame', 'snowglobe'] },
    game: { trinket: ['figure', 'speaker', 'trophy'], wall: ['neon', 'poster'], motif: ['star', 'heart'], floor: ['pouf', 'boxes'] },
    code: { trinket: ['pencils', 'alarm', 'speaker'], cup: ['mug', 'iced'], wall: ['calendar', 'cork'], motif: ['star', 'planet'], floor: ['boxes', 'bag'] }
  };

  // 칸 하나를 고른다. 소품 주제가 없으면 R.pick과 같다(난수도 같은 만큼 쓴다)
  function hintOf(R, env, slot, arr, v) {
    var h = env.hint && HINT[env.hint] && HINT[env.hint][slot];
    if (!h) return v;
    var ok = h.filter(function (k) {
      return arr.indexOf(k) >= 0;
    });
    return ok.length && R.ch(0.65) ? R.pick(ok) : v;
  }
  function hp(R, env, slot, arr) {
    return hintOf(R, env, slot, arr, R.pick(arr));
  }
  function hopt(R, env, slot, arr, none) {
    return hintOf(R, env, slot, arr, R.ch(none) ? 'none' : R.pick(arr));
  }

  // 원래 plant()의 새 종류. 화분은 plant()가 그린다
  function plant2(g, env, x, yb, kind, t, salt) {
    var P = env.P;
    var sw = Math.sin(t * 0.25 + x) > 0.6 ? 1 : 0;
    var i;
    var k;
    if (kind === 'monstera') {
      g.r(x, yb - 12, 1, 5, P.leafD);
      g.line(x, yb - 9, x - 4, yb - 13, P.leafD);
      g.line(x, yb - 9, x + 4, yb - 14, P.leafD);
      g.ell(x - 4 + sw, yb - 15, 3, 2, P.leaf);
      g.ell(x + 4, yb - 16, 3, 3, P.leafD);
      g.ell(x, yb - 18, 3, 2, P.leaf);
      g.p(x - 5 + sw, yb - 15, P.wall);
      g.p(x + 3, yb - 16, P.leaf);
      g.p(x + 5, yb - 17, P.wall);
      g.p(x - 1, yb - 19, P.leafL);
      g.p(x - 3 + sw, yb - 16, P.leafL);
    } else if (kind === 'succulent') {
      g.ell(x, yb - 8, 3, 1, P.leafD);
      g.ell(x, yb - 9, 2, 1, P.leaf);
      g.p(x - 3, yb - 9, P.leaf);
      g.p(x + 3, yb - 9, P.leaf);
      g.p(x, yb - 11, P.leafL);
      g.p(x - 1, yb - 10, P.leafL);
      g.p(x + 2, yb - 10, mix(P.leaf, P.accent, 0.4));
    } else if (kind === 'sunflower') {
      g.r(x, yb - 17, 1, 10, P.leafD);
      g.p(x + 1, yb - 11, P.leaf);
      g.p(x + 2, yb - 12, P.leaf);
      g.p(x - 1, yb - 13, P.leaf);
      var sy = yb - 20 + sw;
      g.ell(x, sy, 3, 3, '#f6c63a');
      g.p(x, sy - 4, '#f6c63a');
      g.p(x, sy + 4, '#f6c63a');
      g.p(x - 4, sy, '#f6c63a');
      g.p(x + 4, sy, '#f6c63a');
      g.ell(x, sy, 1, 1, '#7a4a2a');
    } else if (kind === 'tulip') {
      var tc = ['#e8483a', '#f6a0c0', '#f6d04a', P.accent];
      for (i = 0; i < 3; i++) {
        var fx = x - 2 + i * 2;
        var fh = 5 + fl(hash(salt || 2, i) * 4);
        g.r(fx, yb - 6 - fh, 1, fh, P.leafD);
        var c = tc[(i + (salt || 0)) % tc.length];
        g.r(fx - 1, yb - 9 - fh, 3, 2, c);
        g.p(fx - 1, yb - 10 - fh, c);
        g.p(fx + 1, yb - 10 - fh, c);
        g.p(fx, yb - 9 - fh, mix(c, WHITE, 0.35));
      }
      g.p(x - 3, yb - 8, P.leaf);
      g.p(x + 3, yb - 9, P.leaf);
    } else if (kind === 'snake') {
      var hs = [9, 13, 11, 8];
      for (i = 0; i < 4; i++) {
        var bx = x - 2 + i * 1.3;
        for (k = 0; k < hs[i]; k++) g.p(rnd(bx), yb - 7 - k, k === hs[i] - 1 ? P.leafL : (k % 3 === 0 ? '#c8c860' : (i % 2 ? P.leafD : P.leaf)));
      }
    } else if (kind === 'fern') {
      for (i = -2; i <= 2; i++) {
        for (k = 0; k < 7; k++) {
          var px = x + i * k * 0.55 + (i === 0 ? 0 : sw * 0.4);
          var py = yb - 8 - k + Math.abs(i) * k * 0.28;
          g.p(px, py, k % 2 ? P.leaf : P.leafD);
          if (k > 1 && k % 2 === 0) g.p(px + (i < 0 ? -1 : 1), py + 1, P.leafL);
        }
      }
    } else if (kind === 'herb') {
      g.ell(x, yb - 9, 4, 2, P.leafD);
      for (i = 0; i < 6; i++) g.p(x - 3 + i + (i % 2), yb - 11 + (i % 3), i % 2 ? P.leafL : P.leaf);
      g.p(x, yb - 12, P.leaf);
    } else {
      // bonsai
      g.line(x, yb - 7, x - 2, yb - 10, P.woodD);
      g.line(x - 2, yb - 10, x + 2, yb - 13, P.woodD);
      g.ell(x - 3, yb - 13, 3, 1, P.leafD);
      g.ell(x + 3, yb - 15, 3, 1, P.leaf);
      g.p(x - 4, yb - 14, P.leafL);
      g.p(x + 2, yb - 16, P.leafL);
    }
  }

  function cupOf(g, env, kind, x, yb, c, t) {
    var P = env.P;
    if (kind === 'mug') {
      mug(g, env, x, yb, c, t, env.time !== 'day');
    } else if (kind === 'tea') {
      g.r(x - 1, yb - 1, 9, 1, P.paperD);
      g.r(x, yb - 5, 7, 4, c);
      g.r(x, yb - 5, 7, 1, mix(c, WHITE, 0.4));
      g.r(x + 1, yb - 5, 5, 1, '#c8843a');
      g.r(x + 1, yb - 2, 5, 1, mix(c, P.ink, 0.25));
      g.p(x + 7, yb - 4, c);
      g.p(x + 7, yb - 3, mix(c, P.ink, 0.25));
      steam(g, x + 3, yb - 6, t, 8, x + 1);
    } else if (kind === 'iced') {
      g.r(x, yb - 8, 6, 8, mix('#e8f4fa', P.wall, 0.3));
      g.r(x, yb - 6, 6, 6, '#c88a5a');
      g.r(x, yb - 6, 6, 1, '#f0dcc0');
      g.r(x + 1, yb - 5, 2, 2, '#f4fbff');
      g.r(x + 3, yb - 3, 2, 2, '#e8f6ff');
      g.r(x + 4, yb - 11, 1, 5, P.accent);
      g.p(x + 5, yb - 11, P.accent);
      g.br(x - 1, yb, 8, 1, P.ink, 0.2);
    } else if (kind === 'bottle') {
      g.r(x + 1, yb - 10, 3, 10, P.accent2);
      g.r(x + 1, yb - 10, 1, 10, P.accent2L);
      g.r(x + 2, yb - 12, 1, 2, P.metalD);
      g.r(x + 1, yb - 7, 3, 3, P.paper);
      g.br(x, yb, 6, 1, P.ink, 0.2);
    } else if (kind === 'teapot') {
      g.ell(x + 4, yb - 4, 4, 3, c);
      g.r(x + 1, yb - 1, 7, 1, mix(c, P.ink, 0.3));
      g.p(x + 2, yb - 6, mix(c, WHITE, 0.45));
      g.r(x + 3, yb - 8, 3, 1, mix(c, P.ink, 0.2));
      g.p(x + 4, yb - 9, mix(c, P.ink, 0.3));
      g.p(x + 9, yb - 5, c);
      g.p(x + 10, yb - 6, c);
      g.p(x - 1, yb - 5, mix(c, P.ink, 0.3));
      g.p(x - 1, yb - 3, mix(c, P.ink, 0.3));
      g.br(x, yb, 10, 1, P.ink, 0.2);
      steam(g, x + 10, yb - 7, t, 6, x + 3);
    } else {
      // wine glass of juice
      g.r(x + 1, yb - 9, 5, 4, mix('#e8f4fa', P.wall, 0.3));
      g.r(x + 1, yb - 7, 5, 2, P.accent);
      g.p(x + 1, yb - 9, WHITE);
      g.r(x + 3, yb - 5, 1, 4, mix('#e8f4fa', P.ink, 0.15));
      g.r(x + 1, yb - 1, 5, 1, mix('#e8f4fa', P.ink, 0.15));
    }
  }

  // 선반·책상 위 작은 소품. 폭 10px, 높이 12px 안쪽
  function trinket(g, env, kind, x, yb, t) {
    var P = env.P;
    var i;
    g.br(x - 1, yb, 10, 1, P.ink, 0.18);
    if (kind === 'vase') {
      g.r(x + 2, yb - 6, 4, 6, P.accent2);
      g.r(x + 1, yb - 4, 6, 3, P.accent2);
      g.r(x + 2, yb - 6, 1, 5, P.accent2L);
      g.r(x + 6, yb - 4, 1, 3, P.accent2D);
      g.line(x + 3, yb - 7, x + 1, yb - 11, P.leafD);
      g.line(x + 4, yb - 7, x + 6, yb - 12, P.leafD);
      g.p(x + 1, yb - 12, P.accent);
      g.p(x, yb - 11, P.accent);
      g.p(x + 6, yb - 13, '#ffd25a');
      g.p(x + 7, yb - 12, '#ffd25a');
      g.p(x + 4, yb - 10, P.paper);
    } else if (kind === 'candle') {
      g.r(x + 1, yb - 2, 6, 2, P.metalD);
      g.r(x + 3, yb - 8, 3, 6, P.paper);
      g.r(x + 5, yb - 8, 1, 6, P.paperD);
      g.p(x + 4, yb - 9, P.ink);
      var f = mod(t + x, 3);
      g.em = 1;
      g.p(x + 4, yb - 10, '#ffd34a');
      g.p(x + 4 + (f === 1 ? 1 : 0), yb - 11, f === 0 ? '#fff4c0' : '#ffb03a');
      g.em = 0;
      if (env.time !== 'day') g.light(x + 4, yb - 10, 18, [1, 0.75, 0.4], 0.5 * (f === 2 ? 0.85 : 1), { n: 3 });
    } else if (kind === 'frame') {
      g.r(x, yb - 10, 8, 10, P.woodD);
      g.r(x + 1, yb - 9, 6, 7, '#bfe0ee');
      g.r(x + 1, yb - 4, 6, 2, P.leaf);
      g.r(x + 3, yb - 7, 2, 3, P.accent);
      g.p(x + 3, yb - 8, '#ffd0a0');
      g.p(x + 4, yb - 8, '#ffd0a0');
      g.r(x + 6, yb - 2, 2, 2, P.woodDD);
    } else if (kind === 'figure') {
      var fc = mix(P.paper, P.accent2, 0.25);
      g.r(x + 1, yb - 2, 7, 2, P.woodD);
      g.r(x + 2, yb - 6, 5, 4, fc);
      g.r(x + 2, yb - 9, 5, 3, fc);
      g.p(x + 2, yb - 10, fc);
      g.p(x + 6, yb - 10, fc);
      g.p(x + 3, yb - 8, P.ink);
      g.p(x + 5, yb - 8, P.ink);
      g.p(x + 7, yb - 4, fc);
      g.p(x + 8, yb - 5, fc);
      g.r(x + 6, yb - 6, 1, 4, mix(fc, P.ink, 0.2));
    } else if (kind === 'radio') {
      g.r(x, yb - 7, 9, 7, P.accent);
      g.r(x, yb - 7, 9, 1, P.accentL);
      g.r(x + 1, yb - 5, 4, 4, P.accentD);
      for (i = 0; i < 2; i++) g.r(x + 1, yb - 4 + i * 2, 4, 1, mix(P.accentD, P.ink, 0.3));
      g.r(x + 6, yb - 5, 2, 1, P.paper);
      g.p(x + 6, yb - 3, P.paper);
      g.line(x + 7, yb - 8, x + 9, yb - 12, P.metalD);
      if (mod(t, 8) < 4) {
        g.p(x + 10, yb - 9, P.accent2);
        g.p(x + 11, yb - 11, P.accent2);
      }
    } else if (kind === 'hourglass') {
      g.r(x + 1, yb - 1, 7, 1, P.woodD);
      g.r(x + 1, yb - 11, 7, 1, P.woodD);
      var sand = mod(t, 40);
      for (i = 0; i < 4; i++) {
        g.r(x + 2 + i * 0.5, yb - 10 + i, 5 - i, 1, i * 10 >= sand ? '#f0d08a' : mix('#e8f4fa', P.wall, 0.3));
        g.r(x + 2 + (3 - i) * 0.5, yb - 5 + i, 2 + i, 1, (3 - i) * 10 < sand ? '#f0d08a' : mix('#e8f4fa', P.wall, 0.3));
      }
      g.p(x + 4, yb - 6, '#f0d08a');
      g.r(x + 1, yb - 10, 1, 9, P.woodD);
      g.r(x + 7, yb - 10, 1, 9, P.woodD);
    } else if (kind === 'snowglobe') {
      g.r(x + 1, yb - 3, 7, 3, P.woodD);
      g.r(x + 1, yb - 3, 7, 1, P.woodL);
      g.ell(x + 4, yb - 7, 3, 3, mix('#d8eef8', P.wall, 0.2));
      g.r(x + 3, yb - 6, 3, 2, P.leafD);
      g.p(x + 4, yb - 8, P.leafD);
      for (i = 0; i < 3; i++) g.p(x + 2 + mod(i * 2 + fl(t / 3), 5), yb - 9 + mod(i * 3 + t, 5), WHITE);
      g.p(x + 2, yb - 9, WHITE);
    } else if (kind === 'trophy') {
      g.r(x + 2, yb - 2, 5, 2, P.woodD);
      g.r(x + 4, yb - 5, 1, 3, '#d8a838');
      g.r(x + 2, yb - 10, 5, 4, '#f0c84a');
      g.r(x + 3, yb - 6, 3, 1, '#d8a838');
      g.p(x + 1, yb - 9, '#f0c84a');
      g.p(x + 7, yb - 9, '#f0c84a');
      g.p(x + 3, yb - 9, '#fff0a0');
    } else if (kind === 'pencils') {
      g.r(x + 2, yb - 6, 5, 6, P.metal);
      g.r(x + 2, yb - 6, 1, 6, P.metalL);
      g.line(x + 3, yb - 7, x + 2, yb - 11, P.accent);
      g.line(x + 4, yb - 7, x + 5, yb - 12, '#f6c63a');
      g.line(x + 5, yb - 7, x + 7, yb - 10, P.accent2);
      g.p(x + 5, yb - 13, P.ink);
    } else if (kind === 'alarm') {
      g.ell(x + 4, yb - 5, 3, 3, P.accent);
      g.ell(x + 4, yb - 5, 2, 2, P.paper);
      g.p(x + 4, yb - 6, P.ink);
      g.p(x + 5, yb - 5, P.ink);
      var rg = mod(t, 30) < 3 ? 1 : 0;
      g.p(x + 1 - rg, yb - 9, P.metalD);
      g.p(x + 7 + rg, yb - 9, P.metalD);
      g.p(x + 2, yb - 1, P.ink);
      g.p(x + 6, yb - 1, P.ink);
    } else if (kind === 'books') {
      g.r(x, yb - 9, 2, 9, P.books[0]);
      g.r(x + 2, yb - 10, 2, 10, P.books[2]);
      g.line(x + 5, yb - 1, x + 8, yb - 9, P.books[4]);
      g.line(x + 6, yb - 1, x + 9, yb - 9, P.books[4]);
      g.line(x + 4, yb - 1, x + 7, yb - 9, mix(P.books[4], P.ink, 0.3));
    } else if (kind === 'fruit') {
      g.r(x, yb - 3, 9, 3, P.paper);
      g.r(x + 1, yb - 1, 7, 1, P.paperD);
      g.ell(x + 2, yb - 4, 1, 1, '#e8483a');
      g.ell(x + 5, yb - 5, 2, 2, '#f6a03a');
      g.ell(x + 7, yb - 4, 1, 1, '#9ad04a');
      g.p(x + 5, yb - 8, P.leafD);
    } else if (kind === 'succulent') {
      g.r(x + 1, yb - 4, 7, 4, P.pot);
      g.r(x + 1, yb - 4, 7, 1, P.potL);
      plant2(g, env, x + 4, yb + 4, 'succulent', t, 1);
    } else {
      // speaker
      g.r(x + 1, yb - 10, 7, 10, '#34343f');
      g.ell(x + 4, yb - 3, 2, 2, '#55556a');
      g.p(x + 4, yb - 3, '#1a1820');
      g.ell(x + 4, yb - 8, 1, 1, '#55556a');
      g.em = 1;
      if (env.time !== 'day') g.p(x + 7, yb - 10, mod(t, 6) < 3 ? '#6aff8a' : '#2a8a3a');
      g.em = 0;
    }
  }

  function motif(g, kind, x, y, c, c2) {
    var S = {
      heart: ['.##.##.', '#######', '#######', '.#####.', '..###..', '...#...'],
      star: ['...#...', '..###..', '#######', '.#####.', '.##.##.', '#.....#'],
      mountain: ['...#...', '..###..', '.##*##.', '##***##', '#*****#', '*******'],
      wave: ['..###..', '.#...#.', '#..#..#', '..#.#..', '*******', '*******'],
      note: ['..####', '..#..#', '..#..#', '###.##', '##.###', '##.##.'],
      planet: ['...##..', '.#####.', '*#####*', '.#####.', '..###..', '.......'],
      flower: ['.#.#.', '#####', '.#*#.', '#####', '.#.#.', '..*..'],
      moon: ['..###.', '.##...', '##....', '##....', '.##...', '..###.'],
      cat: ['#...#.', '##.##.', '#####.', '#*#*#.', '#####.', '.###..'],
      sun: ['#..#..#', '.#####.', '.#####.', '#######', '.#####.', '#..#..#']
    };
    g.spr(x, y, S[kind] || S.heart, { '#': c, '*': c2 });
  }

  // 벽 장식. (x, y)부터 대략 18x18 칸
  function wallArt(g, env, kind, x, y, t, salt) {
    var P = env.P;
    var i;
    var R2 = makeRand((env.seed * 31 + (salt || 0) * 977 + 5) >>> 0);
    if (kind === 'poster') {
      var bg = R2.pick([P.accent2, P.accent, P.paper, P.leaf, mix(P.ink, P.accent2, 0.3)]);
      var fg = bg === P.paper ? P.accent : (bg === P.accent ? P.paper : R2.pick([P.accent, P.paper, '#ffd25a']));
      g.r(x, y, 14, 18, P.paper);
      g.r(x + 1, y + 1, 12, 16, bg);
      motif(g, hp(R2, env, 'motif', MOTIFS), x + 3, y + 4, fg, mix(fg, P.ink, 0.25));
      g.r(x + 3, y + 13, 8, 1, mix(bg, fg, 0.6));
      g.r(x + 4, y + 15, 6, 1, mix(bg, fg, 0.4));
      g.p(x + 6, y, P.metalD);
      g.br(x + 1, y + 18, 14, 1, P.ink, 0.15);
    } else if (kind === 'pennant') {
      var pc = R2.pick([P.accent, P.accent2, P.leaf]);
      g.r(x, y + 2, 1, 10, P.woodD);
      for (i = 0; i < 6; i++) g.r(x + 1, y + 2 + i, 16 - i * 2.6, 1, i === 0 ? mix(pc, WHITE, 0.3) : pc);
      for (i = 6; i < 10; i++) g.r(x + 1, y + 2 + i - 6 + 5, Math.max(1, 3 - (i - 6)), 1, pc);
      g.r(x + 4, y + 4, 5, 1, P.paper);
    } else if (kind === 'wreath') {
      for (i = 0; i < 20; i++) {
        var a = (i / 20) * 2 * PI;
        var wx = x + 8 + rnd(Math.cos(a) * 6);
        var wy = y + 8 + rnd(Math.sin(a) * 6);
        g.r(wx - 1, wy - 1, 2, 2, i % 2 ? P.leafD : P.leaf);
        if (i % 5 === 0) g.p(wx, wy, P.accent);
      }
      g.r(x + 6, y + 13, 5, 2, P.accent);
      g.p(x + 6, y + 15, P.accent);
      g.p(x + 10, y + 15, P.accent);
    } else if (kind === 'calendar') {
      g.r(x + 1, y + 2, 14, 16, P.paper);
      g.r(x + 1, y + 2, 14, 4, P.accent);
      g.p(x + 4, y + 1, P.metalD);
      g.p(x + 11, y + 1, P.metalD);
      for (var cy = 0; cy < 4; cy++) {
        for (var cx = 0; cx < 5; cx++) g.p(x + 3 + cx * 2.5, y + 8 + cy * 2.5, cy === 1 && cx === 2 ? P.accent : P.paperDD);
      }
      g.br(x + 2, y + 18, 14, 1, P.ink, 0.15);
    } else if (kind === 'mirror') {
      g.ell(x + 8, y + 9, 6, 8, P.woodL);
      g.ell(x + 8, y + 9, 5, 7, mix('#d8eef6', P.wall, 0.3));
      g.line(x + 5, y + 10, x + 8, y + 5, WHITE);
      g.line(x + 7, y + 13, x + 11, y + 7, mix(WHITE, '#d8eef6', 0.5));
    } else if (kind === 'cork') {
      g.r(x, y + 2, 18, 14, P.woodD);
      g.r(x + 1, y + 3, 16, 12, '#c8955a');
      for (i = 0; i < 12; i++) g.p(x + 1 + fl(hash(salt || 3, i) * 16), y + 3 + fl(hash(salt || 3, i + 30) * 12), '#a87a42');
      g.r(x + 2, y + 4, 5, 5, '#ffe27a');
      g.r(x + 9, y + 5, 6, 4, P.paper);
      g.r(x + 4, y + 10, 6, 4, '#a8e6cf');
      g.r(x + 11, y + 10, 4, 4, '#ffb3c6');
      g.p(x + 4, y + 4, P.accent);
      g.p(x + 12, y + 5, P.accent2);
      g.p(x + 6, y + 10, P.accent);
    } else if (kind === 'photos') {
      for (i = 0; i < 3; i++) {
        var px = x + i * 6;
        var py = y + 3 + (i % 2) * 3;
        g.r(px, py, 6, 7, P.paper);
        g.r(px + 1, py + 1, 4, 4, [P.accent2L, '#bfe0ee', P.accentL][i]);
        g.p(px + 2, py + 3, [P.leaf, P.accent, P.leafD][i]);
        g.p(px + 3, py + 2, [P.leaf, P.accent, P.leafD][i]);
      }
      g.line(x - 1, y + 2, x + 19, y + 2, P.ink);
    } else if (kind === 'clock') {
      clock(g, env, x + 8, y + 8, 6, t);
    } else if (kind === 'picture') {
      picture(g, env, x, y + 2, 18, 14);
    } else if (kind === 'map') {
      g.r(x, y + 2, 18, 13, mix(P.paper, '#e8d8a8', 0.5));
      g.r(x, y + 2, 18, 1, P.woodD);
      g.r(x, y + 14, 18, 1, P.woodD);
      g.ell(x + 5, y + 7, 3, 2, P.leaf);
      g.ell(x + 12, y + 10, 4, 2, P.leaf);
      g.p(x + 14, y + 5, P.leaf);
      for (i = 0; i < 6; i++) g.p(x + 6 + i, y + 9 + (i % 2), P.accent);
      g.p(x + 12, y + 10, P.accentD);
    } else if (kind === 'plates') {
      for (i = 0; i < 3; i++) {
        var pr = i === 1 ? 4 : 3;
        var pcx = x + 3 + i * 6;
        var pcy = y + 8 + (i === 1 ? -2 : 2);
        disc(g, pcx, pcy, pr, P.paper);
        disc(g, pcx, pcy, pr - 1, [P.accent2L, P.accentL, P.accent2L][i]);
        g.p(pcx, pcy, P.paper);
      }
    } else if (kind === 'neon') {
      var nc = R2.pick(['#ff7eb0', '#7fd8ff', '#ffd47a', '#a6f0a0']);
      g.r(x, y + 3, 18, 13, mix(P.ink, '#1a1a2a', 0.5));
      g.em = 1;
      motif(g, hp(R2, env, 'motif', ['heart', 'star', 'moon', 'note']), x + 6, y + 6, mod(t, 30) === 0 ? mix(nc, P.ink, 0.5) : nc, nc);
      g.em = 0;
      if (env.time !== 'day') g.light(x + 9, y + 9, 22, [1, 0.7, 0.9], 0.35, { n: 3 });
    } else if (kind === 'shelf') {
      g.r(x - 2, y + 14, 22, 2, P.woodL);
      g.r(x - 2, y + 16, 22, 1, P.woodD);
      trinket(g, env, R2.pick(['vase', 'succulent', 'frame', 'trophy', 'snowglobe']), x - 1, y + 14, t);
      trinket(g, env, R2.pick(['books', 'candle', 'figure', 'hourglass', 'alarm']), x + 10, y + 14, t);
    } else {
      // garland banner
      for (i = 0; i < 20; i++) {
        var sag = rnd(Math.sin((i / 19) * PI) * 3);
        g.p(x - 1 + i, y + 3 + sag, P.ink);
        if (i % 5 === 2) {
          var bc = [P.accent, P.accent2, P.paper, '#ffd25a'][fl(i / 5) % 4];
          for (var r = 0; r < 4; r++) g.r(x - 3 + i + r, y + 4 + sag + r, 5 - r * 2, 1, bc);
        }
      }
    }
  }

  // 바닥에 두는 큰 소품. 폭 16px, 높이 28px 안쪽
  function floorThing(g, env, kind, x, yb, t) {
    var P = env.P;
    var i;
    g.br(x - 1, yb, 16, 1, P.ink, 0.22);
    if (kind === 'bigplant') {
      g.r(x + 2, yb - 9, 11, 9, P.pot);
      g.r(x + 1, yb - 10, 13, 2, P.potL);
      g.r(x + 11, yb - 8, 2, 8, P.potD);
      var sw = Math.sin(t * 0.2 + x) > 0.5 ? 1 : 0;
      g.line(x + 7, yb - 10, x + 3, yb - 18, P.leafD);
      g.line(x + 7, yb - 10, x + 11, yb - 20, P.leafD);
      g.line(x + 7, yb - 10, x + 7, yb - 24, P.leafD);
      g.ell(x + 2 + sw, yb - 19, 4, 3, P.leaf);
      g.ell(x + 12, yb - 21, 4, 3, P.leafD);
      g.ell(x + 7, yb - 26, 4, 3, P.leaf);
      g.p(x + 1 + sw, yb - 20, P.wall);
      g.p(x + 13, yb - 22, P.wall);
      g.p(x + 6, yb - 27, P.leafL);
      g.p(x + 3 + sw, yb - 21, P.leafL);
    } else if (kind === 'basket') {
      g.r(x + 1, yb - 7, 13, 7, P.woodL);
      for (i = 0; i < 13; i += 2) g.r(x + 1 + i, yb - 7, 1, 7, P.wood);
      g.r(x + 1, yb - 7, 13, 1, P.woodD);
      g.ell(x + 5, yb - 8, 2, 2, P.accent);
      g.ell(x + 9, yb - 9, 2, 2, P.accent2);
      g.line(x + 9, yb - 9, x + 14, yb - 3, P.accent2);
      g.p(x + 4, yb - 9, P.accentL);
    } else if (kind === 'stool') {
      g.r(x + 2, yb - 12, 11, 2, P.woodL);
      g.r(x + 2, yb - 10, 11, 1, P.woodD);
      g.line(x + 3, yb - 9, x + 2, yb - 1, P.woodD);
      g.line(x + 11, yb - 9, x + 12, yb - 1, P.woodD);
      g.r(x + 3, yb - 5, 9, 1, P.woodD);
      trinket(g, env, 'succulent', x + 3, yb - 12, t);
    } else if (kind === 'boxes') {
      g.r(x, yb - 8, 12, 8, '#c8955a');
      g.r(x, yb - 8, 12, 1, '#e0b47a');
      g.r(x + 5, yb - 8, 2, 8, '#e8d8a8');
      g.r(x + 3, yb - 15, 9, 7, '#d8a86a');
      g.r(x + 3, yb - 15, 9, 1, '#f0c88a');
      g.r(x + 7, yb - 15, 1, 7, '#e8d8a8');
      g.r(x + 1, yb - 5, 3, 2, P.paper);
    } else if (kind === 'guitar') {
      var gc = P.woodL;
      g.ell(x + 7, yb - 4, 5, 4, gc);
      g.ell(x + 7, yb - 10, 4, 3, gc);
      g.ell(x + 7, yb - 7, 1, 1, '#2a1a14');
      g.r(x + 6, yb - 26, 2, 14, P.woodD);
      g.r(x + 5, yb - 28, 4, 3, P.woodDD);
      g.r(x + 5, yb - 2, 5, 1, P.woodD);
    } else if (kind === 'umbrella') {
      g.r(x + 3, yb - 9, 8, 9, P.metalD);
      g.r(x + 3, yb - 9, 8, 1, P.metal);
      g.line(x + 5, yb - 9, x + 4, yb - 22, P.accent);
      g.r(x + 3, yb - 24, 2, 3, P.accent);
      g.line(x + 9, yb - 9, x + 10, yb - 19, P.accent2);
      g.r(x + 9, yb - 21, 3, 3, P.accent2D);
      g.r(x + 9, yb - 20, 1, 3, P.accent2);
    } else if (kind === 'catbed') {
      g.ell(x + 7, yb - 3, 7, 3, P.accent2D);
      g.ell(x + 7, yb - 3, 5, 2, P.paperD);
      critter(g, env, 'kitty', x + 3, yb - 2, t, false, true);
    } else if (kind === 'floorlamp') {
      g.r(x + 3, yb - 2, 9, 2, P.metalD);
      g.r(x + 7, yb - 26, 1, 24, P.metalD);
      for (i = 0; i < 5; i++) g.r(x + 5 - i * 0.6, yb - 32 + i, 5 + i * 1.2, 1, i === 4 ? P.paperD : P.paper);
      g.em = 1;
      g.r(x + 4, yb - 27, 7, 1, env.time === 'day' ? '#f6efd8' : '#fff1c2');
      g.em = 0;
      g.light(x + 7, yb - 26, 40, env.T.lamp, env.lampK * 0.9, { top: yb - 28 });
    } else if (kind === 'books') {
      bookStack(g, env, x, yb, 4, x + 3);
    } else if (kind === 'pouf') {
      g.ell(x + 7, yb - 4, 7, 4, P.accent);
      g.ell(x + 7, yb - 6, 6, 2, P.accentL);
      for (i = 0; i < 4; i++) g.p(x + 3 + i * 3, yb - 3, P.accentD);
    } else if (kind === 'bag') {
      g.r(x + 2, yb - 10, 11, 10, P.accent2);
      g.r(x + 2, yb - 10, 11, 2, P.accent2L);
      g.r(x + 4, yb - 6, 7, 4, P.accent2D);
      g.line(x + 4, yb - 10, x + 6, yb - 14, P.accent2D);
      g.line(x + 11, yb - 10, x + 9, yb - 14, P.accent2D);
      g.r(x + 6, yb - 14, 4, 1, P.accent2D);
    } else {
      // skateboard leaning on the wall
      g.line(x + 4, yb - 1, x + 10, yb - 22, P.accent);
      g.line(x + 5, yb - 1, x + 11, yb - 22, P.accent);
      g.line(x + 6, yb - 1, x + 12, yb - 22, P.accentD);
      g.r(x + 2, yb - 4, 2, 2, P.metalD);
      g.r(x + 9, yb - 20, 2, 2, P.metalD);
    }
  }

  // 작은 동물. 마스코트와 따로 장면 구석에서 움직인다. (x, yb)는 왼쪽 아래
  function critter(g, env, kind, x, yb, t, flip, sleep) {
    var P = env.P;
    var f = flip ? -1 : 1;
    var ox = flip ? x + 8 : x;
    function q(dx, dy, c) {
      g.p(ox + dx * f, yb + dy, c);
    }
    function rr(dx, dy, w, h, c) {
      for (var j = 0; j < h; j++) {
        for (var i = 0; i < w; i++) q(dx + i, dy + j, c);
      }
    }
    var step = mod(t, 4) < 2 ? 0 : 1;
    var c;
    var cD;
    if (kind === 'kitty' || kind === 'pup') {
      c = kind === 'kitty' ? hx(['#e8a050', '#6a6a78', '#f4ece0', '#3a3036'][env.seed % 4]) : hx(['#c8955a', '#f4ece0', '#8a6a4a'][env.seed % 3]);
      cD = mix(c, P.ink, 0.35);
      if (sleep || env.time === 'night') {
        rr(0, -3, 8, 3, c);
        rr(1, -4, 6, 1, c);
        rr(5, -5, 3, 2, c);
        q(5, -6, kind === 'kitty' ? c : cD);
        q(7, -6, kind === 'kitty' ? c : cD);
        q(6, -4, cD);
        rr(0, -1, 8, 1, cD);
        q(-1, -2, c);
        q(-1, -3, c);
      } else {
        var tail = mod(t, 12) < 6 ? 0 : 1;
        rr(0, -4, 6, 3, c);
        rr(5, -7, 4, 4, c);
        q(5, -8, kind === 'kitty' ? c : cD);
        q(8, -8, kind === 'kitty' ? c : cD);
        if (kind === 'pup') {
          q(5, -6, cD);
          q(8, -6, cD);
        }
        q(6, -6, P.ink);
        q(8, -6, P.ink);
        q(9, -5, kind === 'pup' ? P.ink : c);
        rr(0, -1, 1, 1, cD);
        rr(2, -1, 1, 1, cD);
        rr(4, -1, 1, 1, cD);
        q(-1, -4 - tail, c);
        q(-2, -5 - tail, c);
        if (kind === 'pup') q(-1, -5, c);
      }
    } else if (kind === 'bird') {
      var hop = mod(t + x, 14) < 2 ? 1 : 0;
      c = hx(['#5a8ad0', '#e8483a', '#f6c63a', '#8a6a4a'][env.seed % 4]);
      rr(0, -3 - hop, 4, 2, c);
      rr(3, -5 - hop, 2, 2, c);
      q(5, -4 - hop, '#f0a23a');
      q(4, -5 - hop, P.ink);
      q(-1, -3 - hop, mix(c, P.ink, 0.3));
      rr(1, -2 - hop, 2, 1, mix(c, WHITE, 0.4));
      q(1, -1, '#f0a23a');
      q(3, -1, '#f0a23a');
    } else if (kind === 'snail') {
      var crawl = fl(mod(t * 0.1, 6));
      rr(crawl, -1, 8, 1, '#c8b8a0');
      q(crawl + 7, -2, '#c8b8a0');
      q(crawl + 7, -3, '#c8b8a0');
      q(crawl + 8, -4, P.ink);
      g.ell(ox + (crawl + 3) * f, yb - 3, 2, 2, '#b8784a');
      q(crawl + 3, -3, '#e8b87a');
    } else if (kind === 'squirrel') {
      c = hx('#b8683a');
      var flick = mod(t, 10) < 5 ? 0 : 1;
      rr(2, -4, 4, 3, c);
      rr(5, -6, 3, 3, c);
      q(6, -7, c);
      q(6, -5, P.ink);
      q(8, -5, c);
      rr(0, -8 - flick, 2, 6, mix(c, WHITE, 0.15));
      rr(1, -9 - flick, 2, 2, mix(c, WHITE, 0.15));
      q(3, -1, mix(c, P.ink, 0.3));
      q(5, -1, mix(c, P.ink, 0.3));
      q(6, -3, '#a87a42');
    } else if (kind === 'duck') {
      var bob = mod(t + x, 8) < 4 ? 0 : 1;
      c = hx('#f4f4f0');
      rr(0, -3 + bob, 6, 3, c);
      rr(4, -6 + bob, 3, 3, hx('#3a8a5a'));
      q(7, -5 + bob, '#f0a23a');
      q(8, -5 + bob, '#f0a23a');
      q(5, -5 + bob, P.ink);
      q(-1, -3 + bob, c);
      rr(1, -2 + bob, 3, 1, mix(c, '#8a8aa0', 0.4));
    } else if (kind === 'bunny') {
      c = hx(['#f4f0ea', '#c8a888', '#8a8a96'][env.seed % 3]);
      var hp = mod(t + x, 16) < 3 ? 1 : 0;
      rr(0, -4 - hp, 6, 4, c);
      rr(5, -6 - hp, 3, 3, c);
      q(5, -9 - hp, c);
      q(5, -8 - hp, c);
      q(7, -9 - hp, c);
      q(7, -8 - hp, c);
      q(6, -5 - hp, P.ink);
      q(-1, -3 - hp, WHITE);
    } else if (kind === 'hedgehog') {
      rr(0, -3, 7, 3, '#8a6a4a');
      for (var s = 0; s < 7; s += 2) q(s, -4, '#5a4030');
      rr(6, -2, 2, 2, '#e8c8a0');
      q(8, -2, P.ink);
      q(7, -3, P.ink);
    } else if (kind === 'butterfly') {
      var ph = t * 0.15 + x;
      var bx = x + 4 + Math.sin(ph) * 6;
      var by = yb - 8 + Math.sin(ph * 1.7) * 4;
      var open = mod(t, 2) === 0;
      c = [P.accent, '#ffd25a', P.accent2L][env.seed % 3];
      g.p(bx, by, P.ink);
      g.p(bx, by + 1, P.ink);
      if (open) {
        g.r(bx - 2, by - 1, 2, 2, c);
        g.r(bx + 1, by - 1, 2, 2, c);
      } else {
        g.r(bx - 1, by - 1, 1, 2, c);
        g.r(bx + 1, by - 1, 1, 2, c);
      }
    } else if (kind === 'ladybug') {
      var lx = fl(mod(t * 0.2, 10));
      rr(lx, -2, 3, 2, '#e8483a');
      q(lx + 3, -2, P.ink);
      q(lx + 1, -2, P.ink);
      q(lx + 1, -1, P.ink);
    } else if (kind === 'chick') {
      var pk = mod(t + x, 10) < 2 ? 1 : 0;
      rr(0, -3, 4, 3, '#ffd84a');
      rr(2, -5 + pk, 3, 2, '#ffd84a');
      q(5, -4 + pk, '#f08a3a');
      q(3, -5 + pk, P.ink);
      q(1, -2, '#f6c03a');
    } else {
      // mouse
      var run = fl(mod(t * 0.4, 14));
      rr(run, -3, 4, 3, '#a8a0a8');
      q(run + 4, -2, '#a8a0a8');
      q(run + 5, -2, '#f0a0b0');
      q(run + 2, -4, '#f0a0b0');
      q(run + 3, -2, P.ink);
      q(run - 1, -1, '#c8a0a8');
      q(run - 2, -1, '#c8a0a8');
      q(run - 3, -2, '#c8a0a8');
    }
  }

  // ---- outdoor pieces

  function skyTop(g, env, h, t, ch) {
    g.em = 1;
    skyBg(g, env, 0, 0, W, h);
    stars(g, env, 0, 0, W, h - 4, t);
    celestial(g, env, 0, 0, W, h);
    g.em = 0;
    clouds(g, env, 0, 0, W, ch || fl(h * 0.7), t);
  }

  var STREET = ['bench', 'mailbox', 'hydrant', 'bike', 'sign', 'lantern', 'bin', 'vending', 'barrel', 'scooter', 'phone', 'cart'];

  function streetThing(g, env, kind, x, yb, t) {
    var P = env.P;
    var i;
    g.br(x - 1, yb, 18, 1, P.ink, 0.22);
    if (kind === 'bench') {
      g.r(x, yb - 6, 18, 2, P.woodL);
      g.r(x, yb - 10, 18, 2, P.woodL);
      g.r(x, yb - 4, 18, 1, P.woodD);
      g.r(x + 2, yb - 4, 1, 4, P.metalD);
      g.r(x + 15, yb - 4, 1, 4, P.metalD);
      g.r(x + 1, yb - 11, 1, 7, P.metalD);
      g.r(x + 16, yb - 11, 1, 7, P.metalD);
    } else if (kind === 'mailbox') {
      g.r(x + 6, yb - 8, 2, 8, P.woodD);
      g.r(x + 2, yb - 14, 10, 6, P.accent);
      g.r(x + 2, yb - 15, 10, 1, P.accentL);
      g.r(x + 3, yb - 12, 6, 1, P.accentD);
      g.r(x + 11, yb - 18, 1, 5, P.metalD);
      g.r(x + 12, yb - 18, 3, 2, '#ffd25a');
    } else if (kind === 'hydrant') {
      g.r(x + 4, yb - 9, 7, 9, '#e8483a');
      g.r(x + 5, yb - 11, 5, 2, '#e8483a');
      g.r(x + 6, yb - 12, 3, 1, '#c8382a');
      g.r(x + 2, yb - 7, 2, 2, '#c8382a');
      g.r(x + 11, yb - 7, 2, 2, '#c8382a');
      g.r(x + 4, yb - 9, 1, 9, '#ff7a6a');
      g.r(x + 3, yb - 1, 9, 1, '#a8281a');
    } else if (kind === 'bike') {
      var wc = P.ink;
      for (i = 0; i < 16; i++) {
        var a = (i / 16) * 2 * PI;
        g.p(x + 3 + rnd(Math.cos(a) * 3), yb - 4 + rnd(Math.sin(a) * 3), wc);
        g.p(x + 14 + rnd(Math.cos(a) * 3), yb - 4 + rnd(Math.sin(a) * 3), wc);
      }
      g.line(x + 3, yb - 4, x + 8, yb - 4, P.accent);
      g.line(x + 8, yb - 4, x + 12, yb - 9, P.accent);
      g.line(x + 3, yb - 4, x + 6, yb - 9, P.accent);
      g.line(x + 6, yb - 9, x + 12, yb - 9, P.accent);
      g.line(x + 12, yb - 9, x + 14, yb - 4, P.accent);
      g.line(x + 8, yb - 4, x + 6, yb - 10, P.accent);
      g.r(x + 5, yb - 11, 3, 1, P.ink);
      g.line(x + 12, yb - 9, x + 11, yb - 12, P.metalD);
      g.r(x + 10, yb - 12, 3, 1, P.metalD);
      g.r(x + 12, yb - 11, 4, 2, P.woodL);
    } else if (kind === 'sign') {
      g.r(x + 7, yb - 20, 1, 20, P.woodD);
      g.r(x + 1, yb - 19, 12, 4, P.woodL);
      g.p(x + 13, yb - 18, P.woodL);
      g.p(x + 13, yb - 17, P.woodL);
      g.p(x + 14, yb - 17, P.woodL);
      g.r(x + 3, yb - 14, 12, 4, P.wood);
      g.p(x + 2, yb - 13, P.wood);
      g.p(x + 2, yb - 12, P.wood);
      g.r(x + 3, yb - 18, 7, 1, P.woodD);
      g.r(x + 6, yb - 13, 7, 1, P.woodD);
    } else if (kind === 'lantern') {
      g.r(x + 7, yb - 22, 2, 22, P.metalD);
      g.r(x + 5, yb - 1, 6, 1, P.metalD);
      g.r(x + 4, yb - 27, 8, 1, P.metalD);
      g.r(x + 5, yb - 26, 6, 4, env.time === 'day' ? mix('#e8e8dc', P.metal, 0.3) : '#fff1c2');
      g.r(x + 5, yb - 28, 6, 1, P.metalD);
      g.r(x + 7, yb - 30, 2, 2, P.metalD);
      if (env.time !== 'day') {
        g.em = 1;
        g.r(x + 6, yb - 25, 4, 2, '#fff8d8');
        g.em = 0;
        halo(g, x + 8, yb - 24, 2, 5, '#ffe9a8', 0.5);
      }
      g.light(x + 8, yb - 22, 34, env.T.lamp, env.lampK * 0.9, { top: yb - 26 });
    } else if (kind === 'bin') {
      g.r(x + 4, yb - 11, 9, 11, P.leafD);
      g.r(x + 3, yb - 12, 11, 2, P.leaf);
      for (i = 0; i < 4; i++) g.r(x + 5 + i * 2, yb - 9, 1, 8, mix(P.leafD, P.ink, 0.25));
    } else if (kind === 'vending') {
      g.r(x + 1, yb - 24, 14, 24, P.accent);
      g.r(x + 1, yb - 24, 14, 1, P.accentL);
      g.em = 1;
      g.r(x + 3, yb - 22, 8, 14, '#e8f4fa');
      for (var vy = 0; vy < 3; vy++) {
        for (var vx = 0; vx < 3; vx++) g.r(x + 4 + vx * 2.5, yb - 21 + vy * 5, 2, 3, [P.accent2, '#f6c63a', '#e8483a'][(vx + vy) % 3]);
      }
      g.em = 0;
      g.r(x + 12, yb - 20, 2, 4, P.accentD);
      g.r(x + 3, yb - 6, 8, 3, '#2a2a33');
      if (env.time !== 'day') g.light(x + 7, yb - 14, 26, [0.85, 0.95, 1], 0.5, { n: 3 });
    } else if (kind === 'barrel') {
      g.r(x + 3, yb - 8, 11, 8, P.woodD);
      g.r(x + 3, yb - 7, 11, 1, P.metalD);
      g.r(x + 3, yb - 3, 11, 1, P.metalD);
      g.ell(x + 8, yb - 10, 6, 2, P.leafD);
      var fc = [P.accent, '#ffd25a', P.paper, P.accent2];
      for (i = 0; i < 6; i++) g.p(x + 3 + i * 2, yb - 11 - (i % 2), fc[i % 4]);
    } else if (kind === 'scooter') {
      g.ell(x + 3, yb - 2, 2, 2, P.ink);
      g.ell(x + 14, yb - 2, 2, 2, P.ink);
      g.r(x + 3, yb - 5, 11, 2, P.accent2);
      g.ell(x + 10, yb - 7, 4, 2, P.accent2);
      g.r(x + 8, yb - 9, 5, 1, P.ink);
      g.line(x + 3, yb - 5, x + 2, yb - 13, P.metalD);
      g.r(x, yb - 14, 5, 1, P.metalD);
    } else if (kind === 'phone') {
      g.r(x + 3, yb - 26, 11, 26, '#d8382a');
      g.r(x + 3, yb - 26, 11, 1, '#ff6a5a');
      g.r(x + 5, yb - 22, 7, 18, env.time === 'day' ? '#bfe0ee' : '#fff1c2');
      for (i = 0; i < 4; i++) g.r(x + 5, yb - 22 + i * 5, 7, 1, '#a8281a');
      g.r(x + 8, yb - 22, 1, 18, '#a8281a');
      g.r(x + 5, yb - 25, 7, 2, '#2a2a33');
    } else {
      // food cart
      g.r(x, yb - 12, 18, 9, P.paper);
      g.r(x, yb - 12, 18, 1, P.paperD);
      g.r(x, yb - 4, 18, 1, P.paperDD);
      g.ell(x + 4, yb - 2, 2, 2, P.ink);
      g.ell(x + 14, yb - 2, 2, 2, P.ink);
      g.r(x + 1, yb - 22, 1, 10, P.metalD);
      g.r(x + 16, yb - 22, 1, 10, P.metalD);
      for (i = 0; i < 20; i++) g.p(x - 1 + i, yb - 23, (i >> 1) & 1 ? P.accent : P.paper);
      for (i = 0; i < 20; i++) g.p(x - 1 + i, yb - 22, (i >> 1) & 1 ? P.accent : P.paper);
      g.r(x + 4, yb - 14, 4, 2, '#e8a050');
      g.r(x + 10, yb - 14, 4, 2, '#e8483a');
      steam(g, x + 6, yb - 15, t, 7, x);
    }
  }

  var BOATS = ['sail', 'row', 'ferry', 'kayak', 'yacht', 'swan', 'fishing'];

  // 물 위의 배. (x, y)는 물에 닿는 왼쪽
  function boat(g, env, kind, x, y, t) {
    var P = env.P;
    var r;
    var bob = mod(t + x, 8) < 4 ? 0 : 1;
    y += bob;
    if (kind === 'sail') {
      g.r(x, y, 14, 2, P.woodD);
      g.r(x + 1, y + 2, 12, 1, P.woodDD);
      g.r(x + 6, y - 14, 1, 14, P.woodDD);
      for (r = 0; r < 12; r++) g.r(x + 7, y - 13 + r, rnd(r * 0.55) + 1, 1, r % 4 === 2 ? P.accent2 : P.paper);
      for (r = 0; r < 9; r++) g.r(x + 5 - rnd(r * 0.45), y - 10 + r, rnd(r * 0.45) + 1, 1, P.paperD);
    } else if (kind === 'row') {
      g.r(x + 1, y - 2, 12, 2, P.accent);
      g.r(x, y - 3, 14, 1, P.accentL);
      g.r(x + 2, y, 10, 1, P.accentD);
      var oar = mod(t, 12) < 6 ? 0 : 1;
      g.line(x + 4, y - 3, x - 2, y + 1 - oar, P.woodD);
      g.line(x + 10, y - 3, x + 16, y + 1 - oar, P.woodD);
    } else if (kind === 'ferry') {
      g.r(x, y - 3, 26, 3, P.paper);
      g.r(x + 1, y, 24, 1, P.accent2D);
      g.r(x + 4, y - 7, 17, 4, P.paper);
      g.r(x + 4, y - 7, 17, 1, P.paperD);
      for (r = 0; r < 5; r++) g.r(x + 6 + r * 3, y - 6, 2, 2, env.time === 'night' ? '#ffd36e' : '#4f6f9f');
      g.r(x + 9, y - 11, 3, 4, P.accent);
      g.r(x + 9, y - 11, 3, 1, P.ink);
      g.r(x, y - 2, 26, 1, P.accent);
    } else if (kind === 'kayak') {
      g.r(x + 2, y - 1, 12, 1, '#f6c63a');
      g.r(x, y, 16, 1, '#e8a83a');
      g.r(x + 7, y - 3, 2, 2, P.accent2);
      g.p(x + 7, y - 4, '#ffd0a0');
      var pa = mod(t, 8) < 4;
      g.line(x + 3, y - (pa ? 5 : 1), x + 12, y - (pa ? 1 : 5), P.ink);
    } else if (kind === 'yacht') {
      g.r(x, y - 2, 22, 2, P.paper);
      g.r(x + 2, y, 18, 1, P.metalD);
      g.r(x + 5, y - 5, 12, 3, P.paper);
      g.r(x + 7, y - 4, 8, 1, '#4f6f9f');
      g.r(x + 8, y - 7, 6, 2, P.paperD);
      g.r(x, y - 1, 22, 1, P.accent2);
    } else if (kind === 'swan') {
      g.ell(x + 5, y - 2, 4, 2, '#f8f8f4');
      g.r(x + 8, y - 8, 1, 6, '#f8f8f4');
      g.r(x + 8, y - 9, 2, 1, '#f8f8f4');
      g.p(x + 10, y - 9, '#f0a23a');
      g.p(x + 9, y - 9, P.ink);
      g.r(x + 2, y - 4, 3, 1, '#e0e0e8');
    } else {
      // fishing boat with cabin
      g.r(x, y - 3, 18, 3, P.accent2);
      g.r(x + 1, y, 16, 1, P.accent2D);
      g.r(x, y - 3, 18, 1, P.accent2L);
      g.r(x + 9, y - 8, 6, 5, P.paper);
      g.r(x + 10, y - 7, 2, 2, '#4f6f9f');
      g.r(x + 9, y - 9, 6, 1, P.accent);
      g.r(x + 4, y - 14, 1, 11, P.woodD);
      g.line(x + 4, y - 14, x - 3, y - 6, P.ink);
    }
  }

  var FAR = ['windmill', 'lighthouse', 'wheel', 'tower', 'castle', 'church', 'turbines', 'house'];

  // 멀리 보이는 건물. (x, yb)는 땅에 닿는 가운데
  function farThing(g, env, kind, x, yb, t) {
    var P = env.P;
    var c = tone(env, mix(env.skyLow, P.wallD, 0.45));
    var cD = mix(c, P.ink, 0.3);
    var i;
    if (kind === 'windmill') {
      g.r(x - 2, yb - 14, 5, 14, c);
      g.r(x - 3, yb - 4, 7, 4, c);
      g.r(x - 1, yb - 16, 3, 2, cD);
      var a0 = t * 0.12;
      for (i = 0; i < 4; i++) {
        var a = a0 + (i * PI) / 2;
        g.line(x, yb - 15, x + rnd(Math.cos(a) * 9), yb - 15 + rnd(Math.sin(a) * 9), cD);
      }
    } else if (kind === 'lighthouse') {
      for (i = 0; i < 20; i++) g.r(x - 2 - fl(i / 7), yb - 20 + i, 5 + fl(i / 7) * 2, 1, fl(i / 4) % 2 ? tone(env, P.accent) : tone(env, P.paper));
      g.r(x - 2, yb - 24, 5, 4, cD);
      g.em = 1;
      g.r(x - 1, yb - 23, 3, 2, env.time === 'day' ? '#e8e8dc' : '#fff1a0');
      g.em = 0;
      if (env.time !== 'day') {
        var sweep = Math.sin(t * 0.1);
        for (var k = 2; k < 26; k++) g.b(x + rnd(sweep * k), yb - 22 + rnd(k * 0.05), '#fff6c8', 0.45 * (1 - k / 26));
      }
    } else if (kind === 'wheel') {
      var rr = 11;
      g.line(x, yb - rr - 2, x - 5, yb, cD);
      g.line(x, yb - rr - 2, x + 5, yb, cD);
      for (i = 0; i < 28; i++) {
        var aa = (i / 28) * 2 * PI;
        g.p(x + rnd(Math.cos(aa) * rr), yb - rr - 2 + rnd(Math.sin(aa) * rr), c);
      }
      for (i = 0; i < 8; i++) {
        var ab = (i / 8) * 2 * PI + t * 0.03;
        var ex = x + rnd(Math.cos(ab) * rr);
        var ey = yb - rr - 2 + rnd(Math.sin(ab) * rr);
        g.line(x, yb - rr - 2, ex, ey, cD);
        g.r(ex - 1, ey, 3, 2, tone(env, [P.accent, P.accent2, '#ffd25a'][i % 3]));
      }
    } else if (kind === 'tower') {
      g.line(x - 6, yb, x, yb - 28, c);
      g.line(x + 6, yb, x, yb - 28, c);
      g.r(x - 4, yb - 10, 9, 1, c);
      g.r(x - 2, yb - 18, 5, 1, c);
      g.line(x - 5, yb - 2, x + 4, yb - 10, cD);
      g.line(x + 5, yb - 2, x - 4, yb - 10, cD);
      g.em = 1;
      if (mod(t, 10) < 5) g.p(x, yb - 29, '#ff4a4a');
      g.em = 0;
    } else if (kind === 'castle') {
      g.r(x - 10, yb - 10, 21, 10, c);
      g.r(x - 12, yb - 16, 5, 16, c);
      g.r(x + 8, yb - 16, 5, 16, c);
      g.r(x - 3, yb - 18, 7, 8, c);
      for (i = 0; i < 3; i++) {
        g.p(x - 12 + i * 2, yb - 17, c);
        g.p(x + 8 + i * 2, yb - 17, c);
      }
      g.r(x - 13, yb - 18, 7, 2, tone(env, P.accent));
      g.r(x + 7, yb - 18, 7, 2, tone(env, P.accent));
      g.r(x - 1, yb - 4, 3, 4, cD);
      g.r(x, yb - 24, 1, 6, cD);
      g.r(x + 1, yb - 24, 3, 2, tone(env, P.accent2));
    } else if (kind === 'church') {
      g.r(x - 6, yb - 9, 13, 9, c);
      for (i = 0; i < 5; i++) g.r(x - 6 + i, yb - 9 - i, 13 - i * 2, 1, cD);
      g.r(x + 3, yb - 20, 4, 11, c);
      g.r(x + 4, yb - 23, 2, 3, cD);
      g.r(x + 4, yb - 25, 2, 1, cD);
      g.r(x + 4, yb - 16, 2, 2, env.time === 'day' ? cD : env.litC);
    } else if (kind === 'turbines') {
      for (var tb = 0; tb < 3; tb++) {
        var tx = x - 14 + tb * 14;
        var th = 18 + (tb % 2) * 4;
        g.r(tx, yb - th, 1, th, c);
        var a1 = t * 0.08 + tb * 1.3;
        for (i = 0; i < 3; i++) {
          var a2 = a1 + (i * 2 * PI) / 3;
          g.line(tx, yb - th, tx + rnd(Math.cos(a2) * 6), yb - th + rnd(Math.sin(a2) * 6), c);
        }
      }
    } else {
      g.r(x - 6, yb - 7, 12, 7, c);
      for (i = 0; i < 5; i++) g.r(x - 7 + i, yb - 8 - i, 14 - i * 2, 1, tone(env, P.accent));
      g.r(x - 4, yb - 5, 2, 2, env.time === 'day' ? cD : env.litC);
      g.r(x + 1, yb - 4, 2, 4, cD);
      g.r(x + 3, yb - 12, 2, 4, cD);
      if (env.time !== 'day') puffs(g, x + 4, yb - 14, t, 2, 14, x);
    }
  }

  var FLYERS = ['kite', 'balloon', 'flock', 'plane', 'blimp', 'lanterns', 'none'];

  function flyer(g, env, kind, x, y, t) {
    var P = env.P;
    var i;
    if (kind === 'kite') {
      var kx = x + rnd(Math.sin(t * 0.1) * 3);
      var ky = y + rnd(Math.sin(t * 0.13) * 2);
      for (i = 0; i < 4; i++) {
        g.r(kx - i, ky + i, i * 2 + 1, 1, i < 2 ? P.accent : P.accent2);
        g.r(kx - 3 + i, ky + 4 + i, 7 - i * 2, 1, i < 1 ? P.accent2 : P.accent);
      }
      for (i = 0; i < 14; i++) {
        var tx = kx + rnd(Math.sin(i * 0.6 + t * 0.3) * 1.5);
        g.p(tx, ky + 8 + i, i % 4 === 2 ? P.accent : P.ink);
      }
    } else if (kind === 'balloon') {
      var bx = x + rnd(Math.sin(t * 0.05) * 2);
      var by = y + rnd(Math.sin(t * 0.07) * 2);
      g.ell(bx, by, 4, 5, P.accent2);
      g.r(bx - 1, by - 5, 2, 11, P.paper);
      g.p(bx - 2, by - 3, WHITE);
      g.line(bx - 2, by + 5, bx - 1, by + 8, P.woodD);
      g.line(bx + 2, by + 5, bx + 1, by + 8, P.woodD);
      g.r(bx - 1, by + 8, 3, 2, P.woodD);
    } else if (kind === 'flock') {
      for (i = 0; i < 5; i++) {
        var fx = mod(x + t * 0.45 + Math.abs(i - 2) * -4, W + 30) - 15;
        var fy = y + Math.abs(i - 2) * 2 + i;
        var up = mod(t + i, 4) < 2;
        var bc = env.time === 'night' ? '#c8ccee' : '#4a4060';
        g.p(fx, fy, bc);
        g.p(fx - 1, fy + (up ? -1 : 1), bc);
        g.p(fx + 1, fy + (up ? -1 : 1), bc);
      }
    } else if (kind === 'plane') {
      var px = mod(x + t * 0.9, W + 30) - 15;
      g.r(px, y, 7, 1, '#f4f4f8');
      g.p(px + 7, y, '#c3cbe0');
      g.r(px + 3, y - 1, 2, 1, '#f4f4f8');
      g.r(px + 3, y + 1, 2, 1, '#c3cbe0');
      g.p(px, y - 1, P.accent);
      for (i = 1; i < 18; i++) if (bay(px - i, y) < 0.6 * (1 - i / 18)) g.b(px - i, y, WHITE, 0.6);
      if (env.time !== 'day' && mod(t, 6) < 2) {
        g.em = 1;
        g.p(px + 3, y + 1, '#ff4a4a');
        g.em = 0;
      }
    } else if (kind === 'blimp') {
      var lx = mod(x + t * 0.2, W + 40) - 20;
      g.ell(lx, y, 9, 3, P.metalL);
      g.r(lx - 9, y + 1, 18, 1, P.metal);
      g.r(lx - 3, y + 3, 6, 2, P.accent);
      g.r(lx - 11, y - 3, 2, 6, P.accent);
      g.r(lx - 4, y - 1, 8, 1, P.accent2);
    } else if (kind === 'lanterns') {
      if (env.time === 'day') return;
      g.em = 1;
      for (i = 0; i < 5; i++) {
        var ly = mod(y + 30 - t * (0.2 + i * 0.03) + i * 13, 50) - 4;
        var lxx = x - 20 + i * 11 + rnd(Math.sin(t * 0.1 + i) * 2);
        g.r(lxx, ly, 2, 3, '#ffb04a');
        g.p(lxx, ly, '#ffe08a');
        g.p(lxx + 1, ly + 3, '#ff7a3a');
      }
      g.em = 0;
    }
  }

  // 위로 늘어진 전구 줄
  function stringLights(g, env, y, t, amp, step, cols) {
    var P = env.P;
    cols = cols || ['#ffd36e', '#ff8a9a', '#8ad8ff'];
    for (var x = 0; x < W; x++) {
      var sag = rnd(Math.abs(Math.sin((x / 48) * PI)) * amp);
      g.p(x, y + sag, P.ink);
      if (x % step === step >> 1) {
        g.em = 1;
        var lc = cols[fl(x / step) % cols.length];
        g.p(x, y + 1 + sag, mod(fl(x / step) + fl(t / 4), 5) === 0 ? mix(lc, WHITE, 0.6) : lc);
        g.em = 0;
      }
    }
  }

  function awning(g, env, x, y, w, c1, c2, rows) {
    rows = rows || 4;
    for (var yy = 0; yy < rows; yy++) {
      for (var xx = 0; xx < w; xx++) g.p(x + xx, y + yy, (fl(xx / 3) & 1) ? c1 : c2);
    }
    for (var sx = 0; sx < w; sx += 3) {
      var c = (fl(sx / 3) & 1) ? c1 : c2;
      g.r(x + sx, y + rows, 3, 1, c);
      g.p(x + sx + 1, y + rows + 1, c);
    }
    g.br(x, y + rows + 1, w, 1, env.P.ink, 0.2);
  }

  function grass(g, env, y0, y1, salt) {
    var P = env.P;
    var snow = snowy(env);
    var gc = snow ? hx('#eef3fa') : P.leaf;
    var gD = snow ? hx('#ccd8ea') : P.leafD;
    for (var y = y0; y < y1; y++) {
      for (var x = 0; x < W; x++) g.p(x, y, (x * 7 + y * 13 + (salt || 0)) % 17 === 0 ? gD : gc);
    }
  }

  function flowerDots(g, env, x0, y0, w, h, n, salt) {
    var P = env.P;
    var cols = snowy(env) ? ['#ffffff'] : [P.accent, '#ffffff', '#ffd25a', P.accent2];
    for (var k = 0; k < n; k++) {
      var fx = x0 + fl(hash(env.seed + salt, k) * w);
      var fy = y0 + fl(hash(env.seed + salt, k + 99) * h);
      g.p(fx, fy, cols[k % cols.length]);
    }
  }

  function water(g, env, y0, y1, t, deepC, shallowC) {
    var deep = deepC || mix(env.skyMid, '#1a4a8a', 0.6);
    var sh = shallowC || hx('#4ab8c8');
    for (var y = y0; y < y1; y++) {
      var f = clamp((y - y0) / Math.max(1, y1 - y0 - 1), 0, 1);
      var c = mix(deep, sh, f * 0.6);
      g.r(0, y, W, 1, c);
      if (y & 1) {
        for (var k = 0; k < 3; k++) {
          var dl = 2 + fl(hash(y, k) * 4);
          var dx = mod(hash(k, y) * W + t * (0.2 + f * 0.5) * (k & 1 ? 1 : -1), W);
          g.r(dx, y, dl, 1, mix(c, WHITE, 0.3));
        }
      }
    }
  }

  // ---- new scenes (T158, D-129)

  function banker(g, env, x, yb) {
    var P = env.P;
    var sc = mix('#2f7a52', P.leafD, 0.3);
    g.r(x - 3, yb - 1, 7, 1, '#c8a040');
    g.r(x - 2, yb - 2, 5, 1, '#e0bc58');
    g.r(x, yb - 7, 1, 5, '#c8a040');
    g.r(x - 5, yb - 9, 11, 2, sc);
    g.r(x - 4, yb - 10, 9, 1, mix(sc, WHITE, 0.3));
    g.em = 1;
    g.r(x - 4, yb - 7, 9, 1, env.time === 'day' ? '#f6efd8' : '#fff1c2');
    g.em = 0;
    g.light(x, yb - 6, 36, env.T.lamp, env.lampK, { top: yb - 8 });
  }

  SC.library = {
    still: 6,
    setup: function (env, R) {
      var wpos = R.pick([8, 78, 148]);
      return {
        wx: wpos,
        shape: R.pick(['arch', 'arch', 'rect', 'round']),
        view: R.pick(['trees', 'hills', 'city']),
        cover: R.pick(['accent', 'accent2', 'leafD', 'woodD']),
        ladder: R.ch(0.6),
        lx: R.i(0, 1),
        lampK: R.pick(['banker', 'banker', 'plain', 'candle']),
        itemA: R.pick(['stack', 'globe', 'cup', 'trinket', 'none']),
        itemB: R.pick(['stack', 'cup', 'trinket', 'quill', 'none']),
        tr: hp(R, env, 'trinket', TRINKETS),
        cup: hp(R, env, 'cup', CUPS),
        top: R.pick(['clock', 'plaque', 'none', 'clock']),
        critter: hp(R, env, 'critter', ['kitty', 'none', 'mouse', 'bird', 'none']),
        rug: R.pick(['rug', 'accent2', 'accent', 'leafD']),
        pend: R.pick(['pendant', 'none', 'pendant']),
        floor: hp(R, env, 'floor', ['bigplant', 'books', 'basket', 'none', 'stool']),
        spot: R.pick(['table', 'floor'])
      };
    },
    draw: function (g, env, L, t) {
      var P = env.P;
      room(g, env, { wallStyle: 0 }, 64);
      var wx = L.wx;
      var ww = 36;
      // shelves fill the wall except the window column
      var segs = [];
      if (wx > 10) segs.push([2, wx - 6]);
      if (wx + ww + 6 < W - 10) segs.push([wx + ww + 6, W - 2]);
      for (var s = 0; s < segs.length; s++) {
        var a = segs[s][0];
        var b = segs[s][1];
        var n = Math.max(1, rnd((b - a) / 44));
        var sw = (b - a) / n;
        for (var k = 0; k < n; k++) bookshelf(g, env, rnd(a + k * sw) + 1, 3, rnd(sw) - 2, 62, t);
      }
      win(g, env, wx, 10, ww, 40, t, { shape: L.shape, panes: 4, view: L.view, sill: true });
      if (L.top === 'clock') clock(g, env, wx + 18, 4, 4, t);
      else if (L.top === 'plaque') {
        g.r(wx + 8, 2, 20, 6, P.woodD);
        g.r(wx + 9, 3, 18, 4, '#d8b050');
        textRow(g, wx + 11, 5, 14, env.seed, 1, P.woodDD);
      }
      if (L.ladder) {
        var lx = L.lx === 0 ? (wx > 60 ? 30 : 120) : (wx > 120 ? 50 : 168);
        g.line(lx, 2, lx + 8, 64, P.woodDD);
        g.line(lx + 7, 2, lx + 15, 64, P.woodDD);
        for (var ry = 6; ry < 62; ry += 6) g.r(lx + rnd(ry / 7.7) + 1, ry, 7, 1, P.woodL);
        g.r(lx - 2, 2, 20, 1, P.metalD);
      }
      g.ell(96, 72, 78, 6, P[L.rug]);
      g.ell(96, 72, 74, 5, mix(P[L.rug], WHITE, 0.2));
      for (var rx = 24; rx < 170; rx += 6) g.p(rx, 72, mix(P[L.rug], P.ink, 0.25));
      if (L.pend === 'pendant') {
        [52, 140].forEach(function (px) {
          g.r(px, 0, 1, 8, P.ink);
          g.ell(px, 10, 4, 2, P.accentD);
          g.em = 1;
          g.r(px - 2, 12, 5, 1, env.time === 'day' ? '#f6efd8' : '#fff1c2');
          g.em = 0;
        });
      }
      // long reading table
      g.br(26, 49, 140, 1, P.ink, 0.15);
      g.r(26, 50, 140, 2, P.woodL);
      g.r(26, 52, 140, 2, P.woodD);
      g.r(30, 54, 132, 3, P.woodDD);
      g.r(30, 54, 3, 24, P.woodDD);
      g.r(159, 54, 3, 24, P.woodDD);
      g.r(31, 57, 1, 21, P.woodD);
      g.r(160, 57, 1, 21, P.woodD);
      openBook(g, env, L, 96, 39, t);
      if (L.lampK === 'banker') {
        banker(g, env, 62, 50);
        banker(g, env, 132, 50);
      } else if (L.lampK === 'plain') {
        lamp(g, env, 64, 50, { color: P.accent2, r: 40, h: 8 });
      } else {
        trinket(g, env, 'candle', 60, 50, t);
        trinket(g, env, 'candle', 128, 50, t);
      }
      function item(kind, x) {
        if (kind === 'stack') bookStack(g, env, x - 4, 50, 3 + (x % 2), x);
        else if (kind === 'globe') {
          g.r(x - 1, 48, 6, 2, P.woodD);
          g.r(x + 1, 46, 2, 2, P.metalD);
          g.ell(x + 2, 41, 4, 4, '#5ea3d6');
          g.p(x, 40, '#7fc07a');
          g.p(x + 1, 39, '#7fc07a');
          g.p(x + 3, 42, '#7fc07a');
          g.p(x + 4, 41, '#7fc07a');
          g.line(x - 2, 44, x + 5, 37, P.metalD);
        } else if (kind === 'cup') cupOf(g, env, L.cup, x, 50, P.paper, t);
        else if (kind === 'trinket') trinket(g, env, L.tr, x, 50, t);
        else if (kind === 'quill') {
          g.r(x, 47, 5, 3, '#2a2a33');
          g.line(x + 2, 46, x + 6, 38, P.paper);
          g.line(x + 3, 46, x + 7, 39, P.paperD);
        }
      }
      item(L.itemA, 38);
      item(L.itemB, 146);
      if (L.floor !== 'none') floorThing(g, env, L.floor, wx > 120 ? 6 : 172, 77, t);
      if (L.critter !== 'none') critter(g, env, L.critter, 80, 77, t, false);
      if (L.spot === 'table') mascot(g, env, 108, 50, t, { flip: true });
      else mascot(g, env, 40, 76, t);
    }
  };

  function planter(g, env, kind, x, yb, w, t) {
    var P = env.P;
    var i;
    g.r(x, yb - 8, w, 8, P.woodD);
    g.r(x, yb - 8, w, 1, P.woodL);
    for (i = 3; i < w; i += 6) g.r(x + i, yb - 7, 1, 7, P.woodDD);
    g.r(x + 1, yb - 9, w - 2, 1, '#6a4a32');
    var n = fl(w / 6);
    for (i = 0; i < n; i++) {
      var px = x + 3 + i * 6;
      if (kind === 'tomato') {
        g.r(px, yb - 20, 1, 11, P.woodL);
        g.ell(px, yb - 14, 2, 4, P.leafD);
        g.p(px - 1, yb - 12, '#e8483a');
        g.p(px + 1, yb - 15, '#e8483a');
        g.p(px, yb - 17, '#f6803a');
      } else if (kind === 'lettuce') {
        g.ell(px, yb - 11, 2, 2, P.leaf);
        g.p(px - 1, yb - 12, P.leafL);
        g.p(px + 1, yb - 10, P.leafD);
      } else if (kind === 'flowers') {
        g.r(px, yb - 14, 1, 5, P.leafD);
        var c = [P.accent, '#ffd25a', P.paper, P.accent2][(i + env.seed) % 4];
        g.p(px, yb - 15, c);
        g.p(px - 1, yb - 14, c);
        g.p(px + 1, yb - 14, c);
        g.p(px, yb - 13, c);
        g.p(px, yb - 14, '#ffe27a');
      } else if (kind === 'herbs') {
        g.ell(px, yb - 11, 2, 2, P.leafD);
        g.p(px, yb - 13, P.leafL);
        g.p(px - 2, yb - 11, P.leaf);
      } else {
        plant2(g, env, px, yb - 2, 'sunflower', t, i);
      }
    }
  }

  SC.rooftop = {
    still: 4,
    outdoor: true,
    setup: function (env, R) {
      return {
        crops: [R.pick(['tomato', 'lettuce', 'flowers', 'herbs', 'sunflower']), R.pick(['tomato', 'lettuce', 'flowers', 'herbs', 'sunflower'])],
        furn: R.pick(['parasol', 'deckchair', 'hammock', 'bench', 'swing']),
        mid: R.pick(['pots', 'grill', 'telescope', 'bike', 'none']),
        back: R.pick(['tank', 'ac', 'antenna', 'none']),
        lights: R.ch(0.65),
        laundry: R.ch(0.4),
        critter: hp(R, env, 'critter', ['bird', 'kitty', 'none', 'bird']),
        fly: hp(R, env, 'fly', FLYERS),
        umb: R.pick(['accent', 'accent2', 'leaf']),
        pots: [hp(R, env, 'plant', PLANTS2), R.pick(['bush', 'flower', 'cactus']), hp(R, env, 'plant', PLANTS2)]
      };
    },
    draw: function (g, env, L, t) {
      var P = env.P;
      var x;
      skyTop(g, env, 58, t, 30);
      flyer(g, env, L.fly, 60, 14, t);
      distant(g, env, 0, 18, W, 40, bk(env, 'city'), t);
      // neighbour building tops behind the rail
      var nb = mix(env.skyLow, '#5d6788', 0.5);
      g.r(0, 34, 26, 24, nb);
      g.r(166, 30, 26, 28, nb);
      for (x = 4; x < 24; x += 5) g.r(x, 38, 3, 3, env.time === 'day' ? mix(nb, WHITE, 0.2) : env.litC);
      for (x = 170; x < 190; x += 5) g.r(x, 34, 3, 3, env.time === 'day' ? mix(nb, WHITE, 0.2) : env.litC);
      if (L.back === 'tank') {
        g.r(140, 28, 14, 12, P.woodD);
        g.r(140, 28, 14, 1, P.woodL);
        g.r(139, 27, 16, 1, P.metalD);
        g.r(142, 40, 1, 14, P.metalD);
        g.r(151, 40, 1, 14, P.metalD);
      } else if (L.back === 'ac') {
        g.r(136, 44, 20, 12, P.metal);
        g.r(136, 44, 20, 1, P.metalL);
        g.ell(146, 50, 4, 4, P.metalD);
        var fa = mod(t, 2);
        g.line(146 - 3 + fa, 50 - 3, 146 + 3 - fa, 50 + 3, P.metal);
      } else if (L.back === 'antenna') {
        g.r(150, 22, 1, 34, P.metalD);
        g.r(146, 26, 9, 1, P.metalD);
        g.r(147, 30, 7, 1, P.metalD);
        g.em = 1;
        if (mod(t, 10) < 5) g.p(150, 21, '#ff4a4a');
        g.em = 0;
      }
      // deck and railing
      g.r(0, 58, W, 22, P.woodD);
      for (var y = 58; y < H; y += 3) g.r(0, y, W, 1, mix(P.woodD, P.ink, 0.25));
      for (y = 59; y < H; y += 3) {
        for (x = mod(y * 13, 29); x < W; x += 29) g.r(x, y, 1, 2, mix(P.woodD, P.ink, 0.25));
      }
      g.r(0, 58, W, 1, P.woodL);
      g.r(0, 46, W, 2, P.metal);
      g.r(0, 47, W, 1, P.metalD);
      for (x = 2; x < W; x += 10) g.r(x, 48, 1, 10, P.metalD);
      if (L.laundry) {
        for (x = 30; x < 90; x++) g.p(x, 26 + rnd(Math.sin(((x - 30) / 60) * PI) * 3), P.ink);
        g.r(29, 24, 1, 34, P.woodDD);
        g.r(90, 24, 1, 34, P.woodDD);
        var cl = [P.accent, P.paper, P.accent2, '#ffd25a'];
        for (var c = 0; c < 4; c++) {
          var cx = 36 + c * 13;
          var cy = 27 + rnd(Math.sin(((cx - 30) / 60) * PI) * 3);
          var flap = mod(t + c * 3, 10) < 5 ? 0 : 1;
          g.r(cx, cy, 7, 8 - (c % 2) * 2, cl[c]);
          g.r(cx + 7, cy + flap, 1, 6 - (c % 2) * 2, mix(cl[c], P.ink, 0.2));
          g.r(cx, cy, 7, 1, mix(cl[c], WHITE, 0.3));
        }
      }
      if (L.lights) {
        for (x = 0; x < W; x++) {
          var sag = rnd(Math.abs(Math.sin((x / 64) * PI)) * 6);
          g.p(x, 18 + sag, P.ink);
          if (x % 9 === 4) {
            g.em = 1;
            g.p(x, 19 + sag, mod(fl(x / 9) + fl(t / 4), 5) === 0 ? '#fff6c0' : '#ffd36e');
            g.em = 0;
          }
        }
      }
      planter(g, env, L.crops[0], 6, 70, 32, t);
      planter(g, env, L.crops[1], 150, 72, 36, t);
      var fx = 96;
      if (L.furn === 'parasol') {
        var uc = P[L.umb];
        g.r(fx + 10, 36, 1, 32, P.metalD);
        for (var r = 0; r < 6; r++) {
          var uw = rnd(18 * Math.sqrt(1 - Math.pow((5 - r) / 6, 2)));
          for (var ux = -uw; ux <= uw; ux++) g.p(fx + 10 + ux, 32 + r, fl((ux + 18) / 6) % 2 ? uc : P.paper);
        }
        g.r(fx - 2, 62, 24, 2, P.paper);
        g.r(fx + 9, 64, 3, 8, P.metalD);
        cupOf(g, env, 'iced', fx + 2, 62, P.paper, t);
      } else if (L.furn === 'deckchair') {
        g.line(fx, 72, fx + 14, 58, P.woodL);
        g.line(fx + 16, 72, fx + 6, 62, P.woodL);
        for (var d = 0; d < 12; d++) g.r(fx + 1 + d, 70 - d, 4, 1, (d >> 1) & 1 ? P.accent : P.paper);
      } else if (L.furn === 'hammock') {
        g.r(fx - 10, 40, 2, 32, P.woodDD);
        g.r(fx + 34, 40, 2, 32, P.woodDD);
        var sw2 = rnd(Math.sin(t * 0.15) * 1.5);
        for (x = 0; x < 42; x++) {
          var hy = 46 + rnd(Math.sin((x / 41) * PI) * 12) + (x > 6 && x < 36 ? sw2 : 0);
          g.p(fx - 8 + x, hy, P.accent2);
          g.p(fx - 8 + x, hy + 1, (x >> 1) & 1 ? P.accent2D : P.paper);
        }
      } else if (L.furn === 'bench') {
        streetThing(g, env, 'bench', fx, 70, t);
      } else {
        g.r(fx - 2, 20, 30, 2, P.woodDD);
        g.r(fx - 2, 22, 2, 50, P.woodDD);
        g.r(fx + 26, 22, 2, 50, P.woodDD);
        var sw3 = rnd(Math.sin(t * 0.2) * 2);
        g.line(fx + 6, 22, fx + 6 + sw3, 56, P.ink);
        g.line(fx + 18, 22, fx + 18 + sw3, 56, P.ink);
        g.r(fx + 4 + sw3, 56, 17, 2, P.woodL);
      }
      if (L.mid === 'pots') {
        plant(g, env, 58, 72, L.pots[0], t, 1);
        plant(g, env, 68, 74, L.pots[1], t, 2);
        plant(g, env, 78, 72, L.pots[2], t, 3);
      } else if (L.mid === 'grill') {
        g.ell(66, 62, 7, 4, '#2a2a33');
        g.r(59, 59, 15, 1, P.metalD);
        g.line(61, 65, 59, 73, '#2a2a33');
        g.line(71, 65, 73, 73, '#2a2a33');
        puffs(g, 66, 56, t, 3, 16, 5);
      } else if (L.mid === 'telescope') {
        g.line(66, 58, 60, 73, P.ink);
        g.line(66, 58, 72, 73, P.ink);
        g.line(60, 56, 74, 48, P.metalL);
        g.line(60, 57, 74, 49, P.metal);
        g.line(61, 58, 74, 50, P.metalD);
      } else if (L.mid === 'bike') {
        streetThing(g, env, 'bike', 56, 73, t);
      }
      if (L.critter !== 'none') critter(g, env, L.critter, L.critter === 'bird' ? 120 : 40, L.critter === 'bird' ? 46 : 76, t, L.critter === 'bird');
      mascot(g, env, 130, 72, t, { flip: true });
      weather(g, env, 0, 0, W, H, t);
    }
  };

  function campfire(g, env, x, yb, t) {
    var P = env.P;
    for (var s = 0; s < 7; s++) {
      var sx = x - 9 + s * 3;
      g.r(sx, yb - 2, 3, 2, s % 2 ? '#8a8a96' : '#a8a8b4');
    }
    g.line(x - 7, yb - 2, x + 6, yb - 5, P.woodDD);
    g.line(x - 6, yb - 5, x + 7, yb - 2, P.woodD);
    g.em = 1;
    var fl0 = mod(t, 3);
    for (var c = -4; c <= 4; c++) {
      var fh = 4 + rnd((1 - Math.abs(c) / 5) * 6) + ((c + fl0) % 3 === 0 ? 2 : 0);
      for (var k = 0; k < fh; k++) {
        var col = k < fh * 0.35 ? '#ffe28a' : (k < fh * 0.7 ? '#ffb03a' : '#f0603a');
        if (Math.abs(c) > 2 && k < 2) col = '#ffb03a';
        g.p(x + c + (k > fh - 3 ? ((k + fl0) % 2 ? 1 : 0) : 0), yb - 4 - k, col);
      }
    }
    for (var i = 0; i < 5; i++) {
      var a = mod(t * 1.3 + i * 7, 24);
      g.p(x + rnd(Math.sin(a * 0.4 + i) * 4), yb - 12 - a, a < 12 ? '#ffd34a' : '#f0603a');
    }
    g.em = 0;
    halo(g, x, yb - 7, 3, 7, '#ffb060', env.time === 'day' ? 0.25 : 0.6);
    g.light(x, yb - 6, env.time === 'day' ? 30 : 64, [1, 0.62, 0.32], (env.time === 'day' ? 0.3 : 1.1) * (fl0 === 1 ? 0.9 : 1), { n: 6 });
  }

  SC.campsite = {
    still: 4,
    outdoor: true,
    setup: function (env, R) {
      var side = R.ch(0.5);
      return {
        tent: R.pick(['a', 'dome', 'tipi']),
        tc: R.pick(['accent', 'accent2', 'leaf', 'woodL']),
        side: side,
        seat: R.pick(['log', 'chair', 'log']),
        extra: R.pick(['guitar', 'backpack', 'kettle', 'none', 'canoe']),
        lantern: R.ch(0.6),
        lake: R.ch(0.4),
        critter: hp(R, env, 'critter', ['squirrel', 'bunny', 'hedgehog', 'deer', 'owl', 'none']),
        hammock: R.ch(0.35)
      };
    },
    draw: function (g, env, L, t) {
      var P = env.P;
      var x;
      var y;
      skyTop(g, env, 54, t, 30);
      distant(g, env, 0, 20, W, 30, bk(env, 'hills'), t);
      var gnd = L.lake ? 58 : 50;
      if (L.lake) {
        water(g, env, 50, 58, t, mix(env.skyMid, '#1a4a6a', 0.5), mix(env.skyLow, '#4a98b8', 0.5));
      }
      for (var p = 0; p < 9; p++) pine(g, env, 4 + p * 23 + (p % 3) * 5, gnd + 1 + (p % 2), 14 + (p % 3) * 4);
      grass(g, env, gnd, H, 3);
      // trampled earth around the fire
      for (y = 64; y < H; y++) {
        var hw = rnd(30 * Math.sqrt(Math.max(0, 1 - Math.pow((y - 72) / 9, 2))));
        for (x = 96 - hw; x < 96 + hw; x++) if (bay(x, y) < 0.75) g.p(x, y, mix(P.floor, P.woodD, 0.3));
      }
      var tx = L.side ? 18 : 132;
      var tc = P[L.tc];
      var tD = mix(tc, P.ink, 0.3);
      var tL = mix(tc, WHITE, 0.3);
      var inner = env.time === 'day' ? mix(tD, P.ink, 0.3) : hx('#ffcf7a');
      if (L.tent === 'a') {
        for (y = 0; y < 22; y++) {
          var hw2 = rnd(y * 0.95);
          g.r(tx + 21 - hw2, 48 + y, hw2 * 2 + 1, 1, y < 2 ? tL : tc);
          g.p(tx + 21 + hw2, 48 + y, tD);
          if (y > 8) g.r(tx + 21 - rnd((y - 8) * 0.45), 48 + y, rnd((y - 8) * 0.9) + 1, 1, inner);
        }
        g.r(tx, 70, 43, 1, tD);
        g.line(tx + 21, 46, tx + 21, 48, P.woodDD);
      } else if (L.tent === 'dome') {
        for (y = 0; y < 18; y++) {
          var hw3 = rnd(21 * Math.sqrt(1 - Math.pow((17 - y) / 18, 2)));
          g.r(tx + 21 - hw3, 52 + y, hw3 * 2 + 1, 1, y < 3 ? tL : tc);
          g.p(tx + 21 + hw3, 52 + y, tD);
        }
        g.line(tx + 4, 70, tx + 21, 52, tD);
        g.line(tx + 38, 70, tx + 21, 52, tD);
        g.ell(tx + 21, 66, 5, 5, inner);
        g.r(tx + 15, 70, 13, 1, tD);
      } else {
        for (y = 0; y < 28; y++) {
          var hw4 = rnd(y * 0.6);
          g.r(tx + 21 - hw4, 42 + y, hw4 * 2 + 1, 1, (fl(y / 5) % 2) ? tc : tL);
          if (y > 18) g.r(tx + 21 - rnd((y - 18) * 0.4), 42 + y, rnd((y - 18) * 0.8) + 1, 1, inner);
        }
        g.line(tx + 18, 38, tx + 22, 44, P.woodDD);
        g.line(tx + 24, 38, tx + 20, 44, P.woodDD);
      }
      if (env.time !== 'day') g.light(tx + 21, 64, 20, [1, 0.8, 0.5], 1.4, { n: 3 });
      if (L.hammock) {
        var hx0 = L.side ? 140 : 24;
        var sw = rnd(Math.sin(t * 0.15) * 1.2);
        for (x = 0; x < 30; x++) {
          var hy = 50 + rnd(Math.sin((x / 29) * PI) * 7) + (x > 4 && x < 26 ? sw : 0);
          g.p(hx0 + x, hy, P.accent);
          g.p(hx0 + x, hy + 1, (x >> 1) & 1 ? P.accentD : P.paper);
        }
      }
      campfire(g, env, 96, 74, t);
      if (L.seat === 'log') {
        g.r(64, 70, 18, 5, P.woodD);
        g.r(64, 70, 18, 1, P.woodL);
        g.ell(64, 72, 1, 2, '#e8c890');
        g.r(112, 71, 16, 4, P.woodD);
        g.r(112, 71, 16, 1, P.woodL);
      } else {
        [66, 114].forEach(function (cx) {
          g.r(cx, 66, 12, 4, P.accent2);
          g.r(cx, 62, 2, 6, P.accent2D);
          g.line(cx + 1, 70, cx + 10, 76, P.metalD);
          g.line(cx + 10, 70, cx + 1, 76, P.metalD);
        });
      }
      var ex = L.side ? 164 : 18;
      if (L.extra === 'guitar') floorThing(g, env, 'guitar', ex, 76, t);
      else if (L.extra === 'backpack') floorThing(g, env, 'bag', ex, 76, t);
      else if (L.extra === 'kettle') {
        cupOf(g, env, 'teapot', ex + 2, 76, P.metal, t);
        cupOf(g, env, 'mug', ex + 14, 76, P.accent, t);
      } else if (L.extra === 'canoe') {
        g.r(ex - 4, 72, 26, 3, P.accent);
        g.r(ex - 6, 71, 30, 1, P.accentL);
        g.r(ex - 2, 75, 22, 1, P.accentD);
      }
      if (L.lantern) {
        var lx = L.side ? 70 : 118;
        g.r(lx, 44, 1, 20, P.woodDD);
        g.r(lx, 44, 6, 1, P.woodDD);
        g.r(lx + 4, 45, 4, 5, P.metalD);
        g.em = 1;
        g.r(lx + 5, 46, 2, 3, env.time === 'day' ? '#e8e8dc' : '#fff1a0');
        g.em = 0;
        if (env.time !== 'day') g.light(lx + 6, 48, 26, [1, 0.85, 0.55], 0.6, { n: 4 });
      }
      if (L.critter === 'deer') {
        var dx = L.side ? 150 : 40;
        var dc = hx('#b8784a');
        g.r(dx, 54, 9, 4, dc);
        g.r(dx + 8, 50, 3, 5, dc);
        g.r(dx + 9, 49, 3, 2, dc);
        g.p(dx + 12, 50, P.ink);
        g.line(dx + 9, 48, dx + 8, 45, P.woodDD);
        g.line(dx + 11, 48, dx + 12, 45, P.woodDD);
        g.r(dx + 1, 58, 1, 4, mix(dc, P.ink, 0.3));
        g.r(dx + 7, 58, 1, 4, mix(dc, P.ink, 0.3));
        g.p(dx - 1, 54, WHITE);
      } else if (L.critter === 'owl') {
        var ox = L.side ? 166 : 12;
        g.r(ox, 40, 6, 7, '#8a6a4a');
        g.p(ox + 1, 42, env.time === 'night' ? '#ffe08a' : P.ink);
        g.p(ox + 4, 42, env.time === 'night' ? '#ffe08a' : P.ink);
        g.p(ox + 2, 43, '#f0a23a');
        g.p(ox, 39, '#8a6a4a');
        g.p(ox + 5, 39, '#8a6a4a');
        g.r(ox - 3, 47, 12, 1, P.woodDD);
      } else if (L.critter !== 'none') {
        critter(g, env, L.critter, L.side ? 160 : 30, 66, t, !L.side);
      }
      mascot(g, env, L.seat === 'log' ? 64 : 66, L.seat === 'log' ? 70 : 66, t, { sleep: false });
      weather(g, env, 0, 0, W, H, t);
    }
  };

  SC.mountain = {
    still: 3,
    outdoor: true,
    setup: function (env, R) {
      var peaks = [];
      var n = R.i(2, 4);
      for (var i = 0; i < n; i++) peaks.push({ x: R.i(10, 182), h: R.i(10, 26), s: R.r(0.6, 1.1) });
      return {
        peaks: peaks,
        summit: R.pick(['flag', 'cairn', 'hut', 'none']),
        cable: R.ch(0.4),
        fore: R.pick(['sign', 'bench', 'bag', 'tent', 'none']),
        cloudSea: R.ch(0.45),
        critter: hp(R, env, 'critter', ['goat', 'eagle', 'none', 'eagle']),
        trees: R.ch(0.7),
        side: R.ch(0.5),
        fly: R.pick(['none', 'balloon', 'flock', 'none'])
      };
    },
    draw: function (g, env, L, t) {
      var P = env.P;
      var x;
      var y;
      skyTop(g, env, 60, t, 30);
      flyer(g, env, L.fly, 150, 12, t);
      if (env.bd) distant(g, env, 0, 20, W, 40, env.bd, t);
      var far = mix(env.skyLow, '#7a8ab0', 0.4);
      var farD = mix(far, '#2e3656', 0.25);
      var snowC = hx('#f4f6fb');
      var snowD = hx('#c9d3ea');
      var top = [];
      for (x = 0; x < W; x++) {
        var my = 60;
        var pkx = 0;
        for (var k = 0; k < L.peaks.length; k++) {
          var pk = L.peaks[k];
          var hk = pk.h + Math.abs(x - pk.x) * pk.s + Math.sin(x * 0.4 + k) * 0.8;
          if (hk < my) {
            my = hk;
            pkx = pk.x;
          }
        }
        top.push([my, pkx]);
        for (y = fl(my); y < H; y++) {
          var sn = y < my + 4 + ((x * 5) % 3);
          var sh = x > pkx;
          g.p(x, y, sn ? (sh ? snowD : snowC) : (sh ? farD : far));
        }
      }
      if (L.summit !== 'none') {
        var best = 0;
        for (x = 1; x < W; x++) if (top[x][0] < top[best][0]) best = x;
        var sy = fl(top[best][0]);
        if (L.summit === 'flag') {
          g.r(best, sy - 7, 1, 7, P.ink);
          var wv = mod(t, 4) < 2 ? 0 : 1;
          g.r(best + 1, sy - 7, 4, 2, P.accent);
          g.p(best + 5, sy - 7 + wv, P.accent);
        } else if (L.summit === 'cairn') {
          g.r(best - 2, sy - 2, 5, 2, '#8a8a96');
          g.r(best - 1, sy - 4, 3, 2, '#a8a8b4');
          g.r(best, sy - 5, 2, 1, '#8a8a96');
        } else {
          g.r(best - 3, sy - 4, 7, 4, P.woodD);
          for (var hr = 0; hr < 3; hr++) g.r(best - 4 + hr, sy - 5 - hr, 9 - hr * 2, 1, P.accent);
          g.p(best, sy - 3, env.time === 'day' ? P.woodDD : env.litC);
        }
      }
      if (L.cloudSea) {
        for (var c = 0; c < 10; c++) {
          var cx = mod(c * 22 + t * 0.15, W + 30) - 15;
          cloud(g, env, rnd(cx), 50 + (c % 3), 1.1 + (c % 2) * 0.2, env.clouds[c % env.clouds.length].puffs, env.cloudL, env.cloudD);
        }
      }
      // near slope with a zig-zag trail
      var near = P.leaf;
      var nearD = P.leafD;
      if (snowy(env)) {
        near = hx('#eef3fa');
        nearD = hx('#ccd8ea');
      }
      for (x = 0; x < W; x++) {
        var ny = L.side ? 44 + x * 0.12 + Math.sin(x * 0.07) * 3 : 67 - x * 0.12 + Math.sin(x * 0.07) * 3;
        for (y = fl(ny); y < H; y++) g.p(x, y, (x * 7 + y * 11) % 19 === 0 ? nearD : near);
      }
      // switchback trail climbing the slope
      var path = mix(P.paper, P.woodL, 0.5);
      var pathD = mix(path, P.ink, 0.2);
      var pts = [];
      for (var seg = 0; seg < 7; seg++) {
        var px = seg % 2 ? 66 + seg * 5 : 128 - seg * 5;
        var py = 80 - seg * 5;
        var base = L.side ? 44 + px * 0.12 : 67 - px * 0.12;
        if (py < base + 2) break;
        pts.push([px, py]);
      }
      for (var s = 0; s + 1 < pts.length; s++) {
        g.line(pts[s][0], pts[s][1], pts[s + 1][0], pts[s + 1][1], path);
        if (s < 3) g.line(pts[s][0], pts[s][1] + 1, pts[s + 1][0], pts[s + 1][1] + 1, pathD);
      }
      if (L.trees) {
        for (var tr = 0; tr < 6; tr++) {
          var tx = 12 + tr * 33 + (tr % 2) * 7;
          var tb = (L.side ? 44 + tx * 0.12 : 67 - tx * 0.12) + 2;
          pine(g, env, tx, tb, 9 + (tr % 3) * 3);
        }
      }
      if (L.cable) {
        g.line(0, 20, W, 50, P.ink);
        var cp = mod(t * 0.6, W + 20) - 10;
        var cy2 = 20 + cp * (30 / W);
        g.r(cp, cy2, 1, 4, P.ink);
        g.r(cp - 4, cy2 + 4, 9, 6, P.accent);
        g.r(cp - 3, cy2 + 5, 7, 2, env.time === 'night' ? '#ffd36e' : '#bfe0ee');
        g.r(cp - 4, cy2 + 9, 9, 1, P.accentD);
      }
      var fx = L.side ? 150 : 20;
      var fb = L.side ? 44 + (fx + 8) * 0.12 + 18 : 67 - (fx + 8) * 0.12 + 10;
      fb = Math.min(fb, 77);
      if (L.fore === 'sign') streetThing(g, env, 'sign', fx, fb, t);
      else if (L.fore === 'bench') streetThing(g, env, 'bench', fx, fb, t);
      else if (L.fore === 'bag') floorThing(g, env, 'bag', fx, fb, t);
      else if (L.fore === 'tent') {
        for (y = 0; y < 10; y++) g.r(fx + 9 - y, fb - 10 + y, y * 2 + 1, 1, y < 1 ? P.accentL : P.accent);
        g.r(fx + 8, fb - 5, 3, 5, mix(P.accent, P.ink, 0.4));
      }
      if (L.critter === 'goat') {
        var gx = L.side ? 40 : 140;
        var gy = (L.side ? 44 + gx * 0.12 : 67 - gx * 0.12) + 1;
        g.r(gx, gy - 5, 7, 3, '#f4f0ea');
        g.r(gx + 6, gy - 8, 2, 4, '#f4f0ea');
        g.p(gx + 8, gy - 7, '#f4f0ea');
        g.p(gx + 7, gy - 9, '#8a8a96');
        g.p(gx + 5, gy - 9, '#8a8a96');
        g.r(gx + 1, gy - 2, 1, 2, '#8a8a96');
        g.r(gx + 5, gy - 2, 1, 2, '#8a8a96');
      } else if (L.critter === 'eagle') {
        var ex = 96 + rnd(Math.sin(t * 0.04) * 60);
        var ey = 16 + rnd(Math.sin(t * 0.08) * 4);
        var fl2 = mod(t, 8) < 4;
        g.r(ex - 1, ey, 3, 1, '#4a3a36');
        g.r(ex - 5, ey - (fl2 ? 1 : 0), 4, 1, '#4a3a36');
        g.r(ex + 2, ey - (fl2 ? 1 : 0), 4, 1, '#4a3a36');
        g.p(ex + 2, ey - 1, '#f4f0ea');
      }
      var mx = L.side ? 110 : 70;
      var mb = L.side ? 44 + (mx + 8) * 0.12 + 6 : 67 - (mx + 8) * 0.12 + 6;
      mascot(g, env, mx, fl(mb), t, { sleep: false, flip: !L.side });
      weather(g, env, 0, 0, W, H, t);
    }
  };

  // 기차 창밖. 화면 x에 시간만큼 더한 세계 좌표로 그려 창 여러 개가 한 풍경으로 이어진다
  function trainView(g, env, y0, y1, t, kind) {
    var P = env.P;
    var h = y1 - y0;
    var x;
    var y;
    skyBg(g, env, 0, y0, W, h);
    stars(g, env, 0, y0, W, h, t);
    celestial(g, env, 0, y0, W, h + 8);
    clouds(g, env, 0, y0, W, fl(h * 0.4), t * 2);
    var still = null;
    if (env.bd) {
      var tk = { hills: 'hills', sea: 'sea', rice: 'fields', tulip: 'fields', city: 'city', towers: 'city', street: 'city', snow: 'snow', mount: 'snow', aurora: 'snow', trees: 'forest', autumn: 'forest', jungle: 'forest', sakura: 'forest' }[env.bd];
      if (tk) kind = tk;
      else {
        still = env.bd;
        kind = env.bd === 'harbor' || env.bd === 'lake' ? 'sea' : 'hills';
      }
    }
    if (still) scenery(g, env, 0, y0, W, h, still, t);
    var far = mix(env.skyLow, kind === 'snow' ? '#e8eef6' : '#7a8ab0', 0.5);
    var mid = kind === 'snow' ? hx('#f4f7fb') : (kind === 'sea' ? hx('#3a7ab0') : (env.bd === 'autumn' ? hx('#d8843a') : (env.bd === 'sakura' ? hx('#f0b0c8') : P.leaf)));
    var midD = kind === 'snow' ? hx('#ccd8ea') : (kind === 'sea' ? hx('#2a5a90') : P.leafD);
    var o1 = t * 0.6;
    var o2 = t * 2.2;
    var o3 = t * 7;
    for (x = 0; x < W; x++) {
      var wx = x + o1;
      var fy = y0 + h * 0.5 + Math.sin(wx * 0.05) * h * 0.12 + Math.sin(wx * 0.13) * 1.5;
      if (kind === 'city') fy = y0 + h * 0.45 + (fl(wx / 9) % 3) * 3 + (hash(fl(wx / 9), 3) * 6);
      if (!still) for (y = fl(fy); y < y1; y++) g.p(x, y, far);
      if (kind === 'city' && env.time !== 'day' && mod(fl(wx), 3) === 0 && hash(fl(wx), fl(fy)) < 0.5) g.p(x, fl(fy) + 3, env.litC);
      var wx2 = x + o2;
      var my = kind === 'sea' ? y0 + h * 0.7 : y0 + h * 0.72 + Math.sin(wx2 * 0.04 + 2) * h * 0.06;
      for (y = fl(my); y < y1; y++) {
        var cc = (fl(wx2) * 7 + y * 13) % 11 === 0 ? midD : mid;
        if (kind === 'sea' && (y & 1) && mod(fl(wx2) + y * 5, 17) < 3) cc = mix(mid, WHITE, 0.4);
        if (kind === 'fields' && mod(fl(wx2 * 0.5) + y, 6) < 2) cc = mix(mid, '#e8c060', 0.5);
        g.p(x, y, cc);
      }
      if (kind === 'forest' || kind === 'snow') {
        if (mod(fl(wx2), 11) === 0) {
          var th = 6 + fl(hash(fl(wx2), 1) * 6);
          for (var k = 0; k < th; k++) g.r(x - fl(k / 3), fl(my) - th + k, fl(k / 3) * 2 + 1, 1, kind === 'snow' && k % 3 === 0 ? '#f4f8ff' : mix(P.leafD, '#1f4a40', 0.4));
        }
      }
      if (kind === 'sea' && mod(fl(wx2), 70) === 0) {
        g.r(x, fl(my) - 2, 6, 2, P.paper);
        g.r(x + 2, fl(my) - 8, 1, 6, P.woodDD);
        g.r(x + 3, fl(my) - 7, 3, 4, P.paper);
      }
    }
    // poles whipping past
    for (var px = -mod(o3, 64); px < W; px += 64) {
      g.r(px, y0, 2, h, mix(P.metalD, P.ink, 0.3));
      g.line(px - 30, y0 + 3, px + 34, y0 + 3, P.ink);
    }
    weather(g, env, 0, y0, W, h, t, 1.2);
  }

  SC.train = {
    still: 2,
    setup: function (env, R) {
      return {
        view: R.pick(['hills', 'sea', 'fields', 'city', 'snow', 'forest']),
        wins: R.pick([2, 3, 3]),
        seat: R.pick(['accent', 'accent2', 'leafD', 'rug']),
        blind: R.pick([0, 0, 4, 8]),
        rack: [R.pick(['bag', 'case', 'hat', 'none']), R.pick(['bag', 'case', 'none', 'box'])],
        itemA: hp(R, env, 'cup', CUPS),
        itemB: R.pick(['bento', 'book', 'ticket', 'fruit', 'snack']),
        lamp: R.ch(0.6),
        spot: R.pick(['seat', 'table'])
      };
    },
    draw: function (g, env, L, t) {
      var P = env.P;
      var x;
      var top = 14;
      var bot = 44;
      g.em = 1;
      trainView(g, env, top, bot, t, L.view);
      g.em = 0;
      // wall panels around the windows
      var n = L.wins;
      var ww = n === 2 ? 70 : 48;
      var gap = (W - n * ww) / (n + 1);
      var wallC = P.wall;
      g.r(0, 0, W, top, wallC);
      g.r(0, bot, W, 60 - bot, P.wallD);
      var wxs = [];
      for (var i = 0; i < n; i++) wxs.push(rnd(gap + i * (ww + gap)));
      g.r(0, top, wxs[0], bot - top, wallC);
      for (i = 0; i < n; i++) {
        var ex = wxs[i] + ww;
        var nx = i + 1 < n ? wxs[i + 1] : W;
        g.r(ex, top, nx - ex, bot - top, wallC);
        var wx = wxs[i];
        // rounded corners + frame
        g.p(wx, top, wallC);
        g.p(wx + 1, top, wallC);
        g.p(wx, top + 1, wallC);
        g.p(ex - 1, top, wallC);
        g.p(ex - 2, top, wallC);
        g.p(ex - 1, top + 1, wallC);
        g.p(wx, bot - 1, wallC);
        g.p(ex - 1, bot - 1, wallC);
        g.r(wx - 1, top - 1, ww + 2, 1, P.metalD);
        g.r(wx - 1, bot, ww + 2, 2, P.metal);
        g.r(wx - 1, bot + 2, ww + 2, 1, P.metalD);
        for (var k = 0; k < bot - top; k++) g.b(wx + 4 + k * 0.4 + k, bot - 2 - k, WHITE, 0.08);
        if (L.blind) {
          g.r(wx, top, ww, L.blind, P.paperD);
          for (var b = top + 1; b < top + L.blind; b += 2) g.r(wx, b, ww, 1, P.paper);
          g.r(wx + fl(ww / 2), top + L.blind, 2, 2, P.accent);
        }
        g.light(wx + ww / 2, bot - 4, 60, env.T.win, env.winK, { sy: 1.3 });
      }
      // luggage rack
      g.r(0, 6, W, 1, P.metalD);
      g.r(0, 9, W, 1, P.metal);
      for (x = 4; x < W; x += 6) g.p(x, 7, P.metalD);
      for (x = 4; x < W; x += 6) g.p(x, 8, P.metalD);
      function rack(kind, x0) {
        if (kind === 'bag') {
          g.r(x0, 1, 18, 5, P.accent2);
          g.r(x0, 1, 18, 1, P.accent2L);
          g.r(x0 + 7, 0, 4, 1, P.accent2D);
        } else if (kind === 'case') {
          g.r(x0, 0, 22, 6, P.accent);
          g.r(x0, 0, 22, 1, P.accentL);
          g.r(x0 + 4, 0, 1, 6, P.accentD);
          g.r(x0 + 17, 0, 1, 6, P.accentD);
        } else if (kind === 'hat') {
          g.r(x0, 4, 14, 2, P.woodL);
          g.r(x0 + 3, 1, 8, 3, P.woodL);
          g.r(x0 + 3, 3, 8, 1, P.accent);
        } else if (kind === 'box') {
          g.r(x0, 1, 14, 5, '#d8a86a');
          g.r(x0 + 6, 1, 2, 5, '#e8d8a8');
        }
      }
      rack(L.rack[0], 24);
      rack(L.rack[1], 130);
      if (L.lamp && env.time !== 'day') {
        g.em = 1;
        g.r(92, 10, 8, 1, '#fff1c2');
        g.em = 0;
        g.light(96, 11, 50, env.T.lamp, env.lampK * 0.7, { top: 10 });
      }
      // floor
      g.r(0, 60, W, 20, mix(P.floor, P.ink, 0.25));
      for (x = 0; x < W; x += 4) g.r(x, 64 + (x % 8 ? 0 : 6), 2, 1, mix(P.floor, P.ink, 0.4));
      // seats facing each other with a table between
      var sc = P[L.seat];
      var sD = mix(sc, P.ink, 0.3);
      var sL = mix(sc, WHITE, 0.25);
      function seat(x0, flip) {
        var bx = flip ? x0 + 30 : x0;
        g.r(bx, 34, 10, 34, sc);
        g.r(bx, 34, 10, 2, sL);
        g.r(flip ? bx : bx + 9, 36, 1, 32, sD);
        g.r(bx + 2, 30, 6, 6, P.paper);
        var cx0 = flip ? x0 : x0 + 10;
        g.r(cx0, 58, 30, 6, sc);
        g.r(cx0, 58, 30, 1, sL);
        g.r(cx0, 64, 30, 2, sD);
        g.r(cx0 + 2, 66, 26, 10, mix(sD, P.ink, 0.3));
      }
      seat(4, false);
      seat(148, true);
      g.r(68, 50, 56, 3, P.woodL);
      g.r(68, 53, 56, 1, P.woodD);
      g.r(94, 54, 4, 22, P.metalD);
      g.r(88, 76, 16, 2, P.metalD);
      cupOf(g, env, L.itemA, 74, 50, P.paper, t);
      var ib = 104;
      if (L.itemB === 'bento') {
        g.r(ib, 46, 14, 4, P.accent);
        g.r(ib + 1, 46, 12, 1, P.paper);
        g.p(ib + 3, 47, '#e8483a');
        g.p(ib + 6, 47, '#9ad04a');
        g.p(ib + 9, 47, '#f6c63a');
      } else if (L.itemB === 'book') {
        g.r(ib, 48, 14, 2, P.accent2);
        g.r(ib + 1, 47, 12, 1, P.paper);
      } else if (L.itemB === 'ticket') {
        g.r(ib, 48, 12, 2, '#f6e08a');
        g.r(ib + 3, 48, 1, 2, P.accent);
        g.p(ib + 6, 48, P.ink);
        g.p(ib + 8, 48, P.ink);
      } else if (L.itemB === 'fruit') {
        trinket(g, env, 'fruit', ib, 50, t);
      } else {
        g.r(ib, 43, 8, 7, P.accent2);
        g.r(ib, 43, 8, 2, P.paper);
        g.r(ib + 2, 41, 1, 3, '#f6c63a');
        g.r(ib + 4, 40, 1, 4, '#f6c63a');
      }
      if (L.spot === 'seat') mascot(g, env, 20, 58, t, { flip: false, sleep: env.time === 'night' });
      else mascot(g, env, 80, 50, t, { flip: false });
    }
  };

  function fishSpr(g, x, y, kind, c, flip) {
    var S = {
      round: ['..##..', '.####.#', '######.', '.####.#', '..##..'],
      long: ['..####...', '########.#', '.#######.#', '..####...'],
      clown: ['.#*#.', '##*##.#', '##*##.', '.#*#.#'],
      tiny: ['.##.#', '####.', '.##.#']
    };
    var rows = S[kind] || S.round;
    var cD = mix(c, '#1a2a4a', 0.35);
    g.spr(x, y, rows, { '#': c, '*': WHITE }, flip);
    g.p(flip ? x + rows[0].length - 2 : x + 1, y + 1, cD);
    g.p(flip ? x + rows[0].length - 3 : x + 2, y + 1, '#1a1a2a');
  }

  SC.aquarium = {
    still: 6,
    setup: function (env, R) {
      var fish = [];
      var n = R.i(5, 9);
      var kinds = ['round', 'long', 'clown', 'tiny'];
      var cols = ['#ffb03a', '#ff7a5a', '#7fd8ff', '#ffe27a', '#c7a8ff', '#ff9fbf', '#a6f0a0'];
      for (var i = 0; i < n; i++) fish.push({ k: R.pick(kinds), c: R.pick(cols), y: R.i(10, 48), v: R.r(0.25, 0.7) * (R.ch(0.5) ? 1 : -1), o: R.r(0, 300) });
      return {
        fish: fish,
        big: R.pick(['turtle', 'ray', 'jelly', 'whale', 'octopus', 'none']),
        coral: [R.pick(['#ff7a8a', '#ffb03a', '#c7a8ff', '#ff9fbf']), R.pick(['#7fd8a0', '#ffe27a', '#ff7a5a'])],
        arch: R.ch(0.4),
        water: R.pick(['#1a5a9a', '#1a6a8a', '#2a4a8a', '#1a7a7a']),
        fore: R.pick(['bench', 'rail', 'none', 'plaque']),
        chest: R.ch(0.4)
      };
    },
    draw: function (g, env, L, t) {
      var P = env.P;
      var x;
      var y;
      var room0 = mix(P.ink, '#101830', 0.4);
      g.r(0, 0, W, H, room0);
      var x0 = 6;
      var x1 = 186;
      var y0 = 4;
      var y1 = 62;
      var wc = hx(L.water);
      var night = env.time === 'night';
      g.em = 1;
      var inTank = function (qx, qy) {
        if (qx < x0 || qx >= x1 || qy < y0 || qy >= y1) return false;
        if (!L.arch) return true;
        var dx = (qx - 96) / 90;
        var dy = (qy - y1) / (y1 - y0);
        return dx * dx + dy * dy <= 1;
      };
      for (y = y0; y < y1; y++) {
        var f = (y - y0) / (y1 - y0);
        var c = mix(mix(wc, '#7fd8ff', 0.35 * (1 - f)), '#0a1a3a', f * 0.45 + (night ? 0.2 : 0));
        for (x = x0; x < x1; x++) if (inTank(x, y)) g.p(x, y, bay(x, y) < 0.15 && f > 0.5 ? mix(c, '#0a1a3a', 0.2) : c);
      }
      g.clip(x0, y0, x1 - x0, y1 - y0, inTank);
      // god rays
      for (var r = 0; r < 4; r++) {
        var rx = 30 + r * 44 + rnd(Math.sin(t * 0.05 + r) * 6);
        for (y = y0; y < y0 + 40; y++) {
          for (x = rx + fl((y - y0) * 0.3); x < rx + 6 + fl((y - y0) * 0.5); x++) if (bay(x, y) < 0.35) g.b(x, y, '#c8f0ff', 0.25 * (1 - (y - y0) / 40));
        }
      }
      // sand, rocks, corals, weed
      for (x = x0; x < x1; x++) {
        var sy = 54 + rnd(Math.sin(x * 0.06) * 2);
        for (y = sy; y < y1; y++) g.p(x, y, y === sy ? '#f0dca8' : (bay(x, y) < 0.12 ? '#b8a070' : '#d8c08a'));
      }
      g.ell(40, 54, 8, 4, '#5a6a7a');
      g.ell(38, 52, 5, 2, '#7a8a9a');
      g.ell(150, 55, 10, 4, '#5a6a7a');
      for (var w = 0; w < 7; w++) {
        var wx = 14 + w * 26 + (w % 2) * 5;
        var wh = 10 + (w % 3) * 5;
        for (var k = 0; k < wh; k++) {
          var sx = wx + rnd(Math.sin(k * 0.4 + t * 0.12 + w) * (k / wh) * 2.5);
          g.p(sx, 55 - k, k % 4 === 0 ? '#5ab86a' : '#3a9a5a');
          if (k % 3 === 1) g.p(sx + 1, 55 - k, '#3a9a5a');
        }
      }
      [[66, 0], [118, 1], [172, 0]].forEach(function (cr) {
        var cc = hx(L.coral[cr[1]]);
        var cx = cr[0];
        g.r(cx, 46, 2, 9, cc);
        g.r(cx - 3, 49, 2, 5, cc);
        g.r(cx + 3, 47, 2, 6, cc);
        g.r(cx - 3, 53, 9, 2, cc);
        g.p(cx, 45, mix(cc, WHITE, 0.4));
        g.p(cx + 3, 46, mix(cc, WHITE, 0.4));
      });
      if (L.chest) {
        g.r(92, 50, 12, 6, P.woodD);
        g.r(92, 48, 12, 2, P.woodL);
        g.r(97, 51, 2, 2, '#f0c84a');
        if (mod(t, 20) < 10) {
          for (var bb = 0; bb < 3; bb++) g.p(96 + bb * 2, 46 - mod(t + bb * 3, 10), '#d8f4ff');
        }
      }
      // bubbles
      for (var bl = 0; bl < 10; bl++) {
        var by = y1 - mod(t * 0.9 + bl * 6, y1 - y0);
        g.p(24 + rnd(Math.sin(t * 0.3 + bl) * 1.5), by, '#d8f4ff');
        if (bl % 3 === 0) g.p(25, by + 1, '#a8e0ff');
      }
      // big creature
      var bxp = mod(t * 0.35, x1 - x0 + 60) - 30 + x0;
      if (L.big === 'turtle') {
        g.ell(bxp, 22, 8, 4, '#5a9a5a');
        g.ell(bxp, 21, 6, 2, '#7aba6a');
        g.r(bxp + 8, 20, 4, 3, '#8aca7a');
        g.p(bxp + 10, 20, '#1a1a2a');
        var fp = mod(t, 8) < 4 ? 1 : 0;
        g.r(bxp - 5, 25 + fp, 4, 2, '#8aca7a');
        g.r(bxp + 3, 25 - fp, 4, 2, '#8aca7a');
      } else if (L.big === 'ray') {
        var wv = rnd(Math.sin(t * 0.3) * 2);
        for (var rr = -8; rr <= 8; rr++) g.r(bxp + rr, 26 - (Math.abs(rr) > 4 ? wv : 0) + Math.abs(rr) * 0.3, 1, 3 - Math.abs(rr) * 0.25, '#5a7a9a');
        g.line(bxp - 9, 27, bxp - 20, 28, '#4a6a8a');
      } else if (L.big === 'jelly') {
        for (var j = 0; j < 3; j++) {
          var jx = 50 + j * 46;
          var jy = 14 + mod(-t * 0.25 + j * 12, 30);
          var pulse = mod(t + j * 2, 6) < 3 ? 0 : 1;
          g.ell(jx, jy, 4 - pulse, 3, '#f0c0f0');
          g.r(jx - 3 + pulse, jy + 1, 7 - pulse * 2, 1, '#d8a0e0');
          for (var tl = -2; tl <= 2; tl += 2) {
            for (var tk = 0; tk < 6; tk++) g.b(jx + tl + rnd(Math.sin(tk * 0.8 + t * 0.3 + tl) * 0.7), jy + 2 + tk, '#f0c0f0', 0.6);
          }
        }
      } else if (L.big === 'whale') {
        g.ell(bxp, 18, 16, 5, '#3a5a8a');
        g.ell(bxp, 20, 14, 3, '#5a7aaa');
        g.r(bxp - 22, 15 + (mod(t, 10) < 5 ? 0 : 1), 6, 2, '#3a5a8a');
        for (var sp = 0; sp < 6; sp++) g.p(bxp - 10 + sp * 4, 15, '#f0f4ff');
        g.p(bxp + 10, 18, '#1a1a2a');
      } else if (L.big === 'octopus') {
        var ox = 140;
        g.ell(ox, 44, 5, 4, '#e8705a');
        g.p(ox - 2, 44, WHITE);
        g.p(ox + 2, 44, WHITE);
        for (var a = 0; a < 5; a++) {
          for (var ak = 0; ak < 5; ak++) g.p(ox - 4 + a * 2 + rnd(Math.sin(ak + t * 0.25 + a) * 1), 48 + ak, '#d8604a');
        }
      }
      for (var fi = 0; fi < L.fish.length; fi++) {
        var fsh = L.fish[fi];
        var span = x1 - x0 + 20;
        var fxp = x0 - 10 + mod(fsh.o + t * fsh.v * 1.4, span);
        var fyp = fsh.y + rnd(Math.sin(t * 0.15 + fi) * 2);
        fishSpr(g, fxp, fyp, fsh.k, hx(fsh.c), fsh.v < 0);
      }
      g.noclip();
      g.em = 0;
      // glass frame + room floor
      if (!L.arch) {
        g.r(x0 - 2, y0 - 2, x1 - x0 + 4, 2, '#2a3048');
        g.r(x0 - 2, y0, 2, y1 - y0, '#2a3048');
        g.r(x1, y0, 2, y1 - y0, '#2a3048');
        g.r(64, y0, 2, y1 - y0, '#2a3048');
        g.r(126, y0, 2, y1 - y0, '#2a3048');
      }
      g.r(0, y1, W, 2, '#3a4060');
      g.r(0, y1 + 2, W, H - y1 - 2, mix(room0, '#2a3a60', 0.4));
      for (x = 0; x < W; x += 2) g.b(x, y1 + 3 + (x % 4 ? 1 : 0), '#7fd8ff', 0.15);
      g.light(96, 50, 120, [0.45, 0.7, 1], night ? 0.9 : 0.6, { sy: 1.6 });
      if (L.fore === 'bench') {
        g.r(60, 70, 72, 3, '#1a1e30');
        g.r(66, 73, 3, 7, '#1a1e30');
        g.r(124, 73, 3, 7, '#1a1e30');
      } else if (L.fore === 'rail') {
        g.r(0, 66, W, 2, '#3a4060');
        for (x = 8; x < W; x += 16) g.r(x, 68, 1, 12, '#3a4060');
      } else if (L.fore === 'plaque') {
        g.r(155, 70, 2, 10, '#2a3048');
        g.em = 1;
        g.r(148, 64, 16, 7, '#d8eef8');
        fishSpr(g, 150, 65, L.fish[0].k, hx(L.fish[0].c), false);
        textRow(g, 157, 66, 6, env.seed, 1, '#5a6a8a');
        textRow(g, 157, 68, 5, env.seed, 2, '#5a6a8a');
        g.em = 0;
      }
      mascot(g, env, 90, L.fore === 'bench' ? 70 : 76, t, { sleep: false });
    }
  };

  var PAINTINGS = ['landscape', 'portrait', 'abstract', 'still', 'sea', 'night', 'flower', 'sunset'];

  function painting(g, env, kind, x, y, w, h, t) {
    var P = env.P;
    var xx;
    var i;
    if (kind === 'landscape' || kind === 'sunset') {
      var sky = kind === 'sunset' ? '#f0a070' : '#a8d4ea';
      g.r(x, y, w, h, sky);
      if (kind === 'sunset') g.r(x, y, w, fl(h / 3), '#c86a8a');
      for (xx = 0; xx < w; xx++) {
        var hy = rnd(h * 0.55 + Math.sin(xx * 0.5) * 1.5);
        g.r(x + xx, y + hy, 1, h - hy, xx < w / 2 ? '#7fb87a' : '#6aa86a');
      }
      g.p(x + w - 3, y + 2, kind === 'sunset' ? '#ffe08a' : '#fff2a8');
    } else if (kind === 'portrait') {
      g.r(x, y, w, h, mix(P.accent2, P.ink, 0.3));
      g.ell(x + w / 2, y + h * 0.4, Math.max(1, fl(w / 5)), Math.max(1, fl(h / 5)), '#f0c8a0');
      g.r(x + fl(w / 4), y + fl(h * 0.65), fl(w / 2), h - fl(h * 0.65), P.accent);
      g.r(x + fl(w / 2) - fl(w / 5), y + fl(h * 0.2), fl(w / 5) * 2 + 1, 1, '#4a3a36');
    } else if (kind === 'abstract') {
      g.r(x, y, w, h, P.paper);
      g.r(x + 1, y + 1, fl(w / 2), fl(h / 2), P.accent);
      g.r(x + fl(w / 2), y + fl(h / 3), fl(w / 2) - 1, fl(h / 2), P.accent2);
      g.r(x + 2, y + h - 3, w - 4, 2, '#f6c63a');
      g.r(x + fl(w / 3), y, 1, h, P.ink);
    } else if (kind === 'still') {
      g.r(x, y, w, h, mix(P.wallD, P.ink, 0.2));
      g.r(x, y + fl(h * 0.7), w, h - fl(h * 0.7), P.woodL);
      g.r(x + 2, y + fl(h * 0.35), 3, fl(h * 0.35), P.accent2);
      g.ell(x + w - 4, y + fl(h * 0.62), 2, 2, '#e8483a');
      g.ell(x + w - 7, y + fl(h * 0.65), 1, 1, '#f6a03a');
    } else if (kind === 'sea') {
      g.r(x, y, w, h, '#a8d4ea');
      g.r(x, y + fl(h / 2), w, h - fl(h / 2), '#3a7ab0');
      for (i = 0; i < w; i += 3) g.p(x + i, y + fl(h / 2) + 2 + (i % 2), '#d8f0ff');
      g.r(x + 3, y + fl(h / 2) - 3, 1, 3, P.paper);
      g.r(x + 4, y + fl(h / 2) - 2, 2, 2, P.paper);
    } else if (kind === 'night') {
      g.r(x, y, w, h, '#1a2a5a');
      for (i = 0; i < 6; i++) g.p(x + fl(hash(x, i) * w), y + fl(hash(y, i) * h * 0.6), '#fff6c8');
      g.ell(x + w - 4, y + 3, 1, 1, '#ffe08a');
      for (xx = 0; xx < w; xx++) g.r(x + xx, y + h - 2 - (xx % 5 === 0 ? 2 : 0), 1, 2 + (xx % 5 === 0 ? 2 : 0), '#2a2030');
    } else {
      g.r(x, y, w, h, P.paper);
      g.r(x + fl(w / 2), y + fl(h / 3), 1, h - fl(h / 3), P.leafD);
      g.ell(x + fl(w / 2), y + fl(h / 3), 2, 2, P.accent);
      g.p(x + fl(w / 2), y + fl(h / 3), '#ffe27a');
      g.p(x + fl(w / 2) - 2, y + fl(h * 0.6), P.leaf);
    }
  }

  SC.atelier = {
    still: 5,
    setup: function (env, R) {
      return {
        wallStyle: R.pick([0, 1, 2]),
        win: R.pick(['big', 'skylight', 'round']),
        view: R.pick(['trees', 'city', 'hills']),
        canvas: R.pick(PAINTINGS),
        wallP: [R.pick(PAINTINGS), R.pick(PAINTINGS), R.pick(PAINTINGS)],
        easelX: R.pick([70, 96, 120]),
        bust: R.ch(0.5),
        stack: R.ch(0.6),
        cloth: R.pick(['paper', 'accent2', 'woodL']),
        jar: R.i(2, 4),
        floor: hp(R, env, 'floor', ['stool', 'bigplant', 'none', 'pouf']),
        critter: hp(R, env, 'critter', ['kitty', 'none', 'bird'])
      };
    },
    draw: function (g, env, L, t) {
      var P = env.P;
      var x;
      room(g, env, L, 64);
      if (L.win === 'big') win(g, env, 8, 6, 44, 42, t, { panes: 4, view: L.view, curtain: P.paper, cw: 7 });
      else if (L.win === 'round') win(g, env, 14, 8, 30, 30, t, { shape: 'round', view: L.view });
      else {
        // slanted skylight in the ceiling
        g.em = 1;
        g.clip(6, 0, 52, 22, function (qx, qy) {
          return qy < 20 - (qx - 6) * 0.25 && qy >= 0;
        });
        outside(g, env, 6, 0, 52, 22, t, null);
        g.noclip();
        g.em = 0;
        for (x = 6; x < 58; x++) g.r(x, rnd(20 - (x - 6) * 0.25), 1, 2, P.woodD);
        g.r(30, 0, 2, 15, P.woodD);
        g.light(32, 26, 60, env.T.win, env.winK, { sy: 1.1 });
      }
      // wall paintings
      var spots = [[62, 8, 18, 14], [86, 12, 12, 16], [154, 8, 20, 14]];
      for (var i = 0; i < 3; i++) {
        var s = spots[i];
        if (Math.abs(s[0] + s[2] / 2 - L.easelX - 14) < 22) continue;
        g.r(s[0] - 1, s[1] - 1, s[2] + 2, s[3] + 2, P.woodD);
        painting(g, env, L.wallP[i], s[0], s[1], s[2], s[3], t);
        g.br(s[0], s[1] + s[3] + 1, s[2] + 2, 1, P.ink, 0.15);
      }
      // shelf with paint jars and a plaster bust
      g.r(150, 34, 38, 2, P.woodL);
      g.r(150, 36, 38, 1, P.woodD);
      for (var j = 0; j < L.jar; j++) {
        var jx = 152 + j * 7;
        g.r(jx, 28, 5, 6, mix('#e8f4f8', P.wall, 0.3));
        g.r(jx, 31, 5, 3, P.books[(j + env.seed) % P.books.length]);
        g.line(jx + 2, 28, jx + 1, 23, P.woodD);
        g.line(jx + 3, 28, jx + 4, 24, P.woodD);
        g.p(jx + 1, 22, P.ink);
      }
      if (L.bust) {
        var bx = 178;
        g.r(bx - 3, 31, 8, 3, P.paperD);
        g.r(bx - 1, 26, 5, 5, P.paper);
        g.ell(bx + 1, 23, 3, 3, P.paper);
        g.p(bx + 3, 23, P.paperD);
        g.p(bx + 2, 25, P.paperD);
      }
      // drop cloth with paint splatters
      var dc = P[L.cloth];
      g.ell(L.easelX + 14, 72, 36, 5, dc);
      for (var sp = 0; sp < 14; sp++) g.p(L.easelX + 14 + fl((hash(env.seed, sp) - 0.5) * 60), 70 + fl(hash(env.seed, sp + 20) * 5), [P.accent, P.accent2, '#ffd25a'][sp % 3]);
      // easel + canvas
      var ex = L.easelX;
      g.line(ex + 4, 22, ex - 2, 74, P.woodD);
      g.line(ex + 24, 22, ex + 30, 74, P.woodD);
      g.line(ex + 14, 18, ex + 14, 72, P.woodDD);
      g.r(ex - 1, 52, 32, 2, P.woodL);
      g.r(ex + 1, 22, 26, 30, P.paper);
      g.r(ex + 1, 22, 26, 1, P.paperD);
      painting(g, env, L.canvas, ex + 3, 24, 22, 26, t);
      // the brush keeps working
      var ph = mod(t, 24);
      var bxp = ex + 8 + rnd(Math.sin(ph * 0.5) * 6);
      var byp = 34 + rnd(Math.cos(ph * 0.4) * 5);
      g.line(bxp, byp, bxp + 8, byp + 9, P.woodD);
      g.p(bxp, byp, P.accent);
      // side table with palette and tubes
      var tx = ex > 100 ? 40 : 136;
      g.r(tx, 54, 22, 2, P.woodL);
      g.r(tx + 2, 56, 2, 20, P.woodD);
      g.r(tx + 18, 56, 2, 20, P.woodD);
      g.ell(tx + 8, 53, 7, 1, P.woodL);
      g.p(tx + 4, 52, P.accent);
      g.p(tx + 7, 52, P.accent2);
      g.p(tx + 10, 52, '#ffd25a');
      g.p(tx + 12, 53, P.paper);
      g.r(tx + 15, 52, 5, 2, P.metal);
      g.p(tx + 20, 52, P.accent);
      if (L.stack) {
        var sx2 = ex > 100 ? 8 : 166;
        for (var c = 0; c < 3; c++) {
          g.r(sx2 + c * 3, 50 - c * 2, 16, 26 + c * 2, P.woodL);
          g.r(sx2 + c * 3 + 1, 51 - c * 2, 14, 24 + c * 2, c === 2 ? P.paper : P.paperD);
        }
        painting(g, env, L.wallP[0], sx2 + 8, 50, 12, 10, t);
      }
      if (L.floor !== 'none') floorThing(g, env, L.floor, ex > 100 ? 64 : 112, 77, t);
      if (L.critter !== 'none') critter(g, env, L.critter, L.critter === 'bird' ? 160 : 140, L.critter === 'bird' ? 34 : 78, t, true);
      mascot(g, env, ex + 30, 74, t, { flip: true, sleep: false });
    }
  };

  var TOOLS = ['hammer', 'saw', 'wrench', 'screwdriver', 'scissors', 'pliers', 'tape', 'brush', 'ruler', 'clamp'];

  function tool(g, env, kind, x, y) {
    var P = env.P;
    g.p(x + 3, y, P.metalD);
    if (kind === 'hammer') {
      g.r(x + 3, y + 3, 1, 10, P.woodD);
      g.r(x, y + 1, 7, 3, P.metalD);
      g.r(x, y + 1, 7, 1, P.metal);
    } else if (kind === 'saw') {
      g.r(x + 1, y + 1, 5, 3, P.accent);
      for (var i = 0; i < 12; i++) g.r(x + 1, y + 4 + i, 6 - fl(i / 3), 1, i % 2 ? P.metal : P.metalL);
    } else if (kind === 'wrench') {
      g.r(x + 3, y + 3, 1, 10, P.metal);
      g.r(x + 1, y + 1, 5, 2, P.metal);
      g.p(x + 3, y + 1, P.wall);
      g.r(x + 2, y + 13, 3, 2, P.metal);
    } else if (kind === 'screwdriver') {
      g.r(x + 2, y + 1, 3, 5, P.accent2);
      g.r(x + 2, y + 1, 1, 5, P.accent2L);
      g.r(x + 3, y + 6, 1, 7, P.metal);
    } else if (kind === 'scissors') {
      disc(g, x + 2, y + 3, 1, P.accent);
      disc(g, x + 5, y + 3, 1, P.accent);
      g.line(x + 2, y + 5, x + 5, y + 12, P.metal);
      g.line(x + 5, y + 5, x + 2, y + 12, P.metalL);
    } else if (kind === 'pliers') {
      g.line(x + 2, y + 2, x + 3, y + 8, P.accent);
      g.line(x + 5, y + 2, x + 4, y + 8, P.accent);
      g.r(x + 3, y + 8, 2, 5, P.metalD);
    } else if (kind === 'tape') {
      disc(g, x + 3, y + 5, 3, '#f6c63a');
      disc(g, x + 3, y + 5, 1, P.metalD);
      g.r(x + 5, y + 7, 3, 1, P.metalL);
    } else if (kind === 'brush') {
      g.r(x + 3, y + 1, 1, 7, P.woodD);
      g.r(x + 1, y + 8, 5, 2, P.metal);
      g.r(x + 1, y + 10, 5, 3, P.accent);
    } else if (kind === 'ruler') {
      g.r(x + 2, y + 1, 3, 14, '#f0d08a');
      for (var k = 0; k < 14; k += 2) g.p(x + 2, y + 1 + k, P.ink);
    } else {
      g.r(x, y + 2, 2, 10, P.accent);
      g.r(x, y + 2, 6, 2, P.accent);
      g.r(x, y + 10, 6, 2, P.accent);
      g.r(x + 5, y + 4, 1, 6, P.metal);
    }
  }

  SC.workshop = {
    still: 3,
    setup: function (env, R) {
      var tools = [];
      for (var i = 0; i < 7; i++) tools.push(R.pick(TOOLS));
      return {
        wallStyle: R.pick([0, 1, 3]),
        tools: tools,
        project: R.pick(['birdhouse', 'chair', 'robot', 'boat', 'pottery', 'lamp']),
        action: R.pick(['saw', 'sand', 'paint', 'none']),
        under: [R.pick(['toolbox', 'planks', 'bucket', 'none']), R.pick(['boxes', 'planks', 'none', 'stool'])],
        view: R.pick(['trees', 'city', 'hills']),
        jars: R.i(3, 5),
        critter: hp(R, env, 'critter', ['kitty', 'mouse', 'none', 'bird']),
        spot: R.pick(['bench', 'floor'])
      };
    },
    draw: function (g, env, L, t) {
      var P = env.P;
      var x;
      var i;
      room(g, env, L, 66);
      win(g, env, 8, 8, 30, 26, t, { panes: 4, view: L.view, sill: true });
      // pegboard with tools
      var pb = mix(P.woodL, '#c8955a', 0.4);
      g.r(48, 6, 92, 34, pb);
      g.r(48, 6, 92, 1, mix(pb, WHITE, 0.3));
      g.r(48, 39, 92, 1, mix(pb, P.ink, 0.3));
      for (var py = 9; py < 39; py += 4) {
        for (x = 51; x < 139; x += 4) g.p(x, py, mix(pb, P.ink, 0.35));
      }
      for (i = 0; i < L.tools.length; i++) tool(g, env, L.tools[i], 53 + i * 12, 10 + (i % 2) * 6);
      // shelf with jars of screws
      g.r(148, 22, 40, 2, P.woodL);
      g.r(148, 24, 40, 1, P.woodD);
      for (i = 0; i < L.jars; i++) {
        var jx = 150 + i * 8;
        g.r(jx, 15, 6, 7, mix('#e8f4f8', P.wall, 0.3));
        g.r(jx, 14, 6, 1, P.metalD);
        for (var d = 0; d < 6; d++) g.p(jx + 1 + (d * 3) % 4, 17 + fl(d / 2), [P.metal, '#d8a838', P.metalD][(d + i) % 3]);
      }
      g.r(150, 32, 30, 2, P.woodL);
      g.r(150, 34, 30, 1, P.woodD);
      trinket(g, env, 'radio', 152, 32, t);
      trinket(g, env, 'pencils', 166, 32, t);
      // hanging bulb, gently swinging
      var sw = Math.sin(t * 0.18) * 2;
      g.line(96, 0, 96 + sw, 3, P.ink);
      g.r(95 + rnd(sw), 3, 3, 2, P.metalD);
      g.em = 1;
      g.r(95 + rnd(sw), 5, 3, 2, env.time === 'day' ? '#f6efd8' : '#fff1c2');
      g.em = 0;
      g.light(96 + sw, 6, 60, env.T.lamp, env.lampK * 1.1, { n: 6 });
      // workbench
      g.br(4, 47, 184, 1, P.ink, 0.15);
      g.r(4, 48, 184, 4, P.woodL);
      g.r(4, 48, 184, 1, mix(P.woodL, WHITE, 0.3));
      g.r(4, 52, 184, 2, P.woodD);
      g.r(8, 54, 4, 24, P.woodDD);
      g.r(180, 54, 4, 24, P.woodDD);
      g.r(8, 66, 176, 2, P.woodD);
      // vise
      g.r(14, 42, 10, 6, P.metalD);
      g.r(14, 42, 10, 1, P.metal);
      g.r(24, 44, 6, 1, P.metal);
      g.r(29, 42, 1, 5, P.metal);
      // project
      var px = 82;
      if (L.project === 'birdhouse') {
        g.r(px, 38, 14, 10, P.woodL);
        for (i = 0; i < 6; i++) g.r(px - 2 + i, 37 - i, 18 - i * 2, 1, P.accent);
        disc(g, px + 7, 42, 2, P.woodDD);
        g.r(px + 6, 45, 3, 1, P.woodD);
      } else if (L.project === 'chair') {
        g.r(px + 2, 36, 2, 12, P.woodD);
        g.r(px + 2, 40, 12, 2, P.woodL);
        g.r(px + 12, 42, 2, 6, P.woodD);
        g.r(px + 4, 42, 1, 6, P.woodD);
        g.r(px + 2, 36, 1, 1, P.woodL);
      } else if (L.project === 'robot') {
        g.r(px + 2, 38, 10, 10, P.metal);
        g.r(px + 3, 31, 8, 6, P.metalL);
        g.em = 1;
        g.p(px + 5, 33, mod(t, 8) < 6 ? '#6aff8a' : '#2a8a3a');
        g.p(px + 8, 33, mod(t, 8) < 6 ? '#6aff8a' : '#2a8a3a');
        g.em = 0;
        g.r(px + 7, 29, 1, 2, P.metalD);
        g.p(px + 7, 28, P.accent);
        g.r(px, 40, 2, 5, P.metalD);
        g.r(px + 12, 40 - (mod(t, 10) < 5 ? 0 : 2), 2, 5, P.metalD);
      } else if (L.project === 'boat') {
        g.r(px, 44, 16, 3, P.woodD);
        g.r(px + 2, 47, 12, 1, P.woodDD);
        g.r(px + 7, 30, 1, 14, P.woodDD);
        for (i = 0; i < 10; i++) g.r(px + 8, 31 + i, rnd(i * 0.6) + 1, 1, P.paper);
      } else if (L.project === 'pottery') {
        g.ell(px + 8, 47, 9, 1, P.metalD);
        var spin = mod(t, 3);
        g.r(px + 4, 38, 8, 9, '#c87a5a');
        g.r(px + 5, 35, 6, 3, '#c87a5a');
        g.r(px + 6, 34, 4, 1, '#a85a3a');
        g.r(px + 4 + spin * 2, 39, 1, 7, '#e8a080');
      } else {
        g.r(px + 4, 45, 9, 3, P.woodD);
        g.r(px + 8, 33, 1, 12, P.metalD);
        for (i = 0; i < 5; i++) g.r(px + 4 - i * 0.5, 30 + i, 9 + i, 1, P.accent2);
      }
      // tool in action
      var ax = 130;
      if (L.action === 'saw') {
        g.r(ax, 45, 34, 3, P.woodL);
        g.r(ax, 45, 34, 1, mix(P.woodL, WHITE, 0.3));
        var so = rnd(Math.sin(t * 0.8) * 4);
        g.line(ax + 12 + so, 46, ax + 22 + so, 38, P.metal);
        g.line(ax + 13 + so, 46, ax + 23 + so, 38, P.metalL);
        g.r(ax + 22 + so, 36, 4, 3, P.accent);
        for (i = 0; i < 6; i++) g.p(ax + 12 + fl(hash(i, fl(t / 2)) * 8), 47 + mod(t + i * 2, 8), '#f0d8a8');
      } else if (L.action === 'sand') {
        g.r(ax, 45, 34, 3, P.woodL);
        var so2 = rnd(Math.sin(t * 0.9) * 6);
        g.r(ax + 12 + so2, 42, 8, 3, P.accent2);
        g.r(ax + 14 + so2, 40, 4, 2, P.accent2D);
        for (i = 0; i < 4; i++) g.b(ax + 10 + so2 + i * 3, 41 - mod(t + i, 4), '#f0e0c0', 0.5);
      } else if (L.action === 'paint') {
        g.r(ax + 4, 42, 8, 6, P.metal);
        g.r(ax + 4, 42, 8, 2, P.accent);
        g.line(ax + 16, 47, ax + 22, 38, P.woodD);
        g.r(ax + 15, 45, 3, 2, P.accent);
        g.r(ax + 24, 44, 10, 4, P.accentL);
      }
      // under the bench
      function under(kind, x0) {
        if (kind === 'toolbox') {
          g.r(x0, 70, 18, 8, P.accent);
          g.r(x0, 70, 18, 1, P.accentL);
          g.r(x0 + 6, 67, 6, 1, P.metalD);
          g.r(x0 + 6, 67, 1, 3, P.metalD);
          g.r(x0 + 11, 67, 1, 3, P.metalD);
          g.r(x0 + 8, 72, 2, 2, P.metal);
        } else if (kind === 'planks') {
          for (i = 0; i < 3; i++) {
            g.r(x0 - i * 2, 75 - i * 3, 30, 3, i % 2 ? P.woodL : P.wood);
            g.r(x0 - i * 2, 75 - i * 3, 30, 1, mix(P.woodL, WHITE, 0.2));
          }
        } else if (kind === 'bucket') {
          g.r(x0 + 2, 69, 10, 9, P.metal);
          g.r(x0 + 1, 68, 12, 1, P.metalL);
          g.line(x0 + 2, 68, x0 + 7, 64, P.metalD);
          g.line(x0 + 12, 68, x0 + 7, 64, P.metalD);
        } else if (kind !== 'none') {
          floorThing(g, env, kind, x0, 78, t);
        }
      }
      under(L.under[0], 24);
      under(L.under[1], 140);
      if (L.critter !== 'none') critter(g, env, L.critter, L.critter === 'bird' ? 26 : 70, L.critter === 'bird' ? 34 : 78, t, false);
      if (L.spot === 'bench') mascot(g, env, 108, 48, t, { sleep: false });
      else mascot(g, env, 104, 78, t);
    }
  };

  var BLOOMS = ['rose', 'tulip', 'sunflower', 'daisy', 'lavender', 'baby', 'hydrangea', 'lily'];

  function bloomBucket(g, env, kind, x, yb, salt, t) {
    var P = env.P;
    g.r(x, yb - 7, 9, 7, P.metal);
    g.r(x - 1, yb - 8, 11, 1, P.metalL);
    g.r(x + 8, yb - 7, 1, 7, P.metalD);
    var col = {
      rose: ['#d8384a', '#f0607a'],
      tulip: ['#f6a0c0', '#f6d04a'],
      sunflower: ['#f6c63a', '#7a4a2a'],
      daisy: ['#ffffff', '#ffe27a'],
      lavender: ['#a88ad8', '#c8a8f0'],
      baby: ['#ffffff', '#f0e8f0'],
      hydrangea: ['#8ab0f0', '#c0a0f0'],
      lily: ['#fff0e0', '#f6a03a']
    }[kind];
    var sw = Math.sin(t * 0.2 + salt) > 0.7 ? 1 : 0;
    for (var i = 0; i < 5; i++) {
      var fx = x + 1 + i * 2 + (i === 2 ? sw : 0);
      var fh = 5 + fl(hash(salt, i) * 5);
      g.r(fx, yb - 8 - fh, 1, fh, P.leafD);
      var fy = yb - 9 - fh;
      if (kind === 'lavender') {
        g.r(fx, fy - 3, 1, 4, col[i % 2]);
      } else if (kind === 'baby') {
        g.p(fx - 1, fy, col[0]);
        g.p(fx + 1, fy - 1, col[0]);
        g.p(fx, fy - 2, col[0]);
      } else if (kind === 'hydrangea') {
        g.ell(fx, fy, 2, 1, col[i % 2]);
      } else if (kind === 'sunflower') {
        g.r(fx - 1, fy - 1, 3, 3, col[0]);
        g.p(fx, fy, col[1]);
      } else {
        g.p(fx, fy, col[0]);
        g.p(fx - 1, fy, col[0]);
        g.p(fx + 1, fy, col[0]);
        g.p(fx, fy - 1, kind === 'daisy' || kind === 'lily' ? col[1] : col[0]);
        if (kind === 'rose' || kind === 'tulip') g.p(fx, fy, col[1]);
      }
    }
    g.p(x, yb - 10, P.leaf);
    g.p(x + 8, yb - 11, P.leaf);
  }

  SC['flower-shop'] = {
    still: 4,
    setup: function (env, R) {
      var b = [];
      for (var i = 0; i < 12; i++) b.push(R.pick(BLOOMS));
      return {
        wallStyle: R.pick([0, 1, 2]),
        blooms: b,
        hang: [R.ch(0.7), R.ch(0.5), R.ch(0.7)],
        tiles: R.pick(['paper', 'accent2', 'wood', 'leaf']),
        sign: R.pick(['flower', 'heart', 'sun', 'star']),
        counter: R.pick(['bouquet', 'pots', 'register']),
        floor: hp(R, env, 'floor', ['bigplant', 'basket', 'none', 'umbrella']),
        critter: hp(R, env, 'critter', ['kitty', 'butterfly', 'none', 'bird']),
        view: R.pick(['street', 'trees'])
      };
    },
    draw: function (g, env, L, t) {
      var P = env.P;
      var i;
      room(g, env, L, 64, { tiles: mix(P.floor, P[L.tiles], 0.55) });
      win(g, env, 6, 6, 40, 38, t, { panes: 2, view: L.view, sill: true, curtain: P.accent2, tie: true, cw: 6 });
      // shop sign
      g.r(70, 3, 52, 10, P.woodD);
      g.r(71, 4, 50, 8, P.paper);
      motif(g, L.sign === 'flower' ? 'flower' : L.sign, 74, 5, P.accent, P.leaf);
      textRow(g, 84, 6, 34, env.seed + 3, 0, P.ink);
      textRow(g, 84, 9, 26, env.seed + 3, 1, P.accentD);
      // hanging baskets
      [60, 132, 176].forEach(function (hx0, k) {
        if (!L.hang[k]) return;
        g.line(hx0, 0, hx0 - 3, 12, P.paperD);
        g.line(hx0, 0, hx0 + 3, 12, P.paperD);
        plant(g, env, hx0, 20, 'trail', t, k + 3);
      });
      // three-step flower stand
      for (var tier = 0; tier < 3; tier++) {
        var ty = 44 + tier * 9;
        var tx0 = 60 - tier * 6;
        var tw = 84 + tier * 12;
        g.r(tx0, ty, tw, 2, P.woodL);
        g.r(tx0, ty + 2, tw, 1, P.woodD);
        g.r(tx0 + 2, ty + 3, tw - 4, 6, mix(P.woodD, P.ink, 0.2));
        var n = tier === 0 ? 3 : 4;
        for (i = 0; i < n; i++) {
          var bx = tx0 + 4 + i * fl((tw - 8) / n) + (tier === 0 ? 6 : 0);
          bloomBucket(g, env, L.blooms[tier * 4 + i], bx, ty, tier * 4 + i + env.seed % 7, t);
        }
      }
      g.r(54, 71, 4, 7, P.woodDD);
      g.r(152, 71, 4, 7, P.woodDD);
      // counter
      g.r(158, 48, 34, 3, P.woodL);
      g.r(158, 51, 34, 1, P.woodD);
      g.r(160, 52, 32, 26, P.wood);
      g.r(162, 54, 28, 22, P.woodD);
      if (L.counter === 'bouquet') {
        for (i = 0; i < 6; i++) g.r(170 - fl(i / 2), 47 - i, 3 + i, 1, P.paper);
        g.r(170, 47, 3, 1, P.accent);
        for (i = 0; i < 5; i++) g.p(166 + i * 2, 40 - (i % 2), [P.accent, '#ffd25a', '#ffffff', P.accent2, P.accent][i]);
        g.p(167, 41, P.leaf);
        g.p(173, 41, P.leaf);
        g.r(180, 44, 6, 4, P.accent2);
        g.r(182, 43, 2, 1, P.accent2L);
      } else if (L.counter === 'pots') {
        plant(g, env, 166, 48, 'succulent', t, 1);
        plant(g, env, 178, 48, 'cactus', t, 2);
      } else {
        g.r(170, 40, 14, 8, P.metalD);
        g.r(171, 41, 12, 3, P.paper);
        g.r(172, 45, 10, 2, P.metal);
        g.em = 1;
        g.r(172, 42, 5, 1, '#6aff8a');
        g.em = 0;
      }
      if (L.floor !== 'none') floorThing(g, env, L.floor, 30, 77, t);
      if (L.critter !== 'none') critter(g, env, L.critter, L.critter === 'bird' ? 30 : 146, L.critter === 'bird' ? 44 : 78, t, L.critter !== 'bird');
      mascot(g, env, 8, 77, t, { sleep: false });
    }
  };

  var CONSTELLATIONS = [
    [[20, 10], [30, 6], [40, 9], [48, 4], [56, 12]],
    [[120, 8], [128, 14], [136, 10], [146, 16], [140, 22], [128, 20]],
    [[70, 6], [78, 12], [86, 8], [94, 14]],
    [[150, 6], [160, 4], [168, 10], [160, 14], [150, 12]]
  ];

  SC.observatory = {
    still: 9,
    outdoor: true,
    setup: function (env, R) {
      return {
        dome: R.pick(['left', 'right', 'none']),
        scope: R.pick(['paper', 'accent', 'accent2', 'metal']),
        planet: R.pick(['ringed', 'red', 'none', 'ringed', 'twin']),
        px: R.i(60, 150),
        cons: [R.i(0, 3), R.i(0, 3)],
        fore: R.pick(['blanket', 'chair', 'lantern', 'book', 'none']),
        critter: hp(R, env, 'critter', ['owl', 'kitty', 'none', 'bunny']),
        glow: R.ch(0.5),
        meteor: R.i(18, 40)
      };
    },
    draw: function (g, env, L, t) {
      var P = env.P;
      var x;
      var y;
      var dark = env.time !== 'day';
      g.em = 1;
      skyBg(g, env, 0, 0, W, 62);
      stars(g, env, 0, 0, W, 60, t);
      stars(g, env, 7, 3, W - 14, 40, t + 17);
      celestial(g, env, 0, 0, W, 50);
      if (dark) {
        for (var ci = 0; ci < 2; ci++) {
          var cs = CONSTELLATIONS[L.cons[ci]];
          if (ci === 1 && L.cons[1] === L.cons[0]) break;
          for (var k = 0; k + 1 < cs.length; k++) {
            var a = cs[k];
            var b = cs[k + 1];
            for (var s = 1; s < 8; s++) if (s % 2) g.b(a[0] + ((b[0] - a[0]) * s) / 8, a[1] + ((b[1] - a[1]) * s) / 8, '#aeb9ea', 0.35);
          }
          for (k = 0; k < cs.length; k++) {
            g.p(cs[k][0], cs[k][1], '#fff6dc');
            if (mod(t + k * 5, 30) < 2) halo(g, cs[k][0], cs[k][1], 0, 2, '#fff6dc', 0.7);
          }
        }
        var mp = mod(t, L.meteor);
        if (mp < 8) {
          var mx = 40 + ((env.seed * 7) % 100) + mp * 4;
          var my = 6 + mp * 2;
          for (var tl = 0; tl < 6; tl++) g.b(mx - tl * 2, my - tl, WHITE, 0.9 - tl * 0.15);
        }
      }
      if (L.planet !== 'none') {
        var px = L.px;
        if (L.planet === 'ringed') {
          disc(g, px, 18, 4, '#e8c890');
          g.r(px - 4, 17, 9, 1, '#d8a868');
          g.line(px - 8, 20, px + 8, 16, '#f0e0c0');
          g.line(px - 7, 21, px + 7, 17, '#c8b090');
        } else if (L.planet === 'red') {
          disc(g, px, 16, 3, '#e07a5a');
          g.p(px - 1, 15, '#f0a080');
          g.p(px + 1, 17, '#b85a3a');
        } else {
          disc(g, px, 14, 2, '#c8d8f0');
          disc(g, px + 7, 18, 1, '#f0d8c8');
        }
      }
      g.em = 0;
      clouds(g, env, 0, 0, W, 30, t);
      if (env.bd) distant(g, env, 0, 40, W, 22, env.bd, t);
      else if (L.glow) {
        for (x = 0; x < W; x++) {
          var hb = 56 + fl(hash(fl(x / 4), 9) * 4);
          for (y = hb; y < 62; y++) g.p(x, y, tone(env, mix(env.skyLow, '#4a536f', 0.6)));
          if (dark && hash(x, 3) < 0.25) g.p(x, hb + 1, env.litC);
        }
      }
      // hill
      for (x = 0; x < W; x++) {
        var hy = 60 - Math.sin((x / W) * PI) * 8 + Math.sin(x * 0.11) * 1;
        for (y = fl(hy); y < H; y++) g.p(x, y, (x * 7 + y * 13) % 19 === 0 ? P.leafD : (y < hy + 1 ? P.leafL : P.leaf));
      }
      if (snowy(env)) for (x = 0; x < W; x++) for (y = 70; y < H; y++) if (bay(x, y) < 0.5) g.p(x, y, '#eef3fa');
      if (L.dome !== 'none') {
        var dx = L.dome === 'left' ? 28 : 160;
        g.r(dx - 16, 38, 32, 18, P.paper);
        g.r(dx - 16, 38, 32, 1, P.paperD);
        g.r(dx + 12, 39, 4, 17, P.paperD);
        g.r(dx - 3, 46, 6, 10, P.woodD);
        for (y = 0; y < 16; y++) {
          var hw = rnd(17 * Math.sqrt(1 - Math.pow((15 - y) / 16, 2)));
          for (x = -hw; x <= hw; x++) g.p(dx + x, 22 + y, x > hw * 0.4 ? P.metalD : (x < -hw * 0.5 ? P.metalL : P.metal));
        }
        g.r(dx - 2, 22, 5, 16, mix(P.ink, '#101830', 0.5));
        g.line(dx, 30, dx + 10, 20, P.metalL);
        g.line(dx, 31, dx + 10, 21, P.metal);
        g.r(dx - 12, 42, 4, 4, dark ? env.litC : '#bfe0ee');
        if (dark) g.light(dx - 10, 44, 18, [1, 0.8, 0.5], 0.4, { n: 3 });
      }
      // telescope on tripod
      var sc = P[L.scope];
      var tx = 96;
      g.line(tx, 56, tx - 8, 74, P.ink);
      g.line(tx, 56, tx + 8, 74, P.ink);
      g.line(tx, 56, tx, 74, P.ink);
      for (var r = 0; r < 4; r++) g.line(tx - 12, 58 - r + 1, tx + 12, 46 - r + 1, r === 0 ? mix(sc, P.ink, 0.3) : (r === 3 ? mix(sc, WHITE, 0.3) : sc));
      g.r(tx + 10, 44, 3, 5, P.metalD);
      g.r(tx - 1, 54, 3, 3, P.metalD);
      var fx = 130;
      if (L.fore === 'blanket') {
        g.r(fx, 72, 28, 6, P.accent);
        for (x = fx; x < fx + 28; x += 4) g.r(x, 72, 2, 6, P.paper);
        cupOf(g, env, 'mug', fx + 18, 73, P.paper, t);
      } else if (L.fore === 'chair') {
        g.r(fx + 4, 64, 14, 4, P.accent2);
        g.r(fx + 4, 58, 2, 8, P.accent2D);
        g.line(fx + 5, 68, fx + 16, 76, P.metalD);
        g.line(fx + 16, 68, fx + 5, 76, P.metalD);
      } else if (L.fore === 'lantern') {
        streetThing(g, env, 'lantern', fx, 76, t);
      } else if (L.fore === 'book') {
        g.r(fx + 2, 72, 14, 4, P.paper);
        g.r(fx + 9, 72, 1, 4, P.paperD);
        textRow(g, fx + 3, 73, 5, env.seed, 2, P.ink);
        g.r(fx + 18, 74, 4, 2, P.accent);
      }
      if (L.critter === 'owl') {
        var ox = L.dome === 'right' ? 20 : 172;
        g.r(ox + 2, 58, 1, 18, P.woodDD);
        g.r(ox, 51, 6, 7, '#8a6a4a');
        g.p(ox + 1, 53, dark ? '#ffe08a' : P.ink);
        g.p(ox + 4, 53, dark ? '#ffe08a' : P.ink);
        g.p(ox + 2, 54, '#f0a23a');
      } else if (L.critter !== 'none') {
        critter(g, env, L.critter, 60, 74, t, false);
      }
      mascot(g, env, 104, 75, t, { sleep: false });
      weather(g, env, 0, 0, W, H, t);
    }
  };

  var BLANKETS = ['plain', 'stripes', 'check', 'dots', 'quilt', 'hearts'];

  SC.bedroom = {
    still: 7,
    setup: function (env, R) {
      var left = R.ch(0.5);
      return {
        wallStyle: R.i(0, 3),
        left: left,
        shape: R.pick(['rect', 'arch', 'round']),
        view: R.pick(['trees', 'city', 'hills']),
        cur: R.pick(['accent2', 'accent', 'paper']),
        blanket: R.pick(BLANKETS),
        bc: R.pick(['accent', 'accent2', 'leaf', 'rug']),
        art: [hp(R, env, 'wall', WALL_ART), hp(R, env, 'wall', ['poster', 'poster', 'photos', 'pennant', 'neon', 'mirror'])],
        fairy: R.ch(0.55),
        lampC: R.pick(['accent', 'accent2', 'paper']),
        nTr: hp(R, env, 'trinket', TRINKETS),
        floor: hp(R, env, 'floor', FLOOR_THINGS.concat(['none'])),
        rug: R.pick(['rug', 'accent2', 'paper']),
        critter: hp(R, env, 'critter', ['kitty', 'none', 'pup', 'none'])
      };
    },
    draw: function (g, env, L, t) {
      var P = env.P;
      var x;
      var y;
      room(g, env, L, 62);
      var wx = L.left ? 14 : 142;
      win(g, env, wx, 8, 34, 30, t, { shape: L.shape, panes: 4, view: L.view, curtain: P[L.cur], tie: true, cw: 7 });
      if (L.fairy) stringLights(g, env, 2, t, 3, 7, ['#ffe08a', '#fff6c0']);
      var bx = L.left ? 96 : 20;
      // wall art over the bed and beside it
      wallArt(g, env, L.art[0], bx + 24, 12, t, 1);
      wallArt(g, env, L.art[1], L.left ? 62 : 110, 14, t, 2);
      g.ell(96, 72, 56, 5, P[L.rug]);
      g.ell(96, 72, 52, 4, mix(P[L.rug], WHITE, 0.25));
      // bed
      var bw = 76;
      var hbx = L.left ? bx + bw - 4 : bx;
      g.r(hbx, 34, 4, 32, P.woodD);
      g.r(hbx - 1, 33, 6, 2, P.woodL);
      g.r(bx, 52, bw, 6, P.paper);
      g.r(bx, 58, bw, 8, P.woodD);
      g.r(bx, 58, bw, 1, P.woodL);
      g.r(bx + 1, 66, 3, 10, P.woodDD);
      g.r(bx + bw - 4, 66, 3, 10, P.woodDD);
      var pxw = L.left ? bx + bw - 20 : bx + 5;
      g.r(pxw, 46, 15, 7, P.paper);
      g.r(pxw, 46, 15, 1, WHITE);
      g.r(pxw, 52, 15, 1, P.paperD);
      // blanket
      var bc = P[L.bc];
      var bL = mix(bc, WHITE, 0.35);
      var bD = mix(bc, P.ink, 0.25);
      var b0 = L.left ? bx : bx + 22;
      var b1 = L.left ? bx + bw - 22 : bx + bw;
      for (y = 50; y < 64; y++) {
        for (x = b0; x < b1; x++) {
          var c = bc;
          var u = x - b0;
          var v = y - 50;
          if (L.blanket === 'stripes') c = (fl(u / 4) & 1) ? bL : bc;
          else if (L.blanket === 'check') c = ((fl(u / 4) + fl(v / 4)) & 1) ? bL : bc;
          else if (L.blanket === 'dots') c = u % 5 === 2 && v % 4 === 1 ? P.paper : bc;
          else if (L.blanket === 'quilt') c = [bc, bL, P.accent2L, bD][(fl(u / 6) + fl(v / 5) * 3) % 4];
          else if (L.blanket === 'hearts') c = (u % 8 === 3 || u % 8 === 5) && v % 6 === 2 ? P.paper : (u % 8 === 4 && v % 6 === 3 ? P.paper : bc);
          if (y === 50) c = bL;
          if (y >= 62) c = bD;
          g.p(x, y, c);
        }
      }
      g.r(L.left ? b1 - 1 : b0, 50, 1, 14, bD);
      // nightstand + lamp
      var nx = L.left ? bx + bw + 2 : bx - 20;
      if (nx < 2) nx = bx + bw + 2;
      g.r(nx, 50, 18, 3, P.woodL);
      g.r(nx + 1, 53, 16, 13, P.wood);
      g.r(nx + 3, 56, 12, 1, P.woodD);
      g.r(nx + 8, 58, 2, 1, P.metalL);
      g.r(nx + 2, 66, 2, 8, P.woodDD);
      g.r(nx + 14, 66, 2, 8, P.woodDD);
      lamp(g, env, nx + 5, 50, { color: P[L.lampC], h: 6, r: 38 });
      trinket(g, env, L.nTr, nx + 10, 50, t);
      var fx = L.left ? 64 : 136;
      if (L.floor !== 'none') floorThing(g, env, L.floor, fx, 76, t);
      if (L.critter !== 'none') critter(g, env, L.critter, L.left ? 40 : 150, 77, t, L.left);
      mascot(g, env, L.left ? bx + 30 : bx + 36, 51, t, { flip: L.left });
    }
  };

  var STALLS = ['fruit', 'veg', 'fish', 'bread', 'flowers', 'lanterns', 'toys', 'skewer', 'tteok'];

  function goods(g, env, kind, x, y, w, t) {
    var P = env.P;
    var i;
    for (i = 0; i < w; i += 7) {
      var gx = x + i;
      if (kind === 'fruit') {
        var fc = ['#e8483a', '#f6a03a', '#f6d04a', '#9ad04a'][fl(i / 7) % 4];
        g.ell(gx + 3, y - 2, 2, 2, fc);
        g.ell(gx + 1, y - 4, 1, 1, fc);
        g.p(gx + 2, y - 4, mix(fc, WHITE, 0.4));
      } else if (kind === 'veg') {
        if (fl(i / 7) % 2) {
          g.r(gx, y - 2, 6, 2, '#f08a3a');
          g.p(gx + 6, y - 2, '#5fb05a');
        } else {
          g.ell(gx + 3, y - 2, 2, 2, '#8ac860');
          g.p(gx + 3, y - 5, '#5a9a3a');
        }
      } else if (kind === 'fish') {
        g.r(gx, y - 2, 5, 2, '#a8c0d8');
        g.p(gx + 5, y - 1, '#a8c0d8');
        g.p(gx + 6, y - 2, '#a8c0d8');
        g.p(gx + 1, y - 2, P.ink);
        g.r(gx - 1, y - 1, 7, 1, '#e8f4fa');
      } else if (kind === 'bread') {
        pastry(g, env, fl(i / 7) % 4, gx - 1, y - 2);
      } else if (kind === 'flowers') {
        bloomBucket(g, env, BLOOMS[(fl(i / 7) + env.seed) % BLOOMS.length], gx - 1, y, i, t);
      } else if (kind === 'toys') {
        var tc = [P.accent, P.accent2, '#f6d04a'][fl(i / 7) % 3];
        g.r(gx, y - 4, 5, 4, tc);
        g.r(gx + 1, y - 6, 3, 2, tc);
        g.p(gx + 1, y - 5, P.ink);
        g.p(gx + 3, y - 5, P.ink);
      } else if (kind === 'skewer') {
        g.line(gx + 1, y, gx + 4, y - 7, P.woodD);
        g.r(gx + 2, y - 5, 2, 2, '#c85a3a');
        g.r(gx + 3, y - 7, 2, 2, '#a84a2a');
      } else if (kind === 'tteok') {
        g.r(gx, y - 2, 6, 2, '#e8483a');
        g.r(gx + 1, y - 3, 1, 1, '#ff7a5a');
        g.r(gx + 4, y - 3, 1, 1, '#ff7a5a');
      } else {
        g.ell(gx + 3, y - 3, 2, 3, i % 14 ? P.accent : '#f6d04a');
        g.r(gx + 2, y - 7, 3, 1, P.ink);
      }
    }
    if (kind === 'skewer' || kind === 'tteok') steam(g, x + fl(w / 2), y - 8, t, 9, x);
  }

  SC['street-market'] = {
    still: 5,
    outdoor: true,
    setup: function (env, R) {
      var st = [];
      for (var i = 0; i < 3; i++) st.push({ k: R.pick(STALLS), c: R.pick(['accent', 'accent2', 'leaf', 'rug']) });
      return {
        stalls: st,
        lanterns: R.ch(0.7),
        crates: R.pick(['crates', 'cart', 'barrel', 'none']),
        walkers: R.i(0, 2),
        critter: hp(R, env, 'critter', ['pup', 'kitty', 'bird', 'none']),
        spot: R.i(0, 2)
      };
    },
    draw: function (g, env, L, t) {
      var P = env.P;
      var x;
      var y;
      skyTop(g, env, 46, t, 22);
      distant(g, env, 0, 6, W, 42, bk(env, 'street'), t);
      // cobbles
      var cob = mix(P.paper, P.metal, 0.5);
      for (y = 48; y < H; y++) {
        for (x = 0; x < W; x++) {
          var row = fl((y - 48) / 3);
          var edge = (y - 48) % 3 === 2 || mod(x + row * 3, 7) === 0;
          g.p(x, y, edge ? mix(cob, P.ink, 0.25) : cob);
        }
      }
      if (L.lanterns) {
        for (x = 0; x < W; x++) {
          var sag = rnd(Math.abs(Math.sin((x / 64) * PI)) * 5);
          g.p(x, 8 + sag, P.ink);
          if (x % 12 === 6) {
            g.r(x - 1, 9 + sag, 3, 4, ['#e8483a', '#f6a03a'][fl(x / 12) % 2]);
            g.p(x, 13 + sag, '#f6d04a');
            if (env.time !== 'day') {
              g.em = 1;
              g.p(x, 10 + sag, '#ffe08a');
              g.em = 0;
            }
          }
        }
      }
      for (var s = 0; s < 3; s++) {
        var st = L.stalls[s];
        var sx = 6 + s * 62;
        var c1 = P[st.c];
        g.r(sx + 1, 32, 1, 34, P.woodDD);
        g.r(sx + 52, 32, 1, 34, P.woodDD);
        awning(g, env, sx - 2, 26, 58, c1, P.paper, 5);
        g.r(sx, 52, 54, 3, P.woodL);
        g.r(sx, 55, 54, 10, P.woodD);
        for (x = sx + 2; x < sx + 52; x += 6) g.r(x, 56, 1, 9, P.woodDD);
        g.r(sx + 4, 40, 18, 7, P.woodL);
        textRow(g, sx + 6, 43, 14, env.seed + s, s, P.ink);
        goods(g, env, st.k, sx + 4, 52, 46, t);
        if (env.time !== 'day') {
          g.em = 1;
          g.r(sx + 26, 33, 3, 2, '#fff1c2');
          g.em = 0;
          g.light(sx + 27, 34, 34, env.T.lamp, env.lampK * 0.8, { top: 33 });
        }
      }
      // passers-by
      for (var wk = 0; wk < L.walkers; wk++) {
        var dir = wk ? -1 : 1;
        var wxp = mod(wk * 90 + t * 0.6 * dir, W + 20) - 10;
        var leg = mod(t, 4) < 2;
        var cc = [P.accent2D, P.accentD][wk];
        g.r(wxp, 66, 5, 7, cc);
        g.ell(wxp + 2, 63, 2, 2, '#f0c8a0');
        g.r(wxp, 61, 5, 1, P.ink);
        g.r(wxp + (leg ? 0 : 1), 73, 1, 4, P.ink);
        g.r(wxp + (leg ? 4 : 3), 73, 1, 4, P.ink);
      }
      if (L.crates === 'crates') {
        g.r(160, 68, 14, 10, '#c8955a');
        g.r(160, 68, 14, 1, '#e0b47a');
        g.r(166, 68, 2, 10, '#a87a42');
        goods(g, env, 'fruit', 161, 68, 12, t);
      } else if (L.crates !== 'none') {
        streetThing(g, env, L.crates === 'cart' ? 'cart' : 'barrel', 158, 78, t);
      }
      if (L.critter !== 'none') critter(g, env, L.critter, L.critter === 'bird' ? 100 : 130, L.critter === 'bird' ? 26 : 78, t, true);
      mascot(g, env, 20 + L.spot * 62, 77, t, { sleep: false });
      weather(g, env, 0, 0, W, H, t);
    }
  };

  SC.riverside = {
    still: 2,
    outdoor: true,
    setup: function (env, R) {
      return {
        far: R.pick(['trees', 'city', 'hills']),
        farT: R.pick(FAR.concat(['none', 'none'])),
        ftx: R.i(20, 170),
        bridge: R.pick(['arch', 'truss', 'foot', 'none']),
        boats: [R.pick(BOATS), R.pick(BOATS.concat(['none', 'none']))],
        bank: R.pick(STREET),
        tree: R.ch(0.7),
        tl: R.ch(0.5),
        critter: hp(R, env, 'critter', ['duck', 'duck', 'pup', 'none', 'bird']),
        rod: R.ch(0.45),
        fly: hp(R, env, 'fly', FLYERS)
      };
    },
    draw: function (g, env, L, t) {
      var P = env.P;
      var x;
      skyTop(g, env, 42, t, 24);
      flyer(g, env, L.fly, 130, 10, t);
      distant(g, env, 0, 14, W, 28, bk(env, L.far), t);
      if (L.farT !== 'none') farThing(g, env, L.farT, L.ftx, 40, t);
      water(g, env, 40, 62, t, mix(env.skyMid, '#1a4a7a', 0.55), mix(env.skyLow, '#4a9ab8', 0.5));
      g.r(0, 40, W, 1, mix(env.skyLow, WHITE, 0.25));
      if (env.weather !== 'rain' && env.weather !== 'snow') {
        var sx = fl(env.sunX * W);
        g.em = 1;
        for (var ry = 42; ry < 58; ry += 2) if (mod(ry + fl(t / 2), 4) < 3) g.r(sx - 1 + rnd(Math.sin(t * 0.4 + ry) * 2), ry, 3, 1, env.time === 'night' ? '#fff1c6' : (env.time === 'day' ? '#ffffff' : '#ffd27a'));
        g.em = 0;
      }
      if (L.bridge === 'arch') {
        var stone = mix(P.paper, P.metal, 0.5);
        for (x = 20; x < 172; x++) {
          var u = mod(x - 20, 38);
          var ah = u < 5 ? 0 : rnd(Math.sqrt(Math.max(0, 1 - Math.pow((u - 21.5) / 16.5, 2))) * 13);
          for (var y = 30; y < 52 - ah; y++) g.p(x, y, y < 32 ? mix(stone, WHITE, 0.2) : (mod(x + (y >> 1) * 3, 6) === 0 || y % 4 === 0 ? mix(stone, P.ink, 0.2) : stone));
        }
        g.r(18, 27, 156, 3, mix(stone, P.ink, 0.15));
        for (x = 20; x < 172; x += 6) g.r(x, 24, 1, 3, mix(stone, P.ink, 0.25));
        g.r(18, 24, 156, 1, mix(stone, P.ink, 0.25));
      } else if (L.bridge === 'truss') {
        g.r(0, 32, W, 3, P.metalD);
        for (x = 0; x < W; x += 16) {
          g.line(x, 32, x + 8, 20, P.accent);
          g.line(x + 8, 20, x + 16, 32, P.accent);
        }
        g.r(0, 20, W, 1, P.accent);
        g.r(40, 35, 4, 18, P.metalD);
        g.r(148, 35, 4, 18, P.metalD);
        if (env.time !== 'day') {
          g.em = 1;
          for (x = 8; x < W; x += 32) g.p(x, 31, '#ffe08a');
          g.em = 0;
        }
      } else if (L.bridge === 'foot') {
        for (x = 0; x < W; x++) g.p(x, 44 + rnd(Math.sin((x / W) * PI) * -4), P.woodD);
        for (x = 0; x < W; x++) g.p(x, 45 + rnd(Math.sin((x / W) * PI) * -4), P.woodL);
        for (x = 4; x < W; x += 8) {
          var by = 44 + rnd(Math.sin((x / W) * PI) * -4);
          g.r(x, by - 5, 1, 5, P.woodDD);
          g.r(x, by + 1, 1, 60 - by, mix(P.woodDD, '#1a4a7a', 0.4));
        }
        for (x = 0; x < W; x++) g.p(x, 39 + rnd(Math.sin((x / W) * PI) * -4), P.woodDD);
      }
      boat(g, env, L.boats[0], mod(30 + t * 0.2, W + 40) - 20, 52, t);
      if (L.boats[1] !== 'none') boat(g, env, L.boats[1], mod(150 - t * 0.12, W + 40) - 20, 47, t);
      if (L.critter === 'duck') {
        critter(g, env, 'duck', mod(100 - t * 0.15, W), 58, t, true);
        critter(g, env, 'duck', mod(112 - t * 0.15, W), 59, t, true);
      }
      // near bank
      for (x = 0; x < W; x++) {
        var sy = 60 + rnd(Math.sin(x * 0.06) * 1.5);
        g.p(x, sy - 1, mix(env.skyLow, WHITE, 0.35));
        for (var yy = sy; yy < H; yy++) g.p(x, yy, yy < sy + 2 ? P.leafD : ((x * 7 + yy * 13) % 17 === 0 ? P.leafD : P.leaf));
      }
      if (snowy(env)) for (x = 0; x < W; x++) for (yy = 64; yy < H; yy++) if (bay(x, yy) < 0.6) g.p(x, yy, '#eef3fa');
      var path = mix(P.paper, P.woodL, 0.5);
      g.r(0, 68, W, 5, path);
      g.r(0, 68, W, 1, mix(path, P.ink, 0.15));
      flowerDots(g, env, 0, 62, W, 5, 14, 4);
      if (L.tree) tree(g, env, L.tl ? 20 : 172, 68, 10, t);
      streetThing(g, env, L.bank, L.tl ? 150 : 30, 70, t);
      if (L.critter !== 'duck' && L.critter !== 'none') critter(g, env, L.critter, 120, 78, t, false);
      var mx = 92;
      if (L.rod) {
        g.line(mx + 12, 60, mx + 28, 46, P.woodDD);
        g.line(mx + 28, 46, mx + 30, 56 + rnd(Math.sin(t * 0.2)), mix(WHITE, P.ink, 0.3));
        g.p(mx + 30, 57 + rnd(Math.sin(t * 0.2)), P.accent);
      }
      mascot(g, env, mx, 64, t, { sleep: false });
      weather(g, env, 0, 0, W, H, t);
    }
  };

  SC.greenhouse = {
    still: 6,
    setup: function (env, R) {
      var pots = [];
      for (var i = 0; i < 10; i++) pots.push(hp(R, env, 'plant', PLANTS2.concat(['bush', 'flower', 'cactus', 'tall'])));
      return {
        view: R.pick(['trees', 'hills', 'trees']),
        pots: pots,
        big: R.pick(['palm', 'banana', 'cactus', 'none']),
        bigX: R.pick([96, 96, 30, 162]),
        hang: [R.ch(0.7), R.ch(0.7), R.ch(0.5)],
        mist: R.ch(0.5),
        tool: R.pick(['can', 'hose', 'wheelbarrow', 'none']),
        critter: hp(R, env, 'critter', ['butterfly', 'butterfly', 'snail', 'ladybug', 'kitty']),
        frame: R.pick(['paper', 'metal', 'leafD'])
      };
    },
    draw: function (g, env, L, t) {
      var P = env.P;
      var x;
      var y;
      g.em = 1;
      outside(g, env, 0, 0, W, 62, t, env.bd || (snowy(env) ? 'hills' : L.view));
      g.em = 0;
      // glass sheen
      for (var k = 0; k < 60; k++) {
        g.b(20 + k, 60 - k, WHITE, 0.08);
        g.b(120 + k, 60 - k, WHITE, 0.08);
      }
      // frame: gable roof + mullions
      var fc = P[L.frame];
      var fD = mix(fc, P.ink, 0.3);
      for (x = 0; x < W; x++) {
        var ry = rnd(14 - Math.abs(x - 96) * 0.14);
        g.r(x, ry, 1, 2, fc);
        g.p(x, ry + 2, fD);
      }
      for (x = 0; x < W; x += 24) {
        var top = rnd(14 - Math.abs(x - 96) * 0.14);
        g.r(x, top, 2, 62 - top, fc);
        g.r(x + 1, top + 2, 1, 60 - top, fD);
      }
      g.r(0, 22, W, 1, fc);
      g.r(0, 40, W, 1, fc);
      g.r(95, 0, 2, 62, fc);
      g.light(96, 30, 110, env.T.win, env.winK * 1.2, { sy: 0.8 });
      // brick floor
      for (y = 62; y < H; y++) {
        var row = fl((y - 62) / 3);
        for (x = 0; x < W; x++) {
          var edge = (y - 62) % 3 === 2 || mod(x + row * 4, 8) === 0;
          g.p(x, y, edge ? P.floorD : mix(P.floor, '#c8704a', 0.35));
        }
      }
      if (L.big !== 'none') {
        var bx = L.bigX;
        g.r(bx - 7, 54, 15, 10, P.pot);
        g.r(bx - 8, 53, 17, 2, P.potL);
        g.r(bx + 6, 55, 2, 9, P.potD);
        if (L.big === 'palm') {
          for (y = 22; y < 53; y++) g.p(bx + rnd(Math.sin(y * 0.2) * 1), y, y % 3 ? P.woodD : P.woodDD);
          for (var f = 0; f < 6; f++) {
            var a = -PI + (f / 5) * PI + Math.sin(t * 0.1 + f) * 0.05;
            for (var s = 0; s < 14; s++) {
              var px = bx + Math.cos(a) * s;
              var py = 22 + Math.sin(a) * s * 0.5 + (s * s) / 28;
              g.p(px, py, f % 2 ? P.leaf : P.leafD);
              if (s > 3 && s % 2) g.p(px, py + 1, P.leafL);
            }
          }
        } else if (L.big === 'banana') {
          g.r(bx - 1, 30, 3, 23, P.leafD);
          for (var lf = 0; lf < 4; lf++) {
            var dir = lf % 2 ? 1 : -1;
            var ly = 26 + lf * 6;
            for (s = 0; s < 14; s++) g.r(bx + dir * s, ly - fl(s * 0.4) + fl((s * s) / 40), 1, 4 - fl(s / 5), lf % 2 ? P.leaf : P.leafL);
          }
        } else {
          g.r(bx - 2, 24, 5, 29, P.leaf);
          g.r(bx + 2, 24, 1, 29, P.leafD);
          g.r(bx - 8, 34, 6, 3, P.leaf);
          g.r(bx - 8, 28, 3, 6, P.leaf);
          g.r(bx + 3, 38, 5, 3, P.leafD);
          g.r(bx + 6, 31, 3, 7, P.leafD);
          g.p(bx, 23, '#ff8fb0');
        }
      }
      // plant benches
      [[4, 70], [118, 70]].forEach(function (bn, bi) {
        var b0 = bn[0];
        var bw = bn[1];
        g.r(b0, 48, bw, 2, P.woodL);
        g.r(b0, 50, bw, 1, P.woodD);
        g.r(b0 + 2, 51, 2, 24, P.woodDD);
        g.r(b0 + bw - 4, 51, 2, 24, P.woodDD);
        g.r(b0 + 2, 64, bw - 4, 1, P.woodD);
        for (var i = 0; i < 5; i++) {
          var px0 = b0 + 7 + i * 14;
          if (Math.abs(px0 - L.bigX) < 10 && L.big !== 'none') continue;
          plant(g, env, px0, 48, L.pots[bi * 5 + i], t, i + bi * 5);
        }
      });
      [40, 150, 72].forEach(function (hx0, k) {
        if (!L.hang[k]) return;
        var ty = rnd(14 - Math.abs(hx0 - 96) * 0.14) + 2;
        g.line(hx0, ty, hx0 - 3, ty + 8, P.paperD);
        g.line(hx0, ty, hx0 + 3, ty + 8, P.paperD);
        plant(g, env, hx0, ty + 16, 'trail', t, k + 9);
      });
      if (L.mist) {
        g.r(0, 6, W, 1, P.metalD);
        for (var m = 0; m < 4; m++) {
          var nx = 24 + m * 48;
          g.r(nx, 7, 1, 2, P.metalD);
          if (mod(t + m * 7, 40) < 14) {
            for (var d = 0; d < 10; d++) g.b(nx + rnd((hash(d, m) - 0.5) * 10), 9 + mod(d * 3 + t, 12), WHITE, 0.35);
          }
        }
      }
      if (L.tool === 'can') {
        g.r(70, 70, 10, 7, P.accent2);
        g.r(70, 70, 10, 1, P.accent2L);
        g.line(80, 72, 85, 67, P.accent2D);
        g.line(71, 69, 75, 66, P.accent2D);
        g.line(75, 66, 79, 69, P.accent2D);
      } else if (L.tool === 'hose') {
        for (var hh = 0; hh < 18; hh++) {
          var a2 = (hh / 18) * 2 * PI;
          g.p(78 + rnd(Math.cos(a2) * 7), 72 + rnd(Math.sin(a2) * 3), '#4a9a5a');
          g.p(78 + rnd(Math.cos(a2) * 5), 72 + rnd(Math.sin(a2) * 2), '#3a8a4a');
        }
      } else if (L.tool === 'wheelbarrow') {
        g.r(64, 66, 18, 6, P.accent);
        g.r(64, 66, 18, 1, P.accentL);
        g.ell(68, 75, 2, 2, P.ink);
        g.line(82, 68, 90, 72, P.woodD);
        g.ell(73, 65, 6, 2, '#6a4a32');
      }
      critter(g, env, L.critter, L.critter === 'butterfly' ? 50 : 30, L.critter === 'butterfly' ? 34 : 48, t, false);
      if (L.critter === 'butterfly') critter(g, env, 'butterfly', 140, 30, t + 13, false);
      mascot(g, env, 100, 78, t, { sleep: false });
    }
  };

  // ---- extra slots for the first 14 scenes (D-129)
  //
  // 원래 그림의 빈자리에만 그린다. 칸마다 'none'이 섞여 있어 예전 그림 그대로인 배치도 남는다.

  function opt(R, arr, none) {
    return R.ch(none) ? 'none' : R.pick(arr);
  }
  function addMore(key, extra, more) {
    SC[key].extra = extra;
    SC[key].more = more;
  }
  // ph 0은 뒤쪽(먼 풍경), 1은 앞쪽 소품. 장면이 그리는 순서 중간에 끼워 넣는다
  function moreOf(g, env, L, t, key, ph) {
    if (L.x) SC[key].more(g, env, L.x, t, L, ph || 1);
  }
  var SMALL_ART = ['poster', 'calendar', 'clock', 'pennant', 'neon', 'mirror'];
  var SAND = ['castle', 'ball', 'bucket', 'crab', 'shells', 'none'];

  addMore(
    'study',
    function (env, R) {
      return { under: hopt(R, env, 'floor', ['basket', 'books', 'catbed', 'pouf', 'bag', 'boxes'], 0.3), critter: hopt(R, env, 'critter', ['kitty', 'mouse', 'snail'], 0.6), desk: hopt(R, env, 'trinket', TRINKETS, 0.35), plant: hopt(R, env, 'plant', PLANTS2.concat(['flower', 'cactus']), 0.5) };
    },
    function (g, env, X, t, L) {
      if (X.under !== 'none') floorThing(g, env, X.under, 40, 78, t);
      if (X.critter !== 'none') critter(g, env, X.critter, 80, 78, t, false);
      if (X.desk !== 'none' && L.spot !== 'desk') trinket(g, env, X.desk, 6, 51, t);
      if (X.plant !== 'none') plant(g, env, 106, 79, X.plant, t, 3);
    }
  );

  addMore(
    'desk',
    function (env, R) {
      return { wall: hopt(R, env, 'wall', ['poster', 'calendar', 'cork', 'photos', 'neon', 'mirror', 'pennant'], 0.3), desk: hopt(R, env, 'trinket', TRINKETS, 0.3), under: hopt(R, env, 'floor', ['bag', 'basket', 'boxes', 'catbed', 'books', 'skate'], 0.4), critter: hopt(R, env, 'critter', ['kitty', 'mouse'], 0.7) };
    },
    function (g, env, X, t) {
      if (X.wall !== 'none') wallArt(g, env, X.wall, 127, 24, t, 11);
      if (X.desk !== 'none') trinket(g, env, X.desk, 148, 53, t);
      if (X.under !== 'none') floorThing(g, env, X.under, 118, 79, t);
      if (X.critter !== 'none') critter(g, env, X.critter, 54, 79, t, false);
    }
  );

  addMore(
    'kitchen',
    function (env, R) {
      return { a: hopt(R, env, 'trinket', ['fruit', 'vase', 'succulent', 'radio', 'alarm', 'candle'], 0.3), b: hopt(R, env, 'cup', CUPS, 0.35), wall: hopt(R, env, 'wall', ['calendar', 'clock', 'poster', 'herbs'], 0.35) };
    },
    function (g, env, X, t) {
      if (X.a !== 'none') trinket(g, env, X.a, 108, 51, t);
      if (X.b !== 'none') cupOf(g, env, X.b, 124, 51, env.P.paper, t);
      if (X.wall === 'herbs') {
        g.r(176, 4, 14, 1, env.P.woodD);
        for (var i = 0; i < 3; i++) {
          g.line(178 + i * 5, 5, 178 + i * 5, 9, env.P.paperD);
          g.ell(178 + i * 5, 12, 2, 3, i % 2 ? env.P.leaf : env.P.leafD);
        }
      } else if (X.wall !== 'none') wallArt(g, env, X.wall, 176, 6, t, 12);
    }
  );

  addMore(
    'cafe',
    function (env, R) {
      return { sill: hopt(R, env, 'trinket', ['succulent', 'vase', 'candle', 'figure', 'books', 'frame'], 0.3), sill2: hopt(R, env, 'plant', PLANTS2, 0.5), critter: hopt(R, env, 'critter', ['kitty', 'pup', 'bird'], 0.55) };
    },
    function (g, env, X, t, L) {
      if (X.sill !== 'none') trinket(g, env, X.sill, 66, 49, t);
      if (X.sill2 !== 'none' && L.spot !== 'sill') plant(g, env, 112, 49, X.sill2, t, 4);
      if (X.critter !== 'none') critter(g, env, X.critter, 112, 78, t, true);
    }
  );

  addMore(
    'sky',
    function (env, R) {
      return { fly: hopt(R, env, 'fly', ['kite', 'blimp', 'lanterns', 'flock', 'balloon'], 0.3), fx: R.i(90, 130) };
    },
    function (g, env, X, t) {
      if (X.fly !== 'none') flyer(g, env, X.fly, X.fx, 12, t);
    }
  );

  addMore(
    'stage',
    function (env, R) {
      return { poster: hopt(R, env, 'wall', ['poster', 'neon', 'banner'], 0.35), crowd: opt(R, ['heads', 'sticks'], 0.4), disco: R.ch(0.4) };
    },
    function (g, env, X, t) {
      var P = env.P;
      if (X.poster !== 'none') wallArt(g, env, X.poster, 38, 6, t, 13);
      if (X.disco) {
        g.r(112, 0, 1, 6, P.ink);
        disc(g, 112, 9, 3, P.metal);
        g.em = 1;
        for (var i = 0; i < 4; i++) {
          var sx = 110 + ((i * 2 + fl(t / 2)) % 5);
          var sy = 7 + ((i * 3 + fl(t / 2)) % 5);
          g.p(sx, sy, WHITE);
        }
        for (i = 0; i < 6; i++) g.p(mod(i * 33 + t * 3, W), 14 + mod(i * 13 + t, 40), mix(WHITE, P.accent2, 0.3));
        g.em = 0;
      }
      if (X.crowd !== 'none') {
        for (var x = 4; x < W; x += 9) {
          var bob = mod(t + x, 4) < 2 ? 0 : 1;
          var hc = mix(P.ink, '#101018', 0.4);
          g.ell(x + 3, 77 + bob, 3, 3, hc);
          g.r(x, 79, 7, 1, hc);
          if (X.crowd === 'sticks' && x % 27 === 4) {
            g.em = 1;
            g.r(x + 5, 69 + bob, 1, 6, hc);
            g.p(x + 5, 68 + bob, mod(t + x, 6) < 3 ? '#fff6a0' : P.accent2L);
            g.em = 0;
          }
        }
      }
    }
  );

  addMore(
    'photo',
    function (env, R) {
      return { boat: opt(R, ['row', 'swan', 'kayak', 'sail'], 0.4), bx: R.i(30, 110), shore: opt(R, ['picnic', 'tent', 'sign', 'bench'], 0.4), fly: hopt(R, env, 'fly', ['balloon', 'flock', 'kite'], 0.6) };
    },
    function (g, env, X, t) {
      var P = env.P;
      if (X.fly !== 'none') flyer(g, env, X.fly, 60, 10, t);
      if (X.boat !== 'none') boat(g, env, X.boat, X.bx, 58, t);
      if (X.shore === 'picnic') {
        g.r(36, 72, 22, 5, P.accent);
        for (var x = 36; x < 58; x += 4) g.r(x, 72, 2, 5, P.paper);
        floorThing(g, env, 'basket', 44, 73, t);
      } else if (X.shore === 'tent') {
        for (var y = 0; y < 10; y++) g.r(48 - y, 66 + y, y * 2 + 1, 1, y < 1 ? P.accent2L : P.accent2);
        g.r(47, 71, 3, 5, mix(P.accent2, P.ink, 0.4));
      } else if (X.shore !== 'none') streetThing(g, env, X.shore, 40, 76, t);
    }
  );

  addMore(
    'game',
    function (env, R) {
      return { floor: hopt(R, env, 'floor', ['catbed', 'pouf', 'boxes', 'books', 'skate', 'bag'], 0.35), wall: hopt(R, env, 'wall', ['shelf', 'pennant', 'clock', 'photos', 'neon', 'calendar'], 0.35), snack: hopt(R, env, 'cup', ['iced', 'bottle', 'glass'], 0.5) };
    },
    function (g, env, X, t) {
      if (X.wall !== 'none') wallArt(g, env, X.wall, 128, 10, t, 14);
      if (X.floor !== 'none') floorThing(g, env, X.floor, 140, 74, t);
      if (X.snack !== 'none') cupOf(g, env, X.snack, 50, 70, env.P.accent, t);
    }
  );

  addMore(
    'park',
    function (env, R) {
      return { critter: hopt(R, env, 'critter', ['pup', 'squirrel', 'bunny', 'duck', 'hedgehog'], 0.35), street: opt(R, ['mailbox', 'hydrant', 'bike', 'sign', 'barrel', 'bin'], 0.4), far: opt(R, ['windmill', 'church', 'tower', 'house'], 0.6), fx: R.i(40, 150), fly: hopt(R, env, 'fly', ['kite', 'balloon', 'flock'], 0.6) };
    },
    function (g, env, X, t, L, ph) {
      if (ph === 0) {
        if (X.far !== 'none') farThing(g, env, X.far, X.fx, 46, t);
        return;
      }
      if (X.fly !== 'none') flyer(g, env, X.fly, 70, 12, t);
      if (X.street !== 'none') streetThing(g, env, X.street, 42, 64, t);
      if (X.critter !== 'none') critter(g, env, X.critter, 60, 76, t, false);
    }
  );

  addMore(
    'bakery',
    function (env, R) {
      return { wall: hopt(R, env, 'wall', ['clock', 'calendar', 'plates', 'wreath', 'poster'], 0.3), end: hopt(R, env, 'trinket', ['vase', 'teapot', 'candle', 'fruit'], 0.4) };
    },
    function (g, env, X, t) {
      if (X.wall !== 'none') wallArt(g, env, X.wall, 112, 10, t, 15);
      if (X.end === 'teapot') cupOf(g, env, 'teapot', 178, 52, env.P.accent2, t);
      else if (X.end !== 'none') trinket(g, env, X.end, 179, 52, t);
    }
  );

  addMore(
    'sea',
    function (env, R) {
      return { sand: R.pick(SAND), boat: opt(R, ['ferry', 'yacht', 'fishing', 'kayak'], 0.45), far: opt(R, ['lighthouse', 'turbines'], 0.6), sx: R.i(20, 110) };
    },
    function (g, env, X, t, L, ph) {
      var P = env.P;
      if (ph === 0) {
        if (X.far === 'lighthouse') farThing(g, env, 'lighthouse', 12, 41, t);
        else if (X.far !== 'none') farThing(g, env, X.far, 40, 41, t);
        if (X.boat !== 'none') boat(g, env, X.boat, mod(130 - t * 0.1, W + 40) - 20, 41, t);
        return;
      }
      var sx = X.sx;
      if (X.sand === 'castle') {
        g.r(sx, 70, 14, 6, '#e0c088');
        g.r(sx + 1, 66, 4, 4, '#e0c088');
        g.r(sx + 9, 66, 4, 4, '#e0c088');
        g.r(sx + 5, 64, 4, 6, '#d8b878');
        g.r(sx + 6, 60, 1, 4, P.ink);
        g.r(sx + 7, 60, 3, 2, P.accent);
      } else if (X.sand === 'ball') {
        disc(g, sx + 4, 72, 3, P.paper);
        g.r(sx + 1, 71, 7, 2, P.accent);
        g.r(sx + 3, 69, 2, 7, P.accent2);
      } else if (X.sand === 'bucket') {
        g.r(sx, 70, 7, 6, P.accent2);
        g.r(sx - 1, 69, 9, 1, P.accent2L);
        g.line(sx + 9, 76, sx + 14, 66, P.accent);
        g.r(sx + 8, 74, 4, 2, P.accent);
      } else if (X.sand === 'crab') {
        var cx = sx + rnd(Math.sin(t * 0.1) * 6);
        g.r(cx, 73, 6, 3, '#e8583a');
        g.p(cx - 1, 72, '#e8583a');
        g.p(cx + 6, 72, '#e8583a');
        g.p(cx + 1, 72, P.ink);
        g.p(cx + 4, 72, P.ink);
        g.p(cx, 76, '#c8382a');
        g.p(cx + 5, 76, '#c8382a');
      } else if (X.sand === 'shells') {
        for (var i = 0; i < 4; i++) {
          g.p(sx + i * 6, 72 + (i % 2) * 3, '#f6d0c0');
          g.p(sx + i * 6 + 1, 72 + (i % 2) * 3, '#e8b0a0');
        }
      }
    }
  );

  addMore(
    'snowfield',
    function (env, R) {
      return { sled: opt(R, ['sled', 'skis'], 0.45), cabin: R.ch(0.45), critter: hopt(R, env, 'critter', ['bunny', 'bird'], 0.5) };
    },
    function (g, env, X, t, L, ph) {
      var P = env.P;
      if (ph === 0) {
        if (X.cabin) farThing(g, env, 'house', 160, 44, t);
        return;
      }
      if (X.sled === 'sled') {
        g.r(150, 72, 16, 3, P.accent);
        g.r(150, 72, 16, 1, P.accentL);
        g.r(148, 76, 20, 1, P.metalD);
        g.p(167, 75, P.metalD);
        g.r(152, 75, 1, 1, P.metalD);
        g.r(163, 75, 1, 1, P.metalD);
      } else if (X.sled === 'skis') {
        g.line(172, 77, 180, 58, P.accent2);
        g.line(175, 77, 183, 58, P.accent2);
        g.line(168, 66, 188, 62, P.metalD);
      }
      if (X.critter !== 'none') critter(g, env, X.critter, 64, 77, t, false);
    }
  );

  addMore(
    'garden',
    function (env, R) {
      return { wall: hopt(R, env, 'wall', ['wreath', 'mirror', 'photos', 'clock', 'calendar', 'poster'], 0.3), shelf: R.ch(0.5), critter: hopt(R, env, 'critter', ['kitty', 'snail', 'ladybug', 'bird'], 0.45) };
    },
    function (g, env, X, t) {
      if (X.wall !== 'none') wallArt(g, env, X.wall, 166, 14, t, 16);
      if (X.shelf) wallArt(g, env, 'shelf', 8, 32, t, 17);
      if (X.critter === 'bird') critter(g, env, 'bird', 120, 50, t, true);
      else if (X.critter !== 'none') critter(g, env, X.critter, 140, 79, t, true);
    }
  );

  addMore(
    'night-city',
    function (env, R) {
      return { item: opt(R, ['bench', 'bike', 'barrel', 'vending'], 0.4), critter: hopt(R, env, 'critter', ['kitty', 'bird'], 0.5), fly: hopt(R, env, 'fly', ['plane', 'lanterns', 'blimp'], 0.5) };
    },
    function (g, env, X, t) {
      if (X.fly !== 'none') flyer(g, env, X.fly, 120, 14, t);
      if (X.item !== 'none') streetThing(g, env, X.item, 70, 71, t);
      if (X.critter !== 'none') critter(g, env, X.critter, 132, 71, t, true);
    }
  );

  // ---- more scenes (T158, D-129): 빨래방, 교실, 헬스장, 레코드 가게, 편의점, 사무실

  // 둥근 문 세탁기 한 대. 돌아가는 중이면 빨래가 문 안에서 돈다
  function washer(g, env, x, y, w, h, body, on, t, salt) {
    var P = env.P;
    var bD = mix(body, P.ink, 0.25);
    g.br(x + 1, y + h, w, 1, P.ink, 0.25);
    g.r(x, y, w, h, body);
    g.r(x, y, w, 1, mix(body, WHITE, 0.4));
    g.r(x + w - 1, y, 1, h, bD);
    g.r(x + 1, y + 2, w - 2, 4, bD);
    g.r(x + 2, y + 3, 6, 2, mix(P.ink, '#203040', 0.5));
    g.em = 1;
    g.p(x + 3, y + 3, on ? (mod(t + salt, 6) < 3 ? '#7cff9a' : '#3ad870') : '#5a6a70');
    g.r(x + 5, y + 3, 2, 1, on ? '#ffd36e' : '#5a6a70');
    g.em = 0;
    g.p(x + w - 4, y + 4, P.metalL);
    g.p(x + w - 6, y + 4, P.metalL);
    var cx = x + fl(w / 2);
    var cy = y + 6 + fl((h - 6) / 2);
    var r = Math.min(fl(w / 2) - 2, fl((h - 6) / 2) - 1);
    disc(g, cx, cy, r, P.metalD);
    disc(g, cx, cy, r - 1, P.metalL);
    disc(g, cx, cy, r - 2, mix('#203040', env.skyLow, 0.2));
    var cols = [P.accent, P.accent2, '#ffffff', P.leaf, '#f6d04a'];
    var n = 5;
    for (var k = 0; k < n; k++) {
      var a = (on ? t * 0.55 : 0) + k * ((PI * 2) / n) + salt;
      var rr = on ? r - 3 : 1;
      var px = cx + rnd(Math.cos(a) * rr);
      var py = on ? cy + rnd(Math.sin(a) * rr) : cy + r - 3 - (k % 2);
      if (!on) px = cx - 3 + k + (k > 2 ? 1 : 0);
      g.r(px, py, 2, 1 + (k & 1), cols[(k + salt) % cols.length]);
    }
    if (on && mod(t + salt, 4) < 2) g.p(cx - 1, cy - r + 3, mix(WHITE, '#bfe0ee', 0.4));
    g.b(cx - r + 2, cy - r + 3, WHITE, 0.5);
    g.b(cx - r + 3, cy - r + 2, WHITE, 0.5);
  }

  SC.laundromat = {
    still: 4,
    setup: function (env, R) {
      var n = R.pick([4, 5, 5]);
      var on = [];
      for (var i = 0; i < n; i++) on.push(R.ch(0.6));
      return {
        wallStyle: R.i(0, 2),
        n: n,
        on: on,
        body: R.pick(['paper', 'metal', 'paper', 'accent2L']),
        stack: R.ch(0.5),
        left: R.ch(0.5),
        shape: R.pick(['rect', 'rect', 'arch']),
        sign: R.pick(['coin', 'neon', 'clock', 'none']),
        table: R.pick(['basket', 'folded', 'both']),
        cup: hp(R, env, 'cup', CUPS),
        art: hp(R, env, 'wall', ['poster', 'calendar', 'cork', 'clock', 'neon']),
        floor: hp(R, env, 'floor', ['basket', 'bag', 'umbrella', 'bigplant', 'none']),
        critter: hp(R, env, 'critter', ['kitty', 'none', 'none', 'mouse']),
        spot: R.pick(['table', 'chair', 'top'])
      };
    },
    draw: function (g, env, L, t) {
      var P = env.P;
      var x;
      room(g, env, L, 64, { tiles: mix(P.floor, P.paper, 0.45) });
      var body = P[L.body];
      var mx0 = L.left ? 74 : 6;
      var mw = 22;
      var wx = L.left ? 10 : mx0 + L.n * mw + 8;
      var ww = Math.min(52, (L.left ? mx0 - 8 : W - 6) - wx);
      win(g, env, wx, 8, ww, 28, t, { shape: L.shape, panes: 4, sill: true });
      // fluorescent strip
      g.em = 1;
      g.r(mx0 + 4, 1, L.n * mw - 8, 2, env.time === 'day' ? '#eef4f6' : '#f6fbff');
      g.em = 0;
      g.light(mx0 + (L.n * mw) / 2, 4, 80, [0.85, 0.95, 1], env.lampK * 0.45, { sy: 1.4 });
      for (var i = 0; i < L.n; i++) {
        var x0 = mx0 + i * mw;
        washer(g, env, x0 + 1, 42, mw - 2, 22, body, L.on[i], t, i * 3 + 1);
        if (L.stack && i % 2 === 0) washer(g, env, x0 + 1, 18, mw - 2, 22, mix(body, P.metal, 0.3), L.on[(i + 1) % L.n], t, i * 3 + 2);
      }
      // sign over the machines
      var sx = mx0 + (L.stack ? mw + 2 : 6);
      if (!L.stack || L.n > 1) {
        if (L.sign === 'coin') {
          g.r(sx, 8, 26, 8, P.accent);
          g.r(sx + 1, 9, 24, 6, P.accentL);
          for (x = 0; x < 4; x++) g.r(sx + 3 + x * 6, 11, 4, 2, P.accentD);
          disc(g, sx + 30, 12, 3, '#f6d04a');
          g.p(sx + 30, 12, '#c8a02a');
        } else if (L.sign === 'neon') {
          g.em = 1;
          var nc = mod(t, 40) === 0 ? mix(P.accent2, P.ink, 0.5) : P.accent2L;
          g.r(sx, 9, 18, 1, nc);
          g.r(sx, 14, 18, 1, nc);
          g.r(sx, 9, 1, 6, nc);
          g.r(sx + 17, 9, 1, 6, nc);
          for (x = 0; x < 3; x++) g.r(sx + 3 + x * 5, 11, 3, 2, nc);
          g.em = 0;
          if (env.time !== 'day') g.light(sx + 9, 12, 24, [0.7, 0.9, 1], 0.35, { n: 3 });
        } else if (L.sign === 'clock') {
          clock(g, env, sx + 8, 11, 5, t);
        }
      }
      // folding table / bench at the open side
      var tx = L.left ? 8 : mx0 + L.n * mw + 6;
      var tw = Math.min(56, (L.left ? mx0 - 4 : W - 4) - tx);
      if (tw > 24) {
        g.r(tx, 54, tw, 2, P.woodL);
        g.r(tx, 56, tw, 1, P.woodD);
        g.r(tx + 2, 57, 2, 19, P.metalD);
        g.r(tx + tw - 4, 57, 2, 19, P.metalD);
        var bx = tx + 4;
        if (L.table !== 'folded') {
          g.r(bx, 47, 14, 7, P.woodL);
          for (x = bx + 1; x < bx + 14; x += 2) g.r(x, 48, 1, 5, P.woodD);
          g.r(bx + 2, 45, 4, 2, P.accent);
          g.r(bx + 7, 44, 5, 3, '#ffffff');
          bx += 17;
        }
        if (L.table !== 'basket') {
          var fc = [P.accent2, P.paper, P.accent, P.leafL];
          for (var f = 0; f < 4; f++) g.r(bx, 51 - f * 2, 12, 2, fc[(f + env.seed) % 4]);
          bx += 14;
        }
        if (bx + 6 < tx + tw) cupOf(g, env, L.cup, bx + 2, 54, P.accent, t);
      }
      wallArt(g, env, L.art, L.left ? 52 : 150, 40, t, 3);
      if (L.floor !== 'none') floorThing(g, env, L.floor, L.left ? 46 : mx0 + L.n * mw - 2, 77, t);
      if (L.critter !== 'none') critter(g, env, L.critter, L.left ? 182 - 12 : 4, 77, t, !L.left);
      var mxp = L.spot === 'top' && !L.stack ? mx0 + mw * 2 + 3 : (L.spot === 'chair' ? tx + 8 : tx + tw - 22);
      var myp = L.spot === 'top' && !L.stack ? 42 : (L.spot === 'chair' ? 76 : 54);
      if (L.spot === 'chair') {
        g.r(mxp - 2, 70, 20, 2, P.accent2);
        g.r(mxp, 72, 2, 6, P.metalD);
        g.r(mxp + 14, 72, 2, 6, P.metalD);
        myp = 70;
      }
      mascot(g, env, mxp, myp, t);
    }
  };

  SC.classroom = {
    still: 6,
    setup: function (env, R) {
      return {
        wallStyle: R.pick([0, 3]),
        board: R.pick(['math', 'map', 'motif', 'timetable', 'letters']),
        motif: hp(R, env, 'motif', MOTIFS),
        wins: R.pick([1, 2]),
        left: R.ch(0.5),
        desks: [R.pick(['book', 'apple', 'pencils', 'globe', 'none']), R.pick(['book', 'bag', 'cup', 'none']), R.pick(['book', 'apple', 'plant', 'none'])],
        cup: hp(R, env, 'cup', CUPS),
        art: hp(R, env, 'wall', ['calendar', 'map', 'pennant', 'cork', 'clock', 'banner']),
        shelf: R.ch(0.6),
        plant: hp(R, env, 'plant', PLANTS2.concat(['tall', 'trail'])),
        critter: hp(R, env, 'critter', ['bird', 'none', 'chick', 'none', 'hamster']),
        seat: R.i(0, 2),
        chalk: R.pick(['#ffffff', '#ffe08a', '#ffb0c0'])
      };
    },
    draw: function (g, env, L, t) {
      var P = env.P;
      var x;
      var y;
      room(g, env, L, 58);
      var bx = L.left ? 64 : 12;
      var bw = 92;
      // chalkboard
      g.r(bx - 2, 8, bw + 4, 30, P.woodD);
      g.r(bx, 10, bw, 26, hx('#2f5a46'));
      for (y = 10; y < 36; y++) for (x = bx; x < bx + bw; x++) if (bay(x, y) < 0.08) g.p(x, y, '#3a6a54');
      var ch = hx(L.chalk);
      var cw = mix(ch, '#2f5a46', 0.25);
      if (L.board === 'math') {
        for (y = 0; y < 4; y++) textRow(g, bx + 5, 14 + y * 5, 40 - y * 6, env.seed, y, cw);
        g.r(bx + 52, 22, 12, 1, ch);
        for (x = 0; x < 20; x++) g.p(bx + 54 + x, 32 - rnd(Math.sqrt(x) * 3.5), ch);
        g.r(bx + 54, 16, 1, 17, cw);
        g.r(bx + 54, 32, 24, 1, cw);
      } else if (L.board === 'map') {
        for (y = 0; y < 18; y++) {
          for (x = 0; x < bw - 16; x++) {
            var v = Math.sin((x + env.seed % 30) * 0.13) + Math.sin(y * 0.4 + x * 0.05) * 0.8;
            if (v > 0.7) g.p(bx + 8 + x, 14 + y, mix(ch, '#2f5a46', 0.45));
            if (v > 0.7 && v < 0.82) g.p(bx + 8 + x, 14 + y, cw);
          }
        }
        g.p(bx + 40, 22, '#ff6a5a');
      } else if (L.board === 'motif') {
        motif(g, L.motif, bx + 20, 16, ch, cw);
        motif(g, L.motif, bx + 60, 20, cw, cw);
        textRow(g, bx + 32, 30, 30, env.seed, 1, cw);
      } else if (L.board === 'timetable') {
        for (x = 0; x <= 5; x++) g.r(bx + 6 + x * 14, 13, 1, 20, cw);
        for (y = 0; y <= 4; y++) g.r(bx + 6, 13 + y * 5, 70, 1, cw);
        for (y = 0; y < 4; y++) for (x = 0; x < 5; x++) if (hash(x + env.seed, y) < 0.7) g.r(bx + 9 + x * 14, 15 + y * 5, 6 + fl(hash(x, y + env.seed) * 4), 1, [ch, '#ffe08a', '#a8e0ff'][(x + y) % 3]);
      } else {
        for (y = 0; y < 3; y++) {
          for (x = 0; x < 7; x++) {
            var lx = bx + 8 + x * 11;
            var ly = 14 + y * 7;
            if (hash(x, y + env.seed) < 0.25) continue;
            var k = fl(hash(x + 7, y + env.seed) * 4);
            g.r(lx, ly, 5, 1, cw);
            if (k & 1) g.r(lx + 2, ly, 1, 5, cw);
            else g.r(lx, ly + 2, 5, 1, cw);
            if (k & 2) g.r(lx + 4, ly + 1, 1, 4, cw);
          }
        }
      }
      // chalk tray
      g.r(bx - 2, 38, bw + 4, 2, P.woodL);
      g.r(bx + 10, 37, 4, 1, ch);
      g.r(bx + 16, 37, 3, 1, '#ffb0c0');
      g.r(bx + bw - 14, 36, 8, 2, P.paperD);
      clock(g, env, bx + bw / 2, 4, 3, t);
      // windows on the free side
      var wx0 = L.left ? 6 : bx + bw + 10;
      var wsp = L.left ? 52 : W - 6 - wx0;
      var wn = L.wins;
      var wwid = fl((wsp - (wn - 1) * 4) / wn);
      for (var w = 0; w < wn; w++) win(g, env, wx0 + w * (wwid + 4), 8, wwid, 30, t, { panes: 4, sill: true, curtain: w === 0 ? P.paper : null, cw: 5 });
      if (L.shelf) {
        // cubbies under the windows
        var sx = wx0;
        g.r(sx, 46, wsp, 12, P.woodL);
        for (x = 0; x < wsp - 2; x += 9) {
          g.r(sx + 1 + x, 47, 8, 10, P.woodD);
          g.r(sx + 2 + x, 49, 6, 8, P.books[(x / 9 + env.seed) % P.books.length]);
        }
        plant(g, env, sx + wsp - 8, 46, L.plant, t, 4);
      } else {
        wallArt(g, env, L.art, wx0 + 6, 44, t, 5);
      }
      // student desks
      for (var d = 0; d < 3; d++) {
        var dx = 14 + d * 60;
        g.r(dx, 62, 34, 3, P.woodL);
        g.r(dx, 65, 34, 1, P.woodD);
        g.r(dx + 2, 66, 2, 12, P.metalD);
        g.r(dx + 30, 66, 2, 12, P.metalD);
        g.r(dx + 4, 66, 26, 3, P.metal);
        // chair back behind the desk
        g.r(dx + 10, 55, 14, 6, P.accent2);
        g.r(dx + 10, 55, 14, 1, P.accent2L);
        var it = L.desks[d];
        if (it === 'book') {
          g.r(dx + 4, 60, 10, 2, P.paper);
          g.r(dx + 4, 61, 10, 1, P.accent);
        } else if (it === 'apple') {
          g.ell(dx + 6, 59, 2, 2, '#e8483a');
          g.p(dx + 6, 56, P.leafD);
        } else if (it === 'pencils') {
          g.r(dx + 4, 60, 9, 2, P.accent);
          g.r(dx + 6, 59, 6, 1, '#f6d04a');
        } else if (it === 'globe') {
          disc(g, dx + 7, 57, 3, '#5aa0d8');
          g.p(dx + 6, 56, P.leaf);
          g.p(dx + 8, 58, P.leaf);
          g.r(dx + 5, 61, 5, 1, P.woodD);
        } else if (it === 'bag') {
          g.r(dx + 22, 56, 9, 6, P.accent);
          g.r(dx + 24, 54, 5, 2, P.accentD);
        } else if (it === 'cup') {
          cupOf(g, env, L.cup, dx + 26, 62, P.paper, t);
        } else if (it === 'plant') {
          plant(g, env, dx + 26, 62, 'cactus', t, 2);
        }
      }
      if (L.critter !== 'none') critter(g, env, L.critter, L.left ? 40 : 160, 46, t, L.left);
      mascot(g, env, 14 + L.seat * 60 + 12, 62, t);
    }
  };

  SC.gym = {
    still: 8,
    setup: function (env, R) {
      return {
        wallStyle: R.i(0, 1),
        main: R.pick(['treadmill', 'bike', 'bench']),
        hang: R.pick(['bag', 'rings', 'none']),
        rack: R.pick(['dumbbells', 'kettle', 'plates']),
        mat: R.pick(['accent', 'accent2', 'leaf', 'rug']),
        left: R.ch(0.5),
        art: hp(R, env, 'wall', ['banner', 'pennant', 'poster', 'clock', 'neon']),
        cup: hp(R, env, 'cup', ['bottle', 'bottle', 'glass']),
        floor: hp(R, env, 'floor', ['bag', 'skate', 'basket', 'bigplant', 'none']),
        ball: R.ch(0.6),
        spot: R.pick(['mat', 'main'])
      };
    },
    draw: function (g, env, L, t) {
      var P = env.P;
      var x;
      var y;
      room(g, env, L, 60, { tiles: mix(P.ink, P.floor, 0.45) });
      // mirror wall
      var mx0 = L.left ? 100 : 8;
      g.r(mx0, 10, 84, 40, P.metalD);
      g.r(mx0 + 1, 11, 82, 38, mix(env.skyLow, P.wall, 0.55));
      for (var k = 0; k < 6; k++) {
        g.b(mx0 + 10 + k * 13, 46, WHITE, 0.2);
        g.line(mx0 + 6 + k * 13, 48, mx0 + 30 + k * 13, 12, mix(mix(env.skyLow, P.wall, 0.55), WHITE, 0.18));
      }
      // 거울 속 방: 바닥선, 맞은편 창, 흐린 기구 그림자
      var mg = mix(env.skyLow, P.wall, 0.55);
      g.r(mx0 + 1, 40, 82, 9, mix(mg, P.floor, 0.5));
      g.r(mx0 + 1, 40, 82, 1, mix(mg, P.trim, 0.5));
      g.r(mx0 + (L.left ? 8 : 52), 16, 20, 16, mix(mg, env.skyLow, 0.6));
      g.r(mx0 + (L.left ? 17 : 61), 16, 1, 16, mix(mg, P.frame, 0.5));
      g.r(mx0 + (L.left ? 8 : 52), 23, 20, 1, mix(mg, P.frame, 0.5));
      for (var q = 0; q < 3; q++) g.r(mx0 + 30 + q * 7, 34 - q * 2, 4, 6 + q * 2, mix(mg, P.ink, 0.12));
      g.r(mx0 + 41, 11, 1, 38, P.metalD);
      // window on the other side
      var wx = L.left ? 10 : 112;
      win(g, env, wx, 8, 34, 30, t, { panes: 4, sill: true });
      wallArt(g, env, L.art, L.left ? 60 : 160, 12, t, 6);
      // mat
      var mc = P[L.mat];
      var matX = L.left ? 112 : 18;
      g.r(matX, 72, 46, 4, mc);
      g.r(matX, 72, 46, 1, mix(mc, WHITE, 0.3));
      g.r(matX + 40, 70, 6, 6, mix(mc, P.ink, 0.2));
      // equipment
      var ex = L.left ? 40 : 128;
      if (L.main === 'treadmill') {
        g.r(ex, 70, 42, 5, P.ink);
        g.r(ex + 1, 70, 40, 1, P.metal);
        for (x = 0; x < 40; x += 4) g.p(ex + 1 + mod(x - t, 40), 71, P.metalL);
        g.line(ex + 36, 70, ex + 40, 50, P.metalD);
        g.r(ex + 34, 48, 10, 4, P.metalD);
        g.em = 1;
        g.r(ex + 35, 49, 6, 2, mod(t, 10) < 5 ? '#7cff9a' : '#3ad870');
        g.em = 0;
      } else if (L.main === 'bike') {
        disc(g, ex + 10, 70, 5, P.ink);
        disc(g, ex + 10, 70, 3, P.metal);
        var a = t * 0.6;
        g.line(ex + 10, 70, ex + 10 + rnd(Math.cos(a) * 3), 70 + rnd(Math.sin(a) * 3), P.metalL);
        g.line(ex + 10, 70, ex + 22, 56, P.accent);
        g.line(ex + 22, 56, ex + 30, 76, P.accent);
        g.r(ex + 18, 54, 8, 2, P.ink);
        g.line(ex + 12, 64, ex + 8, 52, P.accent);
        g.r(ex + 4, 50, 8, 2, P.metalD);
        g.r(ex + 4, 76, 30, 2, P.metalD);
      } else {
        g.r(ex, 64, 34, 4, P.accent);
        g.r(ex, 64, 34, 1, P.accentL);
        g.r(ex + 4, 68, 2, 9, P.metalD);
        g.r(ex + 28, 68, 2, 9, P.metalD);
        g.r(ex + 26, 40, 2, 24, P.metalD);
        g.r(ex + 34, 40, 2, 37, P.metalD);
        g.r(ex + 18, 44, 26, 1, P.metalL);
        g.r(ex + 18, 41, 3, 7, P.ink);
        g.r(ex + 41, 41, 3, 7, P.ink);
      }
      // rack
      var rx = L.left ? 6 : 84;
      g.r(rx, 60, 28, 2, P.metalD);
      g.r(rx + 1, 62, 2, 14, P.metalD);
      g.r(rx + 25, 62, 2, 14, P.metalD);
      g.r(rx, 68, 28, 2, P.metalD);
      for (var i = 0; i < 4; i++) {
        var px = rx + 3 + i * 6;
        if (L.rack === 'dumbbells') {
          g.r(px, 57, 1, 3, P.ink);
          g.r(px + 3, 57, 1, 3, P.ink);
          g.r(px + 1, 58, 2, 1, P.metalL);
          g.r(px, 65, 1, 3, P.accent);
          g.r(px + 3, 65, 1, 3, P.accent);
          g.r(px + 1, 66, 2, 1, P.metalL);
        } else if (L.rack === 'kettle') {
          g.ell(px + 2, 58, 2, 2, i % 2 ? P.accent : P.ink);
          g.r(px + 1, 55, 3, 1, P.metalD);
          g.ell(px + 2, 66, 2, 2, P.accent2);
        } else {
          disc(g, px + 2, 57 - (i % 2), 3, i % 2 ? P.ink : P.accent);
          g.p(px + 2, 57 - (i % 2), P.metalL);
        }
      }
      if (L.hang === 'bag') {
        var sw = rnd(Math.sin(t * 0.3) * 2);
        var hx0 = L.left ? 74 : 104;
        g.line(hx0, 0, hx0 + sw, 18, P.metalD);
        g.r(hx0 - 4 + sw, 18, 9, 22, P.accentD);
        g.r(hx0 - 4 + sw, 18, 2, 22, P.accent);
        g.r(hx0 - 4 + sw, 24, 9, 1, P.ink);
        g.r(hx0 - 4 + sw, 34, 9, 1, P.ink);
      } else if (L.hang === 'rings') {
        var rx2 = L.left ? 70 : 100;
        for (var s = 0; s < 2; s++) {
          var sw2 = rnd(Math.sin(t * 0.2 + s) * 1);
          g.line(rx2 + s * 10, 0, rx2 + s * 10 + sw2, 20, P.woodDD);
          g.ell(rx2 + s * 10 + sw2, 23, 2, 3, P.woodL);
          g.ell(rx2 + s * 10 + sw2, 23, 1, 2, P.wall);
        }
      }
      if (L.ball) {
        var bx = L.left ? 178 : 4;
        disc(g, bx + 5, 71, 6, P.accent2);
        g.r(bx, 70, 11, 1, P.accent2L);
      }
      cupOf(g, env, L.cup, matX + 50, 76, P.accent, t);
      if (L.floor !== 'none') floorThing(g, env, L.floor, L.left ? 156 : 64, 77, t);
      if (L.spot === 'main' && L.main === 'treadmill') mascot(g, env, ex + 12 + (mod(t, 4) < 2 ? 0 : 1), 70, t, { sleep: false });
      else if (L.spot === 'main' && L.main === 'bench') mascot(g, env, ex + 6, 64, t);
      else mascot(g, env, matX + 14, 72, t);
    }
  };

  SC['record-shop'] = {
    still: 10,
    setup: function (env, R) {
      return {
        wallStyle: R.i(0, 2),
        cols: R.i(6, 8),
        deck: R.pick(['turntable', 'turntable', 'cassette']),
        neon: hp(R, env, 'motif', ['note', 'heart', 'star', 'moon']),
        crates: R.pick([2, 3]),
        speaker: R.ch(0.7),
        left: R.ch(0.5),
        art: hp(R, env, 'wall', ['poster', 'neon', 'photos', 'pennant']),
        cup: hp(R, env, 'cup', CUPS),
        critter: hp(R, env, 'critter', ['kitty', 'none', 'pup', 'none']),
        spot: R.pick(['counter', 'crate', 'floor']),
        phones: R.ch(0.5)
      };
    },
    draw: function (g, env, L, t) {
      var P = env.P;
      var x;
      var y;
      room(g, env, L, 60);
      // record wall: sleeves in a grid
      var gx0 = L.left ? 70 : 8;
      var gy0 = 8;
      for (var r = 0; r < 3; r++) {
        g.r(gx0 - 2, gy0 + r * 13 + 11, L.cols * 14 + 2, 2, P.woodL);
        for (var c = 0; c < L.cols; c++) {
          var sx = gx0 + c * 14;
          var sy = gy0 + r * 13;
          var h0 = hash(env.seed + c, r);
          var col = [P.accent, P.accent2, P.leaf, P.paper, '#f6d04a', P.ink, P.accentL, P.accent2D][fl(h0 * 8)];
          g.r(sx, sy, 11, 11, col);
          var col2 = mix(col, h0 > 0.5 ? WHITE : P.ink, 0.45);
          var kind = fl(hash(r, c + env.seed) * 5);
          if (kind === 0) disc(g, sx + 5, sy + 5, 3, col2);
          else if (kind === 1) for (y = 0; y < 11; y += 3) g.r(sx, sy + y, 11, 1, col2);
          else if (kind === 2) motif(g, MOTIFS[fl(h0 * MOTIFS.length)], sx + 2, sy + 2, col2, col2);
          else if (kind === 3) g.r(sx + 2, sy + 2, 7, 4, col2);
          else {
            disc(g, sx + 8, sy + 5, 4, P.ink);
            g.r(sx, sy, 7, 11, col);
          }
        }
      }
      // neon sign + window
      var nx = L.left ? 16 : 150;
      g.em = 1;
      var nc = mod(t, 50) === 0 ? mix(P.accent2, P.ink, 0.6) : P.accent2L;
      motif(g, L.neon, nx, 8, nc, nc);
      g.em = 0;
      if (env.time !== 'day') g.light(nx + 3, 10, 22, [1, 0.6, 0.85], 0.35, { n: 3 });
      win(g, env, L.left ? 8 : 140, 22, 46, 24, t, { panes: 2, sill: true });
      // crates of records
      var cx0 = L.left ? 74 : 20;
      for (var k = 0; k < L.crates; k++) {
        var bx = cx0 + k * 26;
        g.r(bx, 62, 22, 12, P.wood);
        g.r(bx, 62, 22, 1, P.woodL);
        g.r(bx, 67, 22, 1, P.woodD);
        g.r(bx + 2, 74, 2, 4, P.woodDD);
        g.r(bx + 18, 74, 2, 4, P.woodDD);
        for (x = 0; x < 10; x++) {
          var lean = x === 4 ? -2 : 0;
          g.r(bx + 2 + x * 2 + lean, 55 + (x % 3), 1, 8 - (x % 3), P.books[(x + k * 3 + env.seed) % P.books.length]);
        }
      }
      // counter with deck
      var kx = L.left ? 6 : 128;
      g.r(kx, 52, 58, 3, P.woodL);
      g.r(kx + 1, 55, 56, 21, P.woodD);
      g.r(kx + 4, 58, 50, 1, P.woodDD);
      if (L.deck === 'turntable') {
        g.r(kx + 4, 47, 22, 5, P.ink);
        g.r(kx + 4, 47, 22, 1, P.metalD);
        g.ell(kx + 13, 48, 8, 1, '#1a1a20');
        var ang = t * 0.7;
        g.p(kx + 13 + rnd(Math.cos(ang) * 6), 48 + (Math.sin(ang) > 0 ? 1 : 0), P.metalL);
        g.r(kx + 12, 48, 3, 1, P.accent);
        g.line(kx + 23, 46, kx + 18, 48, P.metalL);
        if (env.time !== 'day' || mod(t, 8) < 4) {
          for (var q = 0; q < 2; q++) {
            var ny = 40 - mod(t * 0.5 + q * 8, 14);
            motif(g, 'note', kx + 6 + q * 9 + rnd(Math.sin(t * 0.2 + q) * 2), ny, P.accent2L, P.accent2L);
          }
        }
      } else {
        g.r(kx + 4, 45, 22, 7, P.metalD);
        g.r(kx + 6, 46, 18, 4, P.ink);
        disc(g, kx + 10, 48, 1, mod(t, 4) < 2 ? P.metalL : P.metal);
        disc(g, kx + 20, 48, 1, mod(t, 4) < 2 ? P.metal : P.metalL);
      }
      if (L.speaker) {
        g.r(kx + 30, 40, 10, 12, P.ink);
        disc(g, kx + 35, 43, 2, P.metalD);
        disc(g, kx + 35, 48, 3, P.metalD);
        g.p(kx + 35, 48, mod(t, 3) ? P.metal : P.metalL);
      }
      if (L.phones) {
        g.r(kx + 44, 47, 8, 1, P.ink);
        g.r(kx + 43, 47, 2, 4, P.accent);
        g.r(kx + 51, 47, 2, 4, P.accent);
      }
      cupOf(g, env, L.cup, kx + 48, 52, P.paper, t);
      wallArt(g, env, L.art, L.left ? 34 : 166, 50, t, 7);
      if (L.critter !== 'none') critter(g, env, L.critter, L.left ? 160 : 98, 77, t, L.left, env.time === 'night');
      var mx = L.spot === 'counter' ? kx + 30 : (L.spot === 'crate' ? cx0 + 4 : (L.left ? 130 : 64));
      var my = L.spot === 'counter' ? (L.speaker ? 40 : 52) : (L.spot === 'crate' ? 55 : 77);
      if (L.spot === 'counter' && L.speaker) mx = kx + 28;
      mascot(g, env, mx, my, t);
    }
  };

  var SNACKS = ['ramen', 'chips', 'candy', 'bread', 'cans', 'boxes'];

  SC.convenience = {
    still: 3,
    setup: function (env, R) {
      return {
        wallStyle: 0,
        aisles: [R.pick(SNACKS), R.pick(SNACKS), R.pick(SNACKS)],
        drinks: R.i(0, 1000),
        left: R.ch(0.5),
        hot: R.pick(['buns', 'odeng', 'sausage']),
        cup: hp(R, env, 'cup', ['iced', 'bottle', 'glass']),
        poster: R.pick(['sale', 'ice', 'onigiri']),
        floor: hp(R, env, 'floor', ['umbrella', 'basket', 'boxes', 'none']),
        critter: hp(R, env, 'critter', ['kitty', 'none', 'none']),
        spot: R.pick(['counter', 'aisle', 'door'])
      };
    },
    draw: function (g, env, L, t) {
      var P = env.P;
      var x;
      var y;
      room(g, env, L, 60, { tiles: mix(P.paper, P.floor, 0.3) });
      g.em = 1;
      for (x = 20; x < W; x += 48) g.r(x, 1, 28, 2, '#f8fcff');
      g.em = 0;
      g.light(96, 4, 110, [0.9, 0.97, 1], Math.max(0.25, env.lampK), { sy: 1.1 });
      // big front window with the street behind
      var wx = L.left ? 120 : 6;
      win(g, env, wx, 6, 66, 32, t, { panes: 2, sill: false, open: false, view: 'street' });
      // poster on the glass
      var px = wx + 46;
      if (L.poster === 'sale') {
        g.r(px, 22, 14, 10, '#e8483a');
        g.r(px + 2, 24, 10, 3, '#ffe08a');
        g.r(px + 2, 28, 6, 2, WHITE);
      } else if (L.poster === 'ice') {
        g.r(px, 20, 14, 12, '#7fd0f0');
        g.r(px + 5, 22, 4, 6, P.accent2L);
        g.r(px + 6, 28, 2, 3, P.woodL);
      } else {
        g.r(px, 20, 14, 12, P.paper);
        g.r(px + 3, 23, 8, 6, WHITE);
        g.r(px + 3, 27, 8, 2, P.ink);
      }
      // drink fridge
      var fx = L.left ? 6 : 128;
      g.r(fx, 6, 58, 54, P.metalD);
      g.em = 1;
      for (var d = 0; d < 3; d++) {
        var dx = fx + 2 + d * 19;
        g.r(dx, 8, 17, 50, mix('#dff4ff', env.skyLow, 0.1));
        for (var sh = 0; sh < 4; sh++) {
          var sy = 18 + sh * 11;
          g.r(dx, sy, 17, 1, P.metalL);
          for (var b = 0; b < 5; b++) {
            var hv = hash(L.drinks + d * 7 + sh, b);
            var bc = ['#e8483a', '#3a8ad0', '#5ab86a', '#f6d04a', '#f0a0c0', '#ffffff', '#8a5a3a'][fl(hv * 7)];
            var bh = 5 + fl(hv * 4);
            g.r(dx + 1 + b * 3, sy - bh, 2, bh, bc);
            g.p(dx + 1 + b * 3, sy - bh - 1, mix(bc, P.ink, 0.3));
          }
        }
      }
      g.em = 0;
      for (d = 0; d < 3; d++) {
        g.r(fx + 2 + d * 19 + 15, 26, 1, 8, P.metalL);
        g.b(fx + 5 + d * 19, 10, WHITE, 0.4);
      }
      g.light(fx + 29, 30, 50, [0.8, 0.95, 1], 0.35, { n: 4 });
      // aisle shelf in front
      var shelfX = L.left ? 6 : 80;
      var shelfW = L.left ? 108 : 44;
      for (var row = 0; row < 3; row++) {
        var ry = 50 + row * 9;
        var kind = L.aisles[row];
        g.r(shelfX, ry, shelfW, 1, P.metalD);
        for (x = shelfX + 1; x < shelfX + shelfW - 3; x += 4) {
          var c1 = [P.accent, P.accent2, '#f6d04a', P.leaf, '#ffffff'][fl(hash(x, row + L.drinks) * 5)];
          if (kind === 'ramen') {
            g.r(x, ry - 5, 3, 5, c1);
            g.r(x, ry - 5, 3, 1, WHITE);
          } else if (kind === 'chips') {
            g.r(x, ry - 6, 3, 6, c1);
            g.p(x + 1, ry - 4, mix(c1, WHITE, 0.5));
          } else if (kind === 'candy') {
            g.r(x, ry - 3, 3, 3, c1);
            g.p(x + 1, ry - 4, c1);
          } else if (kind === 'bread') {
            g.ell(x + 1, ry - 2, 2, 1, '#e8b878');
            g.r(x, ry - 1, 3, 1, c1);
          } else if (kind === 'cans') {
            g.r(x, ry - 4, 3, 4, c1);
            g.r(x, ry - 4, 3, 1, P.metalL);
          } else {
            g.r(x, ry - 6, 4, 6, c1);
            g.r(x, ry - 6, 1, 6, mix(c1, P.ink, 0.25));
          }
        }
      }
      g.r(shelfX, 50, 1, 28, P.metalD);
      g.r(shelfX + shelfW - 1, 50, 1, 28, P.metalD);
      // counter + hot case
      var kx = L.left ? 122 : 8;
      g.r(kx, 52, 64, 3, P.paper);
      g.r(kx + 1, 55, 62, 22, P.accent);
      g.r(kx + 1, 60, 62, 2, P.paper);
      g.r(kx + 4, 44, 10, 8, P.ink);
      g.em = 1;
      g.r(kx + 5, 45, 8, 3, '#7cff9a');
      g.em = 0;
      g.r(kx + 18, 40, 20, 12, P.metalD);
      g.em = 1;
      g.r(kx + 19, 41, 18, 10, '#ffe0a8');
      g.em = 0;
      for (var hb = 0; hb < 3; hb++) {
        if (L.hot === 'buns') g.ell(kx + 22 + hb * 6, 48, 2, 2, '#fff4e0');
        else if (L.hot === 'odeng') g.r(kx + 21 + hb * 6, 43, 2, 7, '#d8a060');
        else g.r(kx + 20 + hb * 6, 46, 5, 2, '#c8503a');
      }
      steam(g, kx + 28, 40, t, 6, 3);
      g.light(kx + 28, 46, 20, [1, 0.8, 0.5], 0.3, { n: 3 });
      cupOf(g, env, L.cup, kx + 46, 52, P.accent2, t);
      g.r(kx + 52, 49, 6, 3, '#f6d04a');
      g.r(kx + 53, 48, 4, 1, WHITE);
      if (L.floor !== 'none') floorThing(g, env, L.floor, L.left ? 106 : 66, 77, t);
      if (L.critter !== 'none') critter(g, env, L.critter, L.left ? 60 : 136, 77, t, !L.left);
      var mx = L.spot === 'counter' ? kx + 36 : (L.spot === 'aisle' ? shelfX + fl(shelfW / 2) - 8 : (L.left ? 100 : 70));
      mascot(g, env, mx, L.spot === 'aisle' ? 78 : 77, t);
    }
  };

  SC.office = {
    still: 9,
    setup: function (env, R) {
      return {
        wallStyle: R.i(0, 1),
        nwin: R.pick([2, 3]),
        screens: [R.pick(['code', 'chart', 'doc', 'mail']), R.pick(['code', 'chart', 'doc', 'video'])],
        board: R.pick(['notes', 'chart', 'plan']),
        chair: R.pick(['accent', 'accent2', 'ink', 'leafD']),
        plant: hp(R, env, 'plant', PLANTS2.concat(['tall'])),
        cup: hp(R, env, 'cup', CUPS),
        tr: hp(R, env, 'trinket', TRINKETS),
        cooler: R.ch(0.6),
        left: R.ch(0.5),
        critter: hp(R, env, 'critter', ['none', 'none', 'kitty', 'pup']),
        spot: R.pick(['desk', 'chair'])
      };
    },
    draw: function (g, env, L, t) {
      var P = env.P;
      var x;
      var y;
      room(g, env, L, 60);
      // tall windows
      var n = L.nwin;
      var ww = n === 2 ? 50 : 34;
      var x0 = L.left ? 8 : W - 8 - (n * ww + (n - 1) * 4);
      for (var i = 0; i < n; i++) win(g, env, x0 + i * (ww + 4), 4, ww, 40, t, { panes: 4, sill: false, view: 'city' });
      g.r(x0 - 3, 44, n * ww + (n - 1) * 4 + 6, 2, P.metalL);
      // whiteboard on the remaining wall
      var bx = L.left ? x0 + n * ww + (n - 1) * 4 + 8 : 8;
      var bw = Math.min(56, (L.left ? W - 6 : x0 - 6) - bx);
      if (bw > 24) {
        g.r(bx, 8, bw, 26, P.metalD);
        g.r(bx + 1, 9, bw - 2, 24, '#f8f8f4');
        if (L.board === 'notes') {
          for (var k = 0; k < 6; k++) {
            var nx = bx + 3 + (k % 3) * fl((bw - 6) / 3);
            var ny = 11 + fl(k / 3) * 10;
            g.r(nx, ny, 8, 7, ['#ffe08a', '#ffb0c0', '#a8e0ff', '#b8f0a8'][k % 4]);
            g.r(nx + 1, ny + 2, 5, 1, mix(P.ink, '#f8f8f4', 0.5));
          }
        } else if (L.board === 'chart') {
          for (k = 0; k < 6; k++) {
            var hh = 4 + fl(hash(k, env.seed) * 14);
            g.r(bx + 5 + k * 7, 31 - hh, 4, hh, [P.accent, P.accent2][k % 2]);
          }
          g.line(bx + 4, 28, bx + bw - 6, 13, '#e8483a');
        } else {
          for (k = 0; k < 4; k++) {
            g.r(bx + 4, 12 + k * 5, 4, 3, [P.accent, P.accent2, P.leaf, '#f6d04a'][k]);
            g.r(bx + 10, 13 + k * 5, 6 + fl(hash(k, env.seed + 1) * (bw - 22)), 1, mix(P.ink, '#f8f8f4', 0.4));
          }
        }
        g.r(bx + 4, 34, bw - 8, 1, P.metalD);
        g.r(bx + 8, 33, 4, 1, '#3a6ad0');
      }
      // desks with monitors (two seats)
      for (var s = 0; s < 2; s++) {
        var dx = (L.left ? 92 : 8) + s * 50;
        g.r(dx, 58, 46, 3, P.woodL);
        g.r(dx, 61, 46, 1, P.woodD);
        g.r(dx + 2, 62, 2, 16, P.metalD);
        g.r(dx + 42, 62, 2, 16, P.metalD);
        // partition behind
        g.r(dx - 1, 46, 48, 12, mix(P.metal, P.wall, 0.5));
        g.r(dx - 1, 46, 48, 1, P.metalL);
        g.r(dx + 12, 40, 22, 14, P.ink);
        g.em = 1;
        var sk = L.screens[s];
        if (sk === 'code') codeScreen(g, env, dx + 13, 41, 20, 12, t);
        else {
          g.r(dx + 13, 41, 20, 12, sk === 'video' ? '#203050' : '#eef4fa');
          if (sk === 'chart') {
            for (k = 0; k < 5; k++) g.r(dx + 15 + k * 4, 51 - 2 - fl(hash(k + s, env.seed) * 7), 2, 2 + fl(hash(k + s, env.seed) * 7), [P.accent, P.accent2][k % 2]);
          } else if (sk === 'doc') {
            for (k = 0; k < 4; k++) g.r(dx + 15, 43 + k * 2, 8 + fl(hash(k, s) * 8), 1, '#8a96aa');
          } else if (sk === 'mail') {
            for (k = 0; k < 4; k++) {
              g.r(dx + 14, 42 + k * 3, 18, 2, k === 1 ? '#cfe0ff' : '#eef4fa');
              g.r(dx + 15, 42 + k * 3, 4, 1, '#3a6ad0');
            }
          } else {
            disc(g, dx + 23, 47, 3, '#ffffff');
            g.p(dx + 23, 47, '#203050');
            g.r(dx + 14, 51, fl(mod(t, 60) / 3.3), 1, '#e8483a');
          }
        }
        g.em = 0;
        if (env.time !== 'day') g.light(dx + 23, 47, 22, [0.75, 0.85, 1], 0.4, { n: 3 });
        g.r(dx + 21, 54, 4, 4, P.metalD);
        g.r(dx + 17, 57, 12, 1, P.metalD);
        g.r(dx + 6, 56, 9, 2, P.paper);
        if (s === 0) cupOf(g, env, L.cup, dx + 38, 58, P.accent, t);
        else trinket(g, env, L.tr, dx + 38, 58, t);
      }
      // chair in front of the first desk
      var cx = (L.left ? 92 : 8) + 14;
      g.r(cx, 62, 16, 4, P[L.chair]);
      g.r(cx, 50, 3, 14, P[L.chair]);
      g.r(cx + 7, 66, 2, 8, P.metalD);
      g.r(cx + 2, 75, 12, 1, P.metalD);
      // corner: cooler or big plant
      var ox = L.left ? 170 : 108;
      if (L.cooler) {
        g.r(ox, 56, 12, 22, P.paper);
        g.r(ox + 1, 40, 10, 16, mix('#a8d8f0', env.skyLow, 0.2));
        g.r(ox + 1, 40, 10, 2, '#7fb8e0');
        g.r(ox + 3, 60, 3, 2, '#3a8ad0');
        g.r(ox + 7, 60, 3, 2, '#e8483a');
        if (mod(t, 30) < 4) g.p(ox + 5, 46 + mod(t, 4), WHITE);
      } else {
        plant(g, env, ox + 6, 77, L.plant, t, 9);
        g.r(ox + 2, 70, 9, 8, P.pot);
      }
      if (L.critter !== 'none') critter(g, env, L.critter, L.left ? 60 : 160, 77, t, L.left, env.time === 'night');
      if (L.spot === 'chair') mascot(g, env, cx, 62, t);
      else mascot(g, env, (L.left ? 92 : 8) + 50 + 4, 58, t);
    }
  };

  // ---- more scenes (T158, D-129): 지하철, 버스 정류장, 한옥, 농장, 우주정거장, 포장마차

  SC.subway = {
    still: 14,
    setup: function (env, R) {
      return {
        line: R.pick(['accent', 'accent2', 'leaf', '#3a8ad0', '#f0a030']),
        tile: R.pick(['paper', 'wallL', 'accent2L']),
        ads: [R.pick(PAINTINGS), hp(R, env, 'wall', ['poster', 'map', 'calendar', 'neon'])],
        train: R.pick(['pass', 'stop', 'none']),
        bench: R.ch(0.6),
        item: R.pick(['bag', 'umbrella', 'basket', 'none']),
        critter: hp(R, env, 'critter', ['none', 'none', 'pup', 'bird']),
        cup: hp(R, env, 'cup', CUPS),
        mx: R.i(30, 140)
      };
    },
    draw: function (g, env, L, t) {
      var P = env.P;
      var x;
      var y;
      var lc = P[L.line] || hx(L.line);
      var tc = P[L.tile] || P.wallL;
      // far wall tiles beyond the tracks
      g.r(0, 0, W, 48, tc);
      for (y = 0; y < 48; y += 4) g.r(0, y, W, 1, mix(tc, P.ink, 0.1));
      for (y = 0; y < 48; y += 4) for (x = (y / 4) & 1 ? 3 : 0; x < W; x += 6) g.p(x, y + 1, mix(tc, P.ink, 0.08));
      g.r(0, 10, W, 3, lc);
      g.r(0, 13, W, 1, mix(lc, P.ink, 0.3));
      // station name board
      g.r(70, 18, 52, 12, P.paper);
      g.r(70, 18, 52, 2, lc);
      for (var k = 0; k < 4; k++) g.r(78 + k * 9, 22, 6, 5, P.ink);
      g.r(74, 28, 44, 1, P.paperDD);
      // ads
      if (env.bd) {
        // 섞기 모드면 광고판이 그 배경의 여행 광고가 된다
        g.em = 1;
        outside(g, env, 14, 18, 30, 20, t, env.bd);
        g.r(14, 34, 30, 4, P.paper);
        g.r(16, 35, 14, 2, lc);
        g.em = 0;
      } else painting(g, env, L.ads[0], 14, 18, 30, 20, t);
      g.r(13, 17, 32, 1, P.metalD);
      wallArt(g, env, L.ads[1], 150, 18, t, 8);
      // ceiling lights
      g.em = 1;
      for (x = 16; x < W; x += 40) g.r(x, 1, 20, 2, '#f6fbff');
      g.em = 0;
      for (x = 26; x < W; x += 40) g.light(x, 3, 40, [0.9, 0.97, 1], 0.5, { n: 4 });
      // track pit
      g.r(0, 48, W, 8, mix(P.ink, '#2a2a30', 0.4));
      g.r(0, 52, W, 1, P.metalL);
      g.r(0, 54, W, 1, P.metalL);
      for (x = 2; x < W; x += 7) g.r(x, 53, 4, 3, P.woodDD);
      // train
      var tp = L.train === 'pass' ? mod(t * 4, 600) - 200 : (L.train === 'stop' ? 6 : -999);
      if (tp > -190 && tp < W) {
        var tx = L.train === 'pass' ? W - tp - 180 : tp;
        g.r(tx, 18, 180, 34, P.metalL);
        g.r(tx, 18, 180, 2, P.metal);
        g.r(tx, 40, 180, 3, lc);
        g.r(tx, 50, 180, 2, P.ink);
        for (var wv = 0; wv < 6; wv++) {
          var wx2 = tx + 8 + wv * 29;
          if (wv === 2 || wv === 5) {
            g.r(wx2, 22, 16, 28, P.metal);
            g.r(wx2 + 7, 22, 1, 28, P.metalD);
            g.em = 1;
            g.r(wx2 + 2, 24, 4, 10, '#fff6dc');
            g.r(wx2 + 10, 24, 4, 10, '#fff6dc');
            g.em = 0;
          } else {
            g.em = 1;
            g.r(wx2, 24, 22, 12, env.time === 'day' ? '#d8eaf4' : '#fff1c2');
            g.em = 0;
            if (hash(wv, env.seed) < 0.5) g.r(wx2 + 6, 30, 5, 6, P.ink);
          }
        }
      }
      // platform
      g.r(0, 56, W, H - 56, P.floor);
      g.r(0, 56, W, 2, P.paperD);
      for (x = 0; x < W; x++) if (((x >> 1) + 1) % 2 === 0) g.p(x, 59, '#f6d04a');
      g.r(0, 60, W, 2, '#f6d04a');
      for (x = 0; x < W; x += 3) g.p(x, 61, '#c8a02a');
      for (y = 64; y < H; y += 5) g.r(0, y, W, 1, P.floorD);
      // pillar
      g.r(172, 0, 12, 60, mix(tc, P.metal, 0.4));
      g.r(172, 0, 2, 60, P.metalL);
      g.r(172, 24, 12, 4, lc);
      if (L.bench) {
        g.r(100, 68, 36, 3, P.wood);
        g.r(100, 68, 36, 1, P.woodL);
        g.r(102, 71, 2, 7, P.metalD);
        g.r(132, 71, 2, 7, P.metalD);
        cupOf(g, env, L.cup, 130, 68, P.accent, t);
      }
      if (L.item !== 'none') floorThing(g, env, L.item, 40, 78, t);
      if (L.critter !== 'none') critter(g, env, L.critter, 160, 78, t, true);
      mascot(g, env, L.bench ? 108 : L.mx, L.bench ? 68 : 76, t, { sleep: false });
    }
  };

  SC['bus-stop'] = {
    still: 11,
    outdoor: true,
    setup: function (env, R) {
      return {
        far: R.pick(['street', 'city', 'trees', 'hills']),
        shelter: R.pick(['accent', 'accent2', 'metal', 'leafD']),
        ad: R.pick(PAINTINGS),
        bus: R.pick(['accent', 'accent2', 'leaf', '#3a8ad0', '#f6c63a']),
        busOn: R.pick(['come', 'stop', 'none', 'none']),
        thing: hp(R, env, 'street', ['bin', 'hydrant', 'mailbox', 'vending', 'bike', 'none']),
        tree: R.ch(0.6),
        fly: hp(R, env, 'fly', FLYERS),
        critter: hp(R, env, 'critter', ['bird', 'pup', 'none', 'kitty', 'none']),
        floor: hp(R, env, 'floor', ['umbrella', 'bag', 'basket', 'none']),
        left: R.ch(0.5)
      };
    },
    draw: function (g, env, L, t) {
      var P = env.P;
      var x;
      skyTop(g, env, 46, t, 24);
      flyer(g, env, L.fly, 40, 10, t);
      distant(g, env, 0, 10, W, 36, bk(env, L.far), t);
      // road
      g.r(0, 46, W, 12, mix(P.ink, '#5a5a66', 0.5));
      for (x = 0; x < W; x += 16) g.r(x + mod(-t, 16), 51, 8, 1, '#f0f0e0');
      // bus
      var bp = L.busOn === 'come' ? mod(t * 3, 500) - 120 : (L.busOn === 'stop' ? 20 : -999);
      if (bp > -110 && bp < W + 10) {
        var bxp = L.left ? W - bp - 90 : bp;
        if (L.busOn === 'stop') bxp = L.left ? 96 : 6;
        var bc = P[L.bus] || hx(L.bus);
        g.r(bxp, 28, 90, 26, bc);
        g.r(bxp, 28, 90, 2, mix(bc, WHITE, 0.3));
        g.r(bxp, 48, 90, 2, mix(bc, P.ink, 0.3));
        g.em = 1;
        for (var w = 0; w < 6; w++) g.r(bxp + 4 + w * 14, 32, 11, 9, env.time === 'day' ? mix(env.skyLow, WHITE, 0.3) : '#fff1c2');
        g.r(L.left ? bxp + 86 : bxp, 33, 4, 6, env.time === 'day' ? '#d8eaf4' : '#fff6c0');
        g.em = 0;
        disc(g, bxp + 16, 54, 4, P.ink);
        disc(g, bxp + 16, 54, 1, P.metalL);
        disc(g, bxp + 72, 54, 4, P.ink);
        disc(g, bxp + 72, 54, 1, P.metalL);
        g.r(bxp + 40, 36, 10, 2, P.ink);
      }
      // sidewalk
      g.r(0, 58, W, 2, P.paperD);
      g.r(0, 60, W, H - 60, mix(P.paperD, P.floor, 0.4));
      for (x = 0; x < W; x += 12) g.r(x, 60, 1, H - 60, mix(P.paperD, P.ink, 0.15));
      // shelter
      var sx = L.left ? 108 : 24;
      var sc = P[L.shelter];
      g.r(sx - 2, 26, 64, 3, sc);
      g.r(sx - 2, 29, 64, 1, mix(sc, P.ink, 0.3));
      g.r(sx, 29, 2, 46, sc);
      g.r(sx + 58, 29, 2, 46, sc);
      g.br(sx + 2, 30, 56, 40, mix(env.skyLow, WHITE, 0.4), 0.25);
      g.r(sx + 2, 30, 56, 1, mix(sc, WHITE, 0.3));
      painting(g, env, L.ad, sx + 36, 34, 18, 26, t);
      g.r(sx + 35, 33, 20, 1, P.metalD);
      g.r(sx + 35, 60, 20, 1, P.metalD);
      if (env.time !== 'day') g.light(sx + 45, 46, 26, [1, 0.95, 0.85], 0.4, { n: 3 });
      // bench
      g.r(sx + 4, 64, 30, 3, P.wood);
      g.r(sx + 4, 64, 30, 1, P.woodL);
      g.r(sx + 6, 67, 2, 8, P.metalD);
      g.r(sx + 30, 67, 2, 8, P.metalD);
      // sign pole
      var px = L.left ? 96 : 92;
      g.r(px, 30, 2, 46, P.metalD);
      disc(g, px + 1, 28, 5, P.accent2);
      disc(g, px + 1, 28, 3, P.paper);
      g.r(px - 1, 27, 5, 2, P.accent2);
      g.r(px - 4, 40, 10, 12, P.paper);
      for (var k = 0; k < 4; k++) g.r(px - 3, 42 + k * 2, 6 + (k % 2), 1, P.paperDD);
      if (L.tree) {
        var tx = L.left ? 30 : 170;
        g.r(tx, 50, 3, 26, P.woodDD);
        var leaf = autumnal(env) ? '#e0863a' : (snowy(env) ? '#eef3fa' : (blossomy(env) ? '#f6b8cc' : P.leaf));
        g.ell(tx + 1, 40, 12, 10, leaf);
        g.ell(tx - 2, 37, 7, 5, mix(leaf, WHITE, 0.2));
        g.ell(tx + 6, 44, 5, 4, mix(leaf, P.ink, 0.15));
      }
      if (L.thing !== 'none') streetThing(g, env, L.thing, L.left ? 70 : 150, 77, t);
      if (L.floor !== 'none') floorThing(g, env, L.floor, sx + 40, 76, t);
      if (L.critter !== 'none') critter(g, env, L.critter, L.left ? 10 : 130, 78, t, L.left);
      mascot(g, env, sx + 12, 64, t, { sleep: false });
      weather(g, env, 0, 0, W, H, t);
    }
  };

  SC.hanok = {
    still: 5,
    outdoor: true,
    setup: function (env, R) {
      return {
        far: R.pick(['mount', 'hills', 'trees']),
        tree: R.pick(['persimmon', 'pine', 'plum']),
        doors: R.pick(['shut', 'open', 'half']),
        pots: R.i(3, 6),
        lantern: R.ch(0.6),
        line: R.ch(0.45),
        wall: R.ch(0.7),
        left: R.ch(0.5),
        critter: hp(R, env, 'critter', ['bird', 'kitty', 'none', 'chick', 'none']),
        cup: hp(R, env, 'cup', ['tea', 'teapot', 'tea']),
        fly: hp(R, env, 'fly', ['none', 'kite', 'flock', 'lanterns', 'none'])
      };
    },
    draw: function (g, env, L, t) {
      var P = env.P;
      var x;
      var y;
      var snow = snowy(env);
      skyTop(g, env, 50, t, 26);
      flyer(g, env, L.fly, 150, 10, t);
      distant(g, env, 0, 12, W, 34, bk(env, L.far), t);
      // courtyard ground
      g.r(0, 62, W, H - 62, snow ? hx('#eef3fa') : hx('#d8c8a0'));
      for (y = 63; y < H; y++) for (x = 0; x < W; x++) if (bay(x, y) < 0.08) g.p(x, y, snow ? '#d4dfec' : '#c0ae84');
      // stone wall with tiles at the back
      if (L.wall) {
        for (x = 0; x < W; x++) {
          for (y = 44; y < 54; y++) {
            var st = ((fl((x + (fl((y - 44) / 3) & 1) * 3) / 6) * 7 + fl((y - 44) / 3) * 3) % 5);
            g.p(x, y, (x + (fl((y - 44) / 3) & 1) * 3) % 6 === 0 || (y - 44) % 3 === 0 ? '#8a8478' : ['#bab2a2', '#c8c0ae', '#a8a090', '#b4ac9a', '#c2baa8'][st]);
          }
        }
        g.r(0, 42, W, 2, '#4a4e5a');
        g.r(0, 41, W, 1, snow ? '#f4f8ff' : '#5e6270');
      }
      // house
      var hx0 = L.left ? 70 : 12;
      var hw = 110;
      g.r(hx0 + 4, 56, hw - 8, 6, '#b8b0a0');
      g.r(hx0 + 4, 56, hw - 8, 1, '#d0c8b8');
      // maru (wooden porch)
      g.r(hx0 + 8, 50, hw - 16, 3, P.woodL);
      g.r(hx0 + 8, 53, hw - 16, 3, P.woodD);
      for (x = hx0 + 10; x < hx0 + hw - 8; x += 4) g.p(x, 51, P.wood);
      // walls & doors
      g.r(hx0 + 10, 26, hw - 20, 24, '#f2ead8');
      var nd = 4;
      var dw = fl((hw - 20) / nd);
      for (var d = 0; d < nd; d++) {
        var dx = hx0 + 10 + d * dw;
        g.r(dx, 26, 1, 24, P.woodD);
        var open = L.doors === 'open' || (L.doors === 'half' && d === 1);
        if (open) {
          g.r(dx + 2, 28, dw - 4, 22, mix(P.wallD, P.ink, 0.4));
          g.r(dx + 3, 40, dw - 6, 3, P.accent);
          if (env.time !== 'day') g.light(dx + dw / 2, 38, 24, env.T.lamp, env.lampK, { n: 4 });
        } else {
          var lit = env.time !== 'day';
          if (lit) g.em = 1;
          g.r(dx + 2, 28, dw - 4, 20, lit ? '#ffe6b0' : '#fbf6ea');
          g.em = 0;
          for (x = dx + 2; x < dx + dw - 2; x += 3) g.r(x, 28, 1, 20, P.woodL);
          for (y = 28; y < 48; y += 4) g.r(dx + 2, y, dw - 4, 1, P.woodL);
        }
      }
      // pillars
      for (x = hx0 + 9; x <= hx0 + hw - 11; x += fl((hw - 20) / 2)) g.r(x, 22, 3, 34, P.woodD);
      // tiled roof with upturned eaves
      var roofC = snow ? hx('#f2f6fc') : hx('#4a4e5a');
      var roofD = snow ? hx('#ccd8ea') : hx('#353842');
      for (y = 0; y < 14; y++) {
        var inset = Math.max(0, 10 - y * 1.5);
        var lift = 0;
        for (x = hx0 - 6 + inset; x < hx0 + hw + 6 - inset; x++) {
          var ex = Math.min(x - (hx0 - 6), hx0 + hw + 6 - x);
          lift = ex < 10 ? rnd((10 - ex) * 0.35) : 0;
          var yy = 8 + y - lift;
          g.p(x, yy, (x % 4 === 0 && y > 2) ? roofD : roofC);
        }
      }
      g.r(hx0 + 14, 6, hw - 28, 3, roofD);
      g.r(hx0 + 10, 21, hw - 20, 2, P.accent2D);
      g.r(hx0 + 10, 20, hw - 20, 1, '#3a8a6a');
      if (L.lantern) {
        for (var ln = 0; ln < 2; ln++) {
          var lx = hx0 + 22 + ln * (hw - 44);
          var sw = rnd(Math.sin(t * 0.15 + ln) * 1);
          g.r(lx + sw, 23, 1, 3, P.ink);
          g.em = env.time !== 'day' ? 1 : 0;
          g.r(lx - 2 + sw, 26, 5, 6, '#3a5ad0');
          g.r(lx - 2 + sw, 28, 5, 2, '#e84a3a');
          g.em = 0;
          if (env.time !== 'day') g.light(lx, 29, 20, [1, 0.6, 0.5], 0.4, { n: 3 });
        }
      }
      // jangdokdae (onggi pots)
      var jx = L.left ? 10 : 132;
      g.r(jx - 2, 66, 50, 3, '#a8a090');
      for (var p = 0; p < L.pots; p++) {
        var px = jx + 2 + (p % 4) * 11 + (p >= 4 ? 5 : 0);
        var pyb = 66 - (p >= 4 ? 5 : 0);
        var big = p % 2 === 0 ? 1 : 0;
        g.ell(px + 4, pyb - 4 - big, 4 + big, 4 + big, '#6a3a24');
        g.r(px + 1, pyb - 9 - big * 2, 7, 2, snow ? hx('#f4f8ff') : hx('#4a2a1a'));
        g.p(px + 2, pyb - 6, '#8a5a3a');
      }
      // tree
      var tx = L.left ? 60 : 130;
      if (L.tree === 'pine') {
        g.r(tx, 40, 3, 26, '#6a4a30');
        g.line(tx + 1, 46, tx + 10, 38, '#6a4a30');
        g.ell(tx + 2, 36, 10, 3, snow ? '#e8f0f6' : '#2f6a40');
        g.ell(tx + 10, 34, 7, 2, snow ? '#e8f0f6' : '#3a7a48');
        g.ell(tx - 2, 30, 7, 2, snow ? '#f4f8ff' : '#2f6a40');
      } else {
        g.r(tx, 42, 3, 24, '#5a3a24');
        g.line(tx + 1, 46, tx - 8, 36, '#5a3a24');
        g.line(tx + 2, 44, tx + 10, 34, '#5a3a24');
        var bloom = L.tree === 'plum' ? (snow ? '#ffffff' : '#ffb0c8') : (autumnal(env) || !blossomy(env) ? '#e8803a' : '#ffd0a0');
        var leafC = L.tree === 'persimmon' && !snow ? (autumnal(env) ? '#c8803a' : P.leaf) : null;
        if (leafC) {
          g.ell(tx + 1, 34, 13, 8, leafC);
          g.ell(tx - 3, 31, 7, 4, mix(leafC, WHITE, 0.2));
        }
        for (var b = 0; b < 14; b++) {
          var bx = tx + 1 + rnd((hash(b, 5) - 0.5) * 24);
          var by = 28 + fl(hash(b, 6) * 14);
          g.p(bx, by, bloom);
          if (L.tree === 'persimmon') g.p(bx + 1, by, bloom);
        }
      }
      if (L.line) {
        var lx0 = L.left ? 4 : 120;
        g.line(lx0, 52, lx0 + 60, 50, P.paperDD);
        for (var c = 0; c < 4; c++) {
          var cx = lx0 + 8 + c * 13;
          g.r(cx, 51, 7, 7 + (c % 2) * 2, [P.paper, P.accent2L, '#ffffff', P.accentL][c]);
        }
      }
      // tray of tea on the maru
      g.r(hx0 + 40, 48, 14, 2, P.woodDD);
      cupOf(g, env, L.cup, hx0 + 44, 48, P.paper, t);
      if (L.critter !== 'none') critter(g, env, L.critter, L.left ? 40 : 170, 75, t, !L.left);
      mascot(g, env, hx0 + 60, 50, t);
      weather(g, env, 0, 0, W, H, t);
    }
  };

  var CROPS = ['cabbage', 'corn', 'pumpkin', 'berry', 'sprout'];

  SC.farm = {
    still: 6,
    outdoor: true,
    setup: function (env, R) {
      return {
        far: R.pick(['hills', 'trees', 'hills']),
        barn: R.pick(['#c84a3a', '#c8783a', P0(env, R)]),
        crops: [R.pick(CROPS), R.pick(CROPS)],
        fence: R.pick(['post', 'rail', 'none']),
        extra: R.pick(['scarecrow', 'tractor', 'well', 'silo']),
        critters: [hp(R, env, 'critter', ['chick', 'duck', 'bunny', 'none']), hp(R, env, 'critter', ['chick', 'pup', 'kitty', 'none'])],
        fly: hp(R, env, 'fly', ['flock', 'kite', 'balloon', 'none', 'none']),
        left: R.ch(0.5)
      };
    },
    draw: function (g, env, L, t) {
      var P = env.P;
      var x;
      var y;
      var snow = snowy(env);
      skyTop(g, env, 50, t, 26);
      flyer(g, env, L.fly, 60, 12, t);
      distant(g, env, 0, 14, W, 34, bk(env, L.far), t);
      grass(g, env, 48, H, 3);
      // barn
      var bx = L.left ? 128 : 8;
      var bc = hx(L.barn);
      g.r(bx, 30, 50, 26, bc);
      for (x = bx; x < bx + 50; x += 4) g.r(x, 30, 1, 26, mix(bc, P.ink, 0.2));
      for (y = 0; y < 12; y++) g.r(bx - 2 + y * 2, 30 - y, 54 - y * 4, 1, snow ? '#f4f8ff' : mix(bc, P.ink, 0.35));
      g.r(bx + 16, 38, 18, 18, P.paper);
      g.r(bx + 18, 40, 14, 16, mix(bc, P.ink, 0.2));
      g.line(bx + 18, 40, bx + 31, 55, P.paper);
      g.line(bx + 31, 40, bx + 18, 55, P.paper);
      g.r(bx + 22, 22, 6, 5, P.paper);
      g.r(bx + 23, 23, 4, 3, env.time === 'day' ? P.ink : env.litC);
      // fields
      for (var f = 0; f < 2; f++) {
        var fx = L.left ? 8 + f * 58 : 70 + f * 60;
        var fw = 52;
        g.r(fx, 60, fw, 18, snow ? hx('#e0e6ee') : hx('#8a6a48'));
        for (y = 62; y < 78; y += 5) g.r(fx, y, fw, 1, snow ? '#c8d4e4' : '#6a4e34');
        if (snow) continue;
        var kind = L.crops[f];
        for (y = 61; y < 78; y += 5) {
          for (x = fx + 3; x < fx + fw - 3; x += 7) {
            if (kind === 'cabbage') {
              g.ell(x + 2, y, 3, 2, P.leafL);
              g.ell(x + 2, y, 1, 1, P.leaf);
            } else if (kind === 'corn') {
              g.r(x + 2, y - 8, 1, 9, P.leafD);
              g.r(x + 3, y - 6, 2, 4, '#f6d04a');
              g.p(x, y - 4, P.leaf);
              g.p(x + 4, y - 2, P.leaf);
            } else if (kind === 'pumpkin') {
              g.ell(x + 2, y, 3, 2, '#e8803a');
              g.p(x + 2, y - 3, P.leafD);
            } else if (kind === 'berry') {
              g.ell(x + 2, y, 3, 1, P.leaf);
              g.p(x + 1, y + 1, '#e8483a');
              g.p(x + 4, y, '#e8483a');
            } else {
              g.p(x + 2, y, P.leafL);
              g.p(x + 1, y - 1, P.leaf);
              g.p(x + 3, y - 1, P.leaf);
            }
          }
        }
      }
      // fence
      if (L.fence !== 'none') {
        for (x = 0; x < W; x += 8) {
          g.r(x, 50, 2, 8, P.woodL);
          g.p(x, 49, P.woodL);
        }
        g.r(0, 52, W, 1, P.woodD);
        if (L.fence === 'rail') g.r(0, 55, W, 1, P.woodD);
      }
      var ex = L.left ? 74 : 132;
      if (L.extra === 'scarecrow') {
        ex = L.left ? 34 : 150;
        g.r(ex + 4, 46, 2, 18, P.woodD);
        g.r(ex - 2, 50, 14, 2, P.woodD);
        g.r(ex + 1, 48, 8, 8, P.accent2);
        g.r(ex + 2, 41, 6, 6, '#f0d8a8');
        g.r(ex, 40, 10, 2, '#d8a83a');
        g.r(ex + 2, 37, 6, 3, '#d8a83a');
        g.p(ex + 3, 43, P.ink);
        g.p(ex + 6, 43, P.ink);
      } else if (L.extra === 'tractor') {
        g.r(ex, 46, 22, 8, P.accent);
        g.r(ex + 12, 38, 9, 8, P.accent);
        g.r(ex + 13, 39, 7, 5, mix(env.skyLow, WHITE, 0.3));
        g.r(ex + 4, 42, 2, 4, P.ink);
        disc(g, ex + 16, 56, 5, P.ink);
        disc(g, ex + 16, 56, 2, '#f6d04a');
        disc(g, ex + 4, 57, 3, P.ink);
        if (mod(t, 6) < 3) g.b(ex + 5, 39 - mod(t, 3), P.paperD, 0.6);
      } else if (L.extra === 'well') {
        g.r(ex, 48, 16, 8, '#a8a090');
        g.r(ex, 48, 16, 1, '#c8c0ae');
        g.r(ex + 1, 36, 2, 12, P.woodD);
        g.r(ex + 13, 36, 2, 12, P.woodD);
        g.r(ex - 1, 34, 18, 2, P.accent);
        g.r(ex + 7, 40, 3, 4, P.woodL);
      } else {
        g.r(ex, 20, 14, 36, P.metalL);
        for (y = 22; y < 56; y += 4) g.r(ex, y, 14, 1, P.metal);
        g.ell(ex + 7, 20, 7, 4, P.metal);
      }
      var c0 = L.critters[0];
      var c1 = L.critters[1];
      if (c0 !== 'none') critter(g, env, c0, L.left ? 116 : 60, 78, t, false);
      if (c1 !== 'none') critter(g, env, c1, L.left ? 96 : 80, 77, t, true);
      mascot(g, env, L.left ? 110 : 66, 60, t, { sleep: false });
      weather(g, env, 0, 0, W, H, t);
    }
  };
  // barn colour from the palette accent
  function P0(env, R) {
    return '#' + env.P.accent.map(function (v) {
      return ('0' + v.toString(16)).slice(-2);
    }).join('');
  }

  SC['space-station'] = {
    still: 16,
    setup: function (env, R) {
      return {
        layout: R.pick(['two', 'wide', 'three']),
        planet: R.pick(['earth', 'ringed', 'moon', 'red']),
        screen: R.pick(['code', 'radar', 'map']),
        floats: [hp(R, env, 'cup', CUPS), hp(R, env, 'trinket', TRINKETS), R.pick(['book', 'wrench', 'apple', 'none'])],
        panel: R.pick(['metal', 'paper', 'accent2L']),
        pod: R.ch(0.55),
        plant: hp(R, env, 'plant', PLANTS2),
        left: R.ch(0.5)
      };
    },
    draw: function (g, env, L, t) {
      var P = env.P;
      var x;
      var y;
      var pc = P[L.panel];
      g.r(0, 0, W, H, mix(pc, P.ink, 0.15));
      for (x = 0; x < W; x += 24) {
        g.r(x, 0, 1, H, mix(pc, P.ink, 0.35));
        g.r(x + 1, 0, 1, H, mix(pc, WHITE, 0.2));
      }
      for (y = 0; y < H; y += 20) g.r(0, y, W, 1, mix(pc, P.ink, 0.3));
      for (x = 4; x < W; x += 24) for (y = 4; y < H; y += 20) g.p(x, y, P.metalD);
      // windows into space
      var spaceIn = function (wx, wy, ww, wh) {
        g.r(wx, wy, ww, wh, '#07061a');
        for (var s = 0; s < (ww * wh) / 40; s++) {
          var sx = wx + fl(hash(env.seed + s, wx) * ww);
          var sy = wy + fl(hash(s, env.seed + wy) * wh);
          g.p(sx, sy, mod(t + s * 3, 40) < 2 ? '#ffffff' : (s % 3 ? '#aeb9ea' : '#fff6dc'));
        }
        var px = wx + fl(ww * 0.65);
        var py = wy + fl(wh * 0.62);
        var r = Math.min(10, fl(wh * 0.35));
        if (L.planet === 'earth') {
          disc(g, px, py, r, '#3a7ad0');
          for (var k = 0; k < 8; k++) g.ell(px - r + fl(hash(k, 3) * r * 2), py - r + fl(hash(k, 4) * r * 2), 2, 1, '#5ab86a');
          for (k = 0; k < 4; k++) g.r(px - r + fl(hash(k, 5) * r * 1.5), py - r + 2 + k * 4, 5, 1, '#ffffff');
          halo(g, px, py, r, 3, '#7fc0ff', 0.5);
        } else if (L.planet === 'ringed') {
          disc(g, px, py, r - 2, '#e8c890');
          g.line(px - r - 4, py + 3, px + r + 4, py - 3, '#f0e0c0');
        } else if (L.planet === 'moon') {
          disc(g, px, py, r, '#c8c8d0');
          g.ell(px - 3, py - 2, 2, 1, '#a8a8b4');
          g.ell(px + 3, py + 3, 3, 2, '#a8a8b4');
        } else {
          disc(g, px, py, r, '#d0603a');
          g.r(px - r + 2, py - 2, r * 2 - 4, 1, '#a8482a');
        }
      };
      var wins = L.layout === 'wide' ? [[40, 10, 112, 34]] : (L.layout === 'two' ? [[24, 10, 40, 34], [128, 10, 40, 34]] : [[18, 12, 30, 30], [81, 12, 30, 30], [144, 12, 30, 30]]);
      for (var i = 0; i < wins.length; i++) {
        var wv = wins[i];
        var round = L.layout === 'three';
        win(g, env, wv[0], wv[1], wv[2], wv[3], t, {
          shape: round ? 'round' : 'rect',
          panes: 1,
          sill: false,
          frame: P.metalL,
          open: false,
          inner: env.bd ? null : spaceIn
        });
      }
      // console along the floor
      g.r(0, 56, W, 4, P.metalD);
      g.r(0, 60, W, H - 60, mix(P.metal, P.ink, 0.35));
      for (x = 0; x < W; x += 6) g.r(x, 62, 4, 1, mix(P.metal, P.ink, 0.2));
      g.em = 1;
      for (x = 6; x < W; x += 9) {
        var on = hash(x, fl(t / 6) + env.seed) < 0.5;
        g.p(x, 58, on ? ['#ff6a5a', '#7cff9a', '#ffd36e', '#6ac8ff'][(x / 9) % 4 | 0] : '#3a3f52');
      }
      var cx = L.left ? 12 : 140;
      g.r(cx, 46, 40, 10, '#102030');
      if (L.screen === 'code') codeScreen(g, env, cx + 1, 47, 38, 8, t);
      else if (L.screen === 'radar') {
        g.ell(cx + 20, 51, 8, 4, '#1a4030');
        var a = t * 0.2;
        g.line(cx + 20, 51, cx + 20 + rnd(Math.cos(a) * 8), 51 + rnd(Math.sin(a) * 4), '#7cff9a');
        g.p(cx + 14, 49, mod(t, 8) < 4 ? '#7cff9a' : '#1a4030');
      } else {
        for (var q = 0; q < 38; q++) g.p(cx + 1 + q, 51 + rnd(Math.sin(q * 0.4 + t * 0.2) * 3), '#6ac8ff');
      }
      g.em = 0;
      g.light(cx + 20, 51, 26, [0.6, 0.9, 1], 0.35, { n: 3 });
      if (L.pod) {
        var px2 = L.left ? 156 : 6;
        g.r(px2, 30, 28, 26, P.metalD);
        g.em = 1;
        g.r(px2 + 2, 32, 24, 22, '#e8d8ff');
        g.em = 0;
        plant(g, env, px2 + 8, 54, L.plant, t, 3);
        plant(g, env, px2 + 20, 54, 'tall', t, 5);
        g.light(px2 + 14, 40, 24, [0.9, 0.6, 1], 0.35, { n: 3 });
      }
      // floating things
      for (i = 0; i < 3; i++) {
        var fk = L.floats[i];
        if (fk === 'none') continue;
        var fx = 60 + i * 34 + rnd(Math.sin(t * 0.07 + i * 2) * 4);
        var fy = 34 + i * 4 + rnd(Math.sin(t * 0.09 + i) * 3);
        if (i === 0) cupOf(g, env, fk, fx, fy, P.accent, 0);
        else if (i === 1) trinket(g, env, fk, fx, fy, t);
        else if (fk === 'book') {
          g.r(fx, fy - 2, 8, 4, P.accent2);
          g.r(fx + 1, fy - 2, 6, 1, P.paper);
        } else if (fk === 'wrench') {
          g.line(fx, fy, fx + 6, fy - 4, P.metalL);
          g.r(fx + 6, fy - 6, 2, 3, P.metalL);
        } else {
          g.ell(fx + 2, fy, 2, 2, '#e8483a');
          g.p(fx + 2, fy - 3, P.leafD);
        }
      }
      var bob = rnd(Math.sin(t * 0.1) * 2);
      mascot(g, env, 88, 52 + bob, t, { sleep: false });
    }
  };

  SC.pojangmacha = {
    still: 7,
    setup: function (env, R) {
      return {
        tarp: R.pick(['#e8803a', '#d8503a', '#3a7ad0', '#e8a83a']),
        stool: R.pick(['#e8483a', '#3a8ad0', '#5ab86a']),
        pots: R.pick(['tteok', 'odeng', 'both']),
        menu: R.i(3, 5),
        bottles: R.i(1, 3),
        bulbs: R.pick([2, 3]),
        flap: R.ch(0.65),
        critter: hp(R, env, 'critter', ['kitty', 'none', 'none', 'pup']),
        left: R.ch(0.5)
      };
    },
    draw: function (g, env, L, t) {
      var P = env.P;
      var x;
      var y;
      var tarp = hx(L.tarp);
      var tarpL = mix(tarp, '#fff0c8', 0.35);
      var tarpD = mix(tarp, P.ink, 0.25);
      // tent walls. 밤에는 안쪽 전구 빛이 비쳐 천막이 스스로 빛난다
      var glow = env.time === 'day' ? 0 : 1;
      if (glow) {
        tarpL = mix(mix(tarp, '#ffd890', 0.45), P.ink, env.time === 'night' ? 0.18 : 0.08);
        tarpD = mix(tarpL, P.ink, 0.25);
      }
      g.em = glow;
      g.r(0, 0, W, 60, tarpL);
      for (x = 0; x < W; x += 32) g.r(x, 0, 1, 60, tarpD);
      for (y = 0; y < 60; y++) for (x = 0; x < W; x++) if (bay(x, y) < 0.05) g.p(x, y, mix(tarpL, tarpD, 0.6));
      for (y = 0; y < 60; y++) for (x = 0; x < W; x++) if (bay(x, y) < 0.5 && y > 52) g.p(x, y, mix(tarpL, tarpD, 0.4));
      g.em = 0;
      // clear vinyl flaps showing outside
      if (L.flap || env.bd) {
        var fw = 60;
        var fx = L.left ? 120 : 12;
        win(g, env, fx, 8, fw, 28, t, { panes: 1, sill: false, frame: tarpD, view: 'street' });
        g.br(fx + 2, 10, fw - 4, 24, '#fff6dc', 0.15);
        for (x = fx + 4; x < fx + fw; x += 9) g.b(x, 12, WHITE, 0.3);
      }
      // menu boards
      var mx = L.left ? 20 : 96;
      for (var m = 0; m < L.menu; m++) {
        var bx = mx + m * 16;
        g.r(bx, 10, 13, 18, P.paper);
        g.r(bx + 2, 12, 9, 3, P.ink);
        g.r(bx + 2, 17, 7, 1, P.paperDD);
        g.r(bx + 2, 20, 9, 2, '#e8483a');
        g.r(bx + 3, 24, 6, 1, P.paperDD);
      }
      // bulbs
      for (var b = 0; b < L.bulbs; b++) {
        var lx = 40 + b * (112 / (L.bulbs - 1 || 1));
        var sw = rnd(Math.sin(t * 0.12 + b) * 1);
        g.line(lx, 0, lx + sw, 6, P.ink);
        g.em = 1;
        g.ell(lx + sw, 8, 2, 2, '#ffe08a');
        g.em = 0;
        halo(g, fl(lx + sw), 8, 2, 4, '#ffe9a8', 0.6);
        g.light(lx + sw, 9, 64, [1, 0.78, 0.45], Math.max(0.4, env.lampK * 0.7), { top: 6 });
      }
      // counter
      g.r(0, 44, W, 4, P.metalL);
      g.r(0, 48, W, 12, P.metalD);
      g.r(0, 60, W, H - 60, mix(P.floor, P.ink, 0.25));
      // pots on the counter
      var kx = L.left ? 120 : 20;
      var drawPot = function (px, kind) {
        g.r(px, 36, 30, 8, P.metal);
        g.r(px, 36, 30, 1, P.metalL);
        if (kind === 'tteok') {
          g.r(px + 1, 37, 28, 3, '#d8382a');
          for (var k = 0; k < 6; k++) g.r(px + 3 + k * 4, 37 + (k % 2), 3, 1, '#f6e0c8');
        } else {
          g.r(px + 1, 37, 28, 3, '#c8a060');
          for (var k2 = 0; k2 < 5; k2++) {
            g.r(px + 4 + k2 * 5, 28, 1, 10, P.woodL);
            g.r(px + 3 + k2 * 5, 32, 3, 5, '#e0b878');
          }
        }
        steam(g, px + 10, 35, t, 8, px);
        steam(g, px + 20, 35, t + 3, 7, px + 1);
      };
      if (L.pots === 'both') {
        drawPot(kx, 'tteok');
        drawPot(kx + 34, 'odeng');
      } else drawPot(kx, L.pots);
      // bottles and glasses
      var gx = L.left ? 30 : 140;
      for (var q = 0; q < L.bottles; q++) {
        g.r(gx + q * 8, 34, 4, 10, '#3a9a5a');
        g.r(gx + q * 8 + 1, 32, 2, 2, '#3a9a5a');
        g.r(gx + q * 8, 38, 4, 3, P.paper);
      }
      g.r(gx + 26, 40, 3, 4, mix('#e8f4fa', P.metalL, 0.3));
      g.r(gx + 31, 40, 3, 4, mix('#e8f4fa', P.metalL, 0.3));
      // plastic stools
      var sc = hx(L.stool);
      for (var s = 0; s < 3; s++) {
        var sx = 28 + s * 56;
        g.r(sx, 66, 16, 3, sc);
        g.r(sx + 1, 69, 2, 9, mix(sc, P.ink, 0.25));
        g.r(sx + 13, 69, 2, 9, mix(sc, P.ink, 0.25));
        g.r(sx + 3, 72, 10, 1, mix(sc, P.ink, 0.25));
      }
      if (L.critter !== 'none') critter(g, env, L.critter, L.left ? 6 : 176, 78, t, !L.left);
      mascot(g, env, 84 + 2, 66, t, { sleep: false });
    }
  };

  // ------------------------------------------------ layers (T158, D-129)
  //
  // 저장값에 일곱째 칸(배경~계절)이 있으면 '섞기' 모드로 그린다. 장면(구도)은 그대로 두고
  // 배경(창밖·뒤 풍경), 계절·행사 장식, 벽·바닥 재질, 가구 색, 마스코트 자세, 좌우 뒤집기를
  // 따로 골라 겹친다. 일곱째 칸이 없는 예전 값은 이 함수들을 지나지 않아 그림이 그대로다.

  function snowy(env) {
    return env.weather === 'snow' || env.ev === 'frost' || env.ev === 'xmas';
  }
  function autumnal(env) {
    return env.weather === 'leaves' || env.ev === 'fall';
  }
  function blossomy(env) {
    return env.weather === 'petals' || env.ev === 'bloom';
  }
  // 배경 칸이 있으면 그 배경, 없으면 장면이 정한 풍경
  function bk(env, def) {
    return env.bd || def;
  }

  var SKY_OVERRIDE = {
    space: { sky: ['#07061a', '#0d0b2a', '#16123d', '#211a52', '#2c2266'], stars: 1 },
    aurora: { sky: ['#050f24', '#0a1a36', '#0f2a46', '#153a54', '#1c4a60'], stars: 0.9 },
    under: { sky: ['#0a2a5a', '#0f3f78', '#16578f', '#1f70a6', '#2a8abd'], stars: 0 }
  };

  var WOODS = {
    oak: ['#d8b07a', '#9a7448', '#ecd0a0'],
    walnut: ['#8a5a3a', '#5a3622', '#a8744e'],
    white: ['#ece6dc', '#b8b0a4', '#fbf8f2'],
    mint: ['#a8d4c0', '#6a9a88', '#cce8dc'],
    black: ['#4a4650', '#2a2830', '#6a6670'],
    rose: ['#e0a8a8', '#a86a72', '#f2c8c4']
  };

  // 섞기 모드에서 배치 번호로 정하는 칸들. Env를 만든 뒤 부른다
  function mixLayers(env) {
    var X = xr(env, 101);
    var P = env.P;
    env.mirror = X.ch(0.5);
    env.pose = X.pick(['', '', 'flip', 'hop', 'nap', 'awake']);
    env.wallX = X.pick(['', '', 'brick', 'plank', 'tile', 'stripe', 'dots', 'check']);
    env.floorX = X.pick(['', '', 'herring', 'carpet', 'check', 'concrete']);
    var wood = X.pick(['', '', 'oak', 'walnut', 'white', 'mint', 'black', 'rose']);
    if (wood) {
      var wv = WOODS[wood];
      P.wood = hx(wv[0]);
      P.woodD = hx(wv[1]);
      P.woodL = hx(wv[2]);
      P.woodG = mix(P.wood, P.woodD, 0.3);
      P.woodDD = mix(P.woodD, P.ink, 0.35);
      P.frame = P.woodL;
    }
    var so = SKY_OVERRIDE[env.bd];
    if (so) {
      env.sky = so.sky.map(hx);
      env.skyLow = env.sky[env.sky.length - 1];
      env.skyMid = env.sky[2];
      env.starK = so.stars;
      if (env.bd !== 'under') {
        env.cloudL = mix(env.cloudL, env.sky[1], 0.6);
        env.cloudD = mix(env.cloudD, env.sky[0], 0.6);
      }
    }
  }

  function wallTex(g, env, floorY, kind) {
    var P = env.P;
    var x;
    var y;
    var line = mix(P.wall, P.ink, 0.14);
    if (kind === 'brick') {
      for (y = 2; y < floorY; y += 4) {
        g.r(0, y, W, 1, line);
        for (x = ((y >> 2) & 1) * 4; x < W; x += 8) g.r(x, y + 1, 1, 3, line);
      }
    } else if (kind === 'plank') {
      for (x = 3; x < W; x += 7) g.r(x, 0, 1, floorY, line);
      for (x = 0; x < W; x += 7) g.p(x + 3, mod(x * 13, floorY - 4) + 2, line);
    } else if (kind === 'tile') {
      var tc = mix(P.wall, WHITE, 0.25);
      for (y = 0; y < floorY; y++) {
        for (x = 0; x < W; x++) if ((x % 6 === 0) || (y % 6 === 0)) g.p(x, y, line);
          else if ((x + y) % 6 === 1) g.p(x, y, tc);
      }
    } else if (kind === 'stripe') {
      for (y = 4; y < floorY; y += 9) {
        g.r(0, y, W, 2, P.wallS);
        g.r(0, y + 3, W, 1, mix(P.wall, P.accent2, 0.25));
      }
    } else if (kind === 'dots') {
      for (y = 4; y < floorY; y += 6) {
        for (x = ((y / 6) & 1) * 3 + 2; x < W; x += 6) g.p(x, y, mix(P.wall, P.accent, 0.35));
      }
    } else {
      for (y = 0; y < floorY; y++) {
        for (x = 0; x < W; x++) if (((fl(x / 8) + fl(y / 8)) & 1) === 0 && bay(x, y) < 0.5) g.p(x, y, P.wallS);
      }
    }
  }

  function floorTex(g, env, floorY, kind) {
    var P = env.P;
    var x;
    var y;
    var d = P.floorD;
    if (kind === 'herring') {
      for (y = floorY + 1; y < H; y++) {
        for (x = 0; x < W; x++) if (mod(x + (y - floorY) * (fl(x / 6) & 1 ? 1 : -1), 6) === 0) g.p(x, y, d);
      }
    } else if (kind === 'carpet') {
      var c = mix(P.floor, P.rug, 0.55);
      g.r(0, floorY + 1, W, H - floorY - 1, c);
      for (y = floorY + 2; y < H; y++) {
        for (x = 0; x < W; x++) if (bay(x, y) < 0.12) g.p(x, y, mix(c, P.ink, 0.15));
      }
    } else if (kind === 'check') {
      for (y = floorY + 1; y < H; y++) {
        var row = fl((y - floorY - 1) / 4);
        for (x = 0; x < W; x++) if (((fl(x / 8) + row) & 1) === 0) g.p(x, y, mix(P.floor, P.paper, 0.55));
      }
    } else {
      var cc = mix(P.metal, P.floor, 0.4);
      g.r(0, floorY + 1, W, H - floorY - 1, cc);
      for (x = 0; x < W; x += 32) g.r(x, floorY + 1, 1, H - floorY - 1, mix(cc, P.ink, 0.15));
      for (y = floorY + 1; y < H; y++) for (x = 0; x < W; x++) if (bay(x, y) < 0.06) g.p(x, y, mix(cc, P.ink, 0.2));
    }
  }

  // ---- backdrops (distant()가 모르는 풍경)

  function scenery(g, env, x, y, w, h, kind, t) {
    var P = env.P;
    var low = env.skyLow;
    var sd = env.seed;
    var snow = snowy(env);
    var xx;
    var yy;
    var i;
    var k;
    function band(fy, amp, freq, ph, c, cD) {
      for (var bx = 0; bx < w; bx++) {
        var gx = x + bx;
        var top = y + h * fy + Math.sin((gx + ph) * freq) * h * amp + Math.sin(gx * 0.31 + ph) * 0.6;
        for (var by = fl(top); by < y + h; by++) g.p(gx, by, cD && by - top > 2 && bay(gx, by) < 0.5 ? cD : c);
      }
    }
    function waterFrom(fy, deep, shal) {
      var hz = y + fl(h * fy);
      for (var wy = hz; wy < y + h; wy++) {
        var f = (wy - hz) / Math.max(1, y + h - hz);
        var c = mix(deep, shal, f * 0.7);
        g.r(x, wy, w, 1, c);
        if (wy & 1) {
          for (var q = 0; q < 3; q++) {
            var dx = mod(hash(q, wy) * w + t * (0.15 + f * 0.4) * (q & 1 ? 1 : -1), w);
            g.r(x + dx, wy, 2 + fl(hash(wy, q) * 3), 1, mix(c, WHITE, 0.3));
          }
        }
      }
      g.r(x, hz, w, 1, mix(low, WHITE, 0.3));
      return hz;
    }
    function bushTree(tx, ty, r, c, cL) {
      g.r(tx, ty - 1, 1, r + 2, tone(env, P.woodD));
      g.ell(tx, ty - r, r, r, c);
      g.ell(tx - 1, ty - r - 1, Math.max(1, r - 1), Math.max(1, r - 1), cL);
    }
    if (kind === 'sea') {
      band(0.5, 0.05, 0.05, sd % 60, tone(env, mix(low, '#7a9a8a', 0.5)));
      var hz = waterFrom(0.62, mix(env.skyMid, '#1a4a8a', 0.55), hx('#3aa8c0'));
      var bxp = x + mod(t * 0.12 + sd * 7, w + 10) - 5;
      g.r(bxp, hz + 1, 5, 1, P.paper);
      g.r(bxp + 2, hz - 4, 1, 5, P.woodDD);
      g.r(bxp + 3, hz - 3, 2, 3, P.paper);
    } else if (kind === 'mount') {
      var mc = tone(env, mix(low, '#8090b8', 0.45));
      var mD = mix(mc, '#2e3656', 0.25);
      for (xx = 0; xx < w; xx++) {
        var gx = x + xx;
        var pk1 = Math.abs(mod(gx + sd % 40, 70) - 35);
        var top = y + h * 0.18 + pk1 * h * 0.017;
        for (yy = fl(top); yy < y + h; yy++) {
          var sn = yy < top + 3 + (gx % 3);
          var shd = mod(gx + sd % 40, 70) > 35;
          g.p(gx, yy, sn ? (shd ? hx('#c9d3ea') : hx('#f4f6fb')) : (shd ? mD : mc));
        }
      }
      band(0.78, 0.04, 0.06, 20, tone(env, snow ? '#eef3fa' : P.leaf), tone(env, snow ? '#ccd8ea' : P.leafD));
    } else if (kind === 'snow') {
      band(0.55, 0.08, 0.05, sd % 50, tone(env, '#e8eef6'));
      band(0.75, 0.06, 0.04, 9, tone(env, '#f6f9fc'), tone(env, '#d4dfec'));
      for (i = 0; i < fl(w / 12); i++) {
        var px = x + fl(hash(sd + 3, i) * w);
        var pb = y + fl(h * 0.8);
        for (k = 0; k < 7; k++) g.r(px - fl(k / 2.5), pb - 7 + k, fl(k / 2.5) * 2 + 1, 1, k % 3 === 0 ? hx('#f4f8ff') : tone(env, '#3f6a5e'));
      }
    } else if (kind === 'desert') {
      band(0.5, 0.06, 0.03, sd % 80, tone(env, mix(low, '#e0a070', 0.55)));
      band(0.66, 0.07, 0.045, 13, tone(env, '#e8b878'), tone(env, '#d09a5a'));
      band(0.84, 0.04, 0.06, 41, tone(env, '#f0c88a'), tone(env, '#dcae70'));
      if (w > 60) {
        var pyx = x + fl(w * 0.7);
        var pyb = y + fl(h * 0.68);
        for (k = 0; k < 9; k++) {
          g.r(pyx - k, pyb - 9 + k, k + 1, 1, tone(env, '#e8c080'));
          g.r(pyx + 1, pyb - 9 + k, k, 1, tone(env, '#c8955a'));
        }
      }
      for (i = 0; i < Math.max(1, fl(w / 50)); i++) {
        var cx = x + fl(hash(sd + 5, i) * (w - 6)) + 3;
        var cb = y + fl(h * 0.9);
        var cc = tone(env, '#4a8a4a');
        g.r(cx, cb - 8, 2, 8, cc);
        g.r(cx - 2, cb - 6, 1, 3, cc);
        g.r(cx - 2, cb - 4, 2, 1, cc);
        g.r(cx + 3, cb - 7, 1, 3, cc);
        g.r(cx + 2, cb - 5, 2, 1, cc);
      }
    } else if (kind === 'space') {
      // ground of a small moon with craters
      var gc = tone(env, '#8a8698');
      band(0.74, 0.05, 0.05, sd % 30, gc, tone(env, '#6e6a80'));
      for (i = 0; i < fl(w / 20); i++) {
        var crx = x + fl(hash(sd + 9, i) * w);
        var cry = y + fl(h * (0.84 + hash(sd, i) * 0.1));
        g.ell(crx, cry, 3, 1, tone(env, '#5e5a70'));
        g.r(crx - 2, cry - 1, 4, 1, tone(env, '#a8a4b8'));
      }
      if (w > 30) {
        var fx = x + fl(w * 0.25);
        var fy = y + fl(h * 0.74) - 6;
        g.r(fx, fy, 1, 6, P.metalL);
        g.r(fx + 1, fy, 4, 3, P.accent);
      }
      // drifting rocket now and then
      var rp = mod(t + sd, 160);
      if (rp < 60) {
        var rx = x + fl((rp / 60) * (w + 10)) - 5;
        var ry = y + fl(h * 0.35) - fl(rp / 8);
        g.em = 1;
        g.r(rx, ry, 4, 2, '#f4f4f8');
        g.p(rx + 4, ry, P.accent);
        g.p(rx - 1, ry + 1, mod(t, 2) ? '#ffb03a' : '#ffe08a');
        g.em = 0;
      }
    } else if (kind === 'autumn' || kind === 'sakura' || kind === 'jungle') {
      var cols =
        kind === 'autumn'
          ? [['#e08a3a', '#f6c05a'], ['#c8503a', '#f08a5a'], ['#d8a83a', '#f6dc6a']]
          : kind === 'sakura'
            ? [['#f6b8cc', '#fff0f4'], ['#f0a0bc', '#ffe0ea'], ['#ffc8d8', '#ffffff']]
            : [['#2f8a4a', '#5ab86a'], ['#3a7a3a', '#6ac860'], ['#246a40', '#4aa85a']];
      band(0.6, 0.05, 0.06, sd % 40, tone(env, kind === 'jungle' ? '#3a6a3a' : mix(low, '#8a9a7a', 0.4)));
      var nT = fl(w / (kind === 'jungle' ? 5 : 8));
      for (i = 0; i < nT; i++) {
        var tx = x + fl(hash(sd + 21, i) * w);
        var ty = y + fl(h * (0.72 + hash(sd + 22, i) * 0.12));
        var r = 2 + fl(hash(sd + 23, i) * 3);
        var cp = cols[i % 3];
        if (kind === 'jungle' && i % 3 === 0) {
          g.r(tx, ty - 9, 1, 9, tone(env, '#6a5030'));
          for (k = -1; k <= 1; k += 2) {
            g.line(tx, ty - 9, tx + k * 5, ty - 6, tone(env, cp[0]));
            g.line(tx, ty - 9, tx + k * 4, ty - 11, tone(env, cp[1]));
          }
        } else {
          bushTree(tx, ty, r, tone(env, cp[0]), tone(env, cp[1]));
        }
      }
      band(0.9, 0.02, 0.07, 3, tone(env, kind === 'autumn' ? '#c8843a' : kind === 'sakura' ? mix(P.paper, P.woodL, 0.5) : '#2a5a30'));
      if (kind === 'sakura' && w > 40) {
        var lx = x + fl(w * 0.6);
        g.r(lx, y + fl(h * 0.62), 1, fl(h * 0.28), tone(env, '#3a3f52'));
        g.r(lx - 1, y + fl(h * 0.6), 3, 2, env.time === 'day' ? tone(env, '#d8dccc') : '#ffe08a');
      }
    } else if (kind === 'rice') {
      band(0.42, 0.08, 0.05, sd % 60, tone(env, mix(low, '#6a8a9a', 0.5)));
      var ripe = autumnal(env) ? 1 : 0;
      for (yy = fl(y + h * 0.6); yy < y + h; yy++) {
        var f2 = (yy - (y + h * 0.6)) / (h * 0.4);
        var c1 = ripe ? '#e0c050' : '#7ac050';
        var c2 = ripe ? '#c8a038' : '#5aa040';
        for (xx = 0; xx < w; xx++) {
          var gx2 = x + xx;
          var dyke = mod(yy - fl(y + h * 0.6), 5 + fl(f2 * 4)) === 0;
          g.p(gx2, yy, tone(env, dyke ? '#8a7048' : ((gx2 + yy) % 3 === 0 ? c2 : c1)));
        }
      }
      if (w > 40) {
        var hx1 = x + fl(w * 0.2);
        var hy1 = y + fl(h * 0.6);
        g.r(hx1, hy1 - 5, 10, 5, tone(env, P.paper));
        for (k = 0; k < 4; k++) g.r(hx1 - 1 + k, hy1 - 6 - k, 12 - k * 2, 1, tone(env, '#5a6a8a'));
        g.r(hx1 + 4, hy1 - 3, 2, 3, tone(env, P.woodD));
      }
    } else if (kind === 'harbor') {
      var hz2 = waterFrom(0.66, mix(env.skyMid, '#1a3a6a', 0.6), hx('#2a6a9a'));
      var wc = [tone(env, '#b85a4a'), tone(env, '#5a7a9a'), tone(env, '#c8a058'), tone(env, '#6a8a6a')];
      xx = x;
      i = 0;
      while (xx < x + w) {
        var bw = 8 + fl(hash(sd + 31, i) * 8);
        var bh = 4 + fl(hash(sd + 32, i) * 5);
        g.r(xx, hz2 - bh, bw, bh, wc[i % 4]);
        g.r(xx, hz2 - bh, bw, 1, mix(wc[i % 4], WHITE, 0.3));
        if (env.time !== 'day' && hash(sd, i) < 0.6) g.p(xx + 2, hz2 - 2, env.litC);
        xx += bw;
        i++;
      }
      if (w > 50) {
        var crx2 = x + fl(w * 0.72);
        var cdc = tone(env, P.accent);
        g.r(crx2, hz2 - 16, 2, 16, cdc);
        g.r(crx2 - 6, hz2 - 16, 16, 1, cdc);
        g.line(crx2 + 1, hz2 - 16, crx2 - 5, hz2 - 15, cdc);
        g.r(crx2 + 8, hz2 - 15, 1, 5, tone(env, P.ink));
        g.r(crx2 + 7, hz2 - 10, 3, 2, tone(env, P.accent2));
      }
      var shx = x + mod(sd * 3 + t * 0.08, w + 20) - 10;
      g.r(shx, hz2 + 2, 14, 2, tone(env, '#c84a3a'));
      g.r(shx + 2, hz2 - 1, 8, 3, tone(env, P.paper));
      for (k = 0; k < 3; k++) g.r(shx + 3 + k * 2, hz2 - 3, 2, 2, [tone(env, P.accent2), tone(env, '#f6c63a'), tone(env, P.accent)][k]);
    } else if (kind === 'under') {
      for (i = 0; i < 4; i++) {
        var rx2 = x + fl(((i + 0.5) / 4) * w) + rnd(Math.sin(t * 0.05 + i) * 3);
        for (yy = y; yy < y + h * 0.7; yy++) if (bay(rx2, yy) < 0.3) g.b(rx2 + fl((yy - y) * 0.2), yy, '#c8f0ff', 0.25);
      }
      band(0.84, 0.03, 0.08, sd % 20, tone(env, '#d8c08a'), tone(env, '#b8a070'));
      for (i = 0; i < fl(w / 14) + 1; i++) {
        var wx = x + fl(hash(sd + 41, i) * w);
        var wh = 4 + fl(hash(sd + 42, i) * h * 0.25);
        for (k = 0; k < wh; k++) g.p(wx + rnd(Math.sin(k * 0.5 + t * 0.15 + i) * 1.2), y + h * 0.88 - k, k % 3 ? '#3a9a5a' : '#5ab86a');
      }
      for (i = 0; i < 3; i++) {
        var fsx = x + mod(hash(sd, i + 50) * w + t * (0.3 + i * 0.1) * (i % 2 ? 1 : -1), w + 8) - 4;
        var fsy = y + fl(h * (0.3 + i * 0.15));
        fishSpr(g, fsx, fsy, i % 2 ? 'tiny' : 'clown', hx(['#ffb03a', '#7fd8ff', '#ff9fbf'][i]), i % 2 === 0);
      }
      for (i = 0; i < 5; i++) g.p(x + fl(hash(sd, i) * w), y + h - mod(t * 0.8 + i * 9, h), '#d8f4ff');
    } else if (kind === 'towers') {
      var lit = env.time === 'day' ? 0.05 : 0.65;
      xx = x - 2;
      i = 0;
      while (xx < x + w) {
        var tw = 5 + fl(hash(sd + 61, i) * 7);
        var th = fl(h * (0.35 + hash(sd + 62, i) * 0.55));
        var tc = tone(env, mix(low, i % 2 ? '#3a4260' : '#4a5274', 0.6));
        g.r(xx, y + h - th, tw, th, tc);
        if (hash(sd, i + 3) < 0.4) {
          g.r(xx + fl(tw / 2), y + h - th - 4, 1, 4, tc);
          g.em = 1;
          if (mod(t + i * 3, 10) < 5) g.p(xx + fl(tw / 2), y + h - th - 5, '#ff4a4a');
          g.em = 0;
        }
        for (yy = y + h - th + 2; yy < y + h - 1; yy += 2) {
          for (var wx2 = xx + 1; wx2 < xx + tw - 1; wx2 += 2) {
            if (hash(wx2 * 7 + i, yy + fl((t + hash(wx2, yy) * 300) / 120)) < lit) {
              g.em = 1;
              g.p(wx2, yy, env.litC);
              g.em = 0;
            }
          }
        }
        xx += tw + 1;
        i++;
      }
    } else if (kind === 'aurora') {
      for (i = 0; i < 3; i++) {
        for (xx = 0; xx < w; xx++) {
          var gx3 = x + xx;
          var ay = y + h * (0.12 + i * 0.1) + Math.sin(gx3 * 0.05 + t * 0.03 + i * 2) * h * 0.08;
          var len = fl(h * 0.18);
          for (k = 0; k < len; k++) {
            if (bay(gx3, fl(ay) + k) < (1 - k / len) * 0.7) g.b(gx3, fl(ay) + k, i === 1 ? '#8af0c0' : '#6ad8a8', env.time === 'day' ? 0.15 : 0.45);
          }
        }
      }
      band(0.7, 0.06, 0.05, sd % 50, tone(env, '#e8eef6'), tone(env, '#c8d4e6'));
      for (i = 0; i < fl(w / 16); i++) {
        var px2 = x + fl(hash(sd + 71, i) * w);
        var pb2 = y + fl(h * 0.82);
        for (k = 0; k < 8; k++) g.r(px2 - fl(k / 2.5), pb2 - 8 + k, fl(k / 2.5) * 2 + 1, 1, tone(env, '#1f3a36'));
      }
    } else if (kind === 'tulip') {
      band(0.55, 0.04, 0.05, sd % 30, tone(env, mix(low, '#8ab07a', 0.5)));
      var tcols = ['#e8483a', '#f6d04a', '#f6a0c0', '#ffffff', '#a88ad8'];
      for (yy = fl(y + h * 0.66); yy < y + h; yy++) {
        var f3 = (yy - (y + h * 0.66)) / (h * 0.34);
        for (xx = 0; xx < w; xx++) {
          var gx4 = x + xx;
          var stripe = fl((gx4 - 96) / (6 + f3 * 10) + (yy - y) * 0.05);
          var col = tcols[mod(stripe + sd, tcols.length)];
          g.p(gx4, yy, tone(env, (gx4 + yy) % 4 === 0 ? P.leafD : col));
        }
      }
      if (w > 40) farThing(g, env, 'windmill', x + fl(w * 0.8), y + fl(h * 0.62), t);
    } else if (kind === 'canyon') {
      var rc = [tone(env, '#c8704a'), tone(env, '#b85a3a'), tone(env, '#d88a5a'), tone(env, '#a84a32')];
      for (xx = 0; xx < w; xx++) {
        var gx5 = x + xx;
        var m = mod(gx5 + sd % 90, 90);
        var top2 = m < 30 ? y + h * 0.3 : (m < 40 ? y + h * (0.3 + (m - 30) * 0.035) : (m < 70 ? y + h * 0.65 : y + h * (0.65 - (m - 70) * 0.0175)));
        for (yy = fl(top2); yy < y + h; yy++) g.p(gx5, yy, rc[fl((yy - y) / 3) % 4]);
      }
      band(0.88, 0.02, 0.1, 5, tone(env, '#d8a070'));
    } else {
      // lake with a little dock
      band(0.5, 0.05, 0.06, sd % 40, tone(env, mix(low, '#6a9a6a', 0.45)));
      for (i = 0; i < fl(w / 7); i++) bushTree(x + fl(hash(sd + 81, i) * w), y + fl(h * 0.6), 2 + (i % 2), tone(env, P.leafD), tone(env, P.leaf));
      var hz3 = waterFrom(0.64, mix(env.skyMid, '#2a5a7a', 0.5), mix(low, '#5aa0b8', 0.5));
      if (w > 40) {
        var dx2 = x + fl(w * 0.3);
        g.r(dx2, hz3 + 3, 14, 1, tone(env, P.woodL));
        for (k = 0; k < 14; k += 4) g.r(dx2 + k, hz3 + 4, 1, 3, tone(env, P.woodD));
      }
    }
  }

  // ---- season / event overlays

  var EVENT_KEYS = ['bloom', 'sun', 'fall', 'frost', 'xmas', 'boo', 'bday', 'wet', 'flies'];

  function inWins(g, fn) {
    for (var i = 0; i < g.wins.length; i++) {
      var r = g.wins[i];
      g.clip(r[0], r[1], r[2], r[3], r[4]);
      fn(r[0], r[1], r[2], r[3]);
    }
    g.noclip();
  }

  function particles(g, env, x, y, w, h, t, kind, n) {
    var cols = { bloom: ['#ffc4d6', '#ff9fbf', '#ffe3ec'], fall: ['#e9873b', '#d0533a', '#f2b648'], frost: ['#ffffff', '#e8f0ff'] }[kind];
    for (var i = 0; i < n; i++) {
      var sp = kind === 'frost' ? 0.5 : 0.7;
      var px = x + mod(hash(env.seed + i, 91) * w + t * sp * 0.6 + Math.sin(t * 0.2 + i) * 2, w);
      var py = y + mod(hash(env.seed + i, 92) * h + t * sp * 0.5, h);
      g.p(px, py, cols[i % cols.length]);
      if (kind !== 'frost' && mod(t + i, 3) === 0) g.p(px + 1, py, cols[i % cols.length]);
    }
  }

  function xmasTree(g, env, x, yb, t) {
    var P = env.P;
    g.r(x + 5, yb - 4, 3, 4, P.woodDD);
    g.r(x + 2, yb - 2, 9, 2, P.accent);
    for (var r = 0; r < 16; r++) {
      var hw = fl((r % 6) * 0.8 + r * 0.25);
      g.r(x + 6 - hw, yb - 20 + r, hw * 2 + 1, 1, r % 6 === 0 ? '#3a8a4a' : '#2f7a42');
    }
    g.em = 1;
    for (var b = 0; b < 7; b++) {
      var bx = x + 6 + rnd(Math.sin(b * 2.3) * (1 + b * 0.7));
      var by = yb - 17 + b * 2;
      g.p(bx, by, mod(fl(t / 3) + b, 3) === 0 ? '#ffffff' : ['#ff6a5a', '#ffd34a', '#6ac8ff'][b % 3]);
    }
    g.p(x + 6, yb - 21, '#ffe04a');
    g.p(x + 5, yb - 20, '#ffe04a');
    g.p(x + 7, yb - 20, '#ffe04a');
    g.em = 0;
    if (env.time !== 'day') g.light(x + 6, yb - 12, 22, [1, 0.85, 0.6], 0.5, { n: 3 });
  }

  function pumpkin(g, env, x, yb, t) {
    g.ell(x + 4, yb - 3, 4, 3, '#e8803a');
    g.r(x + 3, yb - 7, 2, 2, '#5a8a3a');
    g.r(x + 1, yb - 4, 1, 3, '#c8602a');
    g.r(x + 6, yb - 4, 1, 3, '#c8602a');
    g.em = 1;
    var fl0 = mod(t + x, 4) === 0 ? '#ffb03a' : '#ffe08a';
    g.p(x + 2, yb - 4, fl0);
    g.p(x + 5, yb - 4, fl0);
    g.r(x + 2, yb - 2, 4, 1, fl0);
    g.em = 0;
    if (env.time !== 'day') g.light(x + 4, yb - 3, 16, [1, 0.6, 0.25], 0.6, { n: 3 });
  }

  function balloons(g, env, x, yb, t) {
    var P = env.P;
    var cs = [P.accent, P.accent2, '#ffd25a', P.accentL];
    for (var i = 0; i < 4; i++) {
      var bx = x + i * 4 - 2 + rnd(Math.sin(t * 0.1 + i) * 1.2);
      var by = yb - 24 - (i % 2) * 5 + rnd(Math.sin(t * 0.13 + i * 2) * 1);
      g.line(bx, by + 3, x + 6, yb - 6, P.paperDD);
      g.ell(bx, by, 2, 3, cs[i]);
      g.p(bx - 1, by - 1, WHITE);
    }
    g.r(x + 2, yb - 6, 8, 6, P.accent2);
    g.r(x + 5, yb - 6, 2, 6, P.paper);
    g.r(x + 2, yb - 4, 8, 1, P.paper);
    g.r(x + 4, yb - 8, 4, 2, P.paper);
  }

  function seasonal(g, env, scene, t) {
    var P = env.P;
    var ev = env.ev;
    var out = !!scene.outdoor;
    var x;
    var i;
    if (!ev) return;
    var right = mod(env.seed, 2) === 0;
    var cx = right ? 176 : 4;
    if (ev === 'bloom' || ev === 'fall' || ev === 'frost') {
      var n = ev === 'frost' ? 40 : 24;
      if (out) particles(g, env, 0, 0, W, H, t, ev, n);
      else inWins(g, function (wx, wy, ww, wh) {
        particles(g, env, wx, wy, ww, wh, t, ev, fl((n * ww * wh) / 6000) + 3);
      });
      if (!out && ev === 'frost') {
        inWins(g, function (wx, wy, ww, wh) {
          for (var fy = 0; fy < 5; fy++) {
            for (var fx = 0; fx < 6 - fy; fx++) {
              if (bay(wx + fx, wy + wh - 1 - fy) < 0.6) g.b(wx + fx, wy + wh - 1 - fy, WHITE, 0.6);
              if (bay(wx + ww - 1 - fx, wy + wh - 1 - fy) < 0.6) g.b(wx + ww - 1 - fx, wy + wh - 1 - fy, WHITE, 0.6);
            }
          }
        });
      }
      if (!out && ev === 'bloom') {
        g.r(cx + 4, 72, 6, 7, P.accent2);
        g.r(cx + 4, 72, 6, 1, P.accent2L);
        g.line(cx + 6, 72, cx + 3, 60, P.woodD);
        g.line(cx + 7, 72, cx + 11, 62, P.woodD);
        for (i = 0; i < 9; i++) g.p(cx + 3 + fl(hash(i, 3) * 9), 59 + fl(hash(i, 4) * 12), i % 2 ? '#ffc4d6' : '#fff0f4');
      }
      if (ev === 'fall') {
        for (i = 0; i < 8; i++) g.p(cx + (i * 5) % 14, 78 - (i % 3), ['#e9873b', '#d0533a', '#f2b648'][i % 3]);
      }
    } else if (ev === 'sun') {
      if (env.time === 'day' || env.time === 'sunset') {
        g.em = 1;
        for (i = 0; i < 4; i++) g.b(150 + i * 9, 6 + i * 6, '#fff6c8', 0.35);
        g.em = 0;
      }
      for (i = 0; i < 2; i++) {
        var dx = mod(t * 0.9 + i * 90, W + 20) - 10;
        var dy = 30 + i * 12 + rnd(Math.sin(t * 0.3 + i) * 3);
        g.r(dx, dy, 5, 1, '#3a8ad0');
        g.p(dx + 1, dy - 1, mod(t, 2) ? '#c8e8ff' : '#e8f4ff');
        g.p(dx + 3, dy - 1, mod(t, 2) ? '#c8e8ff' : '#e8f4ff');
      }
      if (!out) {
        // electric fan
        g.r(cx + 6, 66, 1, 12, P.metalD);
        g.r(cx + 3, 77, 7, 2, P.metalD);
        disc(g, cx + 6, 62, 4, P.metalL);
        var a = t * 0.9;
        for (i = 0; i < 3; i++) g.line(cx + 6, 62, cx + 6 + rnd(Math.cos(a + i * 2.1) * 3), 62 + rnd(Math.sin(a + i * 2.1) * 3), P.accent2);
      } else {
        g.ell(cx + 6, 77, 5, 2, '#e8483a');
        g.r(cx + 1, 77, 11, 1, '#5ab86a');
        g.p(cx + 4, 76, P.ink);
        g.p(cx + 8, 76, P.ink);
      }
    } else if (ev === 'xmas') {
      stringLights(g, env, 1, t, 3, 6, ['#ff6a5a', '#6ad870', '#ffd34a', '#6ac8ff']);
      xmasTree(g, env, cx, 79, t);
      if (out) particles(g, env, 0, 0, W, H, t, 'frost', 24);
      else inWins(g, function (wx, wy, ww, wh) {
        particles(g, env, wx, wy, ww, wh, t, 'frost', 6);
      });
    } else if (ev === 'boo') {
      for (x = 0; x < W; x++) {
        var sag = rnd(Math.abs(Math.sin((x / 48) * PI)) * 3);
        g.p(x, 2 + sag, P.ink);
        if (x % 10 === 5) for (var r = 0; r < 3; r++) g.r(x - 2 + r, 3 + sag + r, 5 - r * 2, 1, fl(x / 10) % 2 ? '#e8803a' : '#7a4ab8');
      }
      pumpkin(g, env, cx, 79, t);
      pumpkin(g, env, right ? cx - 10 : cx + 10, 79, t + 2);
      for (i = 0; i < 3; i++) {
        var bx = mod(t * 0.8 + i * 70, W + 20) - 10;
        var by = 14 + i * 7 + rnd(Math.sin(t * 0.4 + i) * 3);
        var up = mod(t + i, 4) < 2;
        g.r(bx - 1, by, 3, 2, '#2a2030');
        g.p(bx - 2, by + (up ? -1 : 1), '#2a2030');
        g.p(bx - 3, by + (up ? -1 : 0), '#2a2030');
        g.p(bx + 2, by + (up ? -1 : 1), '#2a2030');
        g.p(bx + 3, by + (up ? -1 : 0), '#2a2030');
      }
    } else if (ev === 'bday') {
      for (x = 0; x < W; x++) {
        var sag2 = rnd(Math.abs(Math.sin((x / 64) * PI)) * 4);
        g.p(x, 2 + sag2, P.ink);
        if (x % 9 === 4) for (var r2 = 0; r2 < 4; r2++) g.r(x - 3 + r2, 3 + sag2 + r2, 7 - r2 * 2, 1, [P.accent, P.accent2, '#ffd25a', P.paper][fl(x / 9) % 4]);
      }
      balloons(g, env, cx, 79, t);
      for (i = 0; i < 10; i++) g.p(mod(hash(env.seed, i) * W + t * 0.3, W), mod(hash(i, env.seed) * H + t * 0.6, H), [P.accent, P.accent2, '#ffd25a'][i % 3]);
    } else if (ev === 'wet') {
      if (out) {
        for (i = 0; i < 3; i++) {
          var px = 20 + i * 64 + (env.seed % 20);
          g.ell(px, 76, 9, 1, mix(env.skyLow, WHITE, 0.25));
          g.ell(px, 76, 7, 1, mix(env.skyMid, WHITE, 0.1));
          var rr = mod(t + i * 5, 12);
          if (rr < 8) {
            g.b(px - rr, 76, WHITE, 0.6);
            g.b(px + rr, 76, WHITE, 0.6);
          }
        }
        weather(g, env, 0, 0, W, H, t, env.weather === 'rain' ? 0 : 0.5);
      } else {
        inWins(g, function (wx, wy, ww, wh) {
          for (var d = 0; d < fl(ww / 4); d++) {
            var ddx = wx + fl(hash(env.seed + d, 41) * ww);
            var ddy = wy + mod(hash(env.seed + d, 43) * wh + t * 0.4, wh + 6) - 3;
            g.b(ddx, ddy - 1, WHITE, 0.25);
            g.b(ddx, ddy, WHITE, 0.5);
          }
        });
        floorThing(g, env, 'umbrella', cx, 79, t);
        g.ell(cx + 7, 79, 7, 1, mix(env.skyLow, P.floor, 0.5));
      }
    } else if (ev === 'flies') {
      var night = env.time === 'night' || env.time === 'dawn';
      g.em = 1;
      var draw = function (fx0, fy0, fw, fh, nn) {
        for (var q = 0; q < nn; q++) {
          var on = mod(t + q * 7, 14) < 9;
          if (!on) continue;
          var ffx = fx0 + mod(hash(env.seed, q + 70) * fw + Math.sin(t * 0.08 + q) * 6, fw);
          var ffy = fy0 + mod(hash(q + 70, env.seed) * fh + Math.sin(t * 0.11 + q * 2) * 4, fh);
          g.p(ffx, ffy, night ? '#e8ff8a' : '#c8e870');
          if (night) halo(g, fl(ffx), fl(ffy), 0, 2, '#e8ff8a', 0.5);
        }
      };
      if (out) draw(0, 30, W, 48, 14);
      else {
        inWins(g, function (wx, wy, ww, wh) {
          draw(wx, wy + wh * 0.4, ww, wh * 0.6, 3);
        });
        // a glowing jar in the corner
        g.r(cx + 3, 70, 8, 9, mix('#e8f4fa', P.wall, 0.3));
        g.r(cx + 3, 69, 8, 1, P.metalD);
        draw(cx + 4, 71, 6, 7, 3);
      }
      g.em = 0;
    }
  }

  // 그림 전체 좌우 뒤집기. 빛의 자리도 같이 옮긴다
  function mirror(g) {
    var d = g.d;
    var e = g.e;
    for (var y = 0; y < H; y++) {
      for (var x = 0; x < W >> 1; x++) {
        var a = y * W + x;
        var b = y * W + (W - 1 - x);
        var t0 = e[a];
        e[a] = e[b];
        e[b] = t0;
        var ja = a << 2;
        var jb = b << 2;
        for (var k = 0; k < 3; k++) {
          var tt = d[ja + k];
          d[ja + k] = d[jb + k];
          d[jb + k] = tt;
        }
      }
    }
    for (var i = 0; i < g.lights.length; i++) g.lights[i].x = W - 1 - g.lights[i].x;
  }

  function postLayers(g, env, scene, t) {
    seasonal(g, env, scene, t);
    if (env.mirror) mirror(g);
  }

  // 장면과 맞지 않는 배경은 바꾼다. 바깥 장면에 바닷속은 바다로, 바다 장면의 수평선에는 물 배경을 겹치지 않는다
  function fitBd(key, bd) {
    if (!bd || BACKDROPS.indexOf(bd) < 0) return null;
    if (key === 'sea' && (bd === 'sea' || bd === 'lake' || bd === 'under')) return null;
    if (SC[key] && SC[key].outdoor && bd === 'under') return 'sea';
    return bd;
  }

  // 우주 배경의 해·달 자리에 뜨는 행성
  function planet(g, env, x, y, w, h) {
    var X = xr(env, 103);
    var cs = X.pick([['#e8a85a', '#b8703a'], ['#7ab0e8', '#3a6ab0'], ['#d87a9a', '#a04a6a'], ['#9ad87a', '#5a9a4a'], ['#e8d8a8', '#b8a070']]);
    var ring = X.ch(0.5);
    var cx = x + fl(env.sunX * w);
    var cy = y + fl(h * 0.3);
    var r = clamp(rnd(h * 0.13), 3, 8);
    halo(g, cx, cy, r, 3, cs[0], 0.35);
    for (var dy = -r; dy <= r; dy++) {
      for (var dx = -r; dx <= r; dx++) {
        if (dx * dx + dy * dy > r * r + r * 0.8) continue;
        var sh = dx + dy > r * 0.6;
        var band = mod(dy + r, 4) === 0;
        g.p(cx + dx, cy + dy, sh ? cs[1] : (band ? mix(hx(cs[0]), WHITE, 0.25) : cs[0]));
      }
    }
    if (ring) {
      for (var k = -r - 4; k <= r + 4; k++) {
        var ry = cy + rnd(k * 0.3);
        if (Math.abs(k) > r - 1 || k > 0) g.p(cx + k, ry, '#f0e6c8');
      }
    }
    if (h > 30) disc(g, cx + r + 8, cy - r, 1, '#d8d4e8');
  }

  // ---------------------------------------------------------------- API

  function pick(arr, v, def) {
    return arr.indexOf(v) >= 0 ? v : def;
  }

  function specFor(seedString) {
    var seed = hashStr(String(seedString == null ? '' : seedString));
    var R = makeRand(seed);
    var scene = R.pick(LEGACY_SCENES);
    var time = R.pick(['day', 'day', 'sunset', 'night', 'night', 'dawn']);
    var weather;
    if (scene === 'cafe' && R.ch(0.5)) weather = 'rain';
    else if (scene === 'snowfield') weather = R.ch(0.7) ? 'snow' : 'clear';
    else weather = R.pick(['clear', 'clear', 'clear', 'cloudy', 'rain', 'snow', 'petals', 'leaves']);
    var spec = { scene: scene, time: time, weather: weather, palette: R.pick(LEGACY_PALETTES), seed: seed };
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
    if (PROPS.indexOf(spec.prop) >= 0) out.prop = spec.prop;
    if (spec.mix) {
      out.mix = true;
      if (BACKDROPS.indexOf(spec.backdrop) >= 0) out.backdrop = spec.backdrop;
      if (EVENTS.indexOf(spec.event) >= 0) out.event = spec.event;
    }
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
    // 원래 장면에 덧붙인 소품 칸. setup과 다른 난수라 예전 배치는 그대로다 (D-129)
    if (scene.extra) L.x = scene.extra(env, xr(env, 7));
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
      if (env.mix) postLayers(g, env, scene, frame);
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

  // 서버가 저장한 값(study.night.rain.mint.4821, 소품 주제가 있으면 끝에 .cat처럼 한 칸 더, 섞기 모드면 .sea~xmas처럼 또 한 칸)을 spec으로 바꾼다. 값이 없으면 fallbackSeed(블로그 주소)로 정한다.
  // mascot은 블로그 마스코트 값(cat.mint.beret.heart)이고 바탕이 동물이면 장면 안에 그린다
  function fromValue(value, fallbackSeed, mascot) {
    var spec;
    var p = typeof value === 'string' ? value.split('.') : [];
    if (SCENES.indexOf(p[0]) >= 0) {
      // 일곱째 칸(배경~계절)이 있으면 섞기 모드. 소품 주제가 없을 때 여섯째 칸은 '-'다
      var lay = p.length > 6 ? String(p[6]).split('~') : null;
      spec = normalize({
        scene: p[0], time: p[1], weather: p[2], palette: p[3], seed: Number(p[4]) || 0, prop: p[5],
        mix: !!lay, backdrop: lay && lay[0], event: lay && lay[1]
      });
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
    PROPS: PROPS.slice(),
    BACKDROPS: BACKDROPS.slice(),
    EVENTS: EVENTS.slice(),
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
