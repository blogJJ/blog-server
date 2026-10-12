package com.blog.auth.repository;

import com.blog.auth.domain.SocialAccount;
import com.blog.auth.domain.SocialProvider;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SocialAccountRepository extends JpaRepository<SocialAccount, Long> {

  /** 소셜 로그인: 제공자 회원 번호로 연결된 회원을 함께 읽는다 */
  @Query(
      "select s from SocialAccount s join fetch s.user"
          + " where s.provider = :provider and s.providerUserId = :providerUserId")
  Optional<SocialAccount> findWithUser(
      @Param("provider") SocialProvider provider, @Param("providerUserId") String providerUserId);

  Optional<SocialAccount> findByUserIdAndProvider(Long userId, SocialProvider provider);

  List<SocialAccount> findByUserIdOrderByIdAsc(Long userId);

  long countByUserId(Long userId);

  /** 회원 탈퇴 때 연결을 모두 끊는다. 같은 소셜 계정으로 새로 가입할 수 있게 */
  @Modifying
  @Query("delete from SocialAccount s where s.user.id = :userId")
  int deleteAllOf(@Param("userId") Long userId);
}
