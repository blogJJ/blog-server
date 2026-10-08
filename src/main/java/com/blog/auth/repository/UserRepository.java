package com.blog.auth.repository;

import com.blog.auth.domain.User;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {

  /** email은 User.normalizeEmail로 소문자로 맞춘 값을 넘긴다. */
  Optional<User> findByEmail(String email);

  boolean existsByEmail(String email);

  boolean existsByNickname(String nickname);

  /** SELECT ... FOR UPDATE. 블로그 생성 개수를 셀 때 같은 회원의 동시 요청을 줄 세운다 (BLG-10, D-68). */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select u from User u where u.id = :id")
  Optional<User> findByIdForUpdate(@Param("id") Long id);
}
