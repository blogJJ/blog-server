package com.blog.auth.repository;

import com.blog.auth.domain.AccountFindToken;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountFindTokenRepository extends JpaRepository<AccountFindToken, Long> {

  @EntityGraph(attributePaths = "user")
  Optional<AccountFindToken> findByTokenHash(String tokenHash);
}
