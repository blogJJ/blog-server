package com.blog.common.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.blog.common.error.BusinessException;
import com.blog.common.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/** 메일 보내기와 실패 처리 (T023, OPS-07, D-107). */
class MailServiceTest {

  JavaMailSender sender = mock(JavaMailSender.class);

  @Test
  void sendsFromConfiguredAccount() {
    new MailService(sender, "blog@gmail.com", false).send("user@example.com", "제목", "본문");

    ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
    verify(sender).send(captor.capture());
    SimpleMailMessage message = captor.getValue();
    assertThat(message.getFrom()).isEqualTo("blog@gmail.com");
    assertThat(message.getTo()).containsExactly("user@example.com");
    assertThat(message.getSubject()).isEqualTo("제목");
    assertThat(message.getText()).isEqualTo("본문");
  }

  @Test
  void failureBecomesSameRetryMessageWithoutResending() {
    doThrow(new MailSendException("554 daily limit exceeded"))
        .when(sender)
        .send(any(SimpleMailMessage.class));
    MailService service = new MailService(sender, "blog@gmail.com", false);

    assertThatThrownBy(() -> service.send("user@example.com", "제목", "본문"))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            e -> {
              assertThat(((BusinessException) e).getErrorCode())
                  .isEqualTo(ErrorCode.MAIL_SEND_FAILED);
              assertThat(e.getMessage()).isEqualTo("메일을 보내지 못했어요. 잠시 뒤 다시 시도해 주세요.");
            });
    verify(sender).send(any(SimpleMailMessage.class));
  }

  @Test
  void logOnlyModeDoesNotSend() {
    new MailService(sender, "", true).send("user@example.com", "제목", "인증번호 123456");

    verify(sender, never()).send(any(SimpleMailMessage.class));
  }
}
