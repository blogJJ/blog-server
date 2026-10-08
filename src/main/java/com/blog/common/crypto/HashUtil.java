package com.blog.common.crypto;

import com.blog.auth.domain.User;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * 원문 대신 저장하는 해시 (T020). 인증번호, 임시·Refresh 토큰, 블랙리스트의 이름·이메일·전화번호에 쓴다 (USR-02, SEC-04, BLG-11).
 *
 * <p>SHA-256에 서버 비밀값을 섞은 HMAC-SHA256이다. 같은 값은 늘 같은 해시(64자 16진수)가 나와 DB에서 해시로 찾을 수 있고, DB가 새어도 비밀값
 * 없이는 6자리 인증번호나 전화번호를 하나씩 대입해 되돌릴 수 없다. 비밀번호는 여기가 아니라 bcrypt(SEC-01)로 저장한다.
 *
 * <p>비밀값을 바꾸면 이미 저장한 해시와 맞지 않는다. 블랙리스트가 무효가 되므로 운영 중에는 바꾸지 않는다.
 */
@Component
public class HashUtil {

  private static final String ALGORITHM = "HmacSHA256";
  private static final int MIN_SECRET_BYTES = 32;

  private final SecretKeySpec key;

  public HashUtil(HashProperties properties) {
    String secret = properties.secret();
    if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
      throw new IllegalStateException(
          "HASH_SECRET must be at least " + MIN_SECRET_BYTES + " bytes (see .env.example)");
    }
    this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM);
  }

  public String hash(String raw) {
    if (raw == null) {
      throw new IllegalArgumentException("raw must not be null");
    }
    try {
      Mac mac = Mac.getInstance(ALGORITHM);
      mac.init(key);
      return HexFormat.of().formatHex(mac.doFinal(raw.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new IllegalStateException("HMAC-SHA256 unavailable", e);
    }
  }

  /** 입력값이 저장된 해시와 같은지. 비교 시간으로 맞은 글자 수가 드러나지 않게 한다. */
  public boolean matches(String raw, String storedHash) {
    if (raw == null || storedHash == null) {
      return false;
    }
    return MessageDigest.isEqual(
        hash(raw).getBytes(StandardCharsets.US_ASCII),
        storedHash.getBytes(StandardCharsets.US_ASCII));
  }

  /** 이메일은 대소문자·앞뒤 공백을 맞춘 뒤 해시한다. 블랙리스트 비교가 표기 차이로 빠지지 않게 (BLG-11). */
  public String hashEmail(String email) {
    return hash(User.normalizeEmail(email));
  }

  /** 전화번호는 숫자만 남긴 뒤 해시한다 (010-1234-5678과 01012345678이 같게). */
  public String hashPhone(String phone) {
    return hash(phone.replaceAll("\\D", ""));
  }

  /** 이름은 앞뒤 공백만 지운다. */
  public String hashName(String name) {
    return hash(name.strip());
  }
}
