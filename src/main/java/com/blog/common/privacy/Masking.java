package com.blog.common.privacy;

/**
 * 개인정보 가리기 (T019, 4.4, D-22). 응답과 로그를 만들 때 서버에서 적용한다.
 *
 * <ul>
 *   <li>이메일: @ 앞 처음 2글자만 보이고 나머지는 {@code ***}. @ 앞이 2글자 이하면 첫 글자만. {@code abcdef@gmail.com →
 *       ab***@gmail.com}
 *   <li>전화번호: 앞 3자리와 뒤 4자리만 보이고 가운데를 가린다. {@code 01012345678 → 010-****-5678}
 * </ul>
 *
 * <p>누가 무엇을 볼 수 있는지(본인은 그대로, 블로그장·관리자는 가림 등)는 응답을 만드는 쪽이 정한다.
 */
public final class Masking {

  private static final String MASK = "***";

  private Masking() {}

  public static String email(String email) {
    if (email == null || email.isBlank()) {
      return email;
    }
    int at = email.indexOf('@');
    if (at < 0) {
      return MASK;
    }
    String local = email.substring(0, at);
    String domain = email.substring(at);
    int visible = local.length() <= 2 ? 1 : 2;
    if (local.isEmpty()) {
      return MASK + domain;
    }
    return local.substring(0, visible) + MASK + domain;
  }

  /** 숫자만 저장한 번호(하이픈이 있어도 됨)를 {@code 010-****-5678} 꼴로 가린다. 7자리 미만이면 전부 가린다. */
  public static String phone(String phone) {
    if (phone == null || phone.isBlank()) {
      return phone;
    }
    String digits = phone.replaceAll("\\D", "");
    if (digits.length() < 7) {
      return MASK;
    }
    String head = digits.substring(0, 3);
    String tail = digits.substring(digits.length() - 4);
    String middle = "*".repeat(digits.length() - 7);
    return head + "-" + middle + "-" + tail;
  }
}
