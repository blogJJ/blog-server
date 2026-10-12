/*
 * 블로그 마스코트: 16x16 픽셀 동물 10종과 사물 8종을 SVG로 그린다.
 * 그림은 문자열 배열(글자 하나가 픽셀 하나, '.'은 투명)과 색 표로 적는다.
 * 프레임 A(기본)와 B(동물은 눈 감음, 사물은 작은 변화)를 겹쳐 그리고, 깜빡임·흔들림은 mascot.css가 맡는다.
 *
 * 사용: el.appendChild(Mascot.element('cat', { size: 48 }));
 *       <span data-mascot="fox" data-mascot-size="32"></span> + Mascot.mountAll();
 */
(function () {
  'use strict';

  var SVG_NS = 'http://www.w3.org/2000/svg';
  var GRID = 16;
  var DEFAULT_SIZE = 48;

  // 모든 그림이 같이 쓰는 색. o 윤곽, e 눈, h 눈 반짝임, k 볼
  var BASE = { o: '#2b2b2b', e: '#2b2b2b', h: '#ffffff', k: '#f59fb0' };

  // lid: 눈 감을 때 위쪽 눈 픽셀을 덮을 색 글자(동물만)
  var SPRITES = {
    bear: {
      lid: 'b',
      palette: { b: '#b5835a', m: '#ecd3ad' },
      rows: [
        '................',
        '.ooo........ooo.',
        'obmboooooooobmbo',
        'obbbbbbbbbbbbbbo',
        'obbbbbbbbbbbbbbo',
        'obbhebbbbbbhebbo',
        'obbeebmmmmbeebbo',
        'obkkbmmoommbkkbo',
        '.obbbmmmmmmbbbo.',
        '..obbbbbbbbbbo..',
        '...oooooooooo...',
        '..obbbmmmmbbbo..',
        '.obbbmmmmmmbbbo.',
        '.obbbmmmmmmbbbo.',
        '..obbbmmmmbbbo..',
        '...oooo..oooo...'
      ]
    },
    cat: {
      lid: 'g',
      palette: { g: '#a9afbd', p: '#f29bb0', w: '#fbfbfb' },
      rows: [
        '................',
        '..o..........o..',
        '..oo........oo..',
        '..ogpo....opgo..',
        '..ogggoooogggo..',
        '..oggggggggggo..',
        '..oghegggghego..',
        '..ogeeggggeego..',
        'oookkggppggkkooo',
        'ooogggoggogggooo',
        'oo.oggggggggo.oo',
        '....oooooooo.o..',
        '....ogwwwwgoogo.',
        '...oggwwwwggogo.',
        '...oggwwwwgggo..',
        '....ooooooooo...'
      ]
    },
    dog: {
      lid: 't',
      palette: { t: '#f0c48c', d: '#7a4b30', m: '#fdf4e6', r: '#f07b8a' },
      rows: [
        '................',
        '................',
        '...oooooooooo...',
        '.ooottttttttooo.',
        'odddttttttttdddo',
        'odddthetthetdddo',
        'odddteetteetdddo',
        'odddkmmoommkdddo',
        'odddmmmrrmmmdddo',
        'oddootmmmmtooddo',
        '.oo..oooooo..oo.',
        '....ottmmtto....',
        '...otttmmttto...',
        '...otttmmttto...',
        '...otttmmttto...',
        '...oooo..oooo...'
      ]
    },
    rabbit: {
      lid: 'w',
      palette: { w: '#f7f5f2', p: '#f6b3c4' },
      rows: [
        '................',
        '...oo......oo...',
        '..owwo....owwo..',
        '..owpo....opwo..',
        '..owpo....opwo..',
        '..owpo....opwo..',
        '..owwoooooowwo..',
        '.owwwwwwwwwwwwo.',
        '.owhewwwwwwhewo.',
        '.oweewwwwwweewo.',
        '.okkwwwppwwwkko.',
        '..owwwwwwwwwwo..',
        '...oooooooooo...',
        '...owwwwwwwwo...',
        '...owwwwwwwwo...',
        '....oooooooo....'
      ]
    },
    fox: {
      lid: 'f',
      palette: { f: '#ee8a3c', w: '#fdf7ef', d: '#6b4330' },
      rows: [
        '................',
        '.oo..........oo.',
        '.odo........odo.',
        '.odfo......ofdo.',
        '.offfoooooofffo.',
        'offffffffffffffo',
        'offheffffffheffo',
        'owweeffffffeewwo',
        '.owkwwwoowwwkwo.',
        '..owwwwwwwwwwo..',
        '...oowwwwwwoo...',
        '.....oooooo.ooo.',
        '....offwwffoowwo',
        '...offwwwwfoffo.',
        '...offwwwwffffo.',
        '....oooooooooo..'
      ]
    },
    penguin: {
      lid: 'w',
      palette: { b: '#5a6684', w: '#fbfbfb', y: '#f6a33a' },
      rows: [
        '................',
        '................',
        '.....oooooo.....',
        '...oobbbbbboo...',
        '..obbbbbbbbbbo..',
        '.obbwwwbbwwwbbo.',
        '.obwhewwwwhewbo.',
        '.obweewyyweewbo.',
        '.obkwwwyywwwkbo.',
        '.obbwwwwwwwwbbo.',
        'obbbbwwwwwwbbbbo',
        'obbowwwwwwwwobbo',
        'obbowwwwwwwwobbo',
        '.oobwwwwwwwwboo.',
        '..oobbwwwwbboo..',
        '...oyyyooyyyo...'
      ]
    },
    bird: {
      lid: 'b',
      palette: { b: '#6db6ea', l: '#fdf1c8', d: '#3f88c8', y: '#f6a33a' },
      rows: [
        '................',
        '................',
        '.......o.o......',
        '......o.o.......',
        '.....oooooo.....',
        '....obbbbbbo....',
        '...obbbbbbbbo...',
        '..obhebbbbhebo..',
        '..obeebyybeebo..',
        '.obkbbllllbbkbo.',
        'oddbllllllllbddo',
        'oddbllllllllbddo',
        '.odbllllllllbdo.',
        '..obbllllllbbo..',
        '...oooooooooo...',
        '.....yy..yy.....'
      ]
    },
    frog: {
      lid: 'w',
      palette: { g: '#74c24f', l: '#d3eeaa', w: '#fbfbfb' },
      rows: [
        '................',
        '..oooo....oooo..',
        '.owwwwo..owwwwo.',
        '.owhewoooowhewo.',
        'ogweewggggweewgo',
        'oggggggggggggggo',
        'ogkkggggggggkkgo',
        'oggoggggggggoggo',
        'ogggoooooooogggo',
        '.oggggggggggggo.',
        '..oooooooooooo..',
        '..oggllllllggo..',
        '.oggllllllllggo.',
        '.oggllllllllggo.',
        'ogggoooooooogggo',
        'oooo........oooo'
      ]
    },
    hamster: {
      lid: 't',
      palette: { t: '#f0b36a', w: '#fff8ee', p: '#f6b3c4' },
      rows: [
        '................',
        '................',
        '................',
        '..ooo......ooo..',
        '.otpto....otpto.',
        '.otttoooooottto.',
        'otttttttttttttto',
        'otthetttttthetto',
        'owweetttttteewwo',
        'owwkwwwppwwwkwwo',
        'owwwwwwwwwwwwwwo',
        '.owwwwwwwwwwwwo.',
        '.otwwppwwppwwto.',
        '.ottwwwwwwwwtto.',
        '..ottwwwwwwtto..',
        '...oooooooooo...'
      ]
    },
    owl: {
      lid: 'w',
      palette: { b: '#a0714a', l: '#ecd6ad', w: '#fbfbfb', y: '#f6a33a', d: '#7a5233' },
      rows: [
        '................',
        '.oo..........oo.',
        '.obo........obo.',
        '.obboooooooobbo.',
        '.obbbbbbbbbbbbo.',
        'obwwwwbbbbwwwwbo',
        'owwhewwbbwwhewwo',
        'owweewwyywweewwo',
        'obwwwwbyybwwwwbo',
        'obbkbbbbbbbbkbbo',
        'obbllllllllllbbo',
        'odbldllddlldlbdo',
        'odbllllllllllbdo',
        '.odbldlddldlbdo.',
        '..obllllllllbo..',
        '...oyyo..oyyo...'
      ]
    },
    // 사물은 눈이 없어서 프레임 B를 줄 단위로 직접 적는다(changes: 줄 번호 → 바뀐 줄)
    computer: {
      palette: { g: '#c9ccd6', s: '#2f3a56', c: '#7fd1ae', y: '#f6c453', p: '#f29bb0', w: '#ffffff' },
      rows: [
        '................',
        '.oooooooooooooo.',
        'ogssssssssssssgo',
        'ogscccyyysssssgo',
        'ogssppppccssssgo',
        'ogsyyyscccccssgo',
        'ogscccwsssssssgo',
        'oggggggggggggggo',
        '.oooooooooooooo.',
        '......oggo......',
        '....oooooooo....',
        '.oooooooooooooo.',
        'ogwgwgwgwgwgwgwo',
        'oggwwwwwwwwwwggo',
        '.oooooooooooooo.',
        '................'
      ],
      changes: { 6: 'ogscccpwssssssgo' }
    },
    camera: {
      palette: {
        c: '#f3e6cf', d: '#6b574e', g: '#aab2c3', l: '#3b4a6b',
        r: '#ef6b6b', f: '#e3e8f0', y: '#ffe066'
      },
      rows: [
        '................',
        '................',
        '..ooo.....oooo..',
        '..oro.....offo..',
        '.oooooooooooooo.',
        'occccoooooocccco',
        'occcoggggggoccco',
        'oddogghlllggoddo',
        'oddoggllllggoddo',
        'oddoggllllggoddo',
        'oddoggllllggoddo',
        'occcoggggggoccco',
        'occccoooooocccco',
        '.oooooooooooooo.',
        '................',
        '................'
      ],
      changes: { 1: '..........y..y..', 3: '..oro.....oyyo..' }
    },
    book: {
      palette: { w: '#fbf7ee', l: '#b9c0cc', c: '#5b8bd9', s: '#e3d6bd' },
      rows: [
        '................',
        '................',
        '................',
        '.oooo......oooo.',
        'owwwwoo..oowwwwo',
        'owwwwwwoowwwwwwo',
        'owllllwoowllllwo',
        'owwwwwwoowwwwwwo',
        'owlllwwoowllllwo',
        'owwwwwwoowwwwwwo',
        'owllllwoowlllwwo',
        'owwwwwwoowwwwwwo',
        'occoooooooooocco',
        '.occcccccccccco.',
        '..oooooooooooo..',
        '................'
      ],
      changes: { 2: '.............o..', 3: '.oooo......ooowo', 4: 'owwwwoo..oowwwso' }
    },
    plane: {
      palette: { w: '#f7f7fb', r: '#ef6b6b', l: '#7cc3ef' },
      rows: [
        '................',
        '.....oooooo.....',
        '.......oo.......',
        '......orro......',
        '......owwo......',
        '......ollo......',
        '.oooooowwoooooo.',
        'orrrrrrwwrrrrrro',
        'orrrrrrwwrrrrrro',
        '.oooooowwoooooo.',
        '......owwo......',
        '......owwo......',
        '....ooowwooo....',
        '....orrwwrro....',
        '....oooooooo....',
        '................'
      ],
      changes: { 1: '.......oo.......' }
    },
    headphones: {
      palette: { v: '#9b8cf2', d: '#4a4458', n: '#ff7fa8' },
      rows: [
        '................',
        '.....oooooo.....',
        '...oovvvvvvoo...',
        '..ovvoooooovvo..',
        '.ovo........ovo.',
        '.ovo........ovo.',
        'ovo..........ovo',
        'ovo..........ovo',
        '.ooooo....ooooo.',
        'ovvvdo....odvvvo',
        'ovvvdo....odvvvo',
        'ovvvdo....odvvvo',
        'ovvvdo....odvvvo',
        '.ooooo....ooooo.',
        '................',
        '................'
      ],
      changes: {
        6: 'ovo....nn....ovo',
        7: 'ovo....n.n...ovo',
        8: '.ooooo.n..ooooo.',
        9: 'ovvvdonn..odvvvo',
        10: 'ovvvdonn..odvvvo'
      }
    },
    gamepad: {
      palette: { g: '#c5cad8', r: '#ef6b6b', b: '#6db6ea', y: '#7fd1ae', s: '#8a90a3', p: '#f29bb0', Y: '#fff3a0' },
      rows: [
        '................',
        '................',
        '................',
        '..oooooooooooo..',
        '.oggggggggggggo.',
        'oggoggggggggyggo',
        'ogooogsggsgbgrgo',
        'oggoggggggggpggo',
        'oggggggggggggggo',
        'oggggooooooggggo',
        'ogggo......ogggo',
        '.ooo........ooo.',
        '................',
        '................',
        '................',
        '................'
      ],
      changes: { 5: 'oggoggggggggYggo' }
    },
    coffee: {
      palette: { m: '#8fd3c7', c: '#7a4a2e', s: '#8d9bb5', g: '#e3e8f0' },
      rows: [
        '................',
        '....s...s.......',
        '.....s...s......',
        '....s...s.......',
        '..oooooooooo....',
        '..occcccccco....',
        '..ommmmmmmmooo..',
        '..ommkmkmmmo.o..',
        '..ommkkkmmmo.o..',
        '..ommmkmmmmooo..',
        '..ommmmmmmmo....',
        '...ommmmmmo.....',
        '.oooooooooooo...',
        '..oggggggggo....',
        '...oooooooo.....',
        '................'
      ],
      changes: {
        1: '.....s...s......',
        2: '....s...s.......',
        3: '.....s...s......'
      }
    },
    cake: {
      palette: {
        f: '#ff9f43', F: '#ffe066', c: '#8fd3c7', w: '#fffaf2', p: '#f7a8c0',
        y: '#f6d9a0', r: '#ef4f5f', l: '#6cc04a'
      },
      rows: [
        '................',
        '.......oo.......',
        '......oFFo......',
        '......offo......',
        '.ollo.owco.ollo.',
        '.ohro.ocwo.ohro.',
        '.oooooooooooooo.',
        '.oppppppppppppo.',
        '.opyppyppyppypo.',
        '.oyyyyyyyyyyyyo.',
        '.owwwwwwwwwwwwo.',
        '.oyyyyyyyyyyyyo.',
        '.oyyyyyyyyyyyyo.',
        '..oooooooooooo..',
        '................',
        '................'
      ],
      changes: { 1: '......oo........', 2: '......oFfo......' }
    }
  };

  var LABELS = {
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
    gamepad: '게임기',
    coffee: '커피',
    cake: '케이크'
  };

  var KEYS = [
    'bear', 'cat', 'dog', 'rabbit', 'fox', 'penguin', 'bird', 'frog', 'hamster', 'owl',
    'computer', 'camera', 'book', 'plane', 'headphones', 'gamepad', 'coffee', 'cake'
  ];

  function isEye(ch) {
    return ch === 'e' || ch === 'h';
  }

  // 프레임 B: 세로로 이어진 눈 픽셀 중 맨 아래 줄만 남겨 감은 눈 선으로 만든다
  function blinkRows(sprite) {
    var grid = sprite.rows.map(function (row) {
      return row.split('');
    });
    for (var y = 0; y < GRID; y++) {
      for (var x = 0; x < GRID; x++) {
        if (!isEye(grid[y][x])) {
          continue;
        }
        var below = y + 1 < GRID && isEye(sprite.rows[y + 1].charAt(x));
        grid[y][x] = below ? sprite.lid : 'e';
      }
    }
    return grid.map(function (cells) {
      return cells.join('');
    });
  }

  function frameB(sprite) {
    if (!sprite.changes) {
      return blinkRows(sprite);
    }
    return sprite.rows.map(function (row, y) {
      return sprite.changes[y] || row;
    });
  }

  function colorOf(sprite, ch) {
    return sprite.palette[ch] || BASE[ch] || null;
  }

  // 같은 색이 가로로 이어진 픽셀은 rect 하나로 합친다
  function drawFrame(sprite, rows, className) {
    var g = document.createElementNS(SVG_NS, 'g');
    g.setAttribute('class', className);
    rows.forEach(function (row, y) {
      var x = 0;
      while (x < GRID) {
        var color = colorOf(sprite, row.charAt(x));
        var end = x + 1;
        while (end < GRID && color && colorOf(sprite, row.charAt(end)) === color) {
          end++;
        }
        if (color) {
          var rect = document.createElementNS(SVG_NS, 'rect');
          rect.setAttribute('x', x);
          rect.setAttribute('y', y);
          rect.setAttribute('width', end - x);
          rect.setAttribute('height', 1);
          rect.setAttribute('fill', color);
          g.appendChild(rect);
        }
        x = end;
      }
    });
    return g;
  }

  function hash(text) {
    var h = 2166136261;
    for (var i = 0; i < text.length; i++) {
      h ^= text.charCodeAt(i);
      h = Math.imul(h, 16777619);
    }
    return h >>> 0;
  }

  // 서버가 아직 고르지 않은 블로그는 주제와 어긋날 수 있는 물건 대신 동물 중에서 보여 준다
  var ANIMALS = KEYS.slice(0, 10);

  function fallbackKey(seed) {
    return ANIMALS[hash(String(seed === undefined || seed === null ? '' : seed)) % ANIMALS.length];
  }

  function label(key) {
    return LABELS[key] || '';
  }

  function element(key, options) {
    var opts = options || {};
    var name = SPRITES[key] ? key : fallbackKey(opts.seed !== undefined ? opts.seed : key);
    var size = Number(opts.size) > 0 ? Number(opts.size) : DEFAULT_SIZE;
    var sprite = SPRITES[name];

    var span = document.createElement('span');
    span.className = 'mascot mascot--' + name;
    span.setAttribute('role', 'img');
    span.setAttribute('aria-label', LABELS[name] + ' 마스코트');

    var svg = document.createElementNS(SVG_NS, 'svg');
    svg.setAttribute('viewBox', '0 0 16 16');
    svg.setAttribute('width', size);
    svg.setAttribute('height', size);
    svg.setAttribute('shape-rendering', 'crispEdges');
    svg.setAttribute('aria-hidden', 'true');
    svg.setAttribute('focusable', 'false');

    var body = document.createElementNS(SVG_NS, 'g');
    body.setAttribute('class', 'mascot__body');
    body.appendChild(drawFrame(sprite, sprite.rows, 'mascot__frame mascot__frame--a'));
    body.appendChild(drawFrame(sprite, frameB(sprite), 'mascot__frame mascot__frame--b'));
    svg.appendChild(body);
    span.appendChild(svg);
    return span;
  }

  // data-mascot 자리를 채운다. 이미 채운 자리는 건너뛴다
  function mountAll(root) {
    var scope = root || document;
    var nodes = scope.querySelectorAll('[data-mascot]');
    Array.prototype.forEach.call(nodes, function (node) {
      if (node.getAttribute('data-mascot-mounted') === 'true') {
        return;
      }
      var key = node.getAttribute('data-mascot');
      var mascot = element(key, {
        size: node.getAttribute('data-mascot-size'),
        seed: node.getAttribute('data-mascot-seed') || key || ''
      });
      node.textContent = '';
      node.appendChild(mascot);
      node.setAttribute('data-mascot-mounted', 'true');
    });
  }

  window.Mascot = {
    KEYS: KEYS.slice(),
    label: label,
    element: element,
    fallbackKey: fallbackKey,
    mountAll: mountAll
  };
})();
