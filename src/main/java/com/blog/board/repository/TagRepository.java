package com.blog.board.repository;

import com.blog.board.domain.Tag;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TagRepository extends JpaRepository<Tag, Long> {

  Optional<Tag> findByName(String name);

  List<Tag> findByNameIn(Collection<String> names);

  /** 없으면 만든다. 두 요청이 같은 새 태그를 동시에 만들어도 UNIQUE 오류가 나지 않는다. */
  @Modifying
  @Query(value = "insert ignore into tags (name) values (:name)", nativeQuery = true)
  void insertIfAbsent(@Param("name") String name);
}
