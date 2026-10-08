package com.blog.common.mail;

import com.blog.common.error.BusinessException;
import com.blog.common.error.ErrorCode;
import com.blog.common.privacy.Masking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * 메일 보내기 (T023, D-69). Gmail SMTP로 보내고, 연결·전송 제한 시간은 5초다(application.yml spring.mail).
 *
 * <p>보내지 못하면(Gmail 오류, 5초 시간 초과, 하루 한도 초과) 다시 보내지 않고 오류 로그를 남긴 뒤 MAIL_SEND_FAILED를 던진다. 화면 문구는 가입된
 * 이메일인지와 상관없이 같다. 보내지 못한 인증번호를 무효로 하고 1분 재발송 제한에 세지 않는 일은 부른 쪽(인증번호 기능)이 이 예외를 받아서 한다 (OPS-07,
 * D-107).
 *
 * <p>{@code app.mail.log-only=true}면 보내지 않고 콘솔 로그에 내용을 찍는다. Gmail 설정 없이 가입을 시험하려는 local 프로필 전용이고,
 * prod는 항상 false다. 운영 로그에는 인증번호를 남기지 않는다 (OPS-03).
 */
@Service
public class MailService {

  private static final Logger log = LoggerFactory.getLogger(MailService.class);

  private final JavaMailSender mailSender;
  private final String from;
  private final boolean logOnly;

  public MailService(
      JavaMailSender mailSender,
      @Value("${spring.mail.username:}") String from,
      @Value("${app.mail.log-only:false}") boolean logOnly) {
    this.mailSender = mailSender;
    this.from = from;
    this.logOnly = logOnly;
  }

  /** 글자만 있는 메일을 보낸다. 실패하면 {@link BusinessException}(MAIL_SEND_FAILED). */
  public void send(String to, String subject, String text) {
    if (logOnly) {
      log.info("[log-only mail] to={} subject={}\n{}", Masking.email(to), subject, text);
      return;
    }
    SimpleMailMessage message = new SimpleMailMessage();
    message.setFrom(from);
    message.setTo(to);
    message.setSubject(subject);
    message.setText(text);
    try {
      mailSender.send(message);
    } catch (MailException e) {
      // 받는 주소는 가려서 남긴다. 예외 메시지에 주소가 섞일 수 있어 종류만 남긴다 (OPS-03)
      log.error(
          "mail send failed to={} subject={} cause={}",
          Masking.email(to),
          subject,
          e.getClass().getSimpleName());
      throw new BusinessException(ErrorCode.MAIL_SEND_FAILED);
    }
  }
}
