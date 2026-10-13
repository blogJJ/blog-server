/*
 * 블로그 마스코트: 16x16 픽셀 동물 20종과 사물 10종을 SVG로 그린다 (D-143에서 동물 10종, 사물 2종을 더함).
 * 그림은 문자열 배열(글자 하나가 픽셀 하나, '.'은 투명)과 색 표로 적는다.
 * 프레임 A(기본)와 B(동물은 눈 감음, 사물은 작은 변화)를 겹쳐 그리고, 깜빡임·흔들림은 mascot.css가 맡는다.
 *
 * 값은 '기본.색조.모자.장식'(예: cat.mint.beret.heart)이고, 'cat'만 있으면 cat.original.none.none이다.
 * 캔버스는 20x20이고 본체는 (2,4)에 그려서 위쪽에 모자, 오른쪽 위에 장식이 들어갈 자리를 둔다.
 *
 * 사용: el.appendChild(Mascot.element('cat.mint.beret.heart', { size: 48 }));
 *       <span data-mascot="fox" data-mascot-size="32"></span> + Mascot.mountAll();
 */
(function () {
  'use strict';

  var SVG_NS = 'http://www.w3.org/2000/svg';
  var GRID = 16;
  var CANVAS = 20;
  var OFFSET_X = 2;
  var OFFSET_Y = 4;
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
    // D-143에서 더한 동물 10종
    panda: {
      lid: 'd',
      palette: { w: '#f7f6f2', d: '#45434d' },
      rows: [
        '................',
        '.ooo........ooo.',
        'odddoooooooodddo',
        'oddwwwwwwwwwwddo',
        'owwddwwwwwwddwwo',
        'owdhedwwwwdhedwo',
        'owdeedwwwwdeedwo',
        'owkddwwoowwddkwo',
        '.owwwwwwwwwwwwo.',
        '..owwwwwwwwwwo..',
        '...oooooooooo...',
        '..odddwwwwdddo..',
        '.oddddwwwwddddo.',
        '.odddwwwwwwdddo.',
        '..odddwwwwdddo..',
        '...oooo..oooo...'
      ]
    },
    koala: {
      lid: 'g',
      palette: { g: '#9ea6b4', p: '#f6d3da', w: '#f4f2f0' },
      rows: [
        '.oooo......oooo.',
        'oppggo....oggppo',
        'oppggooooooggppo',
        '.oggggggggggggo.',
        '.oghegggggghego.',
        '.ogeegoooogeego.',
        '.okggooooooggko.',
        '.ogggoooooogggo.',
        '..ogggoooogggo..',
        '...oggwwwwggo...',
        '....oooooooo....',
        '...ogwwwwwwgo...',
        '..oggwwwwwwggo..',
        '..oggwwwwwwggo..',
        '..ogggwwwwgggo..',
        '...oooo..oooo...'
      ]
    },
    pig: {
      lid: 'p',
      palette: { p: '#f7a9bd', D: '#e57e9b', s: '#fcd0dc', n: '#c4577a' },
      rows: [
        '................',
        '................',
        '.oo..........oo.',
        '.oDo........oDo.',
        '.oDpoooooooopDo.',
        'oppppppppppppppo',
        'oppheppppppheppo',
        'oppeeppppppeeppo',
        'opkkpoooooopkkpo',
        'opppossnnssopppo',
        'oppppooooooppppo',
        '.oppppppppppppo.',
        '..oooooooooooo..',
        '.opppsssssspppo.',
        '.opppsssssspppo.',
        '..oooo....oooo..'
      ]
    },
    sheep: {
      lid: 'f',
      palette: { w: '#fbf8f1', f: '#f1d2b8', l: '#8a6f63' },
      rows: [
        '................',
        '....oo.oo.oo....',
        '..oowwowwowwoo..',
        '.owwwwwwwwwwwwo.',
        'owwwffffffffwwwo',
        'offwfheffhefwffo',
        'owwwfeeffeefwwwo',
        'owwwkffooffkwwwo',
        '.owwwffffffwwwo.',
        'owwwwwffffwwwwwo',
        'owwwwwwwwwwwwwwo',
        'owwwwwwwwwwwwwwo',
        '.oowwoowwoowwoo.',
        '...olo....olo...',
        '...olo....olo...',
        '...ooo....ooo...'
      ]
    },
    duck: {
      lid: 'y',
      palette: { y: '#ffd84a', d: '#f2b829', r: '#f6913a' },
      rows: [
        '................',
        '................',
        '......oooo......',
        '.....oyyyyo.....',
        '....oyyyyyyo....',
        '....oyyyyheyo...',
        '....oyyyyeeyooo.',
        '....oyyykyyorrro',
        '....oyyyyyyyooo.',
        'oyo.oyyyyyyo....',
        'oyyooyyyyyyyooo.',
        'oyyyyyyyyyyyyyyo',
        'oyyyyddddyyyyyyo',
        '.oyyyydddyyyyyo.',
        '..oyyyyyyyyyyo..',
        '...oooooooooo...'
      ]
    },
    turtle: {
      lid: 'g',
      palette: { g: '#8fce6a', b: '#a8743f', B: '#d9a55e', y: '#f3e2a4' },
      rows: [
        '................',
        '................',
        '................',
        '................',
        '................',
        '................',
        '....oooo........',
        '...obbbbo...ooo.',
        '..obBbbBbo.ogggo',
        '.obBBbBBbboogheo',
        'obbBbbBbbbbogeeo',
        'obbbbbbbbbbggkgo',
        'oyyyyyyyyyyoooo.',
        '.oooooooooooo...',
        '..ogo.....ogo...',
        '..ooo.....ooo...'
      ]
    },
    hedgehog: {
      lid: 'f',
      palette: { d: '#8a5a3c', D: '#b9825a', f: '#f4ddc0' },
      rows: [
        '................',
        '...o..o..o..o...',
        '..odoodoodoodo..',
        '.oddddddddddddo.',
        'oddDdddDdddDdddo',
        'oddffffffffffddo',
        'oddfheffffhefddo',
        'oddfeeffffeefddo',
        'oddkfffoofffkddo',
        'odddffffffffdddo',
        '.odddffffffdddo.',
        '..oddddddddddo..',
        '.oddffffffffddo.',
        '.oddffffffffddo.',
        '..odffffffffdo..',
        '...oooo..oooo...'
      ]
    },
    whale: {
      lid: 'b',
      palette: { b: '#5b8fd6', l: '#cfe6fb', s: '#9fd3f5' },
      rows: [
        '................',
        '..s...s.........',
        '...s.s......o..o',
        '....s......obooo',
        '...oooooo..obbo.',
        '..obbbbbboo.obo.',
        '.obbbbbbbbbo.obo',
        'obhebbbbbbbbbobo',
        'obeebbbbbbbbbbo.',
        'okbbbbbbbbbbbbo.',
        'ollbbbbbbbbbbbo.',
        'ollllbbbbbbbbo..',
        '.oolllllllllo...',
        '...oooooooo.....',
        '................',
        '................'
      ]
    },
    lion: {
      lid: 'y',
      palette: { m: '#d4742c', y: '#f6c65a', w: '#fdf0d0' },
      rows: [
        '................',
        '....oooooooo....',
        '..oommmmmmmmoo..',
        '.ommmmmmmmmmmmo.',
        'ommyyyyyyyyyymmo',
        'ommyheyyyyheymmo',
        'ommyeeyyyyeeymmo',
        'ommkyywoowyykmmo',
        'ommyywwwwwwyymmo',
        '.ommyyyyyyyymmo.',
        '..ommyyyyyymmo..',
        '...ommmmmmmmo...',
        '....oooooooo....',
        '...oyywwwwyyo...',
        '...oyywwwwyyo...',
        '...oooo..oooo...'
      ]
    },
    mouse: {
      lid: 'g',
      palette: { g: '#b4b8c4', p: '#f6b3c4', w: '#f6f4f2' },
      rows: [
        '.oooo......oooo.',
        'ogppgo....ogppgo',
        'ogpppgo..ogpppgo',
        'ogppgoooooogppgo',
        '.ooggggggggggoo.',
        '..oghegggghego..',
        '..ogeeggggeego..',
        '..okgggppgggko..',
        '...oggggggggo...',
        '....oooooooo....',
        '....ogwwwwgo....',
        '...oggwwwwggo...',
        '...oggwwwwggo.p.',
        '...oggwwwwggo..p',
        '....oggggggo..p.',
        '....oooooooopp..'
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
    },
    pencil: {
      palette: { p: '#f29bb0', g: '#c9ccd6', y: '#ffd23f', Y: '#fff0a0', d: '#e8a317', w: '#f3d5a8', s: '#8a90a3' },
      rows: [
        '...........ooo..',
        '..........opppo.',
        '.........opppppo',
        '........oggpppo.',
        '.......oYyggpo..',
        '......oYyydgo...',
        '.....oYyyydo....',
        '....oYyyydo.....',
        '...oYyyydo......',
        '..owyyydo.......',
        '..owwwdo........',
        '.owwwwo.........',
        '.oowwo..........',
        '.osoo...........',
        '.oo.............',
        '................'
      ],
      changes: { 14: '.oo.s...........', 15: '.....s..........' }
    },
    plant: {
      palette: { l: '#7ccf5a', L: '#4f9e36', t: '#d9794a', T: '#f0a070', s: '#8a5536' },
      rows: [
        '................',
        '.......oo.......',
        '......olLo......',
        '..oo..olLo..oo..',
        '.olLo.olLo.oLlo.',
        '.ollLoolLooLllo.',
        '..ollLoLoLlllo..',
        '...oolLLLloo....',
        '.....ooLoo......',
        '...oooooooooo...',
        '..oTTTTTTTTTTo..',
        '..oooooooooooo..',
        '...ottttttttto..',
        '...otttttttto...',
        '....ottttttto...',
        '....ooooooooo...'
      ],
      changes: { 1: '................', 2: '.......oo.......', 3: '..oo..olLo..oo..' }
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
    panda: '판다',
    koala: '코알라',
    pig: '돼지',
    sheep: '양',
    duck: '오리',
    turtle: '거북이',
    hedgehog: '고슴도치',
    whale: '고래',
    lion: '사자',
    mouse: '생쥐',
    computer: '컴퓨터',
    camera: '카메라',
    book: '책',
    plane: '비행기',
    headphones: '헤드폰',
    gamepad: '게임기',
    coffee: '커피',
    cake: '케이크',
    pencil: '연필',
    plant: '화분'
  };

  var KEYS = [
    'bear', 'cat', 'dog', 'rabbit', 'fox', 'penguin', 'bird', 'frog', 'hamster', 'owl',
    'panda', 'koala', 'pig', 'sheep', 'duck', 'turtle', 'hedgehog', 'whale', 'lion', 'mouse',
    'computer', 'camera', 'book', 'plane', 'headphones', 'gamepad', 'coffee', 'cake',
    'pencil', 'plant'
  ];

  // 동물이 KEYS 앞쪽에 모여 있다. 색조 계산과 서버가 고르기 전 기본 그림(ANIMALS)이 이 수에 기댄다
  var ANIMAL_COUNT = 20;

  // 색조: 그림 자기 색(윤곽·눈·볼 제외)만 HSL로 바꾼다
  var TONES = ['original', 'pastel', 'mint', 'berry', 'sky', 'cocoa'];
  // h 목표 색상, s 채도, l 밝기, dl 밝기를 목표 쪽으로 당기는 정도, sat 사물 색 채도 배율
  var TONE_SPEC = {
    mint: { h: 158, s: 0.48, l: 0.66, dl: 0.6, sat: 0.9 },
    berry: { h: 336, s: 0.62, l: 0.68, dl: 0.6, sat: 1 },
    sky: { h: 205, s: 0.66, l: 0.68, dl: 0.6, sat: 1 },
    cocoa: { h: 24, s: 0.4, l: 0.46, dl: 0.7, sat: 0.6 }
  };

  // 모자: 머리 꼭대기 줄에 overlap 줄만큼 겹쳐 얹는다
  var HATS = ['none', 'beret', 'party', 'crown', 'flower', 'bow', 'sprout', 'beanie'];
  var HAT_SPRITES = {
    beret: {
      overlap: 1,
      palette: { r: '#e5484d', R: '#ff8a8e' },
      rows: [
        '.....oo..',
        '.oooorroo',
        'orRrrrrro',
        'ooooooooo'
      ]
    },
    party: {
      overlap: 1,
      palette: { p: '#7c9cf5', y: '#ffd84d', Y: '#ff7fa8' },
      rows: [
        '...Y...',
        '..oYo..',
        '..opo..',
        '.opypo.',
        'oyppypo',
        'ooooooo'
      ]
    },
    crown: {
      overlap: 1,
      palette: { y: '#ffd23f', Y: '#fff2a8', r: '#ef4f5f', b: '#5ab4f0' },
      rows: [
        '.o.oo.o.',
        'oYoyyoyo',
        'oyyyyyyo',
        'oyrybyro',
        'oooooooo'
      ]
    },
    flower: {
      overlap: 1,
      palette: { p: '#ff8fb8', P: '#ffc4da', y: '#ffd84d', l: '#6cc04a' },
      rows: [
        '..ooo..',
        '.oPPpo.',
        'opPyppo',
        'oppppPo',
        '.oolpo.',
        '..olo..'
      ]
    },
    bow: {
      overlap: 2,
      palette: { r: '#ff6f91', R: '#ffb3c6', d: '#d94a6e' },
      rows: [
        'oo....oo',
        'oRroorRo',
        'orrddrro',
        'orroorro',
        'oo....oo'
      ]
    },
    sprout: {
      overlap: 1,
      palette: { l: '#7ccf5a', L: '#b8ec8a' },
      rows: [
        '.oo.oo.',
        'oLlolLo',
        '.ooloo.',
        '..olo..'
      ]
    },
    beanie: {
      overlap: 2,
      palette: { b: '#5a8de0', B: '#8fb5f2', w: '#fbfbfb' },
      rows: [
        '...oo...',
        '..owwo..',
        '.oobboo.',
        'obbbbbbo',
        'oBBBBBBo',
        'oooooooo'
      ]
    }
  };

  // 장식: 오른쪽 위에 떠 있는 작은 그림(윤곽 없이 진한 색으로 테두리를 낸다)
  var CHARMS = ['none', 'heart', 'star', 'note', 'sparkle', 'leaf'];
  var CHARM_SPRITES = {
    heart: {
      palette: { r: '#ff6f91', d: '#e04870', w: '#ffffff' },
      rows: [
        'rr.rr',
        'rwrrr',
        '.rrd.',
        '..d..'
      ]
    },
    star: {
      palette: { y: '#ffd23f', Y: '#f2a516', w: '#fff8d6' },
      rows: [
        '..y..',
        '.ywy.',
        'yyyyy',
        '.yyY.',
        '.Y.Y.'
      ]
    },
    note: {
      palette: { n: '#8b7cf6', d: '#5f4fd6' },
      rows: [
        '.nnnn',
        '.n..n',
        '.n..n',
        'dn.dn',
        'dd.dd'
      ]
    },
    sparkle: {
      palette: { s: '#7fd6f5', w: '#ffffff' },
      rows: [
        '..s..',
        '..s..',
        'sswss',
        '..s..',
        '..s..'
      ]
    },
    leaf: {
      palette: { l: '#7ccf5a', L: '#4f9e36' },
      rows: [
        '..lll',
        '.llLl',
        '.lLll',
        '.Lll.',
        'L....'
      ]
    }
  };

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

  // ---- 색 계산 ----

  function toHsl(hex) {
    var n = parseInt(hex.slice(1), 16);
    var r = (n >> 16 & 255) / 255;
    var g = (n >> 8 & 255) / 255;
    var b = (n & 255) / 255;
    var max = Math.max(r, g, b);
    var min = Math.min(r, g, b);
    var l = (max + min) / 2;
    var d = max - min;
    var h = 0;
    var s = 0;
    if (d > 0) {
      s = d / (1 - Math.abs(2 * l - 1));
      if (max === r) {
        h = ((g - b) / d + 6) % 6;
      } else if (max === g) {
        h = (b - r) / d + 2;
      } else {
        h = (r - g) / d + 4;
      }
      h *= 60;
    }
    return { h: h, s: s, l: l };
  }

  function toHex(hsl) {
    var h = ((hsl.h % 360) + 360) % 360;
    var s = clamp(hsl.s, 0, 1);
    var l = clamp(hsl.l, 0, 1);
    var c = (1 - Math.abs(2 * l - 1)) * s;
    var x = c * (1 - Math.abs((h / 60) % 2 - 1));
    var m = l - c / 2;
    var rgb = h < 60 ? [c, x, 0] : h < 120 ? [x, c, 0] : h < 180 ? [0, c, x]
      : h < 240 ? [0, x, c] : h < 300 ? [x, 0, c] : [c, 0, x];
    return '#' + rgb.map(function (v) {
      var part = Math.round((v + m) * 255).toString(16);
      return part.length < 2 ? '0' + part : part;
    }).join('');
  }

  function clamp(v, lo, hi) {
    return Math.min(hi, Math.max(lo, v));
  }

  function hueGap(a, b) {
    var d = Math.abs(a - b) % 360;
    return d > 180 ? 360 - d : d;
  }

  // 무채색(회색·흰색)인지
  function isNeutral(hsl) {
    return hsl.s < 0.2 || hsl.l > 0.93;
  }

  // 프레임 A에서 자기 색 글자마다 쓴 픽셀 수
  function letterCounts(sprite) {
    var counts = {};
    sprite.rows.join('').split('').forEach(function (ch) {
      if (sprite.palette[ch]) {
        counts[ch] = (counts[ch] || 0) + 1;
      }
    });
    return counts;
  }

  function tonePalette(name, tone) {
    var sprite = SPRITES[name];
    var palette = sprite.palette;
    if (tone === 'original') {
      return palette;
    }
    var out = {};
    var counts = letterCounts(sprite);
    var letters = Object.keys(counts).sort(function (a, b) {
      return counts[b] - counts[a];
    });
    if (tone === 'pastel') {
      letters.forEach(function (ch) {
        var c = toHsl(palette[ch]);
        out[ch] = toHex({ h: c.h, s: c.s * 0.72, l: c.l + (1 - c.l) * 0.42 });
      });
      return out;
    }
    var spec = TONE_SPEC[tone];
    var main = letters[0];
    var anchor = letters.filter(function (ch) {
      return !isNeutral(toHsl(palette[ch]));
    })[0];
    var anchorHsl = anchor ? toHsl(palette[anchor]) : null;
    var animal = KEYS.indexOf(name) < ANIMAL_COUNT;
    // 몸 색이 무채색이고 유채색은 군데군데뿐이면(고양이·토끼·펭귄·게임기) 몸 색을 목표 색으로 물들인다
    var colorMain = isNeutral(toHsl(palette[main])) && (!anchor || counts[anchor] * 4 < counts[main]);
    var dl = anchorHsl ? (spec.l - anchorHsl.l) * spec.dl : 0;
    letters.forEach(function (ch) {
      var c = toHsl(palette[ch]);
      if (ch === main && colorMain) {
        out[ch] = toHex({ h: spec.h, s: spec.s * 0.85, l: c.l + (spec.l + 0.1 - c.l) * 0.7 });
      } else if (isNeutral(c)) {
        // 흰색·회색은 목표 색을 살짝 묻힌다
        out[ch] = toHex({ h: spec.h, s: Math.max(c.s, c.l > 0.9 ? 0.45 : 0.18), l: c.l });
      } else if (colorMain && animal) {
        out[ch] = palette[ch];
      } else if (animal) {
        // 동물은 털빛 계열만 목표 색으로 옮기고, 부리·혀 같은 다른 계열은 그대로 둔다
        if (hueGap(c.h, anchorHsl.h) <= 45) {
          out[ch] = toHex({
            h: c.h + spec.h - anchorHsl.h,
            s: c.s + (spec.s - c.s) * 0.7,
            l: clamp(c.l + dl, 0.22, 0.88)
          });
        } else {
          out[ch] = palette[ch];
        }
      } else {
        // 사물은 여러 색을 함께 돌려 알록달록함을 지킨다. 어두운 색(화면·렌즈)은 탁해지지 않게 채도를 낮춘다
        out[ch] = toHex({
          h: c.h + spec.h - anchorHsl.h,
          s: c.s * spec.sat * (c.l < 0.4 ? 0.6 : 1),
          l: tone === 'cocoa' ? c.l * 0.85 : c.l
        });
      }
    });
    return out;
  }

  // ---- 그리기 ----

  // 같은 색이 가로로 이어진 픽셀은 rect 하나로 합친다
  function drawRows(g, rows, colors, dx, dy) {
    rows.forEach(function (row, y) {
      var x = 0;
      while (x < row.length) {
        var color = colors[row.charAt(x)] || null;
        var end = x + 1;
        while (end < row.length && color && colors[row.charAt(end)] === color) {
          end++;
        }
        if (color) {
          var rect = document.createElementNS(SVG_NS, 'rect');
          rect.setAttribute('x', x + dx);
          rect.setAttribute('y', y + dy);
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

  function group(className) {
    var g = document.createElementNS(SVG_NS, 'g');
    g.setAttribute('class', className);
    return g;
  }

  function withBase(palette) {
    var colors = {};
    Object.keys(BASE).forEach(function (ch) {
      colors[ch] = BASE[ch];
    });
    Object.keys(palette).forEach(function (ch) {
      colors[ch] = palette[ch];
    });
    return colors;
  }

  // 머리 자리: 맨 윗줄 양 끝의 가운데 열을 따라 내려가며, 3칸 이상 이어 칠한 첫 줄을 머리 꼭대기로 본다
  // (귀 사이·새 깃털·김 같은 가는 장식은 건너뛴다). 가로 가운데는 그 줄에서 이어 칠한 구간의 가운데
  var headCache = {};

  function headOf(name) {
    if (headCache[name]) {
      return headCache[name];
    }
    var rows = SPRITES[name].rows;
    var filled = function (x, y) {
      return x >= 0 && x < GRID && rows[y].charAt(x) !== '.';
    };
    var top = 0;
    while (top < GRID - 1 && !/[^.]/.test(rows[top])) {
      top++;
    }
    var left = rows[top].search(/[^.]/);
    var right = rows[top].search(/[^.]\.*$/);
    var mid = (left + right) / 2;
    var head = { top: top, center: mid };
    for (var y = top; y < GRID; y++) {
      var x = filled(Math.floor(mid), y) ? Math.floor(mid) : Math.ceil(mid);
      if (!filled(x, y)) {
        continue;
      }
      var a = x;
      var b = x;
      while (filled(a - 1, y)) {
        a--;
      }
      while (filled(b + 1, y)) {
        b++;
      }
      if (b - a >= 2) {
        head = { top: y, center: (a + b) / 2 };
        break;
      }
    }
    headCache[name] = head;
    return head;
  }

  function drawHat(name, hat) {
    var spec = HAT_SPRITES[hat];
    var head = headOf(name);
    var width = spec.rows[0].length;
    var dx = Math.round(OFFSET_X + head.center - (width - 1) / 2);
    var dy = Math.max(0, OFFSET_Y + head.top + spec.overlap - spec.rows.length);
    return drawRows(group('mascot__hat'), spec.rows, withBase(spec.palette), dx, dy);
  }

  function drawCharm(charm) {
    var spec = CHARM_SPRITES[charm];
    var dx = CANVAS - spec.rows[0].length;
    var wrap = group('mascot__charm mascot__charm--' + charm);
    wrap.appendChild(drawRows(group('mascot__charm-art'), spec.rows, spec.palette, dx, 0));
    return wrap;
  }

  // ---- 값 읽기 ----

  function hash(text) {
    var h = 2166136261;
    for (var i = 0; i < text.length; i++) {
      h ^= text.charCodeAt(i);
      h = Math.imul(h, 16777619);
    }
    return h >>> 0;
  }

  // 서버가 아직 고르지 않은 블로그는 주제와 어긋날 수 있는 물건 대신 동물 중에서 보여 준다
  var ANIMALS = KEYS.slice(0, ANIMAL_COUNT);

  function seedText(seed) {
    return String(seed === undefined || seed === null ? '' : seed);
  }

  function fallbackKey(seed) {
    return ANIMALS[hash(seedText(seed)) % ANIMALS.length];
  }

  function pick(list, value, fallback) {
    return list.indexOf(value) >= 0 ? value : fallback;
  }

  // 'cat.mint.beret.heart' → 조각별 값. 모르는 기본은 null, 나머지 모르는 조각은 original/none
  function parse(value) {
    var parts = typeof value === 'string' ? value.trim().split('.') : [];
    return {
      base: SPRITES[parts[0]] ? parts[0] : null,
      tone: pick(TONES, parts[1], 'original'),
      hat: pick(HATS, parts[2], 'none'),
      charm: pick(CHARMS, parts[3], 'none')
    };
  }

  // 고른 마스코트가 없으면 seed로 기본·색조·모자·장식을 모두 정해 블로그마다 다르게 보인다
  function resolve(value, seed) {
    var combo = parse(value);
    if (combo.base) {
      return combo;
    }
    var text = seedText(seed !== undefined ? seed : value);
    return {
      base: fallbackKey(text),
      tone: TONES[hash(text + '#tone') % TONES.length],
      hat: HATS[hash(text + '#hat') % HATS.length],
      charm: CHARMS[hash(text + '#charm') % CHARMS.length]
    };
  }

  function label(key) {
    return LABELS[parse(key).base] || '';
  }

  function element(value, options) {
    var opts = options || {};
    var combo = resolve(value, opts.seed);
    var name = combo.base;
    var size = Number(opts.size) > 0 ? Number(opts.size) : DEFAULT_SIZE;
    var sprite = SPRITES[name];
    var colors = withBase(tonePalette(name, combo.tone));

    var span = document.createElement('span');
    span.className = 'mascot mascot--' + name + ' mascot--tone-' + combo.tone;
    span.setAttribute('role', 'img');
    span.setAttribute('aria-label', LABELS[name] + ' 마스코트');

    var svg = document.createElementNS(SVG_NS, 'svg');
    svg.setAttribute('viewBox', '0 0 ' + CANVAS + ' ' + CANVAS);
    svg.setAttribute('width', size);
    svg.setAttribute('height', size);
    svg.setAttribute('shape-rendering', 'crispEdges');
    svg.setAttribute('aria-hidden', 'true');
    svg.setAttribute('focusable', 'false');

    var body = group('mascot__body');
    body.appendChild(drawRows(group('mascot__frame mascot__frame--a'), sprite.rows, colors, OFFSET_X, OFFSET_Y));
    body.appendChild(drawRows(group('mascot__frame mascot__frame--b'), frameB(sprite), colors, OFFSET_X, OFFSET_Y));
    if (combo.hat !== 'none') {
      body.appendChild(drawHat(name, combo.hat));
    }
    svg.appendChild(body);
    if (combo.charm !== 'none') {
      svg.appendChild(drawCharm(combo.charm));
    }
    span.appendChild(svg);
    return span;
  }

  // data-mascot 자리를 채운다. 값은 조합 문자열이어도 된다. 이미 채운 자리는 건너뛴다
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
    TONES: TONES.slice(),
    HATS: HATS.slice(),
    CHARMS: CHARMS.slice(),
    label: label,
    parse: parse,
    element: element,
    fallbackKey: fallbackKey,
    mountAll: mountAll
  };
})();
