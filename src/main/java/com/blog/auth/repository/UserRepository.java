package com.blog.auth.repository;

import com.blog.auth.domain.User;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

  /** email은 User.normalizeEmail로 소문자로 맞춘 값을 넘긴다. */
  Optional<User> findByEmail(String email);

  boolean existsByEmail(String email);

  boolean existsByNickname(String nickname);
}
