/*
 * 신고하기 창 (T084, SOC-06). 사유를 고르고 자세한 내용을 적어 POST /api/reports로 보낸다.
 * 사용: Report.open({ targetType: 'POST', targetId: 12 })  회원 신고를 블로그 안에서 하면 blogId도 보낸다.
 */
(function () {
  'use strict';

  var REASONS = [
    ['SPAM', '스팸·홍보'],
    ['ABUSE', '욕설·비방'],
    ['ADULT', '음란물'],
    ['ILLEGAL', '불법 정보'],
    ['ETC', '기타']
  ];
  var TITLES = { POST: '글 신고', COMMENT: '댓글 신고', BLOG: '블로그 신고', USER: '회원 신고' };

  async function open(target) {
    if (!window.Layout || !window.Layout.user) {
      window.location.href =
        '/login.html?next=' + encodeURIComponent(window.location.pathname + window.location.search);
      return;
    }
    var values = await FormDialog.open({
      title: TITLES[target.targetType] || '신고',
      desc: '같은 대상은 2주에 한 번만 신고할 수 있어요.',
      submitLabel: '신고하기',
      danger: true,
      fields: [
        { name: 'reason', label: '사유', type: 'select', options: REASONS },
        { name: 'detail', label: '자세한 내용 (선택)', type: 'textarea', maxLength: 500 }
      ]
    });
    if (!values) {
      return;
    }
    try {
      var res = await Api.post('/api/reports', {
        targetType: target.targetType,
        targetId: target.targetId,
        blogId: target.blogId || null,
        reason: values.reason,
        detail: values.detail || null
      });
      Api.toast(res.handlerScope === 'ADMIN' ? '신고했어요. 메인 관리자가 확인해요.' : '신고했어요. 블로그 관리자가 확인해요.');
    } catch (e) {
      Api.showError(e);
    }
  }

  window.Report = { open: open };
})();
